package dev.virtualminecraft.rfb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Minimal, dependency-free RFB (VNC) client tuned for talking to QEMU's built-in VNC server.
 *
 * <p>The framebuffer is kept as tightly packed RGBA8 bytes. We negotiate a 32bpp little-endian
 * pixel format with red in the low byte so that raw pixels arrive as {@code R,G,B,X} and can be
 * copied straight into the RGBA buffer (alpha is forced to 0xFF).
 *
 * <p>Supported encodings: Raw, CopyRect, Hextile, ZRLE, plus the DesktopSize and LastRect
 * pseudo-encodings. That is enough for QEMU to prefer ZRLE, which is compact and cheap to decode.
 */
public final class RfbClient implements Closeable {

	public interface Listener {
		void onResize(RfbClient client, int width, int height);

		void onRectUpdated(RfbClient client, int x, int y, int w, int h);

		default void onFrameComplete(RfbClient client) {
		}

		default void onBell(RfbClient client) {
		}

		/** Little-endian signed 16-bit mono PCM at {@link #AUDIO_RATE} Hz (only if audio was enabled). */
		default void onAudio(RfbClient client, byte[] pcm, int len) {
		}

		void onDisconnected(RfbClient client, Throwable cause);
	}

	private static final int ENC_RAW = 0;
	private static final int ENC_COPYRECT = 1;
	private static final int ENC_HEXTILE = 5;
	private static final int ENC_ZRLE = 16;
	private static final int ENC_DESKTOP_SIZE = -223;
	private static final int ENC_LAST_RECT = -224;
	private static final int ENC_QEMU_AUDIO = -259;
	public static final int AUDIO_RATE = 22050;

	private final SocketAddress address;
	private final Listener listener;
	private final boolean wantAudio;
	private byte[] audioBuf = new byte[1 << 14];

	private SocketChannel channel;
	private DataInputStream in;
	private OutputStream out;
	private final Object writeLock = new Object();

	private final Object fbLock = new Object();
	private byte[] fb = new byte[0];
	private int width;
	private int height;
	private String serverName = "";
	private volatile boolean closed;

	private final Inflater zrleInflater = new Inflater();
	private byte[] zrleBuf = new byte[1 << 18];
	private byte[] rowBuf = new byte[0];

	public RfbClient(final SocketAddress address, final Listener listener) {
		this(address, listener, false);
	}

	public RfbClient(final SocketAddress address, final Listener listener, final boolean wantAudio) {
		this.address = address;
		this.listener = listener;
		this.wantAudio = wantAudio;
	}

	public static SocketAddress unix(final Path socketPath) {
		return UnixDomainSocketAddress.of(socketPath);
	}

