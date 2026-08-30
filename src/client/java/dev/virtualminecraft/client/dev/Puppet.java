package dev.virtualminecraft.client.dev;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.block.ComputerBlockEntity;
import dev.virtualminecraft.client.render.ScreenTexture;
import dev.virtualminecraft.client.render.ScreenTextures;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;

/**
 * DEVELOPMENT ONLY. A localhost line-protocol remote control for the client so automated tests can drive the game
 * without a human at the keyboard. Enabled with {@code -Dvirtualminecraft.puppet=<port>}; never active otherwise.
 */
public final class Puppet {
	private Puppet() {
	}

	public static void start(final int port) {
		final Thread t = new Thread(() -> serve(port), "vmc-puppet");
		t.setDaemon(true);
		t.start();
		VirtualMinecraft.LOGGER.warn("Puppet remote control listening on 127.0.0.1:{} (development only)", port);
	}

	private static void serve(final int port) {
		try (ServerSocket server = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
			while (true) {
				try (Socket s = server.accept()) {
					final BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
					final PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
					String line;
					while ((line = in.readLine()) != null) {
						final String cmd = line.strip();
						if (cmd.isEmpty()) {
							continue;
						}
						String result;
						try {
							result = run(cmd);
						} catch (final Throwable ex) {
							result = "ERR " + ex;
						}
						out.println(result.replace('\n', ' '));
					}
				} catch (final IOException e) {
					VirtualMinecraft.LOGGER.warn("Puppet connection error: {}", e.toString());
				}
			}
		} catch (final IOException e) {
			VirtualMinecraft.LOGGER.error("Puppet could not listen: {}", e.toString());
		}
	}

	private static volatile double latencyResult = -1;

