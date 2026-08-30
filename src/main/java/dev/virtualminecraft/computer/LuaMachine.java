package dev.virtualminecraft.computer;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;
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
import org.squiddev.cobalt.function.LuaClosure;
import org.squiddev.cobalt.function.RegisteredFunction;
import org.squiddev.cobalt.interrupt.InterruptAction;

/**
 * One Computer's machine (ROADMAP §7h §1): a Cobalt Lua state with sandboxed globals, the {@code vmc.*} library
 * (§1e), a bounded event queue, and the run protocol the scheduler drives. The boot chunk runs as the main
 * coroutine; every time it yields the machine returns to the scheduler with a {@link Result}.
 * <p>
 * Protocol between the kernel (Lua) and the host: {@code coroutine.yield("wait")} at the top level means "nothing
 * to do until an event arrives" ({@code yield("wait", ms)}: or until {@code ms} milliseconds pass — the desktop's
 * clock); a yield with no values (or the interrupt handler's suspend) means "my slice is
 * over, resume me at once"; any other first value is passed up as {@link Result#VALUE} with {@link #yieldValue()}.
 * Events are pulled with {@code vmc.event_next()}, which never blocks — the kernel waits by yielding.
 * <p>
 * Not thread-safe: the scheduler runs a machine on one worker at a time. {@link #interruptSlice()},
 * {@link #kill()} and {@link #pushEvent(String)} may be called from any thread.
 */
public final class LuaMachine {
	/** What the world provides to a machine. Called on the worker thread that is running the machine. */
	public interface Host {
		String name();

		/** A line from the machine: 1 = print, 2 = warning, 3 = error. Rate-limit and route as you like. */
		void log(int level, String message);

		/** {@code vmc.clock(kind)}: 0 = CPU-ish monotonic nanoseconds, 1 = world ticks, 2 = wall-clock ms. */
		long clock(int kind);

		/** The generic syscall ({@code vmc.call(fn, payload)}). Returns the reply, or throws with a message the program sees. */
		String call(int fn, String payload) throws MachineError;

		/** The Lua-heap budget in bytes (§1b). */
		long memoryCapBytes();

		/** {@code vmc.frame_ms()}: how long the kernel should wait between frames of a program that presents (U1.2). */
		default int frameMillis() {
			return 50;
		}

		/** The machine's spec as JSON (tier, memory, screen cap, colours, disk, sound channels, desktop) — {@code vmc.info()}. */
		default String info() {
			return "{}";
		}

		/** The graphics device, or null when the machine has no screen (a headless machine drops gfx calls). */
		default @Nullable ScreenDevice screen() {
			return null;
		}

		/** The storage (§4), or null in a harness without files. */
		default @Nullable MachineFiles files() {
			return null;
		}

		/** The sound chip (§5), or null when the machine is mute (a harness): {@code snd_*} calls are dropped. */
		default @Nullable SoundChip sound() {
			return null;
		}
	}

	/** A syscall refused with a message for the program. */
	public static final class MachineError extends Exception {
		public MachineError(final String message) {
			super(message);
		}
	}

	public enum Result {
		/** The main coroutine returned or died; the machine is off. */
		FINISHED,
		/** The kernel is waiting for an event. */
		WAIT,
		/** The slice ended (interrupt) or the kernel yielded nothing; resume immediately. */
		SLICE,
		/** The kernel yielded a value for the host ({@link #yieldValue()}). */
		VALUE,
		/** The main coroutine raised an error ({@link #error()}). */
		ERROR
	}

	public static final int MAX_EVENTS = 256;
	private static final LuaString WAIT = LuaString.valueOf("wait");

	private final Host host;
	private final LuaState state;
	private final LuaThread main;
	private final Deque<String> events = new ArrayDeque<>();
	private int droppedEvents;
	private volatile boolean sliceOver;
	private volatile boolean killed;
	/** Set around a blocking host call (a world call waiting for the server thread); the monitor does not count it as stuck. */
	public volatile boolean inHostCall;
	private volatile @Nullable String pendingError;
	private @Nullable Varargs yielded;
	/** The coroutine the interrupt handler suspended mid-slice (possibly nested); resumed next, else {@link #main}. */
	private @Nullable LuaThread suspended;
	private @Nullable String error;
	private boolean finished;
	private final MemoryMeter memory = new MemoryMeter();

