package dev.virtualminecraft.computer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.function.LuaClosure;
import org.squiddev.cobalt.lib.CoreLibraries;

/**
 * The speed reference for the S0 gate (ROADMAP §7h §10, S0 point 6): the same {@code bench.lua} under Cobalt,
 * CC: Tweaked's Java Lua. Bench-only; Cobalt is never shipped.
 */
final class CobaltRef {
	private CobaltRef() {
	}

	/** Best-of-three wall time in ms. */
	static double run(final String bench) throws Exception {
		double best = Double.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			final LuaState state = LuaState.builder().build();
			CoreLibraries.standardGlobals(state);
			final LuaClosure fn = LoadState.load(state, new ByteArrayInputStream(bench.getBytes(StandardCharsets.UTF_8)), "=bench", state.globals());
			final long s = System.nanoTime();
			final var result = LuaThread.runMain(state, fn);
			final double ms = (System.nanoTime() - s) / 1e6;
			System.out.printf(Locale.ROOT, "    cobalt run %d: %.0f ms  (result %s)%n", i, ms, result.first());
			best = Math.min(best, ms);
		}
		return best;
	}
}
