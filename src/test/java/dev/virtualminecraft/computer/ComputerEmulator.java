package dev.virtualminecraft.computer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The Computer outside Minecraft, interactively ([name], session 10: "would it be easier to test computer functions if
 * you made an emulator? that way we only need minecraft for testing the communication between the computer and
 * minecraft"). The real ROM on the real {@link LuaMachine}/{@link ScreenDevice}/{@link MachineFiles}, the harness's
 * pretend bus ({@link RomBootTest.Host}: a redstone/world/chat component at self and two chests), a Swing window for
 * the framebuffer and the hardware cursor, keys as scancodes + chars, the mouse as pointer/wheel, Ctrl+V as paste.
 * {@code /disk} and the desktop's saved state live under {@code run/emulator/} and survive restarts.
 * <p>
 * {@code ./gradlew computerEmulator --args="--size 256x256 --scale 3 --dir run/emulator --shot out.png --after 3000"}
 */
public final class ComputerEmulator {
	private static final Map<Integer, Integer> SCANCODES = new HashMap<>();

	static {
		final int[][] keys = {
			{ KeyEvent.VK_ESCAPE, 0x01 }, { KeyEvent.VK_1, 0x02 }, { KeyEvent.VK_2, 0x03 }, { KeyEvent.VK_3, 0x04 }, { KeyEvent.VK_4, 0x05 },
			{ KeyEvent.VK_5, 0x06 }, { KeyEvent.VK_6, 0x07 }, { KeyEvent.VK_7, 0x08 }, { KeyEvent.VK_8, 0x09 }, { KeyEvent.VK_9, 0x0A },
			{ KeyEvent.VK_0, 0x0B }, { KeyEvent.VK_MINUS, 0x0C }, { KeyEvent.VK_EQUALS, 0x0D }, { KeyEvent.VK_BACK_SPACE, 0x0E },
			{ KeyEvent.VK_TAB, 0x0F }, { KeyEvent.VK_Q, 0x10 }, { KeyEvent.VK_W, 0x11 }, { KeyEvent.VK_E, 0x12 }, { KeyEvent.VK_R, 0x13 },
			{ KeyEvent.VK_T, 0x14 }, { KeyEvent.VK_Y, 0x15 }, { KeyEvent.VK_U, 0x16 }, { KeyEvent.VK_I, 0x17 }, { KeyEvent.VK_O, 0x18 },
			{ KeyEvent.VK_P, 0x19 }, { KeyEvent.VK_OPEN_BRACKET, 0x1A }, { KeyEvent.VK_CLOSE_BRACKET, 0x1B }, { KeyEvent.VK_ENTER, 0x1C },
			{ KeyEvent.VK_CONTROL, 0x1D }, { KeyEvent.VK_A, 0x1E }, { KeyEvent.VK_S, 0x1F }, { KeyEvent.VK_D, 0x20 }, { KeyEvent.VK_F, 0x21 },
			{ KeyEvent.VK_G, 0x22 }, { KeyEvent.VK_H, 0x23 }, { KeyEvent.VK_J, 0x24 }, { KeyEvent.VK_K, 0x25 }, { KeyEvent.VK_L, 0x26 },
			{ KeyEvent.VK_SEMICOLON, 0x27 }, { KeyEvent.VK_QUOTE, 0x28 }, { KeyEvent.VK_BACK_QUOTE, 0x29 }, { KeyEvent.VK_SHIFT, 0x2A },
			{ KeyEvent.VK_BACK_SLASH, 0x2B }, { KeyEvent.VK_Z, 0x2C }, { KeyEvent.VK_X, 0x2D }, { KeyEvent.VK_C, 0x2E }, { KeyEvent.VK_V, 0x2F },
			{ KeyEvent.VK_B, 0x30 }, { KeyEvent.VK_N, 0x31 }, { KeyEvent.VK_M, 0x32 }, { KeyEvent.VK_COMMA, 0x33 }, { KeyEvent.VK_PERIOD, 0x34 },
			{ KeyEvent.VK_SLASH, 0x35 }, { KeyEvent.VK_ALT, 0x38 }, { KeyEvent.VK_SPACE, 0x39 }, { KeyEvent.VK_CAPS_LOCK, 0x3A },
			{ KeyEvent.VK_F1, 0x3B }, { KeyEvent.VK_F2, 0x3C }, { KeyEvent.VK_F3, 0x3D }, { KeyEvent.VK_F4, 0x3E }, { KeyEvent.VK_F5, 0x3F },
			{ KeyEvent.VK_F6, 0x40 }, { KeyEvent.VK_F7, 0x41 }, { KeyEvent.VK_F8, 0x42 }, { KeyEvent.VK_F9, 0x43 }, { KeyEvent.VK_F10, 0x44 },
			{ KeyEvent.VK_F11, 0x57 }, { KeyEvent.VK_F12, 0x58 },
			// extended keys: bit 7, as the client sends them (win.KEY in the ROM)
			{ KeyEvent.VK_HOME, 0xC7 }, { KeyEvent.VK_UP, 0xC8 }, { KeyEvent.VK_PAGE_UP, 0xC9 }, { KeyEvent.VK_LEFT, 0xCB },
			{ KeyEvent.VK_RIGHT, 0xCD }, { KeyEvent.VK_END, 0xCF }, { KeyEvent.VK_DOWN, 0xD0 }, { KeyEvent.VK_PAGE_DOWN, 0xD1 },
			{ KeyEvent.VK_DELETE, 0xD3 },
		};
		for (final int[] k : keys) {
			SCANCODES.put(k[0], k[1]);
		}
	}

	/** The harness host with the state persisted to a file and the emulator's frame rate. */
	static final class EmulatorHost extends RomBootTest.Host {
		final Path stateFile;

		EmulatorHost(final Path dir) throws java.io.IOException {
			super(dir);
			stateFile = dir.resolve("state.json");
			frameMs = 16;
			if (Files.isRegularFile(stateFile)) {
				saved = Files.readString(stateFile, StandardCharsets.UTF_8);
			}
		}

		@Override
		public String call(final int fn, final String payload) throws LuaMachine.MachineError {
			final String r = super.call(fn, payload);
			if (fn == 2 && !payload.isEmpty()) {
				try {
					Files.writeString(stateFile, payload, StandardCharsets.UTF_8);
				} catch (final java.io.IOException e) {
					System.err.println("state not saved: " + e);
				}
			}
			return r;
		}

		@Override
		public void log(final int level, final String message) {
			log.add(level + ":" + message);
			if (log.size() > 500) {
				log.remove(0);
			}
			System.out.println((level == 3 ? "!! " : level == 2 ? " ! " : "   ") + message);
		}
	}

	private final EmulatorHost host;
	private final LuaMachine machine;
	private final Object lock = new Object();
	private volatile boolean stop;
	private volatile String status = "booting";
	private final int scale;
	private volatile BufferedImage image;
	private int buttons;
	/** --park N: seconds with nothing drawn and the window not focused before the framebuffer is parked (0 = never). */
	private int parkSeconds;
	/**
	 * Somebody is watching the screen — a focused window, or what a script's {@code viewers N} step last said. It is
	 * pushed to the machine as the {@code viewers} event, exactly as a player walking up to a monitor is in-game, so
	 * the desktop stops repainting its clock and the picture can go still enough to park.
	 */
	private volatile boolean watched = true;
	private long lastDrawSeq = -1;
	private long idleSince = System.currentTimeMillis();

	/** {@code --loopback}: pretend net.send delivers back to this machine, so one machine can serve itself. */
	private static boolean loopback;

	private ComputerEmulator(final EmulatorHost host, final LuaMachine machine, final int scale) {
		this.host = host;
		this.machine = machine;
		this.scale = scale;
	}

	private void push(final String json) {
		machine.pushEvent(json);
		synchronized (lock) {
			lock.notifyAll();
		}
	}

	/** The machine's thread: run slices, sleep on timed waits, wake on events. */
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

	/**
	 * The same memory budget the scheduler enforces in-game (exact allocation per slice, a reachability walk once a
	 * budget's worth has been allocated, "not enough memory" when the live estimate is over the cap) — without it a
	 * game that hoards could pass here and die on a real Computer.
	 */
	private void meter() {
		final long tid = Thread.currentThread().threadId();
		final long alloc = THREADS.getThreadAllocatedBytes(tid) - sliceStartAlloc;
		sliceStartAlloc = THREADS.getThreadAllocatedBytes(tid);
		machine.memory().addAllocated(alloc);
		final long cap = host.memoryCapBytes();
		if (!machine.isFinished() && machine.memory().walkDue(cap)) {
			final long est = machine.memory().walk(machine.state(), machine.mainThread());
			if (est > cap) {
				System.err.println("not enough memory: live ~" + (est >> 10) + " KB of " + (cap >> 10) + " KB");
				machine.raise("not enough memory");
			}
		}
	}

	private long sliceStartAlloc;

	private void runLoop() {
		sliceStartAlloc = THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
		while (!stop) {
			final LuaMachine.Result r = machine.run();
			meter();
			switch (r) {
				case WAIT -> {
					status = "waiting";
					final long ms = machine.waitMillis();
					synchronized (lock) {
						if (machine.pendingEvents() == 0 && !stop) {
							try {
								lock.wait(ms > 0 ? ms : 0);
							} catch (final InterruptedException e) {
								return;
							}
						}
					}
				}
				case VALUE -> {
					status = "running";
					// The frame boundary. Snapshot the picture HERE, on the machine thread with the program paused
					// between frames, and the window only ever draws that snapshot. Until 2026-08-28 the Swing
					// timer read the framebuffer whenever it fired -- halfway through a raycaster's frame as
					// often as not -- and the Maze flickered in a way the game's flip-time flush never would.
					if ("flip".equals(machine.yieldValue())) {
						render(true);
					}
				}
				case SLICE -> status = "running";
				case FINISHED -> {
					status = "kernel exited";
					return;
				}
				case ERROR -> {
					status = "error: " + machine.error();
					System.err.println(status);
					return;
				}
			}
		}
	}

	private void render(final boolean force) {
		final ScreenDevice s = host.screen;
		final int w = s.width();
		final int h = s.height();
		if (w == 0 || h == 0) {
			return;
		}
		// A parked picture inflates on the first read, so a repaint every 50 ms would undo the park at once. With
		// nobody watching, keep the last image on the panel instead — the same nothing a monitor with no player in
		// front of it streams. A focused window is a viewer, and its read below is the "walk up to it" path.
		final boolean wasParked = s.parked();
		if (wasParked && !force && image != null) {
			return;
		}
		if (image == null || image.getWidth() != w || image.getHeight() != h) {
			image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		}
		final byte[] idx = s.read(0, 0, w, h);
		final int[] pal = s.paletteCopy();
		final int[] rgb = new int[w * h];
		for (int i = 0; i < rgb.length && i < idx.length; i++) {
			rgb[i] = pal[idx[i] & 0xFF];
		}
		final int[] cur = s.cursorState();
		if (cur[2] == 1 && cur[3] > 0) {
			final byte[] sprite = s.cursorSprite();
			final int key = s.cursorKey();
			final int[] hot = s.cursorHotspot();
			for (int y = 0; y < cur[4]; y++) {
				for (int x = 0; x < cur[3]; x++) {
					final int v = sprite[y * cur[3] + x] & 0xFF;
					final int px = cur[0] - hot[0] + x;
					final int py = cur[1] - hot[1] + y;
					if (v != key && px >= 0 && py >= 0 && px < w && py < h) {
						rgb[py * w + px] = pal[v];
					}
				}
			}
		}
		image.setRGB(0, 0, w, h, rgb, 0, w);
		if (wasParked) {
			System.out.println("screen unparked: the first read inflated the picture back into place");
		}
	}

	/** Tells the machine somebody started or stopped watching, the way {@code LuaComputer.tellViewers} does. */
	private void setWatched(final boolean now) {
		if (watched == now) {
			return;
		}
		watched = now;
		push("{\"name\":\"viewers\",\"n\":" + (now ? 1 : 0) + "}");
	}

	/**
	 * The idle framebuffer park ({@link ScreenDevice#park()}), which in-game is {@code LuaComputer.parkScreenIfIdle}:
	 * nothing drawn and nobody watching for {@code --park N} seconds → the picture is deflated and the buffer given
	 * back to the heap; the next draw or read brings it back. Ticked from its own thread so it works in a scripted
	 * run too, where there is no window to repaint.
	 */
	private void tickPark() {
		if (parkSeconds <= 0) {
			return;
		}
		final ScreenDevice s = host.screen;
		final long seq = s.drawSeq();
		if (seq != lastDrawSeq || watched) {
			lastDrawSeq = seq;
			idleSince = System.currentTimeMillis();
			return;
		}
		if (s.parked() || System.currentTimeMillis() - idleSince < parkSeconds * 1000L) {
			return;
		}
		final int live = s.width() * s.height();
		if (s.park()) {
			System.out.println("screen parked: " + (live >> 10) + " KB of framebuffer → " + (s.parkedBytes() >> 10) + " KB deflated");
		}
	}

	// The screen scales to the window ([name], 2026-08-28): --scale only sizes the window at the start. Each paint
	// fits the framebuffer into the panel keeping its aspect, centred, and the mouse maps back through the same
	// numbers -- so a maximised window is a big screen, not a small screen in a corner of a big window.
	private volatile double fitScale = 1;
	private volatile int fitX;
	private volatile int fitY;

	private int fbX(final int x) {
		return Math.clamp((int) Math.floor((x - fitX) / fitScale), 0, Math.max(0, host.screen.width() - 1));
	}

	private int fbY(final int y) {
		return Math.clamp((int) Math.floor((y - fitY) / fitScale), 0, Math.max(0, host.screen.height() - 1));
	}

	private void pointer(final MouseEvent e) {
		push("{\"name\":\"pointer\",\"x\":" + fbX(e.getX()) + ",\"y\":" + fbY(e.getY()) + ",\"buttons\":" + buttons + ",\"player\":\"you\"}");
	}

	// --pos X,Y: open the window at a fixed desktop coordinate instead of wherever the WM would put it. On a
	// multi-head desk that is how you keep the harness off the monitor you are actually using; the coordinates are
	// the compositor's whole logical layout, so DP-2 at 0,0 is "--pos 100,100". Unset = the old platform default.
	private static int posX = Integer.MIN_VALUE;
	private static int posY = Integer.MIN_VALUE;

	/**
	 * AWT to the RFB button mask the game sends, which is what the ROM reads: 1 left, 2 middle, 4 right. AWT's
	 * BUTTON2 is the middle button and BUTTON3 the right one, and this had them the other way round — invisible
	 * until U6 gave the right button a meaning (the desktop's context menus).
	 */
	private static int buttonBit(final MouseEvent e) {
		return switch (e.getButton()) {
			case MouseEvent.BUTTON1 -> 1;
			case MouseEvent.BUTTON2 -> 2;
			case MouseEvent.BUTTON3 -> 4;
			default -> 0;
		};
	}

	private void show(final int w, final int h, final String title) {
		final JFrame frame = new JFrame(title);
		final JPanel panel = new JPanel() {
			@Override
			protected void paintComponent(final Graphics g) {
				super.paintComponent(g);
				// The picture is read at each flip (runLoop); the panel just shows the last one. The two reads
				// left here are the first paint before any frame, and the "walk up to a parked screen" read,
				// which inflates the park exactly as a player arriving at a monitor does.
				if (image == null || host.screen.parked()) {
					render(watched);
				}
				if (image != null) {
					final Graphics2D g2 = (Graphics2D) g;
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
					final double fit = Math.max(0.05, Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight()));
					final int dw = (int) Math.round(image.getWidth() * fit);
					final int dh = (int) Math.round(image.getHeight() * fit);
					fitScale = fit;
					fitX = (getWidth() - dw) / 2;
					fitY = (getHeight() - dh) / 2;
					g2.drawImage(image, fitX, fitY, dw, dh, null);
				}
			}
		};
		panel.setBackground(Color.BLACK);
		panel.setPreferredSize(new Dimension(w * scale, h * scale));
		panel.setFocusable(true);
		panel.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(final KeyEvent e) {
				if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_V) {
					try {
						final String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
						push("{\"name\":\"paste\",\"text\":" + json(text) + "}");
					} catch (final Exception ignored) {
					}
					return;
				}
				final Integer code = SCANCODES.get(e.getKeyCode());
				if (code != null) {
					push("{\"name\":\"scancode\",\"code\":" + code + ",\"down\":true,\"player\":\"you\"}");
				}
			}

			@Override
			public void keyReleased(final KeyEvent e) {
				final Integer code = SCANCODES.get(e.getKeyCode());
				if (code != null) {
					push("{\"name\":\"scancode\",\"code\":" + code + ",\"down\":false,\"player\":\"you\"}");
				}
			}

			@Override
			public void keyTyped(final KeyEvent e) {
				final char c = e.getKeyChar();
				if (c >= 32 && c != 127 && !e.isControlDown()) {
					push("{\"name\":\"char\",\"cp\":" + (int) c + "}");
				}
			}
		});
		final MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mousePressed(final MouseEvent e) {
				panel.requestFocusInWindow();
				buttons |= buttonBit(e);
				pointer(e);
			}

			@Override
			public void mouseReleased(final MouseEvent e) {
				buttons &= ~buttonBit(e);
				pointer(e);
			}

			@Override
			public void mouseMoved(final MouseEvent e) {
				pointer(e);
			}

			@Override
			public void mouseDragged(final MouseEvent e) {
				pointer(e);
			}

			@Override
			public void mouseWheelMoved(final MouseWheelEvent e) {
				push("{\"name\":\"wheel\",\"dx\":0,\"dy\":" + e.getWheelRotation() + ",\"x\":" + fbX(e.getX()) + ",\"y\":" + fbY(e.getY()) + "}");
			}
		};
		panel.addMouseListener(mouse);
		panel.addMouseMotionListener(mouse);
		panel.addMouseWheelListener(mouse);
		frame.add(panel);
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.addWindowFocusListener(new WindowAdapter() {
			@Override
			public void windowGainedFocus(final WindowEvent e) {
				setWatched(true); // a viewer walked up: the next repaint inflates a parked picture
			}

			@Override
			public void windowLostFocus(final WindowEvent e) {
				setWatched(false);
			}
		});
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				shutdown();
				frame.dispose();
				System.exit(0);
			}
		});
		frame.pack();
		if (posX != Integer.MIN_VALUE) {
			frame.setLocation(posX, posY);
		} else {
			frame.setLocationByPlatform(true);
		}
		frame.setVisible(true);
		panel.requestFocusInWindow();
		new Timer(50, ev -> {
			frame.setTitle(title + " — " + status + (host.screen.parked() ? " (screen parked)" : ""));
			panel.repaint();
		}).start();
	}

	/** Ask the kernel to save (the desktop comes back next time), wait briefly for "saved", stop the machine thread. */
	private void shutdown() {
		push("{\"name\":\"save\"}");
		final long deadline = System.currentTimeMillis() + 1000;
		while (System.currentTimeMillis() < deadline && !"saved".equals(machine.yieldValue()) && !stop) {
			try {
				Thread.sleep(20);
			} catch (final InterruptedException e) {
				break;
			}
		}
		stop = true;
		synchronized (lock) {
			lock.notifyAll();
		}
	}

	/** A scripted session: events in, screenshots out — the puppet's job, without Minecraft. */
	private void runScript(final Path script) throws Exception {
		for (final String raw : Files.readAllLines(script, StandardCharsets.UTF_8)) {
			final String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			final String[] p = line.split("\\s+", 2);
			final String arg = p.length > 1 ? p[1] : "";
			switch (p[0]) {
				case "wait" -> Thread.sleep(Long.parseLong(arg));
				case "viewers" -> setWatched(Integer.parseInt(arg.strip()) > 0);
				case "park" -> {
					// Park it now, whatever --park says: the next shot or draw is then the inflate path.
					final ScreenDevice s = host.screen;
					final int live = s.width() * s.height();
					System.out.println(s.park() ? "screen parked: " + (live >> 10) + " KB of framebuffer → " + (s.parkedBytes() >> 10) + " KB deflated"
						: "screen was already parked or has no framebuffer");
				}
				case "shell" -> push("{\"name\":\"shell\",\"line\":" + json(arg) + "}");
				case "exec" -> push("{\"name\":\"exec\",\"code\":" + json(arg) + "}");
				case "type" -> {
					for (final char c : arg.toCharArray()) {
						push("{\"name\":\"char\",\"cp\":" + (int) c + "}");
					}
				}
				case "key" -> {
					final String[] k = arg.split(",");
					final int code = k[0].startsWith("0x") ? Integer.parseInt(k[0].substring(2), 16) : Integer.parseInt(k[0]);
					if (k.length == 1 || !k[1].equals("up")) {
						push("{\"name\":\"scancode\",\"code\":" + code + ",\"down\":true,\"player\":\"you\"}");
					}
					if (k.length == 1 || k[1].equals("up")) {
						push("{\"name\":\"scancode\",\"code\":" + code + ",\"down\":false,\"player\":\"you\"}");
					}
				}
				case "move", "click", "rclick" -> {
					final String[] xy = arg.split("\\s+");
					final String at = "\"x\":" + xy[0] + ",\"y\":" + xy[1];
					final int bit = p[0].equals("click") ? 1 : p[0].equals("rclick") ? 4 : 0;
					if (bit != 0) {
						push("{\"name\":\"pointer\"," + at + ",\"buttons\":" + bit + ",\"player\":\"you\"}");
						Thread.sleep(30);
					}
					push("{\"name\":\"pointer\"," + at + ",\"buttons\":0,\"player\":\"you\"}");
				}
				case "shot" -> {
					Thread.sleep(100);
					// A screenshot is somebody looking: a parked picture is read (and inflated) as a player
					// walking up would; a live one is the last flip's snapshot, never a half-drawn frame.
					if (image == null || host.screen.parked()) {
						render(true);
					}
					if (image != null) {
						// Make the shot's folder if the script points somewhere that does not exist yet, so a script
						// can write into run/shots/ (gitignored) instead of scattering PNGs across the project root.
						final Path shotPath = Path.of(arg).toAbsolutePath();
						if (shotPath.getParent() != null) {
							java.nio.file.Files.createDirectories(shotPath.getParent());
						}
						ImageIO.write(image, "png", shotPath.toFile());
						System.out.println("wrote " + arg);
					}
				}
				default -> System.err.println("script: unknown step " + line);
			}
		}
	}

	private static String json(final String s) {
		final StringBuilder sb = new StringBuilder("\"");
		for (final char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 32) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}

	public static void main(final String[] args) throws Exception {
		int w = 256;
		int h = 256;
		int scale = 3;
		String fd = null;
		Path dir = Path.of("run", "emulator");
		String shot = null;
		long after = 0;
		Path script = null;
		Path cd = null;
		int memMb = -1; // -1: the case's base (4 MB for a bare Computer)
		int tier = 2;
		String parts = ""; // --parts ram,cpu,gfx,drive as levels 0-3, e.g. "3,0,2,0"
		int park = 0; // seconds idle + unwatched before the framebuffer is parked (0 = never, as before)

		boolean shell = false;
		boolean desktop = false;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--size" -> {
					final String[] p = args[++i].split("x");
					w = Integer.parseInt(p[0]);
					h = Integer.parseInt(p[1]);
				}
				case "--scale" -> scale = Integer.parseInt(args[++i]);
				case "--fd" -> fd = args[++i];
				case "--pos" -> {
					final String[] p = args[++i].split(",");
					posX = Integer.parseInt(p[0].trim());
					posY = Integer.parseInt(p[1].trim());
				}
				case "--dir" -> dir = Path.of(args[++i]);
				case "--shot" -> shot = args[++i];
				case "--after" -> after = Long.parseLong(args[++i]);
				case "--script" -> script = Path.of(args[++i]);
				case "--cd" -> {
					// A path, or a bare name resolved exactly the way the game resolves `cds:<name>` -- so
					// `--cd mines` exercises the real bundled-CD lookup instead of only the filesystem one.
					final String v = args[++i];
					final Path direct = Path.of(v);
					cd = java.nio.file.Files.isDirectory(direct) ? direct : dev.virtualminecraft.computer.MachineFiles.bundledCd(v);
					if (cd == null) {
						System.err.println("no such CD: " + v);
						return;
					}
				}
				case "--mem" -> memMb = Integer.parseInt(args[++i]);
				case "--tier" -> tier = Integer.parseInt(args[++i]);
				case "--parts" -> parts = args[++i];
				case "--park" -> park = Integer.parseInt(args[++i]);
				case "--shell" -> shell = true;
				case "--loopback" -> loopback = true;
				case "--desktop" -> desktop = true;
				case "--fresh" -> {
					final Path state = dir.resolve("state.json");
					Files.deleteIfExists(state);
				}
				default -> {
					System.out.println("""
						computerEmulator: the Computer's ROM in a window, outside Minecraft.
						  --size WxH     screen (256x256 = a 1x1 monitor, 512x384, 768x512, 1024x768 = a 4x3 wall)
						  --scale N      window pixels per screen pixel at the start (3); the screen then scales to the window
						  --dir PATH     where /disk, items, config (cds/, import/) and the saved desktop live (run/emulator)
						  --fresh        forget the saved desktop first
						  --cd DIR       a directory mounted read-only as /cd0 (a game CD: main.lua + program.txt)
						  --mem MB       the machine's memory budget (the RAM part's, capped by the case; 16 is the maximum)
						  --tier N       the case: 1 Basic Computer (shell only, 256x256, 3 voices), 2 Computer, 3 Advanced
						  --parts A,B,C,D  what is in the case's four slots as levels 0-3, in the GUI's order:
						                 RAM, CPU, graphics, drive. Default is 3,3,3,3 -- the best the case can hold.
						                 The case is only a ceiling: each part gives its own value, capped by the case.
						                 A slot at 0 is EMPTY, and empty means missing: "0,0,2,0" is a dead box that
						                 refuses to boot, "1,1,0,1" runs blind, "1,1,1,0" boots with no /disk.
						                 --mem still wins for memory.
						  --shell        boot into the shell whatever the tier; --desktop the opposite (the Settings toggle)
						  --park N       park the framebuffer after N seconds with nothing drawn and the window not
						                 focused (the in-game idle park, computerScreenParkSeconds; 0 = never).
						                 A focused window counts as a viewer; the script step "park" forces one.
						  --fd NAME      a floppy template (shipped or config/floppies/NAME) on a writable disk as /fd0
						  --shot F.png   write the screen to a file --after N ms, then exit (for scripts)
						  --script F     drive the machine from a file, one step per line, then exit:
						                 wait N | shell LINE | exec LUA | type TEXT | key CODE[,up] | click X Y | move X Y
						                 | viewers N | park | shot F.png
						Keys are the machine's; Ctrl+V pastes; the window's close button saves the desktop.""");
					return;
				}
			}
		}
		Files.createDirectories(dir);
		final EmulatorHost host = new EmulatorHost(dir);
		// §9 U10(a): the parts are the machine now, so the default case is a fully fitted one -- an empty case is a
		// dead box and there would be nothing to emulate. "--parts 0,0,0,0" asks for exactly that, and gets it.
		final int[] lv = { MachineSpec.LEVELS, MachineSpec.LEVELS, MachineSpec.LEVELS, MachineSpec.LEVELS };
		if (!parts.isEmpty()) {
			java.util.Arrays.fill(lv, 0);
			final String[] f = parts.split(",");
			for (int k = 0; k < Math.min(4, f.length); k++) {
				lv[k] = Math.clamp(Integer.parseInt(f[k].strip()), 0, MachineSpec.LEVELS);
			}
		}
		final MachineSpec spec = MachineSpec.of(tier, lv[0], lv[1], lv[2], lv[3], 64);
		System.out.println("computerEmulator: " + spec.describe());
		if (!spec.canBoot()) {
			System.err.println("this case has " + spec.bootRefusal() + ": it does not boot, and neither does the one in the world");
			System.exit(2);
		}
		host.memCapBytes = (long) (memMb > 0 ? memMb : spec.memMb()) << 20;
		host.setSpec(spec);
		host.setDiskQuota(spec.diskQuotaBytes());
		if (shell || desktop) {
			host.desktop = desktop && !shell;
		}
		if (spec.hasGraphics()) {
			host.screen.resize(w, h);
		} else {
			host.screen.resize(0, 0); // §9 U10(a): no card, no framebuffer -- the same state as a machine with no monitor
			System.out.println("no graphics card: the machine runs blind (no framebuffer, exactly as in the world)");
		}
		if (fd != null) {
			// --fd NAME: a floppy with a template on it, seeded onto a disk under --dir the first time (like the game's
			// first mount) and writable after -- the whole point of a floppy over a CD
			final java.nio.file.Path disk = dir.resolve("fd0-" + fd);
			if (!Files.isDirectory(disk)) {
				MachineFiles.seedFloppy(disk, fd, dir.resolve("config"));
			}
			host.files.mounts().put("fd0", new MachineFiles.Mount("fd0", disk.toAbsolutePath(), false, 1440L << 10, false, "Floppy " + fd, "fd0-" + fd));
		}
		if (cd != null) {
			host.files.mounts().put("cd0", new MachineFiles.Mount("cd0", cd.toAbsolutePath(), true, 700L << 20, false, "CD " + cd.getFileName(), null));
		}
		final String boot = new String(ComputerEmulator.class.getResourceAsStream("/virtualminecraft/rom/boot.lua").readAllBytes(), StandardCharsets.UTF_8);
		final LuaMachine m = new LuaMachine(host, boot, "boot.lua");
		final ComputerEmulator emu = new ComputerEmulator(host, m, scale);
		emu.parkSeconds = park;
		if (loopback) {
			host.netLoopback = emu::push; // net.send comes straight back as a net_message: one machine, both ends
		}
		final Thread t = new Thread(emu::runLoop, "vmc-emulator-machine");
		t.setDaemon(true);
		t.start();
		if (park > 0) {
			final Thread pt = new Thread(() -> {
				while (!emu.stop) {
					emu.tickPark();
					try {
						Thread.sleep(100);
					} catch (final InterruptedException e) {
						return;
					}
				}
			}, "vmc-emulator-park");
			pt.setDaemon(true);
			pt.start();
		}
		if (script != null) {
			Thread.sleep(Math.max(0, after));
			emu.runScript(script);
			emu.shutdown();
			return;
		}
		if (shot != null) {
			Thread.sleep(Math.max(0, after));
			if (emu.image == null || host.screen.parked()) {
				emu.render(true);
			}
			if (emu.image != null) {
				ImageIO.write(emu.image, "png", Path.of(shot).toFile());
				System.out.println("wrote " + shot + " (" + emu.status + ")");
			}
			emu.shutdown();
			return;
		}
		final int fw = w;
		final int fh = h;
		final String title = "vmcOS emulator " + fw + "x" + fh + " ×" + scale;
		SwingUtilities.invokeLater(() -> emu.show(fw, fh, title));
	}
}