	public static SocketAddress tcp(final String host, final int port) {
		return new InetSocketAddress(host, port);
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public String serverName() {
		return serverName;
	}

	/** Lock to hold while reading {@link #framebuffer()}. */
	public Object framebufferLock() {
		return fbLock;
	}

	/** Tightly packed RGBA8 pixels, {@code width * height * 4} bytes. Only valid while holding {@link #framebufferLock()}. */
	public byte[] framebuffer() {
		return fb;
	}

	public boolean isClosed() {
		return closed;
	}

	// ---------------------------------------------------------------------------------------------
	// Connection / handshake
	// ---------------------------------------------------------------------------------------------

	public void connect() throws IOException {
		if (address instanceof UnixDomainSocketAddress) {
			channel = SocketChannel.open(StandardProtocolFamily.UNIX);
		} else {
			channel = SocketChannel.open();
		}
		channel.connect(address);
		in = new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel), 1 << 16));
		out = new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 12);

		final byte[] version = new byte[12];
		in.readFully(version);
		final String verStr = new String(version, StandardCharsets.US_ASCII);
		if (!verStr.startsWith("RFB ")) {
			throw new IOException("Not an RFB server: " + verStr.trim());
		}
		final int major = Integer.parseInt(verStr.substring(4, 7));
		final int minor = Integer.parseInt(verStr.substring(8, 11));
		final int useMinor = major > 3 || minor >= 8 ? 8 : (minor >= 7 ? 7 : 3);
		writeRaw(("RFB 003.00" + useMinor + "\n").getBytes(StandardCharsets.US_ASCII));

		if (useMinor >= 7) {
			final int count = in.readUnsignedByte();
			if (count == 0) {
				throw new IOException("Server refused connection: " + readReason());
			}
			final byte[] types = new byte[count];
			in.readFully(types);
			boolean none = false;
			for (final byte t : types) {
				if (t == 1) {
					none = true;
				}
			}
			if (!none) {
				throw new IOException("VNC server requires authentication (security types " + Arrays.toString(types) + ")");
			}
			writeRaw(new byte[] { 1 });
			if (useMinor >= 8) {
				final int result = in.readInt();
				if (result != 0) {
					throw new IOException("VNC security handshake failed: " + readReason());
				}
			}
		} else {
			final int type = in.readInt();
			if (type != 1) {
				throw new IOException("VNC server requires authentication (security type " + type + ")");
			}
		}

		// ClientInit: shared = 1
		writeRaw(new byte[] { 1 });

		// ServerInit
		final int w = in.readUnsignedShort();
		final int h = in.readUnsignedShort();
		final byte[] pixelFormat = new byte[16];
		in.readFully(pixelFormat);
		final int nameLen = in.readInt();
		final byte[] name = new byte[Math.max(0, nameLen)];
		in.readFully(name);
		serverName = new String(name, StandardCharsets.UTF_8);
		resize(w, h);

		// SetPixelFormat: 32bpp, depth 24, little endian, true colour, R<<0 G<<8 B<<16
		writeRaw(new byte[] {
			0, 0, 0, 0,
			32, 24, 0, 1,
			0, (byte) 255, 0, (byte) 255, 0, (byte) 255,
			0, 8, 16,
			0, 0, 0
		});

		if (wantAudio) {
			setEncodings(ENC_ZRLE, ENC_HEXTILE, ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE, ENC_LAST_RECT, ENC_QEMU_AUDIO);
			// QEMU client message 255 / audio submessage 1: set format (op 2) = S16, 1 channel, AUDIO_RATE; then enable (op 0).
			writeRaw(new byte[] {
				(byte) 255, 1, 0, 2, 3, 1,
				(byte) (AUDIO_RATE >> 24), (byte) (AUDIO_RATE >> 16), (byte) (AUDIO_RATE >> 8), (byte) AUDIO_RATE
			});
			writeRaw(new byte[] { (byte) 255, 1, 0, 0 });
		} else {
			setEncodings(ENC_ZRLE, ENC_HEXTILE, ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE, ENC_LAST_RECT);
		}
		requestUpdate(false);
	}

	private String readReason() throws IOException {
		final int len = in.readInt();
		if (len < 0 || len > 65536) {
			return "(unreadable reason)";
		}
		final byte[] reason = new byte[len];
		in.readFully(reason);
		return new String(reason, StandardCharsets.UTF_8);
	}

	private void setEncodings(final int... encodings) throws IOException {
		final byte[] msg = new byte[4 + encodings.length * 4];
		msg[0] = 2;
		msg[2] = (byte) (encodings.length >> 8);
		msg[3] = (byte) encodings.length;
		int o = 4;
		for (final int e : encodings) {
			msg[o++] = (byte) (e >> 24);
			msg[o++] = (byte) (e >> 16);
			msg[o++] = (byte) (e >> 8);
			msg[o++] = (byte) e;
		}
		writeRaw(msg);
	}

	// ---------------------------------------------------------------------------------------------
	// Client -> server messages
	// ---------------------------------------------------------------------------------------------

	public void requestUpdate(final boolean incremental) throws IOException {
		final int w;
		final int h;
		synchronized (fbLock) {
			w = width;
			h = height;
		}
		writeRaw(new byte[] {
			3, (byte) (incremental ? 1 : 0),
			0, 0, 0, 0,
			(byte) (w >> 8), (byte) w, (byte) (h >> 8), (byte) h
		});
	}

	public void sendKey(final int keysym, final boolean down) throws IOException {
		writeRaw(new byte[] {
			4, (byte) (down ? 1 : 0), 0, 0,
			(byte) (keysym >> 24), (byte) (keysym >> 16), (byte) (keysym >> 8), (byte) keysym
		});
	}

	public void sendPointer(final int buttonMask, final int x, final int y) throws IOException {
		final int cx = Math.clamp(x, 0, Math.max(0, width - 1));
		final int cy = Math.clamp(y, 0, Math.max(0, height - 1));
		writeRaw(new byte[] {
			5, (byte) buttonMask,
			(byte) (cx >> 8), (byte) cx, (byte) (cy >> 8), (byte) cy
		});
	}

	public void sendCutText(final String text) throws IOException {
		final byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
		final byte[] msg = new byte[8 + bytes.length];
		msg[0] = 6;
		msg[4] = (byte) (bytes.length >> 24);
		msg[5] = (byte) (bytes.length >> 16);
		msg[6] = (byte) (bytes.length >> 8);
		msg[7] = (byte) bytes.length;
		System.arraycopy(bytes, 0, msg, 8, bytes.length);
		writeRaw(msg);
	}

	private void writeRaw(final byte[] bytes) throws IOException {
		synchronized (writeLock) {
			if (closed) {
				throw new IOException("RFB connection closed");
			}
			out.write(bytes);
			out.flush();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Server -> client message loop
	// ---------------------------------------------------------------------------------------------

	/** Blocks, processing server messages until the connection drops or {@link #close()} is called. */
	public void run() {
		Throwable cause = null;
		try {
			while (!closed) {
				final int type = in.readUnsignedByte();
				switch (type) {
					case 0 -> handleFramebufferUpdate();
					case 1 -> {
						in.skipNBytes(1);
						in.readUnsignedShort();
						final int n = in.readUnsignedShort();
						in.skipNBytes((long) n * 6);
					}
					case 2 -> listener.onBell(this);
					case 3 -> {
						in.skipNBytes(3);
						final int len = in.readInt();
						// Negative length = extended clipboard pseudo message; we just skip it.
						in.skipNBytes(len < 0 ? -(long) len : len);
					}
					case 255 -> handleQemuMessage();
					default -> throw new IOException("Unknown RFB server message type " + type);
				}
			}
		} catch (final Throwable t) {
			if (!closed) {
				cause = t;
			}
		}
		closeQuietly();
		listener.onDisconnected(this, cause);
	}

	private void handleQemuMessage() throws IOException {
		final int sub = in.readUnsignedByte();
		if (sub != 1) {
			throw new IOException("Unknown QEMU server submessage " + sub);
		}
		final int op = in.readUnsignedShort();
		switch (op) {
			case 0, 1 -> {
				// audio begin / end: nothing to do
			}
			case 2 -> {
				final int len = in.readInt();
				if (len < 0 || len > (1 << 22)) {
					throw new IOException("Bad audio chunk length " + len);
				}
				if (audioBuf.length < len) {
					audioBuf = new byte[Math.max(len, audioBuf.length * 2)];
				}
				in.readFully(audioBuf, 0, len);
				listener.onAudio(this, audioBuf, len);
			}
			default -> throw new IOException("Unknown QEMU audio op " + op);
		}
	}

	private void handleFramebufferUpdate() throws IOException, DataFormatException {
		in.skipNBytes(1);
		final int rects = in.readUnsignedShort();
		for (int i = 0; i < rects; i++) {
			final int x = in.readUnsignedShort();
			final int y = in.readUnsignedShort();
			final int w = in.readUnsignedShort();
			final int h = in.readUnsignedShort();
			final int enc = in.readInt();
			if (enc == ENC_LAST_RECT) {
				break;
			}
			if (enc == ENC_DESKTOP_SIZE) {
				resize(w, h);
				continue;
			}
			if (enc == ENC_QEMU_AUDIO) {
				continue; // QEMU acknowledges audio support with an empty pseudo-rectangle
			}
			synchronized (fbLock) {
				switch (enc) {
					case ENC_RAW -> decodeRaw(x, y, w, h);
					case ENC_COPYRECT -> decodeCopyRect(x, y, w, h);
					case ENC_HEXTILE -> decodeHextile(x, y, w, h);
					case ENC_ZRLE -> decodeZrle(x, y, w, h);
					default -> throw new IOException("Unsupported RFB encoding " + enc);
				}
			}
			listener.onRectUpdated(this, x, y, w, h);
		}
		listener.onFrameComplete(this);
		requestUpdate(true);
	}

	private void resize(final int w, final int h) {
		synchronized (fbLock) {
			width = w;
			height = h;
			fb = new byte[w * h * 4];
			for (int i = 3; i < fb.length; i += 4) {
				fb[i] = (byte) 0xFF;
			}
		}
		listener.onResize(this, w, h);
	}

	// ---------------------------------------------------------------------------------------------
	// Encodings
	// ---------------------------------------------------------------------------------------------

	private void decodeRaw(final int x, final int y, final int w, final int h) throws IOException {
		final int rowBytes = w * 4;
		if (rowBuf.length < rowBytes) {
			rowBuf = new byte[rowBytes];
		}
		for (int row = 0; row < h; row++) {
			in.readFully(rowBuf, 0, rowBytes);
			int o = ((y + row) * width + x) * 4;
			for (int i = 0; i < rowBytes; i += 4, o += 4) {
				fb[o] = rowBuf[i];
				fb[o + 1] = rowBuf[i + 1];
				fb[o + 2] = rowBuf[i + 2];
				fb[o + 3] = (byte) 0xFF;
			}
		}
	}

	private void decodeCopyRect(final int x, final int y, final int w, final int h) throws IOException {
		final int srcX = in.readUnsignedShort();
		final int srcY = in.readUnsignedShort();
		final int rowBytes = w * 4;
		final byte[] tmp = new byte[rowBytes * h];
		for (int row = 0; row < h; row++) {
			System.arraycopy(fb, ((srcY + row) * width + srcX) * 4, tmp, row * rowBytes, rowBytes);
		}
		for (int row = 0; row < h; row++) {
			System.arraycopy(tmp, row * rowBytes, fb, ((y + row) * width + x) * 4, rowBytes);
		}
	}

	private int readPixel() throws IOException {
		final int r = in.readUnsignedByte();
		final int g = in.readUnsignedByte();
		final int b = in.readUnsignedByte();
		in.readUnsignedByte();
		return (r << 16) | (g << 8) | b;
	}

	private void fill(final int x, final int y, final int w, final int h, final int rgb) {
		final byte r = (byte) (rgb >> 16);
		final byte g = (byte) (rgb >> 8);
		final byte b = (byte) rgb;
		for (int row = 0; row < h; row++) {
			int o = ((y + row) * width + x) * 4;
			for (int i = 0; i < w; i++, o += 4) {
				fb[o] = r;
				fb[o + 1] = g;
				fb[o + 2] = b;
				fb[o + 3] = (byte) 0xFF;
			}
		}
	}

	private void decodeHextile(final int x, final int y, final int w, final int h) throws IOException {
		int bg = 0;
		int fg = 0;
		for (int ty = 0; ty < h; ty += 16) {
			final int th = Math.min(16, h - ty);
			for (int tx = 0; tx < w; tx += 16) {
				final int tw = Math.min(16, w - tx);
				final int sub = in.readUnsignedByte();
				if ((sub & 1) != 0) {
					decodeRaw(x + tx, y + ty, tw, th);
					continue;
				}
				if ((sub & 2) != 0) {
					bg = readPixel();
				}
				if ((sub & 4) != 0) {
					fg = readPixel();
				}
				fill(x + tx, y + ty, tw, th, bg);
				if ((sub & 8) != 0) {
					final int n = in.readUnsignedByte();
					for (int i = 0; i < n; i++) {
						final int color = (sub & 16) != 0 ? readPixel() : fg;
						final int xy = in.readUnsignedByte();
						final int wh = in.readUnsignedByte();
						fill(x + tx + (xy >> 4), y + ty + (xy & 15), (wh >> 4) + 1, (wh & 15) + 1, color);
					}
				}
			}
		}
	}

	private void decodeZrle(final int x, final int y, final int w, final int h) throws IOException, DataFormatException {
		final int len = in.readInt();
		if (len < 0) {
			throw new IOException("Bad ZRLE length " + len);
		}
		final byte[] comp = new byte[len];
		in.readFully(comp);
		zrleInflater.setInput(comp);
		int outLen = 0;
		while (!zrleInflater.needsInput()) {
			if (outLen == zrleBuf.length) {
				zrleBuf = Arrays.copyOf(zrleBuf, zrleBuf.length * 2);
			}
			final int n = zrleInflater.inflate(zrleBuf, outLen, zrleBuf.length - outLen);
			if (n == 0 && (zrleInflater.finished() || zrleInflater.needsDictionary())) {
				break;
			}
			outLen += n;
		}

		final byte[] d = zrleBuf;
		int p = 0;
		for (int ty = 0; ty < h; ty += 64) {
			final int th = Math.min(64, h - ty);
			for (int tx = 0; tx < w; tx += 64) {
				final int tw = Math.min(64, w - tx);
				if (p >= outLen) {
					throw new IOException("Truncated ZRLE tile data");
				}
				final int sub = d[p++] & 0xFF;
				final int ox = x + tx;
				final int oy = y + ty;
				if (sub == 0) {
					for (int row = 0; row < th; row++) {
						int o = ((oy + row) * width + ox) * 4;
						for (int i = 0; i < tw; i++, o += 4) {
							fb[o] = d[p++];
							fb[o + 1] = d[p++];
							fb[o + 2] = d[p++];
							fb[o + 3] = (byte) 0xFF;
						}
					}
				} else if (sub == 1) {
					final int rgb = cpixel(d, p);
					p += 3;
					fill(ox, oy, tw, th, rgb);
				} else if (sub <= 16) {
					final int[] palette = new int[sub];
					for (int i = 0; i < sub; i++) {
						palette[i] = cpixel(d, p);
						p += 3;
					}
					final int bits = sub == 2 ? 1 : (sub <= 4 ? 2 : 4);
					final int mask = (1 << bits) - 1;
					for (int row = 0; row < th; row++) {
						int o = ((oy + row) * width + ox) * 4;
						int cur = 0;
						int nbits = 0;
						for (int i = 0; i < tw; i++, o += 4) {
							if (nbits == 0) {
								cur = d[p++] & 0xFF;
								nbits = 8;
							}
							nbits -= bits;
							final int rgb = palette[(cur >> nbits) & mask];
							fb[o] = (byte) (rgb >> 16);
							fb[o + 1] = (byte) (rgb >> 8);
							fb[o + 2] = (byte) rgb;
							fb[o + 3] = (byte) 0xFF;
						}
					}
				} else if (sub == 128) {
					final int total = tw * th;
					int i = 0;
					while (i < total) {
						final int rgb = cpixel(d, p);
						p += 3;
						int run = 1;
						int b;
						do {
							b = d[p++] & 0xFF;
							run += b;
						} while (b == 255);
						i = fillRun(ox, oy, tw, i, run, rgb);
					}
				} else if (sub >= 130) {
					final int psize = sub - 128;
					final int[] palette = new int[psize];
					for (int i = 0; i < psize; i++) {
						palette[i] = cpixel(d, p);
						p += 3;
					}
					final int total = tw * th;
					int i = 0;
					while (i < total) {
						int idx = d[p++] & 0xFF;
						int run = 1;
						if ((idx & 0x80) != 0) {
							idx &= 0x7F;
							int b;
							do {
								b = d[p++] & 0xFF;
								run += b;
							} while (b == 255);
						}
						i = fillRun(ox, oy, tw, i, run, palette[idx]);
					}
				} else {
					throw new IOException("Invalid ZRLE subencoding " + sub);
				}
			}
		}
	}

	private static int cpixel(final byte[] d, final int p) {
		return ((d[p] & 0xFF) << 16) | ((d[p + 1] & 0xFF) << 8) | (d[p + 2] & 0xFF);
	}

	/** Fills {@code run} pixels in tile-linear order starting at linear index {@code i}; returns the new index. */
	private int fillRun(final int ox, final int oy, final int tw, int i, int run, final int rgb) {
		final byte r = (byte) (rgb >> 16);
		final byte g = (byte) (rgb >> 8);
		final byte b = (byte) rgb;
		while (run-- > 0) {
			final int px = ox + i % tw;
			final int py = oy + i / tw;
			final int o = (py * width + px) * 4;
			fb[o] = r;
			fb[o + 1] = g;
			fb[o + 2] = b;
			fb[o + 3] = (byte) 0xFF;
			i++;
		}
		return i;
	}

	// ---------------------------------------------------------------------------------------------

	private void closeQuietly() {
		closed = true;
		try {
			if (channel != null) {
				channel.close();
			}
		} catch (final IOException ignored) {
		}
		zrleInflater.end();
	}

	@Override
	public void close() {
		closeQuietly();
	}

	@SuppressWarnings("unused")
	private static void checkEof(final int v) throws EOFException {
		if (v < 0) {
			throw new EOFException();
		}
	}
}