	public LuaMachine(final Host host, final String bootSource, final String bootName) throws MachineError {
		this.host = host;
		state = LuaState.builder()
			.interruptHandler(() -> {
				if (killed) {
					return InterruptAction.SUSPEND; // uncatchable by Lua, unlike an error (a pcall loop would survive one)
				}
				final String err = pendingError;
				if (err != null) {
					pendingError = null;
					throw new LuaError(err);
				}
				return sliceOver ? InterruptAction.SUSPEND : InterruptAction.CONTINUE;
			})
			.errorReporter((e, msg) -> host.log(3, "Lua runtime error: " + msg.get()))
			.build();
		try {
			Sandbox.install(state, this);
			final LuaClosure boot = LoadState.load(state, new ByteArrayInputStream(bootSource.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "=" + bootName, state.globals());
			main = new LuaThread(state, boot);
		} catch (final CompileException | LuaError e) {
			throw new MachineError("boot: " + e.getMessage());
		}
	}

	public Host host() {
		return host;
	}

	public LuaState state() {
		return state;
	}

	public MemoryMeter memory() {
		return memory;
	}

	/** From any thread: end the current slice at the next safe point. */
	public void interruptSlice() {
		sliceOver = true;
		state.interrupt();
	}

	/** From any thread: raise this error inside the running program at its next safe point (used for "not enough memory"). */
	public void raise(final String message) {
		pendingError = message;
		state.interrupt();
	}

	public void kill() {
		killed = true;
		state.interrupt();
	}

	/** From any thread. Returns false (and counts) when the queue is full; the oldest event is kept, the new one dropped. */
	public boolean pushEvent(final String json) {
		synchronized (events) {
			if (events.size() >= MAX_EVENTS) {
				droppedEvents++;
				return false;
			}
			events.addLast(json);
			return true;
		}
	}

	public int pendingEvents() {
		synchronized (events) {
			return events.size();
		}
	}

	public int droppedEvents() {
		return droppedEvents;
	}

	@Nullable String pollEvent() {
		synchronized (events) {
			return events.pollFirst();
		}
	}

	public boolean isFinished() {
		return finished;
	}

	public @Nullable String error() {
		return error;
	}

	/** The first value of the last {@link Result#VALUE} yield as a string, else null. */
	public @Nullable String yieldValue() {
		final Varargs y = yielded;
		return y == null || y.count() == 0 ? null : y.first().toString();
	}

	public @Nullable Varargs yielded() {
		return yielded;
	}

	/** After a {@link Result#WAIT}: the timed wake the kernel asked for ({@code yield("wait", ms)}), or 0 for "until an event". */
	public long waitMillis() {
		final Varargs y = yielded;
		if (y == null || y.count() < 2) {
			return 0;
		}
		final LuaValue v = y.arg(2);
		return v.isNumber() ? Math.max(0L, (long) v.toDouble()) : 0L;
	}

	/** Run until the kernel yields, the slice is interrupted, the program finishes or errors. Worker thread only. */
	public Result run() {
		if (finished) {
			return Result.FINISHED;
		}
		sliceOver = false;
		yielded = null;
		try {
			final LuaThread thread = suspended != null && suspended.isAlive() ? suspended : main;
			suspended = null;
			final Varargs results = LuaThread.run(thread, Constants.NONE);
			if (killed) {
				finished = true;
				error = "killed";
				return Result.ERROR;
			}
			if (results == null) {
				// suspended by the interrupt handler: whichever coroutine was running is the one to resume
				final LuaThread cur = state.getCurrentThread();
				suspended = cur != null && cur != state.getMainThread() && cur.isAlive() ? cur : thread;
				return Result.SLICE;
			}
			if (!main.isAlive() || main.getStatus() == LuaThread.Status.DEAD) {
				finished = true;
				return Result.FINISHED;
			}
			yielded = results;
			if (results.count() == 0) {
				return Result.SLICE;
			}
			final LuaValue first = results.first();
			if (first.isString() && WAIT.equals(first)) {
				return Result.WAIT;
			}
			return Result.VALUE;
		} catch (final LuaError e) {
			finished = true;
			error = e.getMessage();
			return Result.ERROR;
		} catch (final RuntimeException e) {
			finished = true;
			error = "internal: " + e;
			return Result.ERROR;
		}
	}

	/**
	 * The harness ({@code /vmc computer lua}): compile and run a chunk on a scratch coroutine with the machine's
	 * globals, return the results joined by tabs, or {@code "ERROR: ..."}. Worker thread only, never while
	 * {@link #run()} is executing. A chunk that yields is treated as finished.
	 */
	public String eval(final String source) {
		try {
			final LuaClosure fn = LoadState.load(state, new ByteArrayInputStream(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "=eval", state.globals());
			final LuaThread t = new LuaThread(state, fn);
			sliceOver = false;
			final Varargs r = LuaThread.run(t, Constants.NONE);
			if (r == null) {
				return "ERROR: eval: slice exceeded";
			}
			final StringBuilder sb = new StringBuilder();
			for (int i = 1; i <= r.count(); i++) {
				if (i > 1) {
					sb.append('\t');
				}
				sb.append(r.arg(i).toString());
			}
			return sb.toString();
		} catch (final CompileException | LuaError e) {
			return "ERROR: " + e.getMessage();
		}
	}

	// ---- the vmc library (§1e, the S1 subset) ----
	LuaTable vmcLibrary() {
		final LuaTable t = RegisteredFunction.bind(new RegisteredFunction[] {
			RegisteredFunction.of("log", (st, level, msg) -> {
				host.log(level.checkInteger(), msg.toString());
				return Constants.NIL;
			}),
			RegisteredFunction.of("clock", (st, kind) -> ValueFactory.valueOf((double) host.clock(kind.optInteger(0)))),
			RegisteredFunction.of("frame_ms", st -> ValueFactory.valueOf(host.frameMillis())),
			RegisteredFunction.of("event_next", st -> {
				final String e = pollEvent();
				return e == null ? Constants.NIL : ValueFactory.valueOf(e);
			}),
			RegisteredFunction.of("events", st -> ValueFactory.valueOf(pendingEvents())),
			RegisteredFunction.ofV("call", (st, args) -> {
				final int fn = args.arg(1).checkInteger();
				final String payload = args.arg(2).isNil() ? "" : args.arg(2).toString();
				try {
					final String reply = host.call(fn, payload);
					return reply == null ? Constants.NIL : ValueFactory.valueOf(reply);
				} catch (final MachineError e) {
					return ValueFactory.varargsOf(Constants.NIL, ValueFactory.valueOf(e.getMessage()));
				}
			}),
			RegisteredFunction.ofV("mem", (st, args) -> ValueFactory.varargsOf(
				ValueFactory.valueOf((double) memory.estimate()), ValueFactory.valueOf((double) host.memoryCapBytes()))),
			RegisteredFunction.of("name", st -> ValueFactory.valueOf(host.name())),
			RegisteredFunction.of("info", st -> ValueFactory.valueOf(host.info())),
			// ---- files (§4): mounts, whole-file read/write, quotas ----
			RegisteredFunction.of("fs_mounts", st -> ValueFactory.valueOf(files().mountsJson())),
			RegisteredFunction.of("fs_list", (st, p) -> fsCall(() -> ValueFactory.valueOf(files().list(p.checkString())))),
			RegisteredFunction.of("fs_stat", (st, p) -> fsCall(() -> ValueFactory.valueOf(files().stat(p.checkString())))),
			RegisteredFunction.of("fs_read", (st, p) -> fsCall(() -> ValueFactory.valueOf(files().read(p.checkString())))),
			RegisteredFunction.of("fs_write", (st, p, data, append) -> fsCall(() -> {
				final LuaString s = data.checkLuaString();
				final byte[] b = new byte[s.length()];
				s.copyTo(b, 0);
				files().write(p.checkString(), b, append.toBoolean());
				return Constants.TRUE;
			})),
			RegisteredFunction.of("fs_mkdir", (st, p) -> fsCall(() -> {
				files().mkdir(p.checkString());
				return Constants.TRUE;
			})),
			RegisteredFunction.of("fs_remove", (st, p) -> fsCall(() -> {
				files().remove(p.checkString());
				return Constants.TRUE;
			})),
			RegisteredFunction.of("fs_rename", (st, a, b) -> fsCall(() -> {
				files().rename(a.checkString(), b.checkString());
				return Constants.TRUE;
			})),
			RegisteredFunction.of("fs_format", (st, m) -> fsCall(() -> {
				files().format(m.checkString());
				return Constants.TRUE;
			})),
			RegisteredFunction.of("fs_burn", (st, a, b) -> fsCall(() -> {
				files().burn(a.checkString(), b.checkString());
				return Constants.TRUE;
			})),
			// ---- the screen (§3): indexed colour, palette, primitives; all no-ops without a monitor ----
			RegisteredFunction.ofV("gfx_size", (st, a) -> {
				final ScreenDevice d = host.screen();
				return d == null ? ValueFactory.varargsOf(Constants.ZERO, Constants.ZERO) : ValueFactory.varargsOf(ValueFactory.valueOf(d.width()), ValueFactory.valueOf(d.height()));
			}),
			RegisteredFunction.of("gfx_clear", (st, c) -> {
				final ScreenDevice d = host.screen();
				if (d != null) {
					d.clear(c.optInteger(0));
				}
				return Constants.NIL;
			}),
			RegisteredFunction.of("gfx_pixel", (st, x, y, c) -> {
				final ScreenDevice d = host.screen();
				if (d != null) {
					d.pixel(x.checkInteger(), y.checkInteger(), c.checkInteger());
				}
				return Constants.NIL;
			}),
			RegisteredFunction.of("gfx_get", (st, x, y) -> {
				final ScreenDevice d = host.screen();
				return ValueFactory.valueOf(d == null ? 0 : d.get(x.checkInteger(), y.checkInteger()));
			}),
			RegisteredFunction.ofV("gfx_line", (st, a) -> {
				final ScreenDevice d = host.screen();
				if (d != null) {
					d.line(a.arg(1).checkInteger(), a.arg(2).checkInteger(), a.arg(3).checkInteger(), a.arg(4).checkInteger(), a.arg(5).checkInteger());
				}
				return Constants.NONE;
			}),
			RegisteredFunction.ofV("gfx_rect", (st, a) -> {
				final ScreenDevice d = host.screen();
				if (d != null) {
					d.rect(a.arg(1).checkInteger(), a.arg(2).checkInteger(), a.arg(3).checkInteger(), a.arg(4).checkInteger(), a.arg(5).checkInteger());
				}
				return Constants.NONE;
			}),
			RegisteredFunction.ofV("gfx_fill", (st, a) -> {
				final ScreenDevice d = host.screen();
				if (d != null) {
					d.fillRect(a.arg(1).checkInteger(), a.arg(2).checkInteger(), a.arg(3).checkInteger(), a.arg(4).checkInteger(), a.arg(5).checkInteger());
				}
				return Constants.NONE;
			}),
			RegisteredFunction.ofV("gfx_circle", (st, a) -> {
				final ScreenDevice d = host.screen();
				if (d != null) {
					d.circle(a.arg(1).checkInteger(), a.arg(2).checkInteger(), a.arg(3).checkInteger(), a.arg(4).checkInteger(), a.arg(5).toBoolean());
				}
				return Constants.NONE;
			}),
			RegisteredFunction.ofV("gfx_text", (st, a) -> {
				final ScreenDevice d = host.screen();
				final LuaString s = a.arg(3).checkLuaString();
				final byte[] bytes = new byte[Math.min(s.length(), 4096)];
				s.copyTo(0, bytes, 0, bytes.length);
				final int font = a.arg(6).optInteger(0);
				if (d == null) {
					return ValueFactory.valueOf(ScreenDevice.textWidth(bytes.length, font));
				}
				return ValueFactory.valueOf(d.text(a.arg(1).checkInteger(), a.arg(2).checkInteger(), bytes, a.arg(4).optInteger(7), a.arg(5).optInteger(-1), font));
			}),
			RegisteredFunction.ofV("gfx_blit", (st, a) -> {
				final ScreenDevice d = host.screen();
				final int w = a.arg(3).checkInteger();
				final int h = a.arg(4).checkInteger();
				final LuaString s = a.arg(5).checkLuaString();
				if (d != null && w > 0 && h > 0 && (long) w * h <= 1024L * 768L) {
					final byte[] bytes = new byte[s.length()];
					s.copyTo(bytes, 0);
					d.blit(a.arg(1).checkInteger(), a.arg(2).checkInteger(), w, h, bytes, a.arg(7).optInteger(w), a.arg(6).optInteger(-1));
				}
				return Constants.NONE;
			}),
			RegisteredFunction.ofV("gfx_read", (st, a) -> {
				final ScreenDevice d = host.screen();
				if (d == null) {
					return ValueFactory.valueOf("");
				}
				return ValueFactory.valueOf(d.read(a.arg(1).checkInteger(), a.arg(2).checkInteger(), a.arg(3).checkInteger(), a.arg(4).checkInteger()));
			}),
			RegisteredFunction.ofV("gfx_cursor", (st, a) -> {
				final ScreenDevice d = host.screen();
				if (d != null) {
					d.cursor(a.arg(1).checkInteger(), a.arg(2).checkInteger(), a.arg(3).isNil() || a.arg(3).toBoolean());
				}
				return Constants.NONE;
			}),
			RegisteredFunction.ofV("gfx_cursor_shape", (st, a) -> {
				final ScreenDevice d = host.screen();
				final int w = a.arg(1).checkInteger();
				final int h = a.arg(2).checkInteger();
				final LuaString s = a.arg(5).checkLuaString();
				if (d != null && w > 0 && h > 0 && w <= 32 && h <= 32) {
					final byte[] bytes = new byte[s.length()];
					s.copyTo(bytes, 0);
					d.cursorShape(w, h, a.arg(3).optInteger(0), a.arg(4).optInteger(0), bytes, a.arg(6).optInteger(-1));
				}
				return Constants.NONE;
			}),
			RegisteredFunction.ofV("gfx_copy", (st, a) -> {
				final ScreenDevice d = host.screen();
				if (d != null) {
					d.copy(a.arg(1).checkInteger(), a.arg(2).checkInteger(), a.arg(3).checkInteger(), a.arg(4).checkInteger(), a.arg(5).checkInteger(), a.arg(6).checkInteger());
				}
				return Constants.NONE;
			}),
			RegisteredFunction.ofV("gfx_clip", (st, a) -> {
				final ScreenDevice d = host.screen();
				if (d != null) {
					d.clip(a.arg(1).optInteger(0), a.arg(2).optInteger(0), a.arg(3).optInteger(0), a.arg(4).optInteger(0));
				}
				return Constants.NONE;
			}),
			RegisteredFunction.ofV("gfx_palette", (st, a) -> {
				final ScreenDevice d = host.screen();
				if (d == null) {
					return Constants.ZERO;
				}
				if (a.arg(1).isNil()) {
					d.resetPalette();
					return Constants.NONE;
				}
				final int i = a.arg(1).checkInteger() & 0xFF;
				if (!a.arg(2).isNil()) {
					d.setPalette(i, a.arg(2).checkInteger());
				}
				return ValueFactory.valueOf(d.palette(i));
			}),
			// ---- sound (§5): channels are 1-based from Lua, 1–4 synth, 5–6 samples ----
			RegisteredFunction.ofV("snd_channel", (st, a) -> sndCall(() -> {
				final SoundChip s = host.sound();
				if (s != null) {
					s.channel(a.arg(1).checkInteger() - 1, a.arg(2).optInteger(0), a.arg(3).checkDouble(), a.arg(4).optDouble(1),
							a.arg(5).optDouble(0), a.arg(6).optDouble(0), a.arg(7).optDouble(1), a.arg(8).optDouble(0.05), a.arg(9).optDouble(0.5));
				}
				return Constants.NONE;
			})),
			RegisteredFunction.ofV("snd_note_off", (st, a) -> sndCall(() -> {
				final SoundChip s = host.sound();
				if (s != null) {
					s.noteOff(a.arg(1).checkInteger() - 1);
				}
				return Constants.NONE;
			})),
			RegisteredFunction.ofV("snd_slide", (st, a) -> sndCall(() -> {
				final SoundChip s = host.sound();
				if (s != null) {
					s.slide(a.arg(1).checkInteger() - 1, a.arg(2).checkDouble(), a.arg(3).optDouble(0.1));
				}
				return Constants.NONE;
			})),
			RegisteredFunction.ofV("snd_sample_load", (st, a) -> sndCall(() -> {
				final SoundChip s = host.sound();
				if (s != null) {
					final org.squiddev.cobalt.LuaString data = a.arg(2).checkLuaString();
					final byte[] bytes = new byte[data.length()];
					for (int i = 0; i < bytes.length; i++) {
						bytes[i] = (byte) data.charAt(i);
					}
					s.loadSample(a.arg(1).checkInteger(), bytes, a.arg(3).optInteger(SoundChip.RATE));
				}
				return Constants.NONE;
			})),
			RegisteredFunction.ofV("snd_sample_play", (st, a) -> sndCall(() -> {
				final SoundChip s = host.sound();
				if (s != null) {
					s.playSample(a.arg(1).checkInteger() - 1, a.arg(2).checkInteger(), a.arg(3).optDouble(1), a.arg(4).optBoolean(false));
				}
				return Constants.NONE;
			})),
			RegisteredFunction.ofV("snd_stop", (st, a) -> sndCall(() -> {
				final SoundChip s = host.sound();
				if (s != null) {
					s.stop(a.arg(1).isNil() ? -1 : a.arg(1).checkInteger() - 1);
				}
				return Constants.NONE;
			})),
			RegisteredFunction.ofV("snd_master", (st, a) -> sndCall(() -> {
				final SoundChip s = host.sound();
				if (s == null) {
					return Constants.ZERO;
				}
				if (!a.arg(1).isNil()) {
					s.master(a.arg(1).checkDouble());
				}
				return ValueFactory.valueOf(s.master());
			})),
		});
		return t;
	}

	private interface SndOp {
		Varargs run() throws LuaError;
	}

	/** The chip refuses bad channels and oversize samples with an IllegalArgumentException; the program sees the message. */
	private static Varargs sndCall(final SndOp op) throws LuaError {
		try {
			return op.run();
		} catch (final IllegalArgumentException e) {
			throw new LuaError(e.getMessage());
		}
	}

	private MachineFiles files() throws LuaError {
		final MachineFiles f = host.files();
		if (f == null) {
			throw new LuaError("no storage");
		}
		return f;
	}

	@FunctionalInterface
	private interface FsOp {
		LuaValue run() throws LuaMachine.MachineError, LuaError;
	}

	/** A storage error is an ordinary Lua error with the message the program should see. */
	private static LuaValue fsCall(final FsOp op) throws LuaError {
		try {
			return op.run();
		} catch (final MachineError e) {
			throw new LuaError(e.getMessage());
		}
	}

	static List<String> describeLibrary() {
		return List.of("log(level, msg)", "clock([kind])", "event_next()", "events()", "call(fn, payload)", "mem()", "name()");
	}

	public LuaThread mainThread() {
		return main;
	}
}
