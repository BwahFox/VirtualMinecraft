package dev.virtualminecraft.computer;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.ValType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.Deflater;

/**
 * The S0 harness (ROADMAP §7h §10, S0): drives {@code lua.wasm} outside Minecraft and checks the six acceptance
 * points — sandboxed imports, longjmp/pcall, preemption, the memory cap, snapshot/restore mid-coroutine, and
 * speed against Cobalt. Run with {@code ./gradlew machineBench} (add {@code -PbenchArgs="--interp"} for the
 * interpreter instead of the compiler).
 */
public final class MachineBench {
	private static final Path WASM = Path.of("run/toolchain/lua.wasm");

	/** One machine: an instance plus the host side of the vmc imports. */
	static final class Host {
		final Instance instance;
		final Memory mem;
		final ExportFunction alloc, free, boot, run, eval, error, yieldReason, yieldValue, memUsed, setMemCap;
		long sliceStart;
		long sliceNs = Long.MAX_VALUE;
		int polls;
		final List<String> log = new ArrayList<>();
		final List<byte[]> events = new ArrayList<>();

		Host(final WasmModule module, final boolean compiled, final int maxPages) {
			final var imports = ImportValues.builder()
				.addFunction(new HostFunction("vmc", "poll", List.of(), List.of(ValType.I32), (inst, args) -> {
					polls++;
					return new long[] { System.nanoTime() - sliceStart > sliceNs ? 1 : 0 };
				}))
				.addFunction(new HostFunction("vmc", "log", List.of(ValType.I32, ValType.I32, ValType.I32), List.of(), (inst, args) -> {
					log.add(args[0] + ":" + inst.memory().readString((int) args[1], (int) args[2]));
					return null;
				}))
				.addFunction(new HostFunction("vmc", "seed", List.of(), List.of(ValType.I32), (inst, args) -> new long[] { ThreadLocalRandom.current().nextInt() & 0xffffffffL }))
				.addFunction(new HostFunction("vmc", "clock", List.of(ValType.I32), List.of(ValType.I64), (inst, args) -> new long[] { System.nanoTime() }))
				.addFunction(new HostFunction("vmc", "event_next", List.of(ValType.I32, ValType.I32), List.of(ValType.I32), (inst, args) -> {
					if (events.isEmpty()) {
						return new long[] { -1 };
					}
					final byte[] e = events.remove(0);
					final int n = Math.min(e.length, (int) args[1]);
					inst.memory().write((int) args[0], e, 0, n);
					return new long[] { n };
				}))
				.addFunction(new HostFunction("vmc", "call", List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32), (inst, args) -> new long[] { -38 }))
				.build();
			final var b = Instance.builder(module).withImportValues(imports).withStart(false).withMemoryLimits(new MemoryLimits(32, maxPages));
			if (compiled) {
				b.withMachineFactory(MachineFactoryCompiler.compile(module));
			}
			instance = b.build();
			instance.export("_initialize").apply();
			mem = instance.memory();
			alloc = instance.export("vmc_alloc");
			free = instance.export("vmc_free");
			boot = instance.export("vmc_boot");
			run = instance.export("vmc_run");
			eval = instance.export("vmc_eval");
			error = instance.export("vmc_error");
			yieldReason = instance.export("vmc_yield_reason");
			yieldValue = instance.export("vmc_yield_value");
			memUsed = instance.export("vmc_mem_used");
			setMemCap = instance.export("vmc_set_mem_cap");
		}

		int bootChunk(final String src, final String name) {
			final byte[] s = src.getBytes(StandardCharsets.UTF_8);
			final byte[] n = name.getBytes(StandardCharsets.UTF_8);
			final int ps = (int) alloc.apply(s.length)[0];
			final int pn = (int) alloc.apply(n.length)[0];
			mem.write(ps, s, 0, s.length);
			mem.write(pn, n, 0, n.length);
			final int r = (int) boot.apply(ps, s.length, pn, n.length)[0];
			free.apply(ps);
			free.apply(pn);
			return r;
		}

		/** 0 finished, 1 yielded, 2 error. */
		int step() {
			sliceStart = System.nanoTime();
			return (int) run.apply()[0];
		}

		String eval(final String src) {
			final byte[] s = src.getBytes(StandardCharsets.UTF_8);
			final int ps = (int) alloc.apply(s.length)[0];
			final int po = (int) alloc.apply(65536)[0];
			mem.write(ps, s, 0, s.length);
			final int n = (int) eval.apply(ps, s.length, po, 65536)[0];
			final String out = mem.readString(po, Math.abs(n));
			free.apply(ps);
			free.apply(po);
			return n < 0 ? "ERROR: " + out : out;
		}

