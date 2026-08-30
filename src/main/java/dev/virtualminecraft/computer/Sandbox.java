package dev.virtualminecraft.computer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaString;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.compiler.CompileException;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.function.LibFunction;
import org.squiddev.cobalt.function.LuaClosure;
import org.squiddev.cobalt.function.RegisteredFunction;
import org.squiddev.cobalt.lib.BaseLib;
import org.squiddev.cobalt.lib.CoroutineLib;
import org.squiddev.cobalt.lib.MathLib;
import org.squiddev.cobalt.lib.StringLib;
import org.squiddev.cobalt.lib.TableLib;

/**
 * Builds a machine's globals (ROADMAP §7h §1d): the standard pure libraries and nothing that reaches outside —
 * no {@code io}, {@code os}, {@code package}, {@code debug} (only {@code debug.traceback}), no {@code dofile} /
 * {@code loadfile} / {@code loadstring} / {@code string.dump}, and {@code load} that refuses bytecode. What the
 * machine can do to the world is exactly the {@code vmc.*} table.
 */
final class Sandbox {
	private Sandbox() {
	}

	static void install(final LuaState state, final LuaMachine machine) throws LuaError {
		BaseLib.add(state);
		TableLib.add(state);
		StringLib.add(state);
		MathLib.add(state);
		CoroutineLib.add(state);
		final LuaTable g = state.globals();
		// Cobalt's base library has no print/dofile/loadfile (they live in its "system" library, which we do not add).
		g.rawset("loadstring", Constants.NIL);
		g.rawset("getfenv", Constants.NIL);
		g.rawset("setfenv", Constants.NIL);
		final LuaValue string = g.rawget("string");
		if (string instanceof LuaTable st) {
			st.rawset("dump", Constants.NIL);
			// string.rep is the one call that builds a huge object in a single step; refuse beyond the budget (§1b)
			st.rawset("rep", LibFunction.createV((st2, args) -> {
				final LuaString str = args.arg(1).checkLuaString();
				final long n = Math.max(0, args.arg(2).checkLong());
				final LuaString sep = args.arg(3).isNil() ? null : args.arg(3).checkLuaString();
				final long total = str.length() * n + (sep == null ? 0 : sep.length() * Math.max(0, n - 1));
				if (n > 0 && total > budget(machine)) {
					throw new LuaError("not enough memory");
				}
				if (n == 0 || total == 0) {
					return ValueFactory.valueOf("");
				}
				final byte[] out = new byte[(int) total];
				int at = 0;
				for (long i = 0; i < n; i++) {
					if (i > 0 && sep != null) {
						at = sep.copyTo(out, at); // copyTo returns the offset after the copy, not the length
					}
					at = str.copyTo(out, at);
				}
				return ValueFactory.valueOf(out);
			}));
		}
		// load(chunk [, name [, mode [, env]]]) — text only; a function chunk is read to completion first.
		g.rawset("load", LibFunction.createV((st, args) -> {
			final LuaValue chunk = args.arg(1);
			final String name = args.arg(2).isNil() ? "=(load)" : args.arg(2).toString();
			final LuaValue env = args.arg(4).isNil() ? st.globals() : args.arg(4);
			final byte[] bytes;
			if (chunk.isString()) {
				final LuaString s = chunk.checkLuaString();
				bytes = new byte[s.length()];
				s.copyTo(bytes, 0);
			} else {
				return ValueFactory.varargsOf(Constants.NIL, ValueFactory.valueOf("load: only string chunks are supported"));
			}
			if (bytes.length > 0 && bytes[0] == 27) {
				return ValueFactory.varargsOf(Constants.NIL, ValueFactory.valueOf("load: binary chunks are not allowed"));
			}
			try {
				return LoadState.load(st, new ByteArrayInputStream(bytes), name, env);
			} catch (final CompileException e) {
				return ValueFactory.varargsOf(Constants.NIL, ValueFactory.valueOf(e.getMessage()));
			}
		}));
		g.rawset("print", LibFunction.createV((st, args) -> {
			final StringBuilder sb = new StringBuilder();
			for (int i = 1; i <= args.count(); i++) {
				if (i > 1) {
					sb.append('\t');
				}
				sb.append(args.arg(i).toString());
			}
			machine.host().log(1, sb.toString());
			return Constants.NONE;
		}));
		g.rawset("collectgarbage", LibFunction.createV((st, args) -> {
			final String opt = args.arg(1).optString("collect");
			return switch (opt) {
				case "count" -> ValueFactory.valueOf(machine.memory().estimate() / 1024.0);
				case "collect", "step" -> ValueFactory.valueOf(0);
				default -> ValueFactory.valueOf(0);
			};
		}));
		final LuaTable debug = new LuaTable();
		debug.rawset("traceback", LibFunction.createV((st, args) -> {
			final LuaValue msg = args.arg(1);
			// Cobalt keeps tracebacks on LuaError; for a plain call we return the message as-is.
			return msg.isNil() ? ValueFactory.valueOf("stack traceback:") : msg;
		}));
		g.rawset("debug", debug);
		g.rawset("vmc", machine.vmcLibrary());
		g.rawset("_VERSION", ValueFactory.valueOf("Lua 5.2 (Cobalt)"));
		g.rawset("_HOST", ValueFactory.valueOf("VirtualMinecraft Computer"));
		// string.format and table.concat are the other two calls that can build a string far larger than their
		// arguments; like string.rep they are sized before the allocation instead of after it (§1b). The size check
		// is Java, the wrapping is Lua (BOOTSTRAP), so the real calls keep their metatable and coroutine behaviour.
		g.rawset(CHECK, LibFunction.createV((st, args) -> {
			final long limit = budget(machine);
			final long total = args.arg(1).checkInteger() == 1 ? formatSize(args) : concatSize(state, args, limit);
			if (total > limit) {
				throw new LuaError("not enough memory");
			}
			return Constants.NONE;
		}));
		try {
			final LuaClosure wrap = LoadState.load(state,
				new ByteArrayInputStream(BOOTSTRAP.getBytes(StandardCharsets.UTF_8)), "=(sandbox)", g);
			LuaThread.run(new LuaThread(state, wrap), Constants.NONE);
		} catch (final CompileException e) {
			throw new LuaError("sandbox: " + e.getMessage());
		}
	}

