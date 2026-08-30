package dev.virtualminecraft.computer;

import dev.virtualminecraft.util.Threads;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * S1 harness for the machine core outside Minecraft (ROADMAP §7h §10, S1): the kernel/host protocol, events,
 * preemption, eval, the sandbox, and the memory meter's two halves. {@code ./gradlew luaMachineTest}.
 */
public final class LuaMachineTest {
	private static int failures;

	private static void check(final boolean ok, final String what) {
		System.out.println((ok ? "  PASS " : "  FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}

	static final class TestHost implements LuaMachine.Host {
		final List<String> log = new ArrayList<>();
		long cap = 4L << 20;

		@Override
		public String name() {
			return "test";
		}

		@Override
		public void log(final int level, final String message) {
			log.add(level + ":" + message);
			System.out.println("    [lua " + level + "] " + message);
		}

		@Override
		public long clock(final int kind) {
			return System.nanoTime();
		}

		@Override
		public String call(final int fn, final String payload) throws LuaMachine.MachineError {
			if (fn == 1) {
				return "echo:" + payload;
			}
			throw new LuaMachine.MachineError("no such syscall " + fn);
		}

		@Override
		public long memoryCapBytes() {
			return cap;
		}
	}

	public static void main(final String[] args) throws Exception {
		final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		final long me = Threads.id(Thread.currentThread());

		// 1. kernel/host protocol: wait, events, values
		final TestHost h1 = new TestHost();
		final LuaMachine m1 = new LuaMachine(h1, """
			N = 0
			while true do
				local e = vmc.event_next()
				if e then
					N = N + 1
					vmc.log(1, "got " .. e)
					if e == "quit" then return end
					if e == "ask" then local r = coroutine.yield("answer", N); vmc.log(1, "host said " .. tostring(r)) end
				else
					coroutine.yield("wait")
				end
			end
			""", "kernel");
		check(m1.run() == LuaMachine.Result.WAIT, "kernel waits with no events");
		m1.pushEvent("hello");
		m1.pushEvent("ask");
		check(m1.run() == LuaMachine.Result.VALUE && "answer".equals(m1.yieldValue()), "event delivered, value yielded: " + m1.yieldValue());
		check(m1.run() == LuaMachine.Result.WAIT, "back to waiting");
		check("2".equals(m1.eval("return N")), "eval sees kernel globals: N=" + m1.eval("return N"));
		check("echo:hi".equals(m1.eval("return vmc.call(1, 'hi')")), "vmc.call round trip");
		check("ab-ab-ab".equals(m1.eval("return string.rep('ab', 3, '-')")) && "xxxxx".equals(m1.eval("return string.rep('x', 5)")), "the capped string.rep repeats correctly: " + m1.eval("return string.rep('ab', 3, '-')"));
		check(m1.eval("return vmc.call(99, '')").startsWith("nil\tno such syscall"), "vmc.call error path: " + m1.eval("return vmc.call(99, '')"));
		// the capped string.format / table.concat: the results are unchanged, the metatable path still works, and a
		// result that cannot fit the cap is refused before it is built (the small leftovers, §1b)
		check("ab-7\t 1.50\t42".equals(m1.eval("return string.format('%s-%d', 'ab', 7), string.format('%5.2f', 1.5), ('%d'):format(42)")),
			"the capped string.format formats correctly: " + m1.eval("return string.format('%s-%d', 'ab', 7), string.format('%5.2f', 1.5), ('%d'):format(42)"));
		check("a,b,c\t123\tb-c".equals(m1.eval("return table.concat({'a','b','c'}, ','), table.concat({1,2,3}), table.concat({'a','b','c'}, '-', 2, 3)")),
			"the capped table.concat concatenates correctly: " + m1.eval("return table.concat({'a','b','c'}, ','), table.concat({1,2,3}), table.concat({'a','b','c'}, '-', 2, 3)"));
		check("1,2,3".equals(m1.eval("local p = setmetatable({}, {__index = function(_, k) return tostring(k) end, __len = function() return 3 end}) return table.concat(p, ',')")),
			"table.concat still goes through __index/__len: " + m1.eval("local p = setmetatable({}, {__index = function(_, k) return tostring(k) end, __len = function() return 3 end}) return table.concat(p, ',')"));
		final String bigConcat = m1.eval("local s = string.rep('x', 200000) local t = {} for i = 1, 40 do t[i] = s end return select(2, pcall(table.concat, t))");
		check(bigConcat.contains("not enough memory"), "table.concat of 8 MB into a 4 MB machine is refused: " + bigConcat);
		final String bigFormat = m1.eval("local s = string.rep('x', 300000) local a = {} for i = 1, 20 do a[i] = s end return select(2, pcall(string.format, string.rep('%s', 20), unpack(a)))");
		check(bigFormat.contains("not enough memory"), "string.format of 6 MB into a 4 MB machine is refused: " + bigFormat);
		check("nil".equals(m1.eval("return tostring(__vmc_alloc_check)")), "the size check is not left in the globals");
		m1.pushEvent("quit");
		final LuaMachine.Result rq = m1.run();
		check(rq == LuaMachine.Result.FINISHED && m1.isFinished(), "kernel can finish: " + rq + " status=" + m1.mainThread().getStatus() + " alive=" + m1.mainThread().isAlive());

		// 2. sandbox
		final LuaMachine m2 = new LuaMachine(new TestHost(), "coroutine.yield('wait')", "k");
		m2.run();
		final String s = m2.eval("return type(io), type(os), type(dofile), type(loadfile), type(load), type(string.dump), type(debug.traceback), type(require), type(package)");
		check(s.equals("nil\tnil\tnil\tnil\tfunction\tnil\tfunction\tnil\tnil"), "no io/os/dofile/loadfile/dump/require/package: " + s);
		check(m2.eval("return select(2, load('\\27Lua'))").contains("binary"), "load refuses bytecode");
		check(m2.eval("return load('return 1 + 2')()").equals("3"), "load compiles text");
		check(m2.eval("return select(2, load('return +'))").contains("expected"), "load reports syntax errors: " + m2.eval("return select(2, load('return +'))"));
		check(m2.eval("local ok, e = pcall(error, 'x') return tostring(ok), e").equals("false\tx"), "pcall/error");

		// 3. preemption: a runaway loop yields when the slice is interrupted from another thread
		final LuaMachine m3 = new LuaMachine(new TestHost(), "while true do end", "spin");
		long worst = 0;
		boolean allSlices = true;
		for (int i = 0; i < 10; i++) {
			final Thread timer = new Thread(() -> {
				try {
					Thread.sleep(5);
				} catch (final InterruptedException ignored) {
				}
				m3.interruptSlice();
			});
			timer.start();
			final long t = System.nanoTime();
			final LuaMachine.Result r = m3.run();
			worst = Math.max(worst, System.nanoTime() - t);
			allSlices &= r == LuaMachine.Result.SLICE;
			timer.join();
		}
		System.out.printf(Locale.ROOT, "    10 slices of `while true do end`, worst %.2f ms%n", worst / 1e6);
		check(allSlices && worst < 50_000_000L, "runaway loop is preempted (SLICE)");
		check(m3.eval("return 1 + 1").equals("2"), "eval works while the kernel is suspended mid-loop");

		// 4. memory: exact allocation accounting + the walk + the raise
		final TestHost h4 = new TestHost();
		final LuaMachine m4 = new LuaMachine(h4, """
			local ok, e = pcall(function()
				T = {}
				for i = 1, 1e7 do T[i] = i end
			end)
			vmc.log(1, "pcall: " .. tostring(ok) .. " " .. tostring(e))
			T = nil
			coroutine.yield("wait")
			""", "mem");
		final Thread meter = new Thread(() -> {
			// what the scheduler does: watch the worker's allocation and raise when a slice allocates > 2x the cap
			final long start = threads.getThreadAllocatedBytes(me);
			while (!Thread.currentThread().isInterrupted()) {
				if (threads.getThreadAllocatedBytes(me) - start > 2 * h4.cap) {
					m4.raise("not enough memory");
					return;
				}
				Thread.onSpinWait();
			}
		});
		meter.start();
		final long a0 = threads.getThreadAllocatedBytes(me);
		final LuaMachine.Result r4 = m4.run();
		final long allocated = threads.getThreadAllocatedBytes(me) - a0;
		meter.interrupt();
		meter.join();
		m4.memory().addAllocated(allocated);
		System.out.printf(Locale.ROOT, "    slice allocated %.1f MB; result %s%n", allocated / 1048576.0, r4);
		check(r4 == LuaMachine.Result.WAIT && h4.log.stream().anyMatch(l -> l.contains("not enough memory")), "allocation burst raised 'not enough memory' inside pcall; machine lives");
		final long est = m4.memory().walk(m4.state(), null == null ? machineMain(m4) : null);
		System.out.printf(Locale.ROOT, "    walk: %d objects, estimate %d KB in %.2f ms%n", m4.memory().lastWalkObjects(), est / 1024, m4.memory().lastWalkNanos() / 1e6);
		check(est < 512 * 1024, "after the burst the live estimate is small again");
		final LuaMachine m5 = new LuaMachine(h4, """
			T = {}
			for i = 1, 100000 do T[i] = { i, tostring(i) } end
			coroutine.yield("wait")
			""", "live");
		m5.run();
		final long est5 = m5.memory().walk(m5.state(), machineMain(m5));
		System.out.printf(Locale.ROOT, "    100k small tables: %d objects, estimate %d KB in %.2f ms%n", m5.memory().lastWalkObjects(), est5 / 1024, m5.memory().lastWalkNanos() / 1e6);
		check(est5 > 4L << 20 && m5.memory().lastWalkNanos() < 200_000_000L, "a 100k-table heap is estimated over 4 MB and walked in < 200 ms");

		// 5. kill
		final LuaMachine m6 = new LuaMachine(new TestHost(), "while true do end", "spin");
		new Thread(() -> {
			try {
				Thread.sleep(5);
			} catch (final InterruptedException ignored) {
			}
			m6.kill();
		}).start();
		final LuaMachine.Result r6 = m6.run();
		check(r6 == LuaMachine.Result.ERROR && m6.isFinished(), "kill ends a runaway machine: " + r6 + " " + m6.error());
		// a pcall loop must not survive a kill
		final LuaMachine m7 = new LuaMachine(new TestHost(), "while true do pcall(function() while true do end end) end", "pcallspin");
		new Thread(() -> {
			try {
				Thread.sleep(5);
			} catch (final InterruptedException ignored) {
			}
			m7.kill();
		}).start();
		final long t7 = System.nanoTime();
		final LuaMachine.Result r7 = m7.run();
		check(r7 == LuaMachine.Result.ERROR && m7.isFinished() && System.nanoTime() - t7 < 1_000_000_000L, "kill is not catchable by pcall: " + r7);

		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static org.squiddev.cobalt.LuaThread machineMain(final LuaMachine m) {
		return m.mainThread();
	}
}