		String error() {
			final int po = (int) alloc.apply(4096)[0];
			final int n = (int) error.apply(po, 4096)[0];
			final String s = mem.readString(po, n);
			free.apply(po);
			return s;
		}

		String yieldValue() {
			final int po = (int) alloc.apply(256)[0];
			final int n = (int) yieldValue.apply(po, 256)[0];
			final String s = mem.readString(po, n);
			free.apply(po);
			return s;
		}

		int yieldReason() {
			return (int) yieldReason.apply()[0];
		}

		/** The whole machine state: memory pages plus every global (the shadow stack pointer lives there). */
		Snapshot snapshot() {
			final int pages = mem.pages();
			final byte[] bytes = new byte[pages * Memory.PAGE_SIZE];
			// readBytes in page-sized chunks to keep it simple
			for (int p = 0; p < pages; p++) {
				final byte[] chunk = mem.readBytes(p * Memory.PAGE_SIZE, Memory.PAGE_SIZE);
				System.arraycopy(chunk, 0, bytes, p * Memory.PAGE_SIZE, Memory.PAGE_SIZE);
			}
			final long[] globals = new long[instance.globalCount()];
			for (int i = 0; i < globals.length; i++) {
				globals[i] = instance.global(i).getValue();
			}
			return new Snapshot(pages, bytes, globals);
		}