	private static String run(final String cmd) throws Exception {
		final String[] a = cmd.split("\\s+", 2);
		final String op = a[0];
		final String rest = a.length > 1 ? a[1] : "";
		if (op.equals("wait")) {
			Thread.sleep(Long.parseLong(rest));
			return "OK";
		}
		final Minecraft mc = Minecraft.getInstance();
		if (op.equals("screenshot")) {
			final CompletableFuture<String> f = new CompletableFuture<>();
			mc.execute(() -> Screenshot.grab(mc.gameDirectory, rest.isEmpty() ? null : (rest.endsWith(".png") ? rest : rest + ".png"), mc.gameRenderer.mainRenderTarget(), 1, c -> f.complete("OK " + c.getString())));
			return f.get(10, TimeUnit.SECONDS);
		}
		final CompletableFuture<String> f = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				f.complete(onClientThread(mc, op, rest));
			} catch (final Throwable t) {
				f.complete("ERR " + t);
			}
		});
		return f.get(10, TimeUnit.SECONDS);
	}

	private static String onClientThread(final Minecraft mc, final String op, final String rest) {
		final Screen screen = mc.gui.screen();
		final String[] p = rest.isEmpty() ? new String[0] : rest.split("\\s+");
		switch (op) {
			case "screen" -> {
				return screen == null ? "none" : screen.getClass().getSimpleName() + " " + screen.width + "x" + screen.height + " scale=" + mc.getWindow().getGuiScale();
			}
			case "widgets" -> {
				if (screen == null) {
					return "none";
				}
				final List<String> out = new ArrayList<>();
				for (final GuiEventListener child : screen.children()) {
					if (child instanceof AbstractWidget w) {
						out.add(w.getClass().getSimpleName() + "[" + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight() + "] '" + w.getMessage().getString() + "'");
					}
				}
				return String.join(" | ", out);
			}
			case "press" -> {
				if (screen == null) {
					return "ERR no screen";
				}
				for (final GuiEventListener child : screen.children()) {
					if (child instanceof AbstractWidget w && w.getMessage().getString().equalsIgnoreCase(rest)) {
						click(screen, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, 0);
						return "OK pressed '" + rest + "'";
					}
				}
				return "ERR no widget '" + rest + "'";
			}
			case "click" -> {
				if (screen == null) {
					return "ERR no screen";
				}
				// click x y [button] [mods]: mods is a GLFW modifier mask (1 = Shift), which is how a container screen
				// tells a quick-move from a pickup — it reads the event, not the keyboard, so a shift-click is reachable
				click(screen, Double.parseDouble(p[0]), Double.parseDouble(p[1]), p.length > 2 ? Integer.parseInt(p[2]) : 0,
					p.length > 3 ? Integer.parseInt(p[3]) : 0);
				return "OK";
			}
			case "mousedown", "mouseup" -> {
				// mousedown x y [button] / mouseup x y [button]: a drag is mousedown, `mouse` moves with waits between
				// (the pointer flushes once per client tick), then mouseup — exactly what a real drag produces.
				if (screen == null) {
					return "ERR no screen";
				}
				final double x = Double.parseDouble(p[0]);
				final double y = Double.parseDouble(p[1]);
				final MouseButtonEvent ev = new MouseButtonEvent(x, y, new MouseButtonInfo(p.length > 2 ? Integer.parseInt(p[2]) : 0, 0));
				if (op.equals("mousedown")) {
					screen.mouseMoved(x, y);
					screen.mouseClicked(ev, false);
				} else {
					screen.mouseReleased(ev);
				}
				return "OK";
			}
			case "mouse" -> {
				if (screen == null) {
					return "ERR no screen";
				}
				final double mx = Double.parseDouble(p[0]);
				final double my = Double.parseDouble(p[1]);
				screen.mouseMoved(mx, my);
				// and the pointer render() is handed with it: `hoveredSlot`, every widget's hover state and every
				// tooltip come from MouseHandler's position, not from mouseMoved, so without this a hover is
				// invisible in a screenshot. glfwSetCursorPos is not the way — it needs input focus and does
				// nothing at all on GLFW's Wayland backend, both true of this dev client — so set the field.
				setMouse(mc, mx, my);
				return "OK at " + Math.round(mc.mouseHandler.getScaledXPos(mc.getWindow())) + "," + Math.round(mc.mouseHandler.getScaledYPos(mc.getWindow()));
			}
			case "scroll" -> {
				if (screen == null) {
					return "ERR no screen";
				}
				screen.mouseScrolled(Double.parseDouble(p[0]), Double.parseDouble(p[1]), 0, Double.parseDouble(p[2]));
				return "OK";
			}
			case "key", "keydown", "keyup" -> {
				if (screen == null) {
					return "ERR no screen";
				}
				final KeyEvent ev = new KeyEvent(Integer.parseInt(p[0]), 0, p.length > 1 ? Integer.parseInt(p[1]) : 0);
				if (!op.equals("keyup")) {
					screen.keyPressed(ev);
				}
				if (!op.equals("keydown")) {
					screen.keyReleased(ev);
				}
				return "OK";
			}
			case "type" -> {
				if (screen == null) {
					return "ERR no screen";
				}
				rest.codePoints().forEach(cp -> screen.charTyped(new CharacterEvent(cp)));
				return "OK";
			}
			case "keys" -> {
				// Types text as real key presses (US layout, Shift where needed) so it works when the screen takes
				// scancodes, where `type`'s charTyped is deliberately ignored. Unmappable characters are skipped.
				if (screen == null) {
					return "ERR no screen";
				}
				int skipped = 0;
				for (final int cp : rest.codePoints().toArray()) {
					final int[] ks = usKey(cp);
					if (ks == null) {
						skipped++;
						continue;
					}
					if (ks[1] != 0) {
						screen.keyPressed(new KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0));
					}
					screen.keyPressed(new KeyEvent(ks[0], 0, ks[1]));
					screen.keyReleased(new KeyEvent(ks[0], 0, ks[1]));
					if (ks[1] != 0) {
						screen.keyReleased(new KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0));
					}
				}
				return skipped == 0 ? "OK" : "OK (" + skipped + " unmappable)";
			}
			case "close" -> {
				if (screen != null) {
					screen.onClose();
				}
				return "OK";
			}
			case "sneak" -> {
				// Holds the real sneak key, so the server-side player sneaks too (sneak + use on a monitor opens the view).
				mc.options.keyShift.setDown(p.length > 0 && (p[0].equals("on") || p[0].equals("1") || p[0].equals("true")));
				return "OK";
			}
			case "look" -> {
				if (mc.player == null) {
					return "ERR no player";
				}
				mc.player.absSnapRotationTo(Float.parseFloat(p[0]), Float.parseFloat(p[1]));
				return "OK";
			}
			case "pos" -> {
				if (mc.player == null) {
					return "ERR no player";
				}
				String target = String.valueOf(mc.hitResult);
				if (mc.hitResult instanceof BlockHitResult bhr && mc.level != null) {
					target = bhr.getBlockPos().toShortString() + " " + mc.level.getBlockState(bhr.getBlockPos()) + " face=" + bhr.getDirection();
				}
				return mc.player.position() + " yaw=" + mc.player.getYRot() + " pitch=" + mc.player.getXRot() + " looking=" + target;
			}
			case "use" -> {
				if (mc.player == null || mc.gameMode == null) {
					return "ERR no player";
				}
				if (!(mc.hitResult instanceof BlockHitResult bhr)) {
					return "ERR not looking at a block: " + mc.hitResult;
				}
				return "OK " + mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, bhr);
			}
			case "cmd" -> {
				if (mc.player == null) {
					return "ERR no player";
				}
				mc.player.connection.sendCommand(rest);
				return "OK";
			}
			case "chat" -> {
				if (mc.player == null) {
					return "ERR no player";
				}
				mc.player.connection.sendChat(rest);
				return "OK";
			}
			case "be" -> {
				if (mc.level == null) {
					return "ERR no level";
				}
				final BlockEntity be = mc.level.getBlockEntity(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
				if (be instanceof ComputerBlockEntity c) {
					return "Computer vm=" + c.getVmId() + " status=" + c.getStatus() + " msg='" + c.getStatusMessage() + "' cfg=" + c.getConfig().name + "/" + c.getConfig().memMb + "MB/" + c.getConfig().cpus + "cpu iso=" + c.getConfig().iso;
				}
				return be == null ? "none" : be.getClass().getSimpleName();
			}
			case "updates" -> {
				// updates <vm>: rectangles received so far for that screen (the idle desktop should add none)
				return Long.toString(ScreenTextures.updates(UUID.fromString(p[0])));
			}
			case "latency" -> {
				// latency <vm>: types a character into the open view and reports, on the next "latency?", the time until
				// the first screen rectangle came back — the input → machine → flush → client round trip
				if (screen == null) {
					return "ERR no screen";
				}
				final UUID vm = UUID.fromString(p[0]);
				latencyResult = -1;
				final long start = System.nanoTime();
				ScreenTextures.updateListener = u -> {
					if (u.equals(vm) && latencyResult < 0) {
						latencyResult = (System.nanoTime() - start) / 1_000_000.0;
						ScreenTextures.updateListener = null;
					}
				};
				screen.charTyped(new CharacterEvent('.'));
				return "OK";
			}
			case "latency?" -> {
				return latencyResult < 0 ? "pending" : String.format(java.util.Locale.ROOT, "%.1f ms", latencyResult);
			}
			// §9 U4.0: drive the pointer seam with no headset. `vraim` points the fake source from the player's
			// eye (or an explicit origin) at a world point; the rest is buttons, wheel and what it hit.
			case "vraim" -> {
				final dev.virtualminecraft.client.pointer.FakePointerSource fake = dev.virtualminecraft.client.pointer.FakePointerSource.INSTANCE;
				if (p.length == 1 && p[0].equals("off")) {
					fake.stop();
					dev.virtualminecraft.client.pointer.Pointers.unregister(fake);
					return "OK not aiming";
				}
				if (mc.player == null) {
					return "ERR no player";
				}
				final net.minecraft.world.phys.Vec3 from = p.length >= 6
					? new net.minecraft.world.phys.Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]))
					: mc.player.getEyePosition(1.0f);
				final int i = p.length >= 6 ? 3 : 0;
				if (p.length < i + 3) {
					return "ERR usage: vraim <x> <y> <z> | vraim <ox> <oy> <oz> <x> <y> <z> | vraim off";
				}
				fake.aimAt(from, new net.minecraft.world.phys.Vec3(Double.parseDouble(p[i]), Double.parseDouble(p[i + 1]), Double.parseDouble(p[i + 2])));
				dev.virtualminecraft.client.pointer.Pointers.register(fake);
				return "OK aiming from " + from;
			}
			case "vrbutton" -> {
				if (p.length < 2) {
					return "ERR usage: vrbutton left|middle|right down|up";
				}
				final int bit = switch (p[0]) {
					case "left" -> 1;
					case "middle" -> 2;
					case "right" -> 4;
					default -> 0;
				};
				if (bit == 0) {
					return "ERR button must be left, middle or right";
				}
				dev.virtualminecraft.client.pointer.FakePointerSource.INSTANCE.setButton(bit, p[1].equals("down"));
				return "OK";
			}
			case "vrwheel" -> {
				dev.virtualminecraft.client.pointer.FakePointerSource.INSTANCE.addWheel(p.length > 0 ? Integer.parseInt(p[0]) : 1);
				return "OK";
			}
			// §9 U4.3: the in-world keyboard, driven with no headset. `vrkbd on` starts typing at whatever the
			// pointer is on -- after which the puppet's own `keys` command reaches the machine, because it presses
			// real GLFW keys through the same KeyboardHandler that Vivecraft's floating keyboard synthesises into.
			case "vrkbd" -> {
				final String want = p.length > 0 ? p[0] : "?";
				if (want.equals("off")) {
					dev.virtualminecraft.client.input.WorldKeyboard.close();
					return "OK closed";
				}
				if (want.equals("on")) {
					final java.util.UUID on = dev.virtualminecraft.client.pointer.WorldPointer.screen();
					if (on == null) {
						return "ERR not pointing at a screen (aim first)";
					}
					dev.virtualminecraft.client.input.WorldKeyboard.open(on);
					return "OK open on " + on;
				}
				final java.util.UUID k = dev.virtualminecraft.client.input.WorldKeyboard.screen();
				return k == null ? "closed" : "open on " + k;
			}
			// The same door Vivecraft's floating keyboard comes through: its InputSimulator synthesises GLFW events
			// into KeyboardHandler's private keyPress/charTyped, so these must too, or the test would prove the
			// plumbing while missing the mixin that is the actual new thing. Reflection because those methods are
			// private in 26.2 and this is dev-only harness code; Vivecraft widens them instead.
			case "wtype" -> {
				try {
					final java.lang.reflect.Method m = net.minecraft.client.KeyboardHandler.class
						.getDeclaredMethod("charTyped", long.class, net.minecraft.client.input.CharacterEvent.class);
					m.setAccessible(true);
					final long win = mc.getWindow().handle();
					for (final int cp : rest.codePoints().toArray()) {
						m.invoke(mc.keyboardHandler, win, new net.minecraft.client.input.CharacterEvent(cp));
					}
					return "OK";
				} catch (final ReflectiveOperationException e) {
					return "ERR " + e;
				}
			}
			case "wkey" -> {
				if (p.length < 1) {
					return "ERR usage: wkey <glfw> [mods]";
				}
				try {
					final java.lang.reflect.Method m = net.minecraft.client.KeyboardHandler.class
						.getDeclaredMethod("keyPress", long.class, int.class, net.minecraft.client.input.KeyEvent.class);
					m.setAccessible(true);
					final long win = mc.getWindow().handle();
					final int key = Integer.parseInt(p[0]);
					final int mods = p.length > 1 ? Integer.parseInt(p[1]) : 0;
					m.invoke(mc.keyboardHandler, win, org.lwjgl.glfw.GLFW.GLFW_PRESS, new KeyEvent(key, 0, mods));
					m.invoke(mc.keyboardHandler, win, org.lwjgl.glfw.GLFW.GLFW_RELEASE, new KeyEvent(key, 0, mods));
					return "OK";
				} catch (final ReflectiveOperationException e) {
					return "ERR " + e;
				}
			}
			case "vrpointer" -> {
				final dev.virtualminecraft.client.pointer.PointerSource active = dev.virtualminecraft.client.pointer.Pointers.active();
				final java.util.UUID on = dev.virtualminecraft.client.pointer.WorldPointer.screen();
				return "source=" + (active == null ? "none" : active.name())
					+ " screen=" + (on == null ? "none" : on)
					+ " at=" + dev.virtualminecraft.client.pointer.WorldPointer.x() + "," + dev.virtualminecraft.client.pointer.WorldPointer.y()
					+ " buttons=" + dev.virtualminecraft.client.pointer.WorldPointer.buttons()
					+ " sources=" + dev.virtualminecraft.client.pointer.Pointers.sources().stream().map(dev.virtualminecraft.client.pointer.PointerSource::name).toList();
			}
			case "tex" -> {
				final ScreenTexture t = ScreenTextures.get(UUID.fromString(p[0]));
				return t == null ? "none (running=" + ScreenTextures.isRunning(UUID.fromString(p[0])) + ")" : t.width + "x" + t.height + " lod=" + ScreenTextures.lod(UUID.fromString(p[0])) + " " + t.id;
			}
			case "cursor" -> {
				final ScreenTextures.Cursor c = ScreenTextures.cursor(UUID.fromString(p[0]));
				return c == null ? "none" : c.x() + "," + c.y() + " hot=" + c.hotX() + "," + c.hotY() + " " + c.w() + "x" + c.h();
			}
			case "world" -> {
				mc.createWorldOpenFlows().openWorld(rest, () -> {
				});
				return "OK";
			}
			case "leave" -> {
				mc.disconnectWithProgressScreen();
				return "OK";
			}
			case "audio" -> {
				return dev.virtualminecraft.client.audio.VmAudio.debugState();
			}
			case "fps" -> {
				return mc.getFps() + " fps";
			}
			default -> {
				return "ERR unknown op " + op;
			}
		}
	}

	/** {GLFW key, modifier bits} for a character on a US keyboard, or null. */
	private static int @org.jspecify.annotations.Nullable [] usKey(final int cp) {
		final int shift = org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
		if (cp >= 'a' && cp <= 'z') {
			return new int[] { org.lwjgl.glfw.GLFW.GLFW_KEY_A + (cp - 'a'), 0 };
		}
		if (cp >= 'A' && cp <= 'Z') {
			return new int[] { org.lwjgl.glfw.GLFW.GLFW_KEY_A + (cp - 'A'), shift };
		}
		if (cp >= '0' && cp <= '9') {
			return new int[] { org.lwjgl.glfw.GLFW.GLFW_KEY_0 + (cp - '0'), 0 };
		}
		final String plain = " -=[]\\;'`,./\t\n";
		final int[] plainKeys = { org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE, org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS, org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET,
			org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET, org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH, org.lwjgl.glfw.GLFW.GLFW_KEY_SEMICOLON, org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE,
			org.lwjgl.glfw.GLFW.GLFW_KEY_GRAVE_ACCENT, org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA, org.lwjgl.glfw.GLFW.GLFW_KEY_PERIOD, org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH,
			org.lwjgl.glfw.GLFW.GLFW_KEY_TAB, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER };
		final String shifted = "!@#$%^&*()_+{}|:\"~<>?";
		final int[] shiftedKeys = { org.lwjgl.glfw.GLFW.GLFW_KEY_1, org.lwjgl.glfw.GLFW.GLFW_KEY_2, org.lwjgl.glfw.GLFW.GLFW_KEY_3, org.lwjgl.glfw.GLFW.GLFW_KEY_4, org.lwjgl.glfw.GLFW.GLFW_KEY_5,
			org.lwjgl.glfw.GLFW.GLFW_KEY_6, org.lwjgl.glfw.GLFW.GLFW_KEY_7, org.lwjgl.glfw.GLFW.GLFW_KEY_8, org.lwjgl.glfw.GLFW.GLFW_KEY_9, org.lwjgl.glfw.GLFW.GLFW_KEY_0,
			org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS, org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET,
			org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH, org.lwjgl.glfw.GLFW.GLFW_KEY_SEMICOLON, org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE, org.lwjgl.glfw.GLFW.GLFW_KEY_GRAVE_ACCENT,
			org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA, org.lwjgl.glfw.GLFW.GLFW_KEY_PERIOD, org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH };
		int i = plain.indexOf(cp);
		if (i >= 0) {
			return new int[] { plainKeys[i], 0 };
		}
		i = shifted.indexOf(cp);
		if (i >= 0) {
			return new int[] { shiftedKeys[i], shift };
		}
		return null;
	}

	/** Put MouseHandler where a real pointer would be, in GUI units: dev-only, and the only way that works here. */
	private static void setMouse(final Minecraft mc, final double guiX, final double guiY) {
		final com.mojang.blaze3d.platform.Window w = mc.getWindow();
		final double x = guiX * w.getScreenWidth() / w.getGuiScaledWidth();
		final double y = guiY * w.getScreenHeight() / w.getGuiScaledHeight();
		try {
			for (final String[] f : new String[][] { { "xpos", "" + x }, { "ypos", "" + y } }) {
				final java.lang.reflect.Field field = net.minecraft.client.MouseHandler.class.getDeclaredField(f[0]);
				field.setAccessible(true);
				field.setDouble(mc.mouseHandler, Double.parseDouble(f[1]));
			}
		} catch (final ReflectiveOperationException e) {
			throw new IllegalStateException("puppet: MouseHandler position is not reachable: " + e, e);
		}
	}

	private static void click(final Screen screen, final double x, final double y, final int button) {
		click(screen, x, y, button, 0);
	}

	private static void click(final Screen screen, final double x, final double y, final int button, final int mods) {
		screen.mouseMoved(x, y);
		final MouseButtonEvent ev = new MouseButtonEvent(x, y, new MouseButtonInfo(button, mods));
		screen.mouseClicked(ev, false);
		screen.mouseReleased(ev);
	}
}
