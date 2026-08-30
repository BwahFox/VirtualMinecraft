package dev.virtualminecraft.computer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** S1 harness for {@link MachineScheduler} outside Minecraft. {@code ./gradlew schedulerTest}. */
public final class SchedulerTest {
	private static int failures;

	private static void check(final boolean ok, final String what) {
		System.out.println((ok ? "  PASS " : "  FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}

	static class Host implements LuaMachine.Host {
		final String name;
		final List<String> log = new ArrayList<>();
		volatile long cap = 4L << 20;

		Host(final String name) {
			this.name = name;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public synchronized void log(final int level, final String message) {
			log.add(message);
		}

		@Override
		public long clock(final int kind) {
			return System.nanoTime();
		}

		@Override
		public String call(final int fn, final String payload) {
			return payload;
		}

		@Override
		public long memoryCapBytes() {
			return cap;
		}
	}

	public static void main(final String[] args) throws Exception {
		// 4 workers, 5 ms slices, 25 % of a core each — the design's defaults
		final MachineScheduler sched = new MachineScheduler(4, 5_000_000L, 0.25);

		// 1. one hundred spinning machines share the pool; a waiting one is woken by an event promptly
		final List<LuaMachine> spinners = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			final LuaMachine m = new LuaMachine(new Host("spin" + i), "C = 0 while true do C = C + 1 end", "spin");
			spinners.add(m);
			sched.submit(m, (mm, r) -> {
			});
		}
		final Host hw = new Host("waiter");
		final CountDownLatch woke = new CountDownLatch(1);
		final long[] wakeNanos = new long[1];
		final LuaMachine waiter = new LuaMachine(hw, """
			while true do
				local e = vmc.event_next()
				if e then vmc.log(1, "got " .. e) else coroutine.yield("wait") end
			end
			""", "waiter");
		sched.submit(waiter, (m, r) -> {
			if (r == LuaMachine.Result.WAIT && !hw.log.isEmpty() && woke.getCount() > 0) {
				wakeNanos[0] = System.nanoTime();
				woke.countDown();
			}
		});
		Thread.sleep(1500);
		final long sent = System.nanoTime();
		waiter.pushEvent("ping");
		sched.wake(waiter);
		check(woke.await(2, TimeUnit.SECONDS), "waiting machine woke on an event under load");
		final double latencyMs = (wakeNanos[0] - sent) / 1e6;
		System.out.printf(Locale.ROOT, "    wake latency under 100 spinners: %.1f ms%n", latencyMs);
		check(latencyMs < 250, "wake latency < 250 ms with 100 runnable machines on 4 workers");
		// fairness: every spinner got slices; counts within a wide band of each other
		int min = Integer.MAX_VALUE, max = 0;
		for (final LuaMachine m : spinners) {
			final String d = sched.describe(m);
			final int slices = Integer.parseInt(d.replaceAll(".*slices (\\d+).*", "$1"));
			min = Math.min(min, slices);
			max = Math.max(max, slices);
		}
		System.out.printf(Locale.ROOT, "    slices per spinner after ~1.5 s: min %d max %d; runnable now %d%n", min, max, sched.runnableCount());
		check(min >= 1 && max <= 4 * Math.max(1, min) + 4, "round-robin is roughly fair");

		// 2. CPU share: with the pool otherwise idle, one spinner is held to ~25 % of a core
		for (final LuaMachine m : spinners) {
			sched.remove(m);
		}
		Thread.sleep(200);
		final LuaMachine solo = new LuaMachine(new Host("solo"), "C = 0 while true do C = C + 1 end", "solo");
		sched.submit(solo, (m, r) -> {
		});
		Thread.sleep(2000);
		final String d = sched.describe(solo);
		final double cpuMs = Double.parseDouble(d.replaceAll(".*cpu ([0-9.]+) ms.*", "$1"));
		System.out.printf(Locale.ROOT, "    solo spinner over 2 s: %.0f ms CPU (%s)%n", cpuMs, d);
		check(cpuMs > 300 && cpuMs < 800, "a lone spinner is held near its 25 % share (500 ms of 2 s)");
		sched.remove(solo);

		// 3. the allocation monitor raises into a runaway slice; the machine survives inside pcall
		final Host hm = new Host("mem");
		final CountDownLatch done = new CountDownLatch(1);
		final LuaMachine mem = new LuaMachine(hm, """
			local ok, e = pcall(function() local t = {} for i = 1, 1e7 do t[i] = i end end)
			vmc.log(1, "pcall: " .. tostring(ok) .. " " .. tostring(e))
			coroutine.yield("wait")
			""", "mem");
		sched.submit(mem, (m, r) -> {
			if (r == LuaMachine.Result.WAIT) {
				done.countDown();
			}
		});
		check(done.await(5, TimeUnit.SECONDS) && hm.log.stream().anyMatch(l -> l.contains("not enough memory")), "monitor raised 'not enough memory' into a runaway slice: " + hm.log);

		// 4. the walk catches a heap that grows slowly (never a burst) and raises at the next slice
		final Host hs = new Host("slow");
		final AtomicInteger errors = new AtomicInteger();
		final CountDownLatch dead = new CountDownLatch(1);
		final LuaMachine slow = new LuaMachine(hs, """
			T = {}
			local i = 0
			while true do
				for k = 1, 2000 do i = i + 1; T[i] = { i, tostring(i) } end
				coroutine.yield()
			end
			""", "slow");
		sched.submit(slow, (m, r) -> {
			if (r == LuaMachine.Result.ERROR) {
				errors.incrementAndGet();
				dead.countDown();
			}
		});
		check(dead.await(20, TimeUnit.SECONDS) && String.valueOf(slow.error()).contains("not enough memory"), "slow growth is caught by the walk: " + slow.error() + " (" + sched.describe(slow) + ")");

		// 5. wake while running: a kernel that blocks in host calls while events pour in must never run on two workers
		final Host hr = new Host("race") {
			@Override
			public String call(final int fn, final String payload) {
				try {
					Thread.sleep(2);
				} catch (final InterruptedException ignored) {
				}
				return payload;
			}
		};
		final LuaMachine race = new LuaMachine(hr, """
			C = 0
			while true do
				local e = vmc.event_next()
				if e then C = C + 1; vmc.call(1, e) else coroutine.yield("wait") end
			end
			""", "race");
		final AtomicInteger raceErrors = new AtomicInteger();
		sched.submit(race, (m, r) -> {
			if (r == LuaMachine.Result.ERROR) {
				raceErrors.incrementAndGet();
			}
		});
		int pushed = 0;
		final long until = System.nanoTime() + 2_000_000_000L;
		while (System.nanoTime() < until) {
			if (race.pushEvent("e")) {
				pushed++;
			}
			sched.wake(race);
			sched.eval(race, "return C");
			Thread.sleep(1);
		}
		for (int i = 0; i < 100 && race.pendingEvents() > 0; i++) {
			Thread.sleep(50); // the kernel drains ~1 event per 2 ms host call
		}
		Thread.sleep(100);
		final String c = sched.eval(race, "return C").get(2, TimeUnit.SECONDS);
		System.out.printf(Locale.ROOT, "    race: pushed %d events, C=%s, errors %d, %s%n", pushed, c, raceErrors.get(), sched.describe(race));
		check(raceErrors.get() == 0 && !race.isFinished() && Integer.parseInt(c) == pushed, "events + wakes + evals during blocking host calls: no corruption, every event counted");
		sched.remove(race);

		// 6. remove/shutdown are clean
		check(!sched.contains(slow), "a finished machine leaves the scheduler");
		sched.shutdown();
		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