		void restore(final Snapshot s) {
			if (mem.pages() < s.pages) {
				mem.grow(s.pages - mem.pages());
			}
			mem.write(0, s.bytes, 0, s.bytes.length);
			for (int i = 0; i < s.globals.length; i++) {
				instance.global(i).setValue(s.globals[i]);
			}
		}
	}

	record Snapshot(int pages, byte[] bytes, long[] globals) {
	}

	private static int failures;

	private static void check(final boolean ok, final String what) {
		System.out.println((ok ? "  PASS " : "  FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}

	public static void main(final String[] args) throws Exception {
		final boolean compiled = !List.of(args).contains("--interp");
		final byte[] wasm = Files.readAllBytes(WASM);
		final WasmModule module = Parser.parse(wasm);
		System.out.println("lua.wasm: " + wasm.length + " bytes, " + (compiled ? "compiler" : "interpreter"));

		// (1) sandbox: only vmc.* imports
		final List<String> foreign = new ArrayList<>();
		module.importSection().stream().forEach(i -> {
			if (!"vmc".equals(i.module())) {
				foreign.add(i.module() + "." + i.name());
			}
		});
		System.out.println("(1) imports: " + module.importSection().importCount() + ", foreign: " + foreign);
		check(foreign.isEmpty(), "module imports only vmc.*");

		final long t0 = System.nanoTime();
		Host h = new Host(module, compiled, 1024);
		System.out.printf(Locale.ROOT, "instantiate: %.1f ms%n", (System.nanoTime() - t0) / 1e6);
		check(h.bootChunk("return 1", "boot") == 0, "boot");
		check(h.step() == 0, "trivial chunk finishes");

		// (2) longjmp: pcall catches an error; error() unwinds through the C interpreter
		final String r2 = h.eval("local ok, e = pcall(error, 'boom'); return tostring(ok) .. ' ' .. tostring(e)");
		System.out.println("(2) " + r2);
		check(r2.equals("false boom"), "pcall/error (longjmp) works");
		final String r2b = h.eval("return select('#', pcall(function() local t = nil; return t.x end))");
		check(r2b.equals("2"), "runtime error inside pcall: " + r2b);
		final String r2c = h.eval("local ok, e = pcall(string.rep, 'x', -1); return tostring(ok)");
		check(r2c.equals("true"), "C library error path: " + r2c);

		// (3) preemption: a runaway loop yields within the slice
		h.bootChunk("while true do end", "spin");
		h.sliceNs = 5_000_000L;
		boolean preempted = true;
		long worst = 0;
		for (int i = 0; i < 20; i++) {
			final long s = System.nanoTime();
			final int st = h.step();
			final long took = System.nanoTime() - s;
			worst = Math.max(worst, took);
			if (st != 1 || h.yieldReason() != 0) {
				preempted = false;
			}
		}
		System.out.printf(Locale.ROOT, "(3) 20 slices of `while true do end`, worst %.2f ms (slice 5 ms), polls %d%n", worst / 1e6, h.polls);
		check(preempted && worst < 50_000_000L, "runaway loop is preempted by the count hook");
		h.sliceNs = Long.MAX_VALUE;

		// (4) memory cap: 4 MB Lua heap; over budget is an error and the machine survives
		h.bootChunk("return 1", "boot");
		h.step();
		final String r4 = h.eval("local t = {} for i = 1, 1e7 do t[i] = i end return #t");
		System.out.println("(4) " + r4 + "; heap used after: " + h.memUsed.apply()[0] + " bytes, pages " + h.mem.pages());
		check(r4.contains("not enough memory"), "4 MB cap raises 'not enough memory'");
		check(h.eval("return 1 + 1").equals("2"), "machine still works after OOM");
		// 100k integers = 1.6 MB of TValues, ~3 MB at the last rehash; 200k would need 6 MB at its doubling and is a fair refusal.
		final String r4b = h.eval("local t = {} for i = 1, 1e5 do t[i] = i end return #t");
		check(r4b.equals("100000"), "a 100k-entry table fits in 4 MB: " + r4b);

		// (5) snapshot / restore mid-coroutine: a nested coroutine inside a pcall, counting; freeze at a yield,
		// thaw into a *fresh* instance, the count continues.
		final String prog = """
			N = 0
			local inner = coroutine.wrap(function()
				while true do
					N = N + 1
					local a = {}
					for i = 1, 50 do a[i] = i * N end
					coroutine.yield(#a)
				end
			end)
			local function loop()
				while true do
					local k = inner()
					if N % 1000 == 0 then
						local got = vmc.yield("count", N)
						LAST = got
					end
				end
			end
			pcall(loop)
			""";
		check(h.bootChunk(prog, "snap") == 0, "snapshot program boots");
		for (int i = 0; i < 3; i++) {
			check(h.step() == 1 && h.yieldReason() == 2 && h.yieldValue().equals("count"), "yielded 'count' #" + i);
		}
		final String nBefore = h.eval("return N");
		final long ts = System.nanoTime();
		final Snapshot snap = h.snapshot();
		final long tsnap = System.nanoTime() - ts;
		final Deflater d = new Deflater(Deflater.BEST_SPEED);
		d.setInput(snap.bytes);
		d.finish();
		final byte[] zbuf = new byte[snap.bytes.length];
		int zlen = 0;
		while (!d.finished()) {
			zlen += d.deflate(zbuf, zlen, zbuf.length - zlen);
		}
		final long tz = System.nanoTime() - ts;
		System.out.printf(Locale.ROOT, "(5) N=%s before; snapshot %d pages = %d KB in %.2f ms, deflated to %d KB in %.2f ms total%n",
			nBefore, snap.pages, snap.bytes.length / 1024, tsnap / 1e6, zlen / 1024, tz / 1e6);
		// keep running the original a bit so we know the restored one is independent
		h.step();
		h.step();
		final Host h2 = new Host(module, compiled, 1024);
		final long tr = System.nanoTime();
		h2.restore(snap);
		final long trestore = System.nanoTime() - tr;
		final String nRestored = h2.eval("return N");
		check(nRestored.equals(nBefore), "restored instance sees N=" + nRestored + " (expected " + nBefore + ")");
		final int st = h2.step();
		final String nAfter = h2.eval("return N");
		System.out.printf(Locale.ROOT, "    restore %.2f ms; after one more step: status %d, N=%s, LAST=%s%n", trestore / 1e6, st, nAfter, h2.eval("return tostring(LAST)"));
		check(st == 1 && Long.parseLong(nAfter) == Long.parseLong(nBefore) + 1000, "restored coroutine continues counting");
		check(h2.eval("return select(2, pcall(error, 'again'))").equals("again"), "pcall still works after restore");
		check(tz < 20_000_000L, "snapshot + deflate under 20 ms");

		// (6) speed vs Cobalt
		final String bench = Files.readString(Path.of("src/test/resources/bench.lua"));
		System.out.println("(6) benchmark (ms):");
		final double[] wasmMs = new double[3];
		for (int i = 0; i < 3; i++) {
			final Host hb = new Host(module, compiled, 1024);
			hb.setMemCap.apply(64 << 20);
			check(hb.bootChunk(bench, "bench") == 0, "bench boots");
			final long s = System.nanoTime();
			final int r = hb.step();
			wasmMs[i] = (System.nanoTime() - s) / 1e6;
			if (r != 0) {
				System.out.println("    wasm bench failed: " + r + " " + hb.error());
			}
			System.out.printf(Locale.ROOT, "    wasm run %d: %.0f ms  %s%n", i, wasmMs[i], hb.log.isEmpty() ? "" : hb.log.get(hb.log.size() - 1));
		}
		final double cobalt = CobaltRef.run(bench);
		final double best = Math.min(wasmMs[0], Math.min(wasmMs[1], wasmMs[2]));
		System.out.printf(Locale.ROOT, "    cobalt best: %.0f ms; wasm best: %.0f ms; ratio %.2fx%n", cobalt, best, best / cobalt);
		check(best <= 3 * cobalt, "wasm within 3x of Cobalt");

		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
