package dev.virtualminecraft.vm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.dbus.DbusMessage;
import dev.virtualminecraft.dbus.DbusPeer;
import dev.virtualminecraft.dbus.Libc;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * A live connection to QEMU's D-Bus display ({@code -display dbus,p2p=on}), which is how a guest gets real
 * keyboard <em>scancodes</em> rather than the keysyms VNC forces on it — the difference between a non-US layout
 * or a game working and not (PERFORMANCE.md T2). Later steps use the same connection for the shared-memory
 * scanout and audio.
 * <p>
 * How the peer connection comes to exist: QEMU does not listen anywhere for it. We make a unix socketpair, hand
 * one end to QEMU over its QMP socket with {@code getfd} (an {@code SCM_RIGHTS} fd) followed by
 * {@code add_client} with protocol {@code @dbus-display}, and speak D-Bus on the other end. The QMP hop is a
 * separate short-lived connection made through {@link Libc}, because Java's own sockets cannot pass fds; the
 * mod's regular {@code QmpClient} is one-connection-per-command, so the two never collide.
 */
public final class QemuDisplayLink implements AutoCloseable {
	private static final String CONSOLE = "/org/qemu/Display1/Console_0";
	private static final String KEYBOARD = "org.qemu.Display1.Keyboard";
	private static final String MOUSE = "org.qemu.Display1.Mouse";
	private static final long CALL_TIMEOUT_MS = 2000;

	private final DbusPeer peer;
	/**
	 * Key events are paced, not fired as they arrive: the guest's i8042 has a 16-byte buffer, and a burst of
	 * press/release pairs faster than the guest drains it is silently dropped (the VNC path's "~150 characters"
	 * limit was the same buffer). A few milliseconds between events is invisible to typing and to games.
	 */
	private final java.util.concurrent.LinkedBlockingQueue<int[]> keyQueue = new java.util.concurrent.LinkedBlockingQueue<>();
	private static final long KEY_SPACING_MS = 4;
	private final Thread keyThread;

	private QemuDisplayLink(final DbusPeer peer, final String name) {
		this.peer = peer;
		this.keyThread = new Thread(this::keyLoop, "vmc-keys-" + name);
		this.keyThread.setDaemon(true);
		this.keyThread.start();
	}

	private void keyLoop() {
		try {
			while (!peer.isClosed()) {
				final int[] ev = keyQueue.take();
				peer.callNoReply(CONSOLE, KEYBOARD, ev[1] != 0 ? "Press" : "Release", "u", new DbusMessage.Writer().putU32(ev[0]).toBytes());
				Thread.sleep(KEY_SPACING_MS);
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (final IOException e) {
			VirtualMinecraft.LOGGER.debug("D-Bus key loop stopped: {}", e.toString());
		}
	}

	/** Attaches to a running QEMU; throws if the display is not there or the handoff fails. */
	public static QemuDisplayLink connect(final Path qmpSocket, final String name) throws IOException {
		final int qmp = Libc.connectUnix(qmpSocket.toString());
		int ours = -1;
		try {
			final QmpLine q = new QmpLine(qmp);
			q.readLine(); // greeting
			q.command("{\"execute\":\"qmp_capabilities\"}", null);
			final int[] pair = Libc.socketpair();
			ours = pair[0];
			q.command("{\"execute\":\"getfd\",\"arguments\":{\"fdname\":\"vmcdisplay\"}}", new int[] { pair[1] });
			Libc.close(pair[1]);
			q.command("{\"execute\":\"add_client\",\"arguments\":{\"protocol\":\"@dbus-display\",\"fdname\":\"vmcdisplay\"}}", null);
		} catch (final IOException e) {
			if (ours >= 0) {
				Libc.close(ours);
			}
			throw e;
		} finally {
			Libc.close(qmp);
		}
		final DbusPeer peer = new DbusPeer(ours, name);
		try {
			peer.start();
			// Prove the object is there before anyone relies on it.
			peer.call("/org/qemu/Display1/VM", "org.freedesktop.DBus.Peer", "Ping", "", new byte[0], CALL_TIMEOUT_MS);
		} catch (final IOException e) {
			peer.close();
			throw e;
		}
		return new QemuDisplayLink(peer, name);
	}

	/** Presses or releases a key by QEMU key number — an XT set-1 scancode, extended keys folded into bit 7 (the client maps GLFW keys; see {@code QCodes}). */
	public void key(final int qcode, final boolean down) throws IOException {
		if (peer.isClosed()) {
			throw new IOException("display link closed");
		}
		keyQueue.offer(new int[] { qcode, down ? 1 : 0 });
	}

	/** Absolute pointer position in guest pixels (needs the usb-tablet the launcher always adds). */
	public void mouseMove(final int x, final int y) throws IOException {
		peer.callNoReply(CONSOLE, MOUSE, "SetAbsPosition", "uu", new DbusMessage.Writer().putU32(x).putU32(y).toBytes());
	}

	/** Button by QEMU's numbering: 0 left, 1 middle, 2 right, 3 wheel up, 4 wheel down, 5 side, 6 extra. */
	public void mouseButton(final int button, final boolean down) throws IOException {
		peer.callNoReply(CONSOLE, MOUSE, down ? "Press" : "Release", "u", new DbusMessage.Writer().putU32(button).toBytes());
	}

	public DbusPeer peer() {
		return peer;
	}

	public boolean isClosed() {
		return peer.isClosed();
	}

	@Override
	public void close() {
		peer.close();
		keyThread.interrupt();
	}

	/** Line-oriented JSON over a raw fd, just for the three QMP commands above. */
	private static final class QmpLine {
		private final int fd;
		private final StringBuilder pending = new StringBuilder();
		private final byte[] chunk = new byte[4096];

		QmpLine(final int fd) {
			this.fd = fd;
		}

		String readLine() throws IOException {
			while (true) {
				final int nl = pending.indexOf("\n");
				if (nl >= 0) {
					final String line = pending.substring(0, nl);
					pending.delete(0, nl + 1);
					return line;
				}
				final int n = Libc.read(fd, chunk);
				if (n < 0) {
					throw new IOException("QMP closed");
				}
				pending.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
			}
		}

		JsonObject command(final String json, final int[] fds) throws IOException {
			Libc.sendAll(fd, (json + "\n").getBytes(StandardCharsets.UTF_8), fds);
			while (true) {
				final JsonObject o = JsonParser.parseString(readLine()).getAsJsonObject();
				if (o.has("error")) {
					throw new IOException("QMP " + json + " -> " + o.get("error"));
				}
				if (o.has("return")) {
					return o;
				}
			}
		}
	}
}
