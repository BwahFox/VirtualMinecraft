package dev.virtualminecraft.computer;

import com.sun.management.ThreadMXBean;
import dev.virtualminecraft.VirtualMinecraft;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Runs machines on a worker pool (ROADMAP §7h §1c). The server thread never executes Lua. A machine is
 * <em>runnable</em> when it has something to do (booting, an event arrived, its last slice was cut short) and
 * <em>waiting</em> otherwise, costing nothing. Runnable machines are taken in order of when they became runnable
 * and run for a slice of at most {@link #sliceNanos}; a monitor thread interrupts a slice that overruns and
 * raises "not enough memory" into a slice whose allocation runs away. Each machine has a leaky-bucket CPU share
 * ({@code cpuShare} of one core, with a one-second burst); one that is over its share is deferred, and slows down,
 * while nothing else does.
 * <p>
 * Results reach the owner through {@link Listener#onResult} <b>on the worker thread</b>; owners post to the server
 * thread themselves.
 */
public final class MachineScheduler {
	public interface Listener {
		void onResult(LuaMachine machine, LuaMachine.Result result);
	}

	private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

	private final class Entry implements Comparable<Entry> {
		final LuaMachine machine;
		final Listener listener;
		volatile boolean runnable;
		volatile boolean running;
		volatile boolean removed;
		/** A wake/eval arrived while a slice was running: re-queue as soon as it ends (never run one machine on two workers). */
		volatile boolean pendingWake;
		volatile long sliceStartCpu;
		/** CPU bucket in nanoseconds: refilled at cpuShare per second of wall time, capped at one second's worth. */
		double bucketNanos;
		long lastRefillNanos = System.nanoTime();
		long readyAt;
		long seq;
		// live monitoring of the running slice
		volatile long runningThreadId;
		volatile long sliceStartNanos;
		volatile long sliceStartAlloc;
		volatile boolean raisedThisSlice;
		long cpuTotalNanos;
		long slices;
		long walkCount;
		/** The machine itself has work (boot, event, cut slice); false while it only waits and evals are pending. */
		volatile boolean wantsRun = true;
		final java.util.concurrent.ConcurrentLinkedQueue<EvalRequest> evals = new java.util.concurrent.ConcurrentLinkedQueue<>();
		/** A timed wait ({@code yield("wait", ms)}) in flight; cancelled by any earlier wake. */
		volatile java.util.concurrent.ScheduledFuture<?> timedWake;

		/** This machine's share of one core (the tier ladder's CPU axis); the pool's default when not given. */
		final double share;

		Entry(final LuaMachine machine, final Listener listener, final double share) {
			this.machine = machine;
			this.listener = listener;
			this.share = share;
			this.bucketNanos = share * 1e9;
		}

		@Override
		public int compareTo(final Entry o) {
			final int c = Long.compare(readyAt, o.readyAt);
			return c != 0 ? c : Long.compare(seq, o.seq);
		}
	}

	private record EvalRequest(String source, java.util.concurrent.CompletableFuture<String> result) {
	}

	private final long sliceNanos;
	private final double cpuShare;
	private final Map<LuaMachine, Entry> entries = new ConcurrentHashMap<>();
	private final PriorityBlockingQueue<Entry> queue = new PriorityBlockingQueue<>();
	private final List<Thread> workers = new ArrayList<>();
	private final Thread monitor;
	private final AtomicInteger seq = new AtomicInteger();
	private volatile boolean stopped;
	/** Fires the timed waits: one daemon thread, each wake a {@link #wake} call (a machine waiting for the clock costs no worker). */
	private final java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
		final Thread t = new Thread(r, "vmc-machine-timer");
		t.setDaemon(true);
		return t;
	});

	public MachineScheduler(final int threads, final long sliceNanos, final double cpuShare) {
		this.sliceNanos = sliceNanos;
		this.cpuShare = cpuShare;
		for (int i = 0; i < Math.max(1, threads); i++) {
			final Thread t = new Thread(this::workerLoop, "vmc-computer-" + i);
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			workers.add(t);
			t.start();
		}
		monitor = new Thread(this::monitorLoop, "vmc-computer-monitor");
		monitor.setDaemon(true);
		monitor.start();
	}

	public int threads() {
		return workers.size();
	}

	/** Register a machine and make it runnable (it boots on its first slice). */
	public void submit(final LuaMachine machine, final Listener listener) {
		submit(machine, listener, cpuShare);
	}

	/** The same with the machine's own share of a core (a part in its case decides it). */
	public void submit(final LuaMachine machine, final Listener listener, final double share) {
		final Entry e = new Entry(machine, listener, Math.clamp(share, 0.01, 4.0));
		entries.put(machine, e);
		makeRunnable(e, System.nanoTime());
	}

	/** An event arrived (or the owner wants the machine to continue): make it runnable if it is not already. */
	public void wake(final LuaMachine machine) {
		final Entry e = entries.get(machine);
		if (e != null) {
			cancelTimedWake(e);
			e.wantsRun = true;
			makeRunnable(e, System.nanoTime());
		}
	}

	private static void cancelTimedWake(final Entry e) {
		final java.util.concurrent.ScheduledFuture<?> f = e.timedWake;
		if (f != null) {
			e.timedWake = null;
			f.cancel(false);
		}
	}

	/** The owner wants the machine held until the next {@link #wake} (a "flip": the frame is out when the flush says so). */
	public void park(final LuaMachine machine) {
		final Entry e = entries.get(machine);
		if (e != null) {
			e.wantsRun = false;
		}
	}

	/** Evaluate a chunk on a worker between slices (the {@code /vmc computer lua} harness). */
	public java.util.concurrent.CompletableFuture<String> eval(final LuaMachine machine, final String source) {
		final Entry e = entries.get(machine);
		final java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
		if (e == null) {
			f.complete("ERROR: machine is not scheduled");
			return f;
		}
		e.evals.add(new EvalRequest(source, f));
		makeRunnable(e, System.nanoTime());
		return f;
	}

	public void remove(final LuaMachine machine) {
		final Entry e = entries.remove(machine);
		if (e != null) {
			e.removed = true;
			cancelTimedWake(e);
			queue.remove(e);
			if (e.running) {
				machine.kill();
			}
		}
	}

	public boolean contains(final LuaMachine machine) {
		return entries.containsKey(machine);
	}

	/** Diagnostics for {@code /vmc computer state}. */
	public @Nullable String describe(final LuaMachine machine) {
		final Entry e = entries.get(machine);
		if (e == null) {
			return null;
		}
		return String.format(java.util.Locale.ROOT, "%s, slices %d, cpu %.1f ms total, bucket %.0f ms, walks %d, live ~%d KB, events %d (dropped %d)",
			e.running ? "running" : e.runnable ? "runnable" : "waiting", e.slices, e.cpuTotalNanos / 1e6, e.bucketNanos / 1e6, e.walkCount,
			machine.memory().estimate() / 1024, machine.pendingEvents(), machine.droppedEvents());
	}

	public int runnableCount() {
		return queue.size();
	}

	public void shutdown() {
		stopped = true;
		timer.shutdownNow();
		for (final Entry e : entries.values()) {
			e.machine.kill();
		}
		monitor.interrupt();
		for (final Thread t : workers) {
			t.interrupt();
		}
	}

	private synchronized void makeRunnable(final Entry e, final long readyAt) {
		if (e.removed || e.runnable) {
			return;
		}
		if (e.running) {
			e.pendingWake = true;
			return;
		}
		e.runnable = true;
		e.readyAt = readyAt;
		e.seq = seq.incrementAndGet();
		queue.add(e);
	}

	private void workerLoop() {
		final long tid = Thread.currentThread().threadId();
		while (!stopped) {
			final Entry e;
			try {
				e = queue.take();
			} catch (final InterruptedException ex) {
				return;
			}
			if (e.removed) {
				continue;
			}
			// CPU share: refill the bucket, defer if empty
			final long now = System.nanoTime();
			e.bucketNanos = Math.min(e.share * 1e9, e.bucketNanos + (now - e.lastRefillNanos) * e.share);
			e.lastRefillNanos = now;
			if (e.bucketNanos <= 0) {
				final long wait = (long) (-e.bucketNanos / e.share) + 1_000_000L;
				e.runnable = false;
				makeRunnable(e, now + wait);
				// a deferred entry sits at the front of the queue until its time: sleep off the smallest delay
				sleepUntil(e.readyAt);
				continue;
			}
			if (now < e.readyAt) {
				sleepUntil(e.readyAt);
			}
			runSlice(e, tid);
		}
	}

	private static void sleepUntil(final long nanoTime) {
		final long ms = (nanoTime - System.nanoTime()) / 1_000_000L;
		if (ms > 0) {
			try {
				Thread.sleep(Math.min(ms, 50));
			} catch (final InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void runSlice(final Entry e, final long tid) {
		final LuaMachine m = e.machine;
		EvalRequest ev;
		while ((ev = e.evals.poll()) != null) {
			try {
				ev.result().complete(m.eval(ev.source()));
			} catch (final RuntimeException ex) {
				ev.result().complete("ERROR: " + ex);
			}
		}
		if (!e.wantsRun) {
			e.runnable = false;
			return;
		}
		final long cpu0 = THREADS.getCurrentThreadCpuTime();
		e.sliceStartAlloc = THREADS.getThreadAllocatedBytes(tid);
		e.sliceStartCpu = cpu0;
		e.sliceStartNanos = System.nanoTime();
		e.raisedThisSlice = false;
		e.runningThreadId = tid;
		e.running = true;
		e.runnable = false;
		LuaMachine.Result result;
		try {
			result = m.run();
		} catch (final Throwable t) {
			VirtualMinecraft.LOGGER.error("Computer '{}' crashed the worker: {}", m.host().name(), t.toString());
			m.kill();
			result = LuaMachine.Result.ERROR;
		}
		final long cpu = THREADS.getCurrentThreadCpuTime() - cpu0;
		synchronized (this) {
			e.running = false;
		}
		final long alloc = THREADS.getThreadAllocatedBytes(tid) - e.sliceStartAlloc;
		e.cpuTotalNanos += cpu;
		e.bucketNanos -= cpu;
		e.slices++;
		m.memory().addAllocated(alloc);
		final long cap = m.host().memoryCapBytes();
		if (!m.isFinished() && m.memory().walkDue(cap)) {
			final long est = m.memory().walk(m.state(), m.mainThread());
			e.walkCount++;
			if (est > cap) {
				m.raise("not enough memory (live ~" + (est >> 10) + " KB of " + (cap >> 10) + " KB, " + m.memory().lastWalkObjects() + " objects)");
			}
		}
		if (e.removed) {
			return;
		}
		if (e.pendingWake) {
			e.pendingWake = false;
			e.wantsRun |= result != LuaMachine.Result.FINISHED && result != LuaMachine.Result.ERROR;
		}
		switch (result) {
			case SLICE -> makeRunnable(e, System.nanoTime());
			case VALUE -> {
				e.listener.onResult(m, result);
				if (!e.removed) {
					makeRunnable(e, System.nanoTime());
				}
			}
			case WAIT -> {
				final boolean more = m.pendingEvents() > 0 || !e.evals.isEmpty();
				e.wantsRun = m.pendingEvents() > 0;
				e.listener.onResult(m, result);
				if (more) {
					makeRunnable(e, System.nanoTime()); // an event/eval that arrived during the slice must not be lost
				} else {
					final long ms = m.waitMillis();
					if (ms > 0 && !stopped) {
						e.timedWake = timer.schedule(() -> wake(m), ms, java.util.concurrent.TimeUnit.MILLISECONDS);
					}
				}
			}
			case FINISHED, ERROR -> {
				entries.remove(m);
				e.removed = true;
				e.listener.onResult(m, result);
			}
		}
	}

	/** Every millisecond: cut slices that overran, raise into slices whose allocation ran away, log the stuck. */
	private void monitorLoop() {
		while (!stopped) {
			try {
				Thread.sleep(1);
			} catch (final InterruptedException ex) {
				return;
			}
			final long now = System.nanoTime();
			for (final Entry e : entries.values()) {
				if (!e.running) {
					continue;
				}
				final long elapsed = now - e.sliceStartNanos;
				if (e.machine.inHostCall) {
					continue; // waiting for the server thread: not CPU, not stuck
				}
				if (elapsed > sliceNanos) {
					e.machine.interruptSlice();
				}
				// stuck = CPU time, not wall time: a slice waiting for the server thread burns nothing
				final long cpu = THREADS.getThreadCpuTime(e.runningThreadId) - e.sliceStartCpu;
				if (cpu > 10 * sliceNanos && cpu < 10 * sliceNanos + 3_000_000L) {
					VirtualMinecraft.LOGGER.warn("Computer '{}' has ignored its slice for {} ms of CPU (a Java call that never polls?)", e.machine.host().name(), cpu / 1_000_000L);
				}
				if (cpu > 5_000_000_000L && cpu < 5_003_000_000L) {
					VirtualMinecraft.LOGGER.error("Computer '{}' stuck for 5 s of CPU; killing", e.machine.host().name());
					e.machine.kill();
				}
				if (!e.raisedThisSlice) {
					final long alloc = THREADS.getThreadAllocatedBytes(e.runningThreadId) - e.sliceStartAlloc;
					// a slice that keeps allocating without polling (a Java call that never returns to Lua): the walk
					// after the slice is the real budget, so this only has to catch the absurd — 16 budgets in one slice.
					// A legitimate slice easily churns 2 budgets of garbage (json.decode of a 47 KB file did, at 4 MB).
					if (alloc > 16 * e.machine.host().memoryCapBytes()) {
						e.raisedThisSlice = true;
						e.machine.raise("not enough memory (" + (alloc >> 20) + " MB allocated in one slice)");
					}
				}
			}
		}
	}
}