	/** The Java size check; {@link #BOOTSTRAP} closes over it and drops it from the globals. Names must match. */
	private static final String CHECK = "__vmc_alloc_check";

	private static final String BOOTSTRAP = """
		local size = _G["__vmc_alloc_check"]
		local rawformat, rawconcat = string.format, table.concat
		_G["__vmc_alloc_check"] = nil
		string.format = function(fmt, ...)
			size(1, fmt, ...)
			return rawformat(fmt, ...)
		end
		table.concat = function(t, sep, i, j)
			size(2, t, sep, i, j)
			return rawconcat(t, sep, i, j)
		end
		""";

	/** How big one allocation may be: what is left of the cap once half the live estimate is spoken for. */
	private static long budget(final LuaMachine machine) {
		return machine.host().memoryCapBytes() - machine.memory().estimate() / 2;
	}

	/**
	 * An upper bound on what {@code string.format(fmt, ...)} would produce, computed without building it. Cobalt's
	 * {@code FormatDesc} caps width and precision at two digits, so every directive but {@code %s} / {@code %q} is
	 * worth at most ~100 characters; those two are worth their argument's length.
	 */
	private static long formatSize(final Varargs args) {
		if (!(args.arg(2) instanceof LuaString fmt)) {
			return 0; // not a string: string.format will raise its own error
		}
		final int n = fmt.length();
		long total = 0;
		int arg = 3;
		for (int p = 0; p < n; p++) {
			if (fmt.charAt(p) != '%') {
				total++;
				continue;
			}
			p++;
			if (p >= n || fmt.charAt(p) == '%') {
				total++;
				continue;
			}
			while (p < n && "-+ #0".indexOf(fmt.charAt(p)) >= 0) {
				p++;
			}
			int width = 0;
			for (int d = 0; d < 2 && p < n && isDigit(fmt.charAt(p)); d++) {
				width = width * 10 + fmt.charAt(p++) - '0';
			}
			int precision = 0;
			if (p < n && fmt.charAt(p) == '.') {
				p++;
				for (int d = 0; d < 2 && p < n && isDigit(fmt.charAt(p)); d++) {
					precision = precision * 10 + fmt.charAt(p++) - '0';
				}
			}
			final int conversion = p < n ? fmt.charAt(p) : 0;
			total += Math.max(width, precision) + SPEC_SLACK;
			if (conversion == 's' || conversion == 'q') {
				total += valueSize(args.arg(arg));
			}
			arg++;
		}
		return total;
	}

	/**
	 * The exact size of {@code table.concat(t, sep, i, j)} for a plain table, counted without allocating and
	 * abandoned as soon as it cannot pass. A table with a metatable is left to Cobalt (its {@code __index} may run
	 * Lua, and the allocation monitor still covers it).
	 */
	private static long concatSize(final LuaState state, final Varargs args, final long limit) throws LuaError {
		if (!(args.arg(2) instanceof LuaTable t) || t.getMetatable(state) != null) {
			return 0;
		}
		final LuaString sep = args.arg(3) instanceof LuaString s ? s : null;
		final int from = args.arg(4).isNil() ? 1 : args.arg(4).checkInteger();
		final int to = args.arg(5).isNil() ? t.length() : args.arg(5).checkInteger();
		long total = 0;
		for (int k = from; k <= to && total <= limit; k++) {
			total += valueSize(t.rawget(k));
			if (sep != null && k > from) {
				total += sep.length();
			}
		}
		return total;
	}

	/** What one value contributes to a formatted or concatenated string. */
	private static long valueSize(final LuaValue v) {
		if (v instanceof LuaString s) {
			return s.length();
		}
		// tostring of anything else is short ("table: 0x…", "1e+300"); only a __tostring metamethod could be long,
		// and whatever it returns is already live, so the allocation monitor is the backstop there.
		return 64;
	}

	private static boolean isDigit(final int c) {
		return c >= '0' && c <= '9';
	}

	/** Flags, sign, decimal point and the rest of what a directive adds beyond its width. */
	private static final int SPEC_SLACK = 16;

	/** Not used: keeps the RegisteredFunction import honest for later additions. */
	static RegisteredFunction[] none() {
		return new RegisteredFunction[0];
	}
}
