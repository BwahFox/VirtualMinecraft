package dev.virtualminecraft.dbus;

import dev.virtualminecraft.VirtualMinecraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * A peer-to-peer D-Bus connection over a raw unix socket fd — no message bus, no names, no {@code Hello}. The
 * other end (QEMU) is the authentication server; we authenticate as our own uid with {@code EXTERNAL} and
 * negotiate fd passing, which the shared-memory scanout needs. A reader thread parses incoming messages: replies
 * complete the future waiting on their serial, method calls go to the {@link Handler} (the objects we export,
 * such as the display listener), signals are ignored.
 */
public final class DbusPeer implements AutoCloseable {
	/** Handles method calls made to us; returns the reply to send, or null for none (e.g. when no reply was expected). */
	public interface Handler {
		@Nullable DbusMessage handle(DbusMessage call);
	}

	private static final int MAX_MESSAGE = 64 << 20;

	private final int fd;
	private final String name;
	private final Object writeLock = new Object();
	private final AtomicInteger nextSerial = new AtomicInteger(1);
	private final Map<Integer, CompletableFuture<DbusMessage>> pending = new ConcurrentHashMap<>();
	private volatile @Nullable Handler handler;
	private volatile boolean closed;
	private @Nullable Thread reader;

	public DbusPeer(final int fd, final String name) {
		this.fd = fd;
		this.name = name;
	}

	/** SASL handshake as the client, then starts the reader. Blocking; call once. */
	public void start() throws IOException {
		final String uidHex = hex(Long.toString(uid()));
		Libc.writeAll(fd, ("\0AUTH EXTERNAL " + uidHex + "\r\n").getBytes(StandardCharsets.US_ASCII));
		final String ok = readLine();
		if (!ok.startsWith("OK ")) {
			// Some servers want the uid via ANONYMOUS or reject EXTERNAL; try anonymous before giving up.
			Libc.writeAll(fd, "AUTH ANONYMOUS\r\n".getBytes(StandardCharsets.US_ASCII));
			final String ok2 = readLine();
			if (!ok2.startsWith("OK ")) {
				throw new IOException("D-Bus auth refused: " + ok + " / " + ok2);
			}
		}
		Libc.writeAll(fd, "NEGOTIATE_UNIX_FD\r\n".getBytes(StandardCharsets.US_ASCII));
		final String agree = readLine();
		if (!agree.startsWith("AGREE_UNIX_FD")) {
			VirtualMinecraft.LOGGER.debug("D-Bus peer {}: no fd passing ({})", name, agree);
		}
		Libc.writeAll(fd, "BEGIN\r\n".getBytes(StandardCharsets.US_ASCII));
		final Thread t = new Thread(this::readLoop, "vmc-dbus-" + name);
		t.setDaemon(true);
		reader = t;
		t.start();
	}

	public void setHandler(final @Nullable Handler handler) {
		this.handler = handler;
	}

	private static long uid() {
		try {
			final Object uid = java.nio.file.Files.getAttribute(java.nio.file.Path.of("/proc/self"), "unix:uid");
			return ((Number) uid).longValue();
		} catch (final Exception e) {
			return 0;
		}
	}

