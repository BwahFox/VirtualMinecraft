package dev.virtualminecraft.computer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S4 harness: boots the ROM kernel outside Minecraft with a 256×256 screen and the classpath ROM, drives it with
 * pointer / keyboard / exec / save events and checks it never errors. {@code ./gradlew romBootTest}.
 */
public final class RomBootTest {
	private static int failures;

	/** "longest N of M": no visual row may be wider than the area. */
	private static boolean widthOk(final String line) {
		final java.util.regex.Matcher m = java.util.regex.Pattern.compile("longest (\\d+) of (\\d+)").matcher(line);
		return m.find() && Integer.parseInt(m.group(1)) <= Integer.parseInt(m.group(2));
	}

	private static void check(final boolean ok, final String what) {
		System.out.println((ok ? "  PASS " : "  FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}

	static class Host implements LuaMachine.Host {
		final List<String> log = new ArrayList<>();
		/** Frame pacing for presenting programs: 5 ms in the tests, 16 in the emulator. */
		int frameMs = 5;
		/** The pretend redstone component's outputs by absolute side (the block faces north). */
		final java.util.Map<String, String> outputs = new java.util.HashMap<>();
		final ScreenDevice screen = new ScreenDevice(UUID.randomUUID());
		/**
		 * A real chip, never mixed: nothing here plays audio, but the chip's argument checks run, so a program that
		 * asks for a channel the hardware does not have fails in the harness and the emulator rather than in the game
		 * (Pinball, 2026-08-28: noise on channels 5/6 passed every script and crashed on its first drain in the client).
		 */
		final SoundChip sound = new SoundChip();
		final MachineFiles files;
		String name = "test";
		String saved;
		/** The case (U3b): the spec's caps go onto the screen; {@code desktop} null = the tier decides (a Basic Computer boots into the shell). */
		int tier = 2;
		/** The whole case: a fully fitted tier by default, or the levels the emulator's {@code --parts} names. */
		MachineSpec spec = MachineSpec.fitted(2);
		Boolean desktop;

		void setTier(final int t) {
			setSpec(MachineSpec.fitted(Math.clamp(t, 1, MachineSpec.TIERS)));
		}

		/** §9 U10(a): no drive part, no {@code /disk} — the mount is simply not there, as in the world. */
		void setDiskQuota(final long bytes) {
			if (bytes <= 0) {
				files.mounts().remove("disk");
			}
		}

		void setSpec(final MachineSpec s) {
			tier = s.tier();
			spec = s;
			if (s.hasGraphics()) {
				screen.setLimits(s.maxW(), s.maxH(), s.colours());
			}
		}

		@Override
		public String info() {
			final MachineSpec s = spec;
			final com.google.gson.JsonObject o = s.json();
			o.addProperty("desktop", desktop != null ? desktop : s.desktopByDefault());
			o.addProperty("desktopMode", desktop == null ? 0 : desktop ? 1 : 2);
			o.addProperty("name", name);
			return o.toString();
		}
		/** Added to the world-tick clock: the clock test moves the in-game time forward. */
		long tickOffset;

		Host(final Path tmp) {
			files = new MachineFiles(tmp.resolve("m"), tmp.resolve("items"), tmp.resolve("config"), 8L << 20);
			screen.resize(256, 256);
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public void log(final int level, final String message) {
			log.add(level + ":" + message);
			System.out.println("    [lua " + level + "] " + message);
		}

		@Override
		public int frameMillis() {
			return frameMs;
		}

		@Override
		public long clock(final int kind) {
			final long ticks = System.nanoTime() / 50_000_000L + tickOffset;
			return switch (kind) {
				case 1 -> ticks;
				// §9 U10(b): the world's own milliseconds since 1970, exactly as LuaComputer computes them
				case 2 -> LuaComputer.WORLD_EPOCH_OFFSET_MS + ticks * LuaComputer.MS_PER_TICK;
				case 3 -> System.currentTimeMillis();
				default -> System.nanoTime();
			};
		}

		@Override
		public String call(final int fn, final String payload) throws LuaMachine.MachineError {
			if (fn == 1) {
				return fakeBus(payload);
			}
			if (fn == 2) {
				if (payload.isEmpty()) {
					return saved == null ? "" : saved;
				}
				if (payload.length() > 256 * 1024) { // the same cap as LuaComputer.stateCall
					throw new LuaMachine.MachineError("state too large (256 KB max)");
				}
				saved = payload;
				return "ok";
			}
			if (fn == 4) {
				if (payload.equals("shutdown")) {
					shutdown = true;
					return "ok";
				}
				if (payload.startsWith("desktop:")) {
					final String m = payload.substring(8);
					desktop = m.equals("auto") ? null : m.equals("on") || m.equals("desktop");
					return "ok";
				}
				if (payload.startsWith("label:")) {
					name = payload.substring(6);
					return "ok";
				}
				if (payload.equals("reboot")) {
					// The real machine tears itself down and boots again. Nothing outside Minecraft rebuilds a
					// LuaMachine mid-run, so say so loudly rather than answer "ok" and carry on as if it happened.
					log(2, "reboot: not supported outside Minecraft (the machine keeps running)");
					return "ok";
				}
				throw new LuaMachine.MachineError("machine: unknown request");
			}
			throw new LuaMachine.MachineError("no such syscall " + fn);
		}

		final List<String> busCalls = new ArrayList<>();
		/**
		 * Loopback for the pretend {@code net}: when set, {@code net.send} delivers the message straight back to
		 * this machine as a {@code net_message} event. Without it `send` returns true and the message vanishes,
		 * which makes a request/response protocol impossible to test outside Minecraft — a server and a browser
		 * can now talk to each other on one machine.
		 */
		java.util.function.@org.jspecify.annotations.Nullable Consumer<String> netLoopback;
		/** The pretend redstone component's wake threshold and sleep flag (the block entity's, in the real thing). */
		int wake;
		boolean sleep;
		/** Set by syscall 4 "shutdown": the redstone-sleep test watches for it. */
		boolean shutdown;

		/** A pretend bus: a redstone/world/chat component at self and two chests, answering like the real ones. */
		/** The pretend components' addresses (what {@code bus.list()} advertises) to their types. */
		static final java.util.Map<String, String> FAKE_ADDRESSES = java.util.Map.of(
			"a1", "redstone", "a2", "world", "a3", "chat", "a4", "inventory", "a5", "inventory", "a6", "net");
		/** The REAL components' method names (each {@code *Component.METHODS}), so an unknown one fails here too. */
		static final java.util.Map<String, java.util.Set<String>> FAKE_METHODS = java.util.Map.of(
			"redstone", java.util.Set.of("getInput", "getInputs", "getOutput", "getOutputs", "setOutput", "setOutputs", "getFacing", "getWake", "setWake", "getSleep", "setSleep"),
			"world", java.util.Set.of("getWeather", "getTime", "getPosition", "getPlayers", "getLight", "getBlock", "getBiome", "detect"),
			"chat", java.util.Set.of("say", "send", "getPlayers", "getRange"),
			"inventory", java.util.Set.of("size", "name", "list", "getItemDetail", "getItemLimit", "pushItems", "pullItems"),
			"net", java.util.Set.of("address", "list", "send", "broadcast"));
		final dev.virtualminecraft.bus.RateLimiter chatBudget = new dev.virtualminecraft.bus.RateLimiter(Math.max(2f, 20f / 6f), 20f / 60f);
		final dev.virtualminecraft.bus.RateLimiter netBudget = new dev.virtualminecraft.bus.RateLimiter(Math.max(10f, 600f / 6f), 600f / 60f);

		private String fakeBus(final String payload) throws LuaMachine.MachineError {
			final com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
			final String op = o.get("op").getAsString();
			if (op.equals("list")) {
				return "[{\"address\":\"a1\",\"type\":\"redstone\",\"location\":\"self\"},{\"address\":\"a2\",\"type\":\"world\",\"location\":\"self\"},"
						+ "{\"address\":\"a3\",\"type\":\"chat\",\"location\":\"self\"},{\"address\":\"a4\",\"type\":\"inventory\",\"location\":\"west\"},"
						+ "{\"address\":\"a5\",\"type\":\"inventory\",\"location\":\"east\"},{\"address\":\"a6\",\"type\":\"net\",\"location\":\"self\"}]";
			}
			final String method = o.get("method").getAsString();
			final String target = o.get("target").getAsString();
			// Resolve and check like Components.find + busCall do: a target that is not here, or a method the real
			// component does not have, is an error the program sees -- not a quiet "null". Until 2026-08-28 the
			// pretend bus answered null to anything, so a misspelt method passed every harness and failed in game.
			final String type = FAKE_ADDRESSES.getOrDefault(target, target);
			if (!FAKE_METHODS.containsKey(type)) {
				throw new LuaMachine.MachineError("no such component: " + target);
			}
			if (!FAKE_METHODS.get(type).contains(method)) {
				throw new LuaMachine.MachineError("no such method: " + type + "." + method);
			}
			// The real block entity budgets chat and net (VmcConfig defaults: 20 chat/min, 600 net/min, bursts of a
			// sixth of that); a program that spams in the emulator now hits the same wall with the same message.
			final long tick = clock(1);
			if (type.equals("chat") && (method.equals("say") || method.equals("send")) && !chatBudget.tryAcquire(tick)) {
				throw new LuaMachine.MachineError("this computer is talking too much (max 20/min); retry in " + chatBudget.retryInSeconds() + "s");
			}
			if (type.equals("net") && (method.equals("send") || method.equals("broadcast")) && !netBudget.tryAcquire(tick)) {
				throw new LuaMachine.MachineError("this computer is sending too much (max 600/min); retry in " + netBudget.retryInSeconds() + "s");
			}
			busCalls.add(target + "." + method + " " + o.get("args"));
			if (type.equals("net")) { // one peer two blocks down the cable, one over the radio
				return switch (method) {
					case "list" -> "[{\"address\":\"b2\",\"name\":\"other\",\"location\":\"2,0,0\"},"
							+ "{\"address\":\"b3\",\"name\":\"faraway\",\"location\":\"wireless\"}]";
					case "send" -> {
						if (netLoopback != null) {
							final com.google.gson.JsonArray a = o.get("args").getAsJsonArray();
							final com.google.gson.JsonObject ev = new com.google.gson.JsonObject();
							ev.addProperty("name", "net_message");
							ev.addProperty("from", "self-id");
							ev.addProperty("sender", a.size() > 0 ? a.get(0).getAsString() : "loopback");
							ev.add("message", a.size() > 1 ? a.get(1) : com.google.gson.JsonNull.INSTANCE);
							netLoopback.accept(ev.toString());
						}
						yield "true";
					}
					case "broadcast" -> "2";
					case "address" -> "\"self-id\"";
					default -> "null";
				};
			}
			return switch (method) {
				// like RedstoneComponent: absolute side names in the maps, relative ones accepted as arguments
				case "getFacing" -> "\"north\"";
				case "getWake" -> String.valueOf(wake);
				case "setWake" -> {
					final int was = wake;
					wake = o.getAsJsonArray("args").get(0).getAsInt();
					yield String.valueOf(was);
				}
				case "getSleep" -> String.valueOf(sleep);
				case "setSleep" -> {
					final boolean was = sleep;
					sleep = o.getAsJsonArray("args").get(0).getAsBoolean();
					yield String.valueOf(was);
				}
				case "getInputs" -> "{\"north\":0,\"south\":7,\"west\":0,\"east\":0,\"up\":0,\"down\":0}";
				case "getOutputs" -> "{\"north\":" + outputs.getOrDefault("north", "0") + ",\"south\":" + outputs.getOrDefault("south", "0")
						+ ",\"west\":0,\"east\":" + outputs.getOrDefault("east", "0") + ",\"up\":0,\"down\":0}";
				case "setOutput" -> {
					final String side = o.getAsJsonArray("args").get(0).getAsString();
					final String absSide = switch (side) {
						case "front" -> "north";
						case "back" -> "south";
						case "left" -> "west";
						case "right" -> "east";
						case "top" -> "up";
						case "bottom" -> "down";
						default -> side;
					};
					outputs.put(absSide, o.getAsJsonArray("args").get(1).toString());
					yield o.getAsJsonArray("args").get(1).toString();
				}
				case "list" -> "{\"1\":{\"name\":\"minecraft:stone\",\"count\":3,\"displayName\":\"Stone\",\"maxCount\":64},"
						+ "\"5\":{\"name\":\"minecraft:oak_log\",\"count\":12,\"displayName\":\"Oak Log\",\"maxCount\":64}}";
				case "size" -> "27";
				case "name" -> "\"Chest\"";
				case "pushItems", "pullItems" -> "3";
				case "getTime" -> "{\"time\":6000,\"day\":3,\"ticks\":78000,\"gameTime\":78000,\"daylight\":true}";
				case "getWeather" -> "{\"weather\":\"clear\",\"raining\":false,\"thundering\":false,\"rainingHere\":false}";
				case "getPosition" -> "{\"x\":3,\"y\":-60,\"z\":3,\"dimension\":\"minecraft:overworld\",\"facing\":\"north\"}";
				case "getBiome" -> "\"minecraft:plains\"";
				case "getLight" -> "15";
				case "getPlayers" -> "[{\"name\":\"t\",\"x\":1,\"y\":0,\"z\":2,\"distance\":2.24}]";
				case "say" -> "true";
				default -> "null";
			};
		}

		/** The Computer's memory budget; the emulator's --mem sets it, so a 16 MB machine can be tried outside Minecraft. */
		long memCapBytes = 4L << 20;

		@Override
		public long memoryCapBytes() {
			return memCapBytes;
		}

		@Override
		public ScreenDevice screen() {
			return screen;
		}

		@Override
		public MachineFiles files() {
			return files;
		}

		@Override
		public SoundChip sound() {
			return sound;
		}
	}

	/**
	 * Run until the kernel waits (or errors), passing flips through; returns the number of frames, -1 on death. A
	 * short timed wait (frame pacing, U1.2: < 100 ms) is slept through and counts as busy; a long one (the clock,
	 * a toast) or an untimed one is idle.
	 */
	private static int settle(final LuaMachine m, final int max) {
		int frames = 0;
		for (int i = 0; i < max; i++) {
			final LuaMachine.Result r = m.run();
			if (r == LuaMachine.Result.WAIT) {
				final long ms = m.waitMillis();
				if (ms <= 0 || ms >= 100) {
					return frames;
				}
				sleep(ms);
				continue;
			}
			if (r == LuaMachine.Result.VALUE && "flip".equals(m.yieldValue())) {
				frames++;
			}
			if (r == LuaMachine.Result.ERROR || r == LuaMachine.Result.FINISHED) {
				System.out.println("    machine ended: " + r + " " + m.error());
				return -1;
			}
		}
		return frames;
	}

	private static void sleep(final long ms) {
		try {
			Thread.sleep(Math.max(1, Math.min(ms, 20)));
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void click(final LuaMachine m, final int x, final int y) {
		event(m, "{\"name\":\"pointer\",\"x\":" + x + ",\"y\":" + y + ",\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":" + x + ",\"y\":" + y + ",\"buttons\":0,\"player\":\"t\"}");
	}

	/** The right button is RFB bit 3 — the mask the real client sends, and what U6's context menus read. */
	private static void rclick(final LuaMachine m, final int x, final int y) {
		event(m, "{\"name\":\"pointer\",\"x\":" + x + ",\"y\":" + y + ",\"buttons\":4,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":" + x + ",\"y\":" + y + ",\"buttons\":0,\"player\":\"t\"}");
	}

	private static void hover(final LuaMachine m, final int x, final int y) {
		event(m, "{\"name\":\"pointer\",\"x\":" + x + ",\"y\":" + y + ",\"buttons\":0,\"player\":\"t\"}");
	}

	private static void key(final LuaMachine m, final int code) {
		event(m, "{\"name\":\"scancode\",\"code\":" + code + ",\"down\":true,\"player\":\"t\"}");
		event(m, "{\"name\":\"scancode\",\"code\":" + code + ",\"down\":false,\"player\":\"t\"}");
	}

	private static void event(final LuaMachine m, final String json) {
		m.pushEvent(json);
	}

	public static void main(final String[] args) throws Exception {
		final Path tmp = Files.createTempDirectory("vmc-rom");
		final Host h = new Host(tmp);
		final String boot = new String(RomBootTest.class.getResourceAsStream("/virtualminecraft/rom/boot.lua").readAllBytes(), StandardCharsets.UTF_8);
		final LuaMachine m = new LuaMachine(h, boot, "boot.lua");
		int frames = settle(m, 200);
		check(frames >= 1 && h.screen.active(), "kernel boots, draws the desktop and waits (" + frames + " frames)");
		check(h.log.stream().anyMatch(l -> l.contains("kernel up")), "kernel logged its size");
		check(h.log.stream().noneMatch(l -> l.startsWith("3:")), "no errors while booting");
		// the taskbar clock: the kernel asks for a timed wake, and redraws the clock when the in-game minute changes
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.toast = nil\"}"); // the welcome toast keeps the loop busy for 4 s
		settle(m, 200);
		check(m.waitMillis() > 0 && m.waitMillis() <= 900, "the desktop waits with a timeout for the clock (" + m.waitMillis() + " ms)");
		h.tickOffset += 17; // one in-game minute is 16.67 ticks
		h.screen.takeDrawn();
		settle(m, 50);
		final boolean drewClock = h.screen.takeDrawn();
		event(m, "{\"name\":\"exec\",\"code\":\"print('clock ' .. tostring(kernel.clockText == os.date()))\"}");
		settle(m, 200);
		check(drewClock && h.log.stream().anyMatch(l -> l.equals("1:clock true")), "a timed wake redraws the taskbar clock");
		for (final char c : "=1+1".toCharArray()) { // the shell prompt: `=` is the Lua escape hatch (U2)
			event(m, "{\"name\":\"char\",\"cp\":" + (int) c + ",\"player\":\"t\"}");
		}
		event(m, "{\"name\":\"scancode\",\"code\":28,\"down\":true,\"player\":\"t\"}");
		event(m, "{\"name\":\"scancode\",\"code\":28,\"down\":false,\"player\":\"t\"}");
		frames = settle(m, 200);
		check(frames >= 0 && h.log.stream().anyMatch(l -> l.equals("1:2")), "terminal ran =1+1 -> 2 (typed at the shell prompt)");
		// U2: the shell, driven by the `shell` event (its output is teed to the log)
		event(m, "{\"name\":\"exec\",\"code\":\"fs.write('/disk/hello.lua', [[print('hi ' .. tostring(... or 'none'))]]) fs.write('/disk/greet.sh', [[# a script\necho first $1\nrun hello $2\nhello $*]])\"}");
		settle(m, 200);
		for (final String line : new String[] { "pwd", "mkdir t2", "cd t2", "pwd", "cd ..", "echo hello world", "ls", "cat hello.lua", "run hello arg1", "hello", "greet A B",
			"cp hello.lua t2/h2.lua", "ls t2", "mv t2/h2.lua h3.lua", "rm h3.lua", "rm -r t2", "ls -l", "help cat", "nosuchcmd", "= 6*7", "lua print('L' .. 1)", "set NAME shell", "echo $NAME", "top", "date", "df" }) {
			event(m, "{\"name\":\"shell\",\"line\":\"" + line.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
			settle(m, 200);
		}
		final java.util.List<String> sh = h.log.stream().filter(l -> l.startsWith("1:")).map(l -> l.substring(2)).toList();
		check(sh.contains("/disk") && sh.contains("/disk/t2"), "pwd, mkdir, cd: the working directory moves and the prompt path is real");
		check(sh.contains("hello world") && sh.contains("shell"), "echo prints its words; set + $NAME expands");
		check(sh.stream().anyMatch(l -> l.contains("t2/")) && sh.stream().anyMatch(l -> l.contains("hello.lua") && !l.contains("no such")), "ls lists directories with / and files (" + sh.stream().filter(l -> l.contains("t2/")).findFirst().orElse("?") + ")");
		check(sh.contains("hi arg1") && sh.contains("hi none"), "run passes arguments to a .lua program, and a bare name runs it");
		check(sh.contains("first A") && sh.contains("hi B") && sh.contains("hi A"), "a .sh script runs line by line with $1, $2 and $*");
		check(sh.stream().anyMatch(l -> l.trim().equals("h2.lua")) && sh.stream().noneMatch(l -> l.contains("h3.lua")) && sh.stream().filter(l -> l.contains("t2/")).count() == 1, "cp, mv, rm and rm -r (" + sh.stream().filter(l -> l.contains("h2") || l.contains("h3")).toList() + ")");
		check(sh.stream().anyMatch(l -> l.startsWith("cat <file>")), "help <command> prints its usage");
		check(sh.stream().anyMatch(l -> l.contains("nosuchcmd: command not found")), "an unknown command says so");
		check(sh.contains("42") && sh.contains("L1"), "=<expr> and lua <code> reach the Lua REPL");
		check(sh.stream().anyMatch(l -> l.startsWith("memory ")) && sh.stream().anyMatch(l -> l.startsWith("Minecraft day ")) && sh.stream().anyMatch(l -> l.startsWith("/disk ")), "top, date and df answer");
		event(m, "{\"name\":\"exec\",\"code\":\"local t = kernel.find('terminal') print('tab [' .. t.complete('ca') .. '] [' .. t.complete('cat /disk/hel') .. '] [' .. t.complete('cd /dis') .. ']')\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:tab [cat ] [cat /disk/hello.lua ] [cd /disk/]")), "Tab completes a command, a file and a directory");
		event(m, "{\"name\":\"exec\",\"code\":\"print(#fs.list('/rom'))\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.matches("1:\\d+")), "exec printed the ROM file count");
		final int cell = 8 * 3 + 8;
		event(m, "{\"name\":\"pointer\",\"x\":12,\"y\":" + (6 + cell + 6) + ",\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":12,\"y\":" + (6 + cell + 6) + ",\"buttons\":0,\"player\":\"t\"}");
		frames = settle(m, 200);
		event(m, "{\"name\":\"exec\",\"code\":\"print(kernel.find('files') and 'files-open' or 'files-missing')\"}");
		settle(m, 200);
		check(frames >= 1 && h.log.stream().anyMatch(l -> l.equals("1:files-open")), "clicking the Files icon opens Files");
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.open('edit', {path='/disk/t.lua', text='print(7)'})\"}");
		settle(m, 200);
		event(m, "{\"name\":\"scancode\",\"code\":29,\"down\":true,\"player\":\"t\"}");
		event(m, "{\"name\":\"scancode\",\"code\":31,\"down\":true,\"player\":\"t\"}");
		event(m, "{\"name\":\"scancode\",\"code\":31,\"down\":false,\"player\":\"t\"}");
		event(m, "{\"name\":\"scancode\",\"code\":29,\"down\":false,\"player\":\"t\"}");
		settle(m, 200);
		check(Files.isRegularFile(tmp.resolve("m").resolve("disk").resolve("t.lua")), "Ctrl+S in Edit wrote /disk/t.lua");
		event(m, "{\"name\":\"exec\",\"code\":\"run('/disk/t.lua')\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:7")), "run() executed the saved program");
		// drag the Edit window by its title bar: press, move with the button held, release
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('edit') print(w.x .. ' ' .. w.y)\"}");
		settle(m, 200);
		final String before = h.log.stream().filter(l -> l.matches("1:\\d+ \\d+")).reduce((a, b) -> b).orElse("");
		final int[] start = { Integer.parseInt(before.substring(2).split(" ")[0]) + 40, Integer.parseInt(before.substring(2).split(" ")[1]) + 6 };
		event(m, "{\"name\":\"pointer\",\"x\":" + start[0] + ",\"y\":" + start[1] + ",\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":" + (start[0] + 10) + ",\"y\":" + (start[1] + 20) + ",\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":" + (start[0] + 20) + ",\"y\":" + (start[1] + 36) + ",\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":" + (start[0] + 20) + ",\"y\":" + (start[1] + 36) + ",\"buttons\":0,\"player\":\"t\"}");
		settle(m, 200);
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('edit') print('moved ' .. w.x .. ' ' .. w.y)\"}");
		settle(m, 200);
		final String expect = "1:moved " + (start[0] - 40 + 20) + " " + (start[1] - 6 + 36);
		check(h.log.stream().anyMatch(l -> l.equals(expect)), "dragging a title bar moves the window (" + expect + ")");
		// the resize grip in the bottom-right corner
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('edit') print('size ' .. w.x .. ' ' .. w.y .. ' ' .. w.w .. ' ' .. w.h)\"}");
		settle(m, 200);
		final String[] sz = h.log.stream().filter(l -> l.startsWith("1:size ")).reduce((a, b) -> b).orElse("1:size 0 0 0 0").substring(7).split(" ");
		final int gx = Integer.parseInt(sz[0]) + Integer.parseInt(sz[2]) - 3, gy = Integer.parseInt(sz[1]) + Integer.parseInt(sz[3]) - 3;
		event(m, "{\"name\":\"pointer\",\"x\":" + gx + ",\"y\":" + gy + ",\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":" + (gx - 10) + ",\"y\":" + (gy - 5) + ",\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":" + (gx - 20) + ",\"y\":" + (gy - 10) + ",\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":" + (gx - 20) + ",\"y\":" + (gy - 10) + ",\"buttons\":0,\"player\":\"t\"}");
		settle(m, 200);
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('edit') print('resized ' .. w.w .. ' ' .. w.h)\"}");
		settle(m, 200);
		// the height is clamped to the screen above the taskbar (256 - 14 - y); the width has room
		final String expectSize = "1:resized " + (Integer.parseInt(sz[2]) - 20) + " " + Math.min(Integer.parseInt(sz[3]) - 10, 256 - 14 - Integer.parseInt(sz[1]));
		check(h.log.stream().anyMatch(l -> l.equals(expectSize)), "dragging the grip resizes the window (" + expectSize + ")");
		// U1.5: a double-click on the title bar maximizes, another restores
		event(m, "{\"name\":\"pointer\",\"x\":" + (start[0] - 40 + 20 + 30) + ",\"y\":" + (start[1] - 6 + 36 + 5) + ",\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":" + (start[0] - 40 + 20 + 30) + ",\"y\":" + (start[1] - 6 + 36 + 5) + ",\"buttons\":0,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":" + (start[0] - 40 + 20 + 30) + ",\"y\":" + (start[1] - 6 + 36 + 5) + ",\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":" + (start[0] - 40 + 20 + 30) + ",\"y\":" + (start[1] - 6 + 36 + 5) + ",\"buttons\":0,\"player\":\"t\"}");
		settle(m, 200);
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('edit') print('max ' .. w.x .. ' ' .. w.y .. ' ' .. w.w .. ' ' .. w.h .. ' ' .. tostring(w.maximized ~= nil))\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:max 0 0 256 242 true")), "double-clicking the title bar maximizes over the whole desktop, icon column included");
		event(m, "{\"name\":\"pointer\",\"x\":100,\"y\":5,\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":100,\"y\":5,\"buttons\":0,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":100,\"y\":5,\"buttons\":1,\"player\":\"t\"}");
		event(m, "{\"name\":\"pointer\",\"x\":100,\"y\":5,\"buttons\":0,\"player\":\"t\"}");
		settle(m, 200);
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('edit') print('restored ' .. w.w .. ' ' .. w.h .. ' ' .. tostring(w.maximized == nil))\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:restored " + (Integer.parseInt(sz[2]) - 20) + " " + Math.min(Integer.parseInt(sz[3]) - 10, 256 - 14 - Integer.parseInt(sz[1])) + " true")), "a second double-click restores it");
		// the hardware cursor: a hover moves it and draws nothing
		settle(m, 200);
		event(m, "{\"name\":\"pointer\",\"x\":33,\"y\":44,\"buttons\":0,\"player\":\"t\"}");
		h.screen.takeDrawn();
		final int hoverFrames = settle(m, 50);
		final int[] cur = h.screen.cursorState();
		check(hoverFrames == 0 && !h.screen.takeDrawn() && cur[0] == 33 && cur[1] == 44 && cur[2] == 1 && cur[3] == 9 && cur[4] == 14,
			"a hover moves the hardware cursor without a redraw (" + cur[0] + "," + cur[1] + " visible=" + cur[2] + " " + cur[3] + "x" + cur[4] + ", frames " + hoverFrames + ")");
		// the world apps on the pretend bus
		final int errorsBefore = (int) h.log.stream().filter(l -> l.startsWith("3:")).count();
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.open('inventory') kernel.open('redstone') kernel.open('world') kernel.open('launcher')\"}");
		settle(m, 300);
		check(m.run() != LuaMachine.Result.ERROR && h.log.stream().filter(l -> l.startsWith("3:")).count() == errorsBefore, "Inventory, Redstone, World and the launcher open without errors");
		event(m, "{\"name\":\"exec\",\"code\":\"print(kernel.find('inventory') and kernel.find('redstone') and kernel.find('world') and kernel.find('launcher') and 'apps-open' or 'apps-missing')\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:apps-open")), "all four windows exist");
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('redstone') for _, b in ipairs(w.widgets) do if b.text == 'On' and b.side == 'front' then b.onclick(b) end end\"}");
		settle(m, 200);
		check(h.busCalls.stream().anyMatch(c -> c.equals("redstone.setOutput [\"front\",15]")), "Redstone's On button sets the front output to 15");
		// the columns read the absolute-keyed maps back through the facing: front's out is 15 now, back's in is 7
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('redstone') print('cols ' .. w.widgets[6].text .. ' ' .. w.widgets[10].text)\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:cols 15 7")), "Redstone's out/in columns show the levels (absolute sides mapped through getFacing)");
		event(m, "{\"name\":\"shell\",\"line\":\"ps\"}");
		event(m, "{\"name\":\"shell\",\"line\":\"kill redstone\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.matches("1:\\s*\\d+  (sleeping|running|waiting)\\s+redstone\\s+Redstone")) && h.log.stream().anyMatch(l -> l.equals("1:killed redstone"))
			&& "true".equals(m.eval("return tostring(kernel.find('redstone') == nil)")), "ps lists the Redstone program and kill closes it");
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('inventory') for _, b in ipairs(w.widgets) do if b.text == 'Target' then b.onclick(b) end end w.widgets[3].selected = 2 w.move(true)\"}");
		settle(m, 200);
		// the count matters and was never checked: Move all must leave it OFF (move the whole stack) and Move one
		// must send 1. `all and nil or 1` sent 1 either way, so Move all moved a single item for three sessions.
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('inventory') w.move(false)\"}");
		settle(m, 200);
		check(h.busCalls.stream().anyMatch(c -> c.equals("a4.pushItems [\"a5\",5]")) && h.busCalls.stream().anyMatch(c -> c.equals("a4.pushItems [\"a5\",5,1]")),
			"Inventory moves the selected stack to the target chest: all sends no count, one sends 1 (" + h.busCalls.stream().filter(c -> c.startsWith("a4.pushItems")).toList() + ")");
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('world') print(w.widgets[2].text)\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:Day 3  12:00  daylight")), "World shows the day and the clock from getTime");
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('world') w.widgets[7].text = 'hello' w.widgets[8].onclick() \"}");
		settle(m, 200);
		check(h.busCalls.stream().anyMatch(c -> c.equals("chat.say [\"hello\"]")), "World's Say sends chat.say");
		// the net component (U3): the shell's `net`, a message out, a message in, the Lua library
		event(m, "{\"name\":\"shell\",\"line\":\"net\"}");
		event(m, "{\"name\":\"shell\",\"line\":\"net send other hello there\"}");
		event(m, "{\"name\":\"net_message\",\"from\":\"b2\",\"sender\":\"other\",\"message\":\"hi back\"}");
		event(m, "{\"name\":\"exec\",\"code\":\"print('addr ' .. tostring(net.address()) .. ' ' .. #net.list())\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.startsWith("1:other") && l.contains("b2") && l.contains("2,0,0")), "the shell's `net` lists the peer with its address and location");
		check(h.busCalls.stream().anyMatch(c -> c.equals("net.send [\"other\",\"hello there\"]")), "`net send` goes out as net.send(to, text)");
		check(h.log.stream().anyMatch(l -> l.equals("1:<other> hi back")), "a net_message shows in the Terminal as <sender> text");
		check(h.log.stream().anyMatch(l -> l.equals("1:addr self-id 2")), "net.address() and net.list() from Lua");
		check(h.log.stream().anyMatch(l -> l.startsWith("1:faraway") && l.contains("b3") && l.contains("wireless")), "a modem peer lists as `wireless` (U3, the modem block)");
		// Files: Rename through the prompt dialog ([name], session 11)
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.open('files') local w = kernel.find('files') for i, l in ipairs(w.entries.items) do if l:find('hello.lua', 1, true) then w.entries.selected = i end end w.buttons[4].onclick() local d = kernel.top() print('dlg ' .. tostring(d.title) .. ' ' .. tostring(d.widgets[2].text)) d.widgets[2].text = 'renamed.lua' d.widgets[3].onclick() print('renamed ' .. tostring(fs.exists('/disk/renamed.lua')) .. ' ' .. tostring(fs.exists('/disk/hello.lua')))\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:dlg Rename hello.lua")) && h.log.stream().anyMatch(l -> l.equals("1:renamed true false")), "Files' Rename button prompts with the name and renames the file");
		// The file manager's clipboard ([name], session 18: "the file manager is missing a lot of basic features").
		// There was no copy and no move at all — the shell's cp could not even do a directory — so the thing a
		// player actually wants, taking a program off a CD onto the disk, was impossible from the desktop.
		check("false true false".equals(m.eval("return tostring(fs.validname('a b')) .. ' ' .. tostring(fs.validname('a-b.lua')) .. ' ' .. tostring(fs.validname('..'))")),
			"fs.validname knows what the filesystem takes: no spaces, no dot-dot");
		event(m, "{\"name\":\"exec\",\"code\":\"fs.mkdir('/disk/tree') fs.write('/disk/tree/a.lua', 'return 1') fs.mkdir('/disk/dest') for i = #kernel.windows, 1, -1 do if kernel.windows[i].app == 'files' then kernel.close(kernel.windows[i]) end end kernel.open('files')\"}");
		settle(m, 200);
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('files') local function pick(t) for i, l in ipairs(w.entries.items) do if l == t then w.entries.selected = i return true end end end pick('tree/') w.buttons[5].onclick() pick('dest/') w.buttons[1].onclick() w.buttons[7].onclick() print('copied ' .. tostring(fs.exists('/disk/dest/tree/a.lua')) .. ' ' .. tostring(fs.exists('/disk/tree/a.lua')))\"}");
		settle(m, 300);
		check(h.log.contains("1:copied true true"), "Copy then Paste copies a whole directory tree and leaves the original");
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('files') local function pick(t) for i, l in ipairs(w.entries.items) do if l == t then w.entries.selected = i return true end end end pick('tree/') w.buttons[6].onclick() pick('..') w.buttons[1].onclick() w.buttons[7].onclick() print('moved ' .. tostring(fs.exists('/disk/tree-copy/a.lua')) .. ' ' .. tostring(fs.exists('/disk/dest/tree')))\"}");
		settle(m, 300);
		check(h.log.contains("1:moved true false"), "Cut then Paste moves it, and the copy is named with a hyphen because the filesystem takes no spaces");
		// and the right button inside the window gives the entry its own menu
		final String[] fr = m.eval("local w = kernel.find('files') local cx, cy = w:client() return (cx + w.entries.x + 4) .. ' ' .. (cy + w.entries.y + 3)").split(" ");
		rclick(m, Integer.parseInt(fr[0]), Integer.parseInt(fr[1]));
		settle(m, 200);
		final String fileMenu = m.eval("local t = kernel.menus()[1] return t and t.menu.items[1].text or 'none'");
		key(m, 0x01);
		settle(m, 200);
		check(fileMenu.startsWith("Open"), "right-clicking an entry opens its menu (" + fileMenu + ")");
		event(m, "{\"name\":\"exec\",\"code\":\"fs.mkdir('/disk/game') fs.write('/disk/game/main.lua', 'print(99)') fs.write('/disk/game/program.txt', 'My Game') fs.write('/disk/main.lua', 'print(1)') local p = kernel.diskPrograms() print(#p .. ' ' .. tostring(p[1] and p[1].name) .. ' ' .. tostring(p[2] and p[2].name))\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:2 Internal disk My Game")), "a mount with main.lua at its root (a game CD) and a directory with main.lua + program.txt are programs on disk");
		// U3 program distribution: a program on a disk takes the top of the icon column, and a disk event refreshes it
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.refreshDisks() kernel.draw() print('icon ' .. tostring(kernel.icons[1] and kernel.icons[1].path) .. ' ' .. tostring(#kernel.icons))\"}");
		settle(m, 100);
		check(h.log.stream().anyMatch(l -> l.startsWith("1:icon /disk/") && !l.contains("nil")), "a program on a disk gets a desktop icon");
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.diskProgs = {} kernel.notify(nil)\"}");
		event(m, "{\"name\":\"disk_inserted\",\"description\":\"CD floppy1\",\"kind\":\"cd\",\"location\":\"west\"}");
		settle(m, 100);
		check("2".equals(m.eval("return tostring(#kernel.diskProgs)")) && "true".equals(m.eval("return tostring(kernel.toast ~= nil and kernel.toast:find('My Game', 1, true) ~= nil)")),
			"inserting a disk refreshes the programs and the toast names what arrived");
		event(m, "{\"name\":\"shell\",\"line\":\"run 'My Game'\"}"); // by its program.txt name, as a full-screen program
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:99")) && "true".equals(m.eval("return tostring(kernel.top() == nil or kernel.top().fullscreen ~= true)")), "the shell runs a disk program by its launcher name (full-screen, closed when it returns)");
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.open('music') w.play() snd.beep() print(snd.note('A4') .. ' ' .. snd.note(60))\"}");
		settle(m, 400);
		check(h.log.stream().anyMatch(l -> l.startsWith("1:440 261.6")), "snd.note maps names and MIDI numbers to Hz");
		check(h.log.stream().filter(l -> l.startsWith("3:")).count() == errorsBefore, "Music plays its default pattern without errors (no chip in the harness)");
		// U3 creative round-trips: a song saved by Music played by snd.playsong, the shell's `play`, the examples
		event(m, "{\"name\":\"exec\",\"code\":\"fs.mkdir('/disk/songs') fs.write('/disk/songs/t.json', json.encode({bpm = 600, steps = 4, wave = {0, 0, 0, 0}, notes = {{60,0,0,0},{62,0,0,0},{64,0,0,0},{65,0,0,0}}})) SONG = snd.playsong('/disk/songs/t.json') print('song ' .. tostring(SONG.program ~= nil))\"}");
		settle(m, 300);
		event(m, "{\"name\":\"exec\",\"code\":\"print('step ' .. tostring(SONG.step > 0)) SONG.stop() print('stopped ' .. tostring(SONG.program == nil))\"}");
		settle(m, 100);
		check(h.log.stream().anyMatch(l -> l.equals("1:song true")) && h.log.stream().anyMatch(l -> l.equals("1:step true"))
			&& h.log.stream().anyMatch(l -> l.equals("1:stopped true")), "snd.playsong plays a song file in the background and its handle stops it");
		event(m, "{\"name\":\"shell\",\"line\":\"play /disk/songs/t.json\"}");
		settle(m, 100);
		event(m, "{\"name\":\"shell\",\"line\":\"play stop\"}");
		event(m, "{\"name\":\"shell\",\"line\":\"examples\"}");
		settle(m, 150);
		check(h.log.stream().anyMatch(l -> l.startsWith("1:playing /disk/songs/t.json")) && h.log.stream().anyMatch(l -> l.equals("1:stopped")),
			"the shell's `play` plays a saved song and `play stop` ends it");
		check(h.log.stream().anyMatch(l -> l.startsWith("1:song ") && l.contains("Music")) && h.log.stream().anyMatch(l -> l.startsWith("1:sprite ") && l.contains("Paint")),
			"`examples` lists the example programs with what they do");
		event(m, "{\"name\":\"shell\",\"line\":\"examples song t\"}");
		settle(m, 200);
		final boolean exampleUp = "true".equals(m.eval("return tostring(kernel.top() ~= nil and kernel.top().fullscreen == true)"));
		event(m, "{\"name\":\"scancode\",\"code\":16,\"down\":true,\"player\":\"t\"}"); // q
		event(m, "{\"name\":\"scancode\",\"code\":16,\"down\":false,\"player\":\"t\"}");
		settle(m, 150);
		check(exampleUp && h.log.stream().anyMatch(l -> l.equals("1:song t")), "`examples song <name>` runs full-screen with its argument and quits on Q");
		// a game that loads its own palette must not leave the desktop wearing it (found in-game, session 12)
		event(m, "{\"name\":\"exec\",\"code\":\"fs.write('/disk/pal.lua', 'gfx.palette(1, 0x0122FF) print(\\\"in \\\" .. string.format(\\\"%06X\\\", gfx.palette(1)))') print('before ' .. string.format('%06X', gfx.palette(1)))\"}");
		event(m, "{\"name\":\"shell\",\"line\":\"start /disk/pal.lua\"}");
		settle(m, 200);
		event(m, "{\"name\":\"exec\",\"code\":\"print('after ' .. string.format('%06X', gfx.palette(1)))\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:in 0122FF")) && h.log.stream().anyMatch(l -> l.equals("1:before 1D2B53"))
			&& h.log.stream().anyMatch(l -> l.equals("1:after 1D2B53")), "a program's palette is put back when it leaves");
		// a machine placed before its monitor boots with a 0x0 screen: windows opened then must not come back as
		// slivers when a screen appears (found in-game, session 12 — a window with a negative size draws as a line)
		event(m, "{\"name\":\"exec\",\"code\":\"local sw, sh = kernel.w, kernel.h kernel.w, kernel.h = 0, 0 local wd = win.Window.new{ title = 'Void', w = math.min(kernel.w - kernel.iconW - 8, 300), h = math.min(kernel.h - kernel.taskbarH - 10, 220) } kernel.show(wd) print('made ' .. wd.w .. 'x' .. wd.h) kernel.w, kernel.h = sw, sh kernel.layout() print('laid ' .. wd.w .. 'x' .. wd.h .. ' at ' .. wd.x .. ',' .. wd.y) kernel.close(wd)\"}");
		settle(m, 150);
		check(h.log.stream().anyMatch(l -> l.startsWith("1:made ") && !l.contains("-"))
			&& h.log.stream().anyMatch(l -> l.equals("1:laid 184x220 at 68,6")),
			"a window made while the machine had no screen comes back at a real size once one appears");
		// the same window geometry, but arriving from the saved desktop (which writes x/y/w/h back verbatim)
		event(m, "{\"name\":\"exec\",\"code\":\"local t = kernel.find('terminal') t.x, t.y, t.w, t.h = 64, 6, -68, -26 kernel.layout() print('restored ' .. t.w .. 'x' .. t.h)\"}");
		settle(m, 150);
		check(h.log.stream().anyMatch(l -> l.equals("1:restored 184x220")), "a restored desktop's nonsense geometry is laid out again, not drawn");
		// the wheel: dy = +1 is a wheel *up*, which shows earlier lines (scroll offset down). It was inverted in
		// both scrollable widgets and nothing here covered it ([name], session 12)
		event(m, "{\"name\":\"exec\",\"code\":\"local wd = win.Window.new{ title = 'Scroll', x = 70, y = 10, w = 120, h = 70 } local l = wd:add(win.List{ x = 0, y = 0, w = 100, h = 40 }) local it = {} for i = 1, 50 do it[i] = 'row ' .. i end l.items = it kernel.show(wd) SCROLL = { wd = wd, l = l }\"}");
		event(m, "{\"name\":\"wheel\",\"dy\":-1,\"x\":100,\"y\":40,\"player\":\"t\"}");
		event(m, "{\"name\":\"wheel\",\"dy\":-1,\"x\":100,\"y\":40,\"player\":\"t\"}");
		settle(m, 150);
		event(m, "{\"name\":\"exec\",\"code\":\"print('down ' .. SCROLL.l.scroll)\"}");
		event(m, "{\"name\":\"wheel\",\"dy\":1,\"x\":100,\"y\":40,\"player\":\"t\"}");
		settle(m, 150);
		event(m, "{\"name\":\"exec\",\"code\":\"print('up ' .. SCROLL.l.scroll)\"}");
		event(m, "{\"name\":\"wheel\",\"dy\":1,\"x\":100,\"y\":40,\"player\":\"t\"}");
		event(m, "{\"name\":\"wheel\",\"dy\":1,\"x\":100,\"y\":40,\"player\":\"t\"}");
		settle(m, 150);
		event(m, "{\"name\":\"exec\",\"code\":\"print('top ' .. SCROLL.l.scroll) kernel.close(SCROLL.wd) SCROLL = nil\"}");
		settle(m, 150);
		check(h.log.stream().anyMatch(l -> l.equals("1:down 6")) && h.log.stream().anyMatch(l -> l.equals("1:up 3"))
			&& h.log.stream().anyMatch(l -> l.equals("1:top 0")), "the wheel scrolls the way it is pushed, and stops at the top");
		// word wrap in the Terminal's console ([name], session 12): a long line becomes several visual rows,
		// broken at spaces, and the last one is still what you see
		event(m, "{\"name\":\"exec\",\"code\":\"local t = kernel.find('terminal') local a for _, w in ipairs(t.widgets) do if w.lines then a = w end end a:settext('') a:append(string.rep('word ', 40)) local v = a:visual() local longest = 0 for _, r in ipairs(v) do if #r.text > longest then longest = #r.text end end print('wrap ' .. #a.lines .. ' -> ' .. #v .. ' rows, longest ' .. longest .. ' of ' .. a:cols() .. ', bottom ' .. tostring(a.scroll == math.max(0, #v - a:rows())) .. ', whole ' .. tostring(v[1].text:sub(-1) ~= 'w'))\"}");
		settle(m, 150);
		check(h.log.stream().anyMatch(l -> l.matches("1:wrap 1 -> ([2-9]|\\d\\d+) rows.*") && l.contains("bottom true") && l.contains("whole true")
			&& widthOk(l)), "the Terminal's console wraps long lines at spaces and stays at the bottom");
		// the games and Paint (S7): run each full-screen for a while with keys, then quit with Q
		for (final String game : new String[] { "snake", "breakout" }) {
			final int errs = (int) h.log.stream().filter(l -> l.startsWith("3:")).count();
			event(m, "{\"name\":\"exec\",\"code\":\"kernel.open('" + game + "')\"}");
			settle(m, 50);
			for (int i = 0; i < 6; i++) {
				event(m, "{\"name\":\"scancode\",\"code\":57,\"down\":true,\"player\":\"t\"}"); // space: serve / pause
				event(m, "{\"name\":\"scancode\",\"code\":57,\"down\":false,\"player\":\"t\"}");
				event(m, "{\"name\":\"scancode\",\"code\":" + (i % 2 == 0 ? 203 : 205) + ",\"down\":true,\"player\":\"t\"}");
				event(m, "{\"name\":\"scancode\",\"code\":" + (i % 2 == 0 ? 203 : 205) + ",\"down\":false,\"player\":\"t\"}");
				settle(m, 40);
			}
			event(m, "{\"name\":\"pointer\",\"x\":100,\"y\":200,\"buttons\":1,\"player\":\"t\"}");
			event(m, "{\"name\":\"pointer\",\"x\":120,\"y\":200,\"buttons\":0,\"player\":\"t\"}");
			settle(m, 60);
			final boolean fullscreen = "true".equals(m.eval("return tostring(kernel.top() ~= nil and kernel.top().fullscreen == true)")) && h.screen.cursorState()[2] == 0;
			event(m, "{\"name\":\"scancode\",\"code\":16,\"down\":true,\"player\":\"t\"}"); // q
			event(m, "{\"name\":\"scancode\",\"code\":16,\"down\":false,\"player\":\"t\"}");
			settle(m, 60);
			final boolean closed = "true".equals(m.eval("return tostring(kernel.top() == nil or kernel.top().fullscreen ~= true)"));
			check(fullscreen && closed && h.log.stream().filter(l -> l.startsWith("3:")).count() == errs, game + " runs full-screen with keys and pointer, quits on Q, no errors");
		}
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.open('paint') w.paintAt(5, 5) w.paintAt(6, 5) gfx.savesprite('/disk/s.spr', 16, 16, w.pixels()) local s = gfx.loadsprite('/disk/s.spr') gfx.sprite(0, 0, s) print(s.w .. ' ' .. s.data:byte(1) .. ' ' .. #s.data) kernel.close(w)\"}");
		settle(m, 200);
		check(h.log.stream().anyMatch(l -> l.equals("1:16 9 256")), "Paint paints, a sprite saves as .spr and loads back");
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.find('music').stop() kernel.close(kernel.find('music')) kernel.close(kernel.find('launcher')) kernel.close(kernel.find('world')) kernel.close(kernel.find('inventory')) kernel.close(kernel.find('redstone'))\"}");
		settle(m, 200);
		event(m, "{\"name\":\"save\"}");
		boolean saved = false;
		for (int i = 0; i < 50; i++) {
			final LuaMachine.Result r = m.run();
			if (r == LuaMachine.Result.VALUE && "saved".equals(m.yieldValue())) {
				saved = true;
				break;
			}
			if (r == LuaMachine.Result.WAIT || r == LuaMachine.Result.ERROR) {
				break;
			}
		}
		check(saved && h.saved != null && h.saved.contains("\"windows\""), "save event answered with 'saved' and window state");
		final Host h2 = new Host(tmp);
		h2.saved = h.saved;
		final LuaMachine m2 = new LuaMachine(h2, boot, "boot.lua");
		frames = settle(m2, 200);
		check(frames >= 1 && h2.log.stream().noneMatch(l -> l.startsWith("3:")), "kernel restores saved windows on boot");
		event(m2, "{\"name\":\"exec\",\"code\":\"kernel.open('demo')\"}");
		int f = 0;
		for (int i = 0; i < 300 && f < 10; i++) {
			final LuaMachine.Result r = m2.run();
			if (r == LuaMachine.Result.VALUE && "flip".equals(m2.yieldValue())) {
				f++;
			}
			if (r == LuaMachine.Result.WAIT && m2.waitMillis() > 0) {
				sleep(m2.waitMillis()); // frame pacing between the demo's frames
			}
			if (r == LuaMachine.Result.ERROR) {
				break;
			}
		}
		check(f >= 10, "the demo program produces frames (" + f + ")");
		check(h2.log.stream().noneMatch(l -> l.startsWith("3:")), "no errors in the second kernel");
		// the terminal counterparts of the world apps and program.txt requirements (U3b): all on the first machine's pretend bus
		event(m, "{\"name\":\"exec\",\"code\":\"fs.mkdir('/disk/big') fs.write('/disk/big/main.lua', [[print('ran big')]]) fs.write('/disk/big/program.txt', 'Big' .. string.char(10) .. 'mem=99') kernel.runfile('/disk/big/main.lua') print('toast ' .. tostring(kernel.toast))\"}");
		settle(m, 200);
		for (final String line : new String[] { "rs", "rs on right", "rs wake 5", "rs sleep on", "rs", "inv", "inv 1", "world", "palette 3 123456", "palette 3" }) {
			event(m, "{\"name\":\"shell\",\"line\":\"" + line + "\"}");
			settle(m, 200);
		}
		final java.util.List<String> sh2 = h.log.stream().filter(l -> l.startsWith("1:")).map(l -> l.substring(2)).toList();
		check(sh2.stream().anyMatch(l -> l.startsWith("side      in  out")) && sh2.stream().anyMatch(l -> l.startsWith("right ")) && sh2.contains("right = 15"), "rs lists the six sides and rs on drives an output");
		// redstone wake / sleep (the VM tier's controls, now on this tier too)
		check(h.wake == 5 && h.sleep && sh2.contains("wake at 5") && sh2.contains("redstone sleep on") && sh2.contains("wake at 5, sleep on"),
			"rs wake / rs sleep set the threshold on the component and rs reports it");
		// ---- U6: the desktop's furniture — start menu, right-click menus, minimise/maximise, screensaver, About ----
		// Park every window in the top-left corner first, so the middle of the screen is bare desktop and the
		// coordinates below mean what they say whatever the earlier checks left open.
		event(m, "{\"name\":\"exec\",\"code\":\"if not kernel.find('edit') then kernel.open('edit') end for _, w in ipairs(kernel.windows) do w.x, w.y, w.w, w.h = 100, 0, 96, 60 w.maximized = nil w:relayout() end kernel.focus(kernel.find('edit'))\"}");
		settle(m, 200);
		// the Apps button opens the start menu, above the taskbar it came from
		click(m, 10, 247);
		settle(m, 200);
		final String startMenu = m.eval("local t = kernel.menus()[1] if not t then return 'no menu' end return t.menu.items[1].text .. ' ' .. #kernel.menus() .. ' ' .. tostring(t.y + t.h <= kernel.h - kernel.taskbarH)");
		check("Programs 1 true".equals(startMenu), "the Apps button opens the start menu above the taskbar (" + startMenu + ")");
		// hovering an entry with a submenu opens it; hovering a different entry takes it away again
		final String[] mrect = m.eval("local t = kernel.menus()[1] return t.x .. ' ' .. t.y .. ' ' .. t.w").split(" ");
		final int mx = Integer.parseInt(mrect[0]), my = Integer.parseInt(mrect[1]);
		hover(m, mx + 5, my + 5);
		settle(m, 200);
		final boolean subOpened = "2".equals(m.eval("return tostring(#kernel.menus())"));
		hover(m, mx + 5, my + 40);
		settle(m, 200);
		check(subOpened && "1".equals(m.eval("return tostring(#kernel.menus())")),
			"hovering Programs opens its submenu, and moving off it closes it again");
		key(m, 0x01);
		settle(m, 200);
		check("0".equals(m.eval("return tostring(#kernel.menus())")), "Escape closes the menu");
		// a click anywhere else dismisses the menu and is spent doing that: nothing under it is pressed
		click(m, 10, 247);
		settle(m, 200);
		final String before6 = m.eval("return tostring(#kernel.windows)");
		click(m, 200, 150);
		settle(m, 200);
		check("0".equals(m.eval("return tostring(#kernel.menus())")) && before6.equals(m.eval("return tostring(#kernel.windows + 1)")),
			"a click off the menu closes it and does not press what is under it");
		// the keyboard drives it: down past the rule to Settings, Enter opens it
		event(m, "{\"name\":\"exec\",\"code\":\"local s = kernel.find('settings') if s then kernel.close(s) end\"}");
		click(m, 10, 247);
		settle(m, 200);
		key(m, 208); key(m, 208); key(m, 208); // Programs, Documents, (rule skipped), Settings
		settle(m, 100);
		key(m, 28);
		settle(m, 300);
		check("Settings 0".equals(m.eval("return tostring(kernel.find('settings') ~= nil and 'Settings' or 'no') .. ' ' .. #kernel.menus()")),
			"arrow keys and Enter pick an entry, and choosing one closes the whole menu");
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.close(kernel.find('settings'))\"}");
		settle(m, 200);
		// the three right-click menus: bare desktop, an icon, a title bar
		rclick(m, 200, 150);
		settle(m, 200);
		final String deskMenu = m.eval("local t = kernel.menus()[1] return t and t.menu.items[1].text or 'none'");
		key(m, 0x01);
		rclick(m, 10, 10);
		settle(m, 200);
		final String iconMenu = m.eval("local t = kernel.menus()[1] return t and t.menu.items[1].text or 'none'");
		key(m, 0x01);
		rclick(m, 130, 5);
		settle(m, 200);
		final String winMenu = m.eval("local t = kernel.menus()[1] return t and t.menu.items[1].text or 'none'");
		key(m, 0x01);
		settle(m, 200);
		check("New file...".equals(deskMenu) && "Open".equals(iconMenu) && "Minimise".equals(winMenu),
			"right-click gives the desktop, icon and window menus (" + deskMenu + " / " + iconMenu + " / " + winMenu + ")");
		// minimise: the window leaves the screen, keeps its taskbar button, and the button brings it back
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('edit') w.x, w.y, w.w, w.h = 100, 0, 96, 60 w:relayout() kernel.focus(w)\"}");
		settle(m, 200);
		click(m, 100 + 96 - 12 * 3 + 5, 6); // the leftmost of the three title boxes
		settle(m, 200);
		final String mini = m.eval("local w = kernel.find('edit') local n = 0 for _, b in ipairs(kernel.taskButtons) do if b.window == w then n = n + 1 end end return tostring(w.minimized) .. ' ' .. n .. ' ' .. tostring(kernel.top() ~= w)");
		final String[] tb = m.eval("local w = kernel.find('edit') for _, b in ipairs(kernel.taskButtons) do if b.window == w then return b.x + 3 .. ' ' .. b.y + 3 end end return '0 0'").split(" ");
		click(m, Integer.parseInt(tb[0]), Integer.parseInt(tb[1]));
		settle(m, 200);
		check("true 1 true".equals(mini) && "false".equals(m.eval("return tostring(kernel.find('edit').minimized == true)")),
			"the minimise box hides a window, its taskbar button stays, and the button restores it (" + mini + ")");
		// maximise: the middle box does what the double-click does
		click(m, 100 + 96 - 12 * 2 + 5, 6);
		settle(m, 200);
		check("0 0 256 242 true".equals(m.eval("local w = kernel.find('edit') return w.x .. ' ' .. w.y .. ' ' .. w.w .. ' ' .. w.h .. ' ' .. tostring(w.maximized ~= nil)")),
			"the maximise box fills the desktop");
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('edit') kernel.maximize(w) w.x, w.y, w.w, w.h = 100, 0, 96, 60 w:relayout()\"}");
		settle(m, 200);
		// About: the one place that says what the machine is
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.about() local n = 0 for _, x in ipairs(w.widgets) do if x.text == 'Memory' or x.text == 'Screen' or x.text == 'Case' then n = n + 1 end end print('about ' .. w.title .. ' ' .. n .. ' ' .. tostring(w.modal == true)) kernel.close(w)\"}");
		settle(m, 200);
		check(h.log.contains("1:about About this computer 3 true"), "About lists the case, the memory and the screen in a modal box");
		// the screensaver: on after its timeout, off on the first input, and that input is spent dismissing it
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.saver.kind = 'lines' kernel.saver.timeout = 1 kernel.lastInput = 0\"}");
		settle(m, 400);
		final String saverOn = m.eval("return tostring(kernel.saverOn)");
		click(m, 10, 247); // the Apps button, which must NOT open the start menu
		settle(m, 200);
		check("true".equals(saverOn) && "false 0".equals(m.eval("return tostring(kernel.saverOn) .. ' ' .. #kernel.menus()")),
			"the screensaver comes on when the desktop goes idle, and the click that dismisses it is not passed on");
		// and it never runs unwatched: an idle machine has to go still so the host can park its framebuffer
		event(m, "{\"name\":\"viewers\",\"n\":0}");
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.lastInput = 0\"}");
		settle(m, 400);
		check("false".equals(m.eval("return tostring(kernel.saverOn)")), "the screensaver stays off while nobody is watching");
		event(m, "{\"name\":\"viewers\",\"n\":1}");
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.saver.timeout = 300 kernel.saver.kind = 'stars' kernel.lastInput = os.clock()\"}");
		settle(m, 200);
		// Edit's word wrap ([name], session 18): a long line becomes rows, and the cursor still maps both ways
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.find('edit') local a for _, x in ipairs(w.widgets) do if x.lines then a = x end end a.wrap = true a:settext(string.rep('word ', 40)) a.cy, a.cx = 1, 120 local v = a:visual() local r, c = a:cursorAt() print('wrapedit ' .. #a.lines .. ' ' .. tostring(#v > 4) .. ' ' .. (v[r].off + c) .. ' ' .. tostring(v[r].text:sub(-1) ~= 'w'))\"}");
		settle(m, 200);
		check(h.log.contains("1:wrapedit 1 true 120 true"), "Edit wraps a long line at spaces and the cursor round-trips through the wrapped rows");
		// preferences that outlive a reboot: the saver, the volume and the recent documents ride with the wallpaper
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.setVolume(0.4) kernel.saver.timeout = 120 kernel.addRecent('/disk/hello.lua') kernel.savePrefs() local t = json.decode(fs.read(kernel.prefsPath)) print('prefs ' .. t.volume .. ' ' .. t.saver.timeout .. ' ' .. t.recent[1])\"}");
		settle(m, 200);
		check(h.log.contains("1:prefs 0.4 120 /disk/hello.lua"), "the saver, the volume and the recent documents are saved beside the wallpaper");

		// nobody watching: the desktop stops repainting the clock, so the host can park the framebuffer
		event(m, "{\"name\":\"viewers\",\"n\":0}");
		settle(m, 200);
		event(m, "{\"name\":\"exec\",\"code\":\"print('watched ' .. tostring(kernel.watched) .. ' ' .. tostring(kernel.waitDelay()))\"}");
		settle(m, 200);
		check(h.log.contains("1:watched false nil"), "viewers 0 stops the clock and lets the kernel sleep until an event");
		event(m, "{\"name\":\"viewers\",\"n\":2}");
		settle(m, 200);
		event(m, "{\"name\":\"exec\",\"code\":\"print('watched ' .. tostring(kernel.watched) .. ' ' .. tostring(kernel.waitDelay() ~= nil))\"}");
		settle(m, 200);
		check(h.log.contains("1:watched true true"), "a viewer arriving wakes the clock again");
		// resume: frozen_for_ticks is the longer of the world's and the wall clock, so a stop is not reported as no time
		event(m, "{\"name\":\"resume\",\"frozen_for_ticks\":36000,\"world_ticks\":0,\"real_ticks\":36000,\"reason\":\"stop\",\"exact\":false}");
		settle(m, 200);
		event(m, "{\"name\":\"exec\",\"code\":\"print('resume ' .. tostring(kernel.toast))\"}");
		settle(m, 200);
		check(h.log.contains("1:resume Resumed after 30 min (server was down)"), "a resume after a server stop says how long it really was");
		event(m, "{\"name\":\"power\",\"reason\":\"redstone\"}");
		settle(m, 200);
		check(h.shutdown && h.saved != null, "a power event saves the desktop and shuts the machine down itself");
		check(sh2.stream().anyMatch(l -> l.matches(" ?[12]  .*slots  @.*")) && sh2.stream().anyMatch(l -> l.matches("\\s*\\d+\\s+\\d+\\s+.*")), "inv lists the containers on the bus and inv 1 their stacks");
		check(sh2.stream().anyMatch(l -> l.startsWith("at ")) && sh2.stream().anyMatch(l -> l.startsWith("weather ")) && sh2.stream().anyMatch(l -> l.startsWith("players ")), "world answers from the prompt");
		check(sh2.contains("3 = 123456"), "palette sets and shows a colour");
		check(h.log.stream().anyMatch(l -> l.startsWith("1:toast Needs 99 MB")) && h.log.stream().noneMatch(l -> l.equals("1:ran big")), "a program whose program.txt asks for more memory than the machine has is refused with a toast");
		// the tier ladder (U3b): a Basic Computer boots into the shell -- no icons, no taskbar, the terminal borderless
		// over the whole screen; the stock apps still open, full size, and closing the last one brings the shell back
		// §9 U3c: the Manual. It is in the ROM, so it is there on a machine with no disk in it, and its pages are
		// files -- adding one is adding a file, and nothing has a list of them in it that could go stale.
		event(m, "{\"name\":\"exec\",\"code\":\"local w = kernel.open('manual') print('manual ' .. #w.list.items .. ' ' .. tostring(w.list.items[1])) w.list.onactivate(2) print('page ' .. w.title .. ' / ' .. tostring(w.save().page)) w.buttons[3].onclick() print('next ' .. w.title) w.buttons[1].onclick() print('back ' .. tostring(w.list.hidden == false)) kernel.close(w)\"}");
		event(m, "{\"name\":\"shell\",\"line\":\"man\"}");
		event(m, "{\"name\":\"shell\",\"line\":\"man 4\"}");
		event(m, "{\"name\":\"shell\",\"line\":\"man ls\"}");
		settle(m, 300);
		check(h.log.stream().anyMatch(l -> l.startsWith("1:manual 10 1. What this is")), "the Manual finds its ten pages in the ROM");
		check(h.log.contains("1:page The parts, and the case / 2") && h.log.contains("1:next The desktop") && h.log.contains("1:back true"),
			"a page opens, Next turns it and Contents comes back");
		final java.util.List<String> man = h.log.stream().filter(l -> l.startsWith("1:")).map(l -> l.substring(2)).toList();
		check(man.stream().anyMatch(l -> l.contains("What this is")) && man.stream().anyMatch(l -> l.equals("The shell"))
			&& man.stream().anyMatch(l -> l.startsWith("ls [path]")),
			"`man` lists the pages, `man 4` prints one wrapped to the terminal, and `man ls` still answers as help did");
		// §9 U12: a program keeps its own state across a freeze. Not a call stack — the meaningful state, the way
		// an app already does through app.save. Three programs: one that keeps something, one that wants to keep
		// more than the limit, and one whose save() throws; the last two must cost only themselves.
		event(m, "{\"name\":\"exec\",\"code\":\"fs.write('/disk/keep.lua', \\\"program.version = 2 program.save = function() return { n = 41 } end local k = program.restore() print('kept ' .. tostring(k and k.n)) while true do os.sleep(500) end\\\")\"}");
		event(m, "{\"name\":\"exec\",\"code\":\"fs.write('/disk/greedy.lua', \\\"program.save = function() return { s = string.rep('x', 40000) } end while true do os.sleep(500) end\\\")\"}");
		event(m, "{\"name\":\"exec\",\"code\":\"fs.write('/disk/boom.lua', \\\"program.save = function() error('boom') end while true do os.sleep(500) end\\\")\"}");
		settle(m, 200);
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.runfile('/disk/keep.lua') kernel.runfile('/disk/greedy.lua') kernel.runfile('/disk/boom.lua')\"}");
		settle(m, 300);
		event(m, "{\"name\":\"exec\",\"code\":\"kernel.save()\"}");
		settle(m, 300);
		check(h.log.contains("1:kept nil"), "a program with nothing saved yet gets nil from program.restore()");
		check(h.saved != null && h.saved.contains("keep.lua") && h.saved.contains("\"n\""),
			"the program's state is written into the machine's saved state");
		check(h.log.stream().anyMatch(l -> l.contains("greedy.lua wanted to keep")) && h.log.stream().anyMatch(l -> l.contains("save failed in boom.lua")),
			"a greedy save and a throwing save each say so and cost only themselves");
		final Host hProg = new Host(tmp);
		hProg.saved = h.saved;
		final LuaMachine mProg = new LuaMachine(hProg, boot, "boot.lua");
		settle(mProg, 400);
		check(hProg.log.contains("1:kept 41"), "the program is restarted after a thaw with the state it kept (§9 U12)");
		check(hProg.log.stream().anyMatch(l -> l.contains("restored /disk/keep.lua"))
			&& hProg.log.stream().noneMatch(l -> l.contains("restored /disk/greedy.lua"))
			&& hProg.log.stream().noneMatch(l -> l.contains("restored /disk/boom.lua")),
			"the kernel says which programs came back, and the two whose save did not work are not among them");
		// a program whose version has moved on refuses its old state instead of unpacking it into fields that moved
		event(mProg, "{\"name\":\"exec\",\"code\":\"fs.write('/disk/keep.lua', \\\"program.version = 3 local k = program.restore() print('v3 ' .. tostring(k)) while true do os.sleep(500) end\\\")\"}");
		settle(mProg, 200);
		final Host hVer = new Host(tmp);
		hVer.saved = h.saved;
		final LuaMachine mVer = new LuaMachine(hVer, boot, "boot.lua");
		settle(mVer, 400);
		check(hVer.log.contains("1:v3 nil") && hVer.log.stream().anyMatch(l -> l.contains("this program speaks 3")),
			"a program that has changed version starts fresh instead of unpacking state it does not understand");
		event(m, "{\"name\":\"exec\",\"code\":\"for i = #kernel.windows, 1, -1 do local wd = kernel.windows[i] if wd.programFile then kernel.close(wd) end end\"}");
		settle(m, 200);
		// §9 U10(b): the world's own calendar. Tick 0 is 1970-01-01 06:00, a tick is 3600 ms of world time, and
		// os.date/os.datetable are exact for leap years -- the Calendar app is only as good as this arithmetic.
		event(m, "{\"name\":\"exec\",\"code\":\"print('epoch0 ' .. os.date('%F %T', 0) .. ' wday ' .. os.datetable(0).wday .. ' yday ' .. os.datetable(0).yday)\"}");
		event(m, "{\"name\":\"exec\",\"code\":\"print('tick0 ' .. os.date('%A %d %B %Y %H:%M', 21600000))\"}");
		event(m, "{\"name\":\"exec\",\"code\":\"print('year ' .. os.date('%F %T', 21600000 + 365 * 24000 * 3600))\"}");
		event(m, "{\"name\":\"exec\",\"code\":\"print('leap ' .. os.monthdays(1972, 2) .. ' ' .. os.monthdays(1970, 2) .. ' ' .. os.monthdays(2000, 2) .. ' ' .. os.monthdays(1900, 2))\"}");
		event(m, "{\"name\":\"exec\",\"code\":\"print('round ' .. os.date('%d/%m/%Y', os.daysfromdate(2026, 8, 29) * 86400000))\"}");
		event(m, "{\"name\":\"exec\",\"code\":\"local a, b = os.date() print('taskbar ' .. a .. ' ' .. tostring(b == math.floor(os.time() / 24000)))\"}");
		settle(m, 300);
		check(h.log.contains("1:epoch0 1970-01-01 00:00:00 wday 5 yday 1"), "the epoch is 1970-01-01, a Thursday");
		check(h.log.contains("1:tick0 Thursday 01 January 1970 06:00"), "world tick 0 is 1970-01-01 06:00, as [name] asked (§9 U10(b))");
		check(h.log.contains("1:year 1971-01-01 06:00:00"), "365 Minecraft days later is a year later to the minute");
		check(h.log.contains("1:leap 29 28 29 28"), "leap years: 1972 and 2000 yes, 1970 and 1900 no");
		check(h.log.contains("1:round 29/08/2026"), "os.daysfromdate and os.date are inverses");
		check(h.log.stream().anyMatch(l -> l.startsWith("1:taskbar ") && l.endsWith(" true")), "os.date() with no format is still the taskbar's HH:MM and day");
		// §9 U10(a): a case with no drive in it has no /disk at all. The machine still boots -- the shell just
		// starts somewhere it can list, and nothing in the ROM may crash on a mount that is not there.
		final Host hNoDisk = new Host(tmp);
		hNoDisk.saved = null;
		hNoDisk.setSpec(MachineSpec.of(2, 1, 1, 1, 0, 64));
		hNoDisk.setDiskQuota(0);
		final LuaMachine mNoDisk = new LuaMachine(hNoDisk, boot, "boot.lua");
		settle(mNoDisk, 300);
		event(mNoDisk, "{\"name\":\"exec\",\"code\":\"print('home ' .. shell.home() .. ' ' .. tostring(fs.exists('/disk/autostart.lua')) .. ' ' .. tostring(os.info().drive))\"}");
		settle(mNoDisk, 200);
		check(hNoDisk.log.contains("1:home /rom false false"), "no drive: no /disk, fs.exists says so instead of erroring, and the shell goes home to /rom");
		check(hNoDisk.log.stream().noneMatch(l -> l.startsWith("3:")), "a machine with no drive boots with no errors");

		final Host h3 = new Host(tmp);
		h3.saved = null;
		// §9 U10(a): 16 colours is the *Graphics Card I*, not the case -- an empty case is a dead box now, and the
		// cheapest complete Basic Computer is the C64 look TESTING documents.
		h3.setSpec(MachineSpec.of(1, 1, 1, 1, 1, 64));
		final LuaMachine m3 = new LuaMachine(h3, boot, "boot.lua");
		frames = settle(m3, 200);
		check(frames >= 1 && h3.log.stream().noneMatch(l -> l.startsWith("3:")), "a Basic Computer boots (tier 1: shell only, 16 colours)");
		event(m3, "{\"name\":\"exec\",\"code\":\"local t = kernel.find('terminal') print('console ' .. tostring(kernel.console) .. ' ' .. kernel.taskbarH .. ' ' .. kernel.iconW .. ' ' .. tostring(t.borderless) .. ' ' .. t.x .. ',' .. t.y .. ' ' .. t.w .. 'x' .. t.h)\"}");
		settle(m3, 200);
		check(h3.log.contains("1:console true 0 0 true 0,0 256x256"), "shell-only: no taskbar, no icon column, the terminal borderless over the whole screen");
		event(m3, "{\"name\":\"exec\",\"code\":\"local wd = kernel.open('files') print('files ' .. wd.x .. ',' .. wd.y .. ' ' .. wd.w .. 'x' .. wd.h) kernel.close(wd) print('after ' .. #kernel.windows .. ' ' .. tostring(kernel.find('terminal') ~= nil))\"}");
		settle(m3, 200);
		check(h3.log.contains("1:files 0,0 256x256") && h3.log.contains("1:after 1 true"), "an app opens full size in the console; closing it leaves the shell");
		event(m3, "{\"name\":\"exec\",\"code\":\"local i = os.info() print('info ' .. i.tier .. ' ' .. i.tierName .. ' ' .. i.colours .. ' ' .. tostring(i.desktop))\"}");
		event(m3, "{\"name\":\"shell\",\"line\":\"top\"}");
		settle(m3, 200);
		check(h3.log.contains("1:info 1 Basic Computer 16 false") && h3.log.stream().anyMatch(l -> l.startsWith("1:case    Basic Computer")), "os.info() and top report the case");
		check(h3.screen.colours() == 16 && h3.screen.maxWidth() == 256, "the tier's caps are on the screen device");
		check(h3.log.stream().noneMatch(l -> l.startsWith("3:")), "no errors on the Basic Computer");
		final Host h4 = new Host(tmp);
		h4.setSpec(MachineSpec.of(1, 1, 1, 1, 1, 64));
		h4.desktop = true; // Settings said desktop: the same case with the full desktop
		final LuaMachine m4 = new LuaMachine(h4, boot, "boot.lua");
		settle(m4, 200);
		event(m4, "{\"name\":\"exec\",\"code\":\"print('desk ' .. tostring(kernel.console) .. ' ' .. kernel.taskbarH)\"}");
		settle(m4, 200);
		check(h4.log.stream().anyMatch(l -> l.startsWith("1:desk false ") && !l.endsWith(" 0")), "the desktop toggle overrides the tier");
		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
