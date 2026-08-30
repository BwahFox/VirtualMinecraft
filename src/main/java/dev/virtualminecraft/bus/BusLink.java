package dev.virtualminecraft.bus;

import dev.virtualminecraft.VirtualMinecraft;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * The raw line transport to QEMU's virtio-serial chardev socket. A reader thread turns bytes into lines and
 * queues them (bounded); a writer thread drains an outgoing queue so the server thread never blocks on the
 * socket. Both threads are daemons named {@code vmc-bus-<name>}. Lines are UTF-8, capped at
 * {@link #MAX_LINE} bytes; longer ones are dropped and reported as an oversized line.
 */
public final class BusLink {
	public static final int MAX_LINE = 64 * 1024;
	private static final int MAX_QUEUED_IN = 512;
	private static final int MAX_QUEUED_OUT = 1024;

	/** Marker queued in place of a line that exceeded {@link #MAX_LINE} (compared by identity). */
	public static final String OVERSIZED = new String("<oversized>");
	private static final String WAKE = new String("");

	private final SocketChannel channel;
	private final String name;
	private final ArrayDeque<String> incoming = new ArrayDeque<>();
	private final LinkedBlockingQueue<String> outgoing = new LinkedBlockingQueue<>(MAX_QUEUED_OUT);
	private final AtomicBoolean closed = new AtomicBoolean();
	private volatile int droppedIn;

	private BusLink(final SocketChannel channel, final String name) {
		this.channel = channel;
		this.name = name;
	}

	public static BusLink connect(final @Nullable Path socket, final int port, final String name) throws IOException {
		final SocketAddress addr = socket != null ? UnixDomainSocketAddress.of(socket) : new InetSocketAddress("127.0.0.1", port);
		final SocketChannel ch = socket != null ? SocketChannel.open(StandardProtocolFamily.UNIX) : SocketChannel.open();
		try {
			ch.connect(addr);
		} catch (final IOException e) {
			ch.close();
			throw e;
		}
		final BusLink link = new BusLink(ch, name);
		final Thread reader = new Thread(link::readLoop, "vmc-bus-" + name);
		reader.setDaemon(true);
		reader.start();
		final Thread writer = new Thread(link::writeLoop, "vmc-bus-" + name + "-out");
		writer.setDaemon(true);
		writer.start();
		return link;
	}

	public boolean isOpen() {
		return !closed.get();
	}

	/** Next queued line from the guest, or null. Server thread. */
	public @Nullable String poll() {
		synchronized (incoming) {
			return incoming.pollFirst();
		}
	}

	/** Lines the guest sent while the queue was full (since the last call). */
	public int takeDropped() {
		final int n = droppedIn;
		droppedIn = 0;
		return n;
	}

	/** Queues a line for the guest. Returns false (and drops it) if the guest is not draining. */
	public boolean send(final String line) {
		if (closed.get()) {
			return false;
		}
		return outgoing.offer(line);
	}

	public void close() {
		if (closed.compareAndSet(false, true)) {
			outgoing.offer(WAKE);
			try {
				channel.close();
			} catch (final IOException ignored) {
			}
		}
	}

	private void readLoop() {
		final ByteArrayOutputStream line = new ByteArrayOutputStream(256);
		boolean skipping = false;
		try (InputStream in = Channels.newInputStream(channel)) {
			final byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) > 0) {
				for (int i = 0; i < n; i++) {
					final byte b = buf[i];
					if (b == '\n') {
						if (skipping) {
							skipping = false;
							enqueue(OVERSIZED);
						} else if (line.size() > 0) {
							enqueue(line.toString(StandardCharsets.UTF_8));
						}
						line.reset();
					} else if (!skipping) {
						if (line.size() >= MAX_LINE) {
							skipping = true;
							line.reset();
						} else {
							line.write(b);
						}
					}
				}
			}
		} catch (final IOException e) {
			if (!closed.get()) {
				VirtualMinecraft.LOGGER.debug("Bus {} read failed: {}", name, e.toString());
			}
		} finally {
			close();
		}
	}

	private void enqueue(final String s) {
		synchronized (incoming) {
			if (incoming.size() >= MAX_QUEUED_IN) {
				droppedIn++;
				return;
			}
			incoming.addLast(s);
		}
	}

	private void writeLoop() {
		try (OutputStream out = Channels.newOutputStream(channel)) {
			while (!closed.get()) {
				final String s = outgoing.take();
				if (s == WAKE) {
					continue;
				}
				out.write((s + "\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
		} catch (final IOException | InterruptedException e) {
			if (!closed.get()) {
				VirtualMinecraft.LOGGER.debug("Bus {} write failed: {}", name, e.toString());
			}
		} finally {
			close();
		}
	}
}