	private static String hex(final String s) {
		final StringBuilder sb = new StringBuilder();
		for (final byte b : s.getBytes(StandardCharsets.US_ASCII)) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	private String readLine() throws IOException {
		final StringBuilder sb = new StringBuilder();
		final byte[] one = new byte[1];
		while (true) {
			final int n = Libc.read(fd, one);
			if (n < 0) {
				throw new IOException("D-Bus peer closed during auth");
			}
			if (n == 0) {
				continue;
			}
			sb.append((char) one[0]);
			if (sb.length() >= 2 && sb.charAt(sb.length() - 2) == '\r' && sb.charAt(sb.length() - 1) == '\n') {
				return sb.substring(0, sb.length() - 2);
			}
		}
	}

	// ------------------------------------------------------------------------------------------------

	/** Sends a method call and waits for its reply. Throws on a D-Bus error reply. */
	public DbusMessage call(final String path, final String iface, final String member, final String signature, final byte[] body, final long timeoutMs) throws IOException {
		final DbusMessage m = DbusMessage.methodCall(path, iface, member, signature, body);
		final CompletableFuture<DbusMessage> f = new CompletableFuture<>();
		final int serial = send(m, f);
		try {
			final DbusMessage reply = f.get(timeoutMs, TimeUnit.MILLISECONDS);
			if (reply.type == DbusMessage.ERROR) {
				final String text = reply.signature.startsWith("s") ? new DbusMessage.Reader(reply.body).getString() : "";
				throw new IOException(iface + "." + member + ": " + reply.errorName + (text.isEmpty() ? "" : " (" + text + ")"));
			}
			return reply;
		} catch (final TimeoutException e) {
			pending.remove(serial);
			throw new IOException(iface + "." + member + ": no reply in " + timeoutMs + " ms");
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		} catch (final ExecutionException e) {
			throw new IOException(e.getCause());
		}
	}

	/** Sends a method call without waiting (the NO_REPLY_EXPECTED flag is set). */
	public void callNoReply(final String path, final String iface, final String member, final String signature, final byte[] body) throws IOException {
		final DbusMessage m = DbusMessage.methodCall(path, iface, member, signature, body);
		m.flags = DbusMessage.FLAG_NO_REPLY_EXPECTED;
		send(m, null);
	}

	/** Sends any message (used for replies); returns its serial. */
	public int send(final DbusMessage m, final @Nullable CompletableFuture<DbusMessage> replyFuture) throws IOException {
		if (closed) {
			throw new IOException("D-Bus peer closed");
		}
		synchronized (writeLock) {
			m.serial = nextSerial.getAndIncrement();
			if (replyFuture != null) {
				pending.put(m.serial, replyFuture);
			}
			Libc.sendAll(fd, m.encode(), m.fds.length > 0 ? m.fds : null);
			return m.serial;
		}
	}

	private void readLoop() {
		byte[] buf = new byte[1 << 16];
		int filled = 0;
		final byte[] chunk = new byte[1 << 16];
		final List<Integer> fdQueue = new ArrayList<>();
		try {
			while (!closed) {
				final Libc.Received got = Libc.receive(fd, chunk, 16);
				if (got.bytes() < 0) {
					break;
				}
				for (final int f : got.fds()) {
					fdQueue.add(f);
				}
				if (filled + got.bytes() > buf.length) {
					if (filled + got.bytes() > MAX_MESSAGE) {
						throw new IOException("D-Bus message too large");
					}
					buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, filled + got.bytes()));
				}
				System.arraycopy(chunk, 0, buf, filled, got.bytes());
				filled += got.bytes();
				int offset = 0;
				final int[] consumed = new int[1];
				while (true) {
					final DbusMessage m = DbusMessage.decode(buf, offset, filled - offset, consumed);
					if (m == null) {
						break;
					}
					offset += consumed[0];
					if (m.unixFds > 0) {
						final int n = Math.min(m.unixFds, fdQueue.size());
						m.fds = new int[n];
						for (int i = 0; i < n; i++) {
							m.fds[i] = fdQueue.remove(0);
						}
					}
					dispatch(m);
				}
				if (offset > 0) {
					System.arraycopy(buf, offset, buf, 0, filled - offset);
					filled -= offset;
				}
			}
		} catch (final Throwable t) {
			if (!closed) {
				VirtualMinecraft.LOGGER.debug("D-Bus peer {} reader stopped: {}", name, t.toString());
			}
		} finally {
			closed = true;
			for (final CompletableFuture<DbusMessage> f : pending.values()) {
				f.completeExceptionally(new IOException("D-Bus peer closed"));
			}
			pending.clear();
			for (final int f : fdQueue) {
				Libc.close(f);
			}
		}
	}

	private void dispatch(final DbusMessage m) {
		if (m.isReply()) {
			final CompletableFuture<DbusMessage> f = pending.remove(m.replySerial);
			if (f != null) {
				f.complete(m);
			} else {
				closeFds(m);
			}
			return;
		}
		if (m.type == DbusMessage.METHOD_CALL) {
			final Handler h = handler;
			DbusMessage reply = null;
			try {
				if (h != null) {
					reply = h.handle(m);
				} else {
					reply = DbusMessage.error(m, "org.freedesktop.DBus.Error.UnknownMethod", "no handler");
				}
			} catch (final Throwable t) {
				reply = DbusMessage.error(m, "org.freedesktop.DBus.Error.Failed", String.valueOf(t.getMessage()));
			}
			if (reply != null && (m.flags & DbusMessage.FLAG_NO_REPLY_EXPECTED) == 0) {
				try {
					send(reply, null);
				} catch (final IOException e) {
					VirtualMinecraft.LOGGER.debug("D-Bus peer {}: reply failed: {}", name, e.toString());
				}
			}
			return;
		}
		closeFds(m); // signals: not used
	}

	private static void closeFds(final DbusMessage m) {
		for (final int f : m.fds) {
			Libc.close(f);
		}
	}

	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		Libc.close(fd);
		final Thread t = reader;
		if (t != null) {
			t.interrupt();
		}
	}
}
