package dev.virtualminecraft.dbus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Just enough of the D-Bus wire format for talking to QEMU's display: little-endian, the header fields we use,
 * and the body types in {@code org.qemu.Display1} — {@code y b q i u x t d s o g h ay as au} plus structs and
 * variants of those. No introspection, no bus names, no matching rules. Spec: dbus.freedesktop.org/doc/dbus-specification.html.
 */
public final class DbusMessage {
	public static final byte METHOD_CALL = 1;
	public static final byte METHOD_RETURN = 2;
	public static final byte ERROR = 3;
	public static final byte SIGNAL = 4;
	public static final byte FLAG_NO_REPLY_EXPECTED = 1;

	public static final byte FIELD_PATH = 1;
	public static final byte FIELD_INTERFACE = 2;
	public static final byte FIELD_MEMBER = 3;
	public static final byte FIELD_ERROR_NAME = 4;
	public static final byte FIELD_REPLY_SERIAL = 5;
	public static final byte FIELD_DESTINATION = 6;
	public static final byte FIELD_SENDER = 7;
	public static final byte FIELD_SIGNATURE = 8;
	public static final byte FIELD_UNIX_FDS = 9;

	public byte type;
	public byte flags;
	public int serial;
	public @Nullable String path;
	public @Nullable String iface;
	public @Nullable String member;
	public @Nullable String errorName;
	public int replySerial = -1;
	public @Nullable String sender;
	public @Nullable String destination;
	public String signature = "";
	public int unixFds;
	/** Body bytes, little-endian, starting at an 8-aligned offset (so alignment inside is relative to index 0). */
	public byte[] body = new byte[0];
	/** File descriptors that arrived with this message, indexed by the body's {@code h} values. */
	public int[] fds = new int[0];

	public boolean isReply() {
		return type == METHOD_RETURN || type == ERROR;
	}

	@Override
	public String toString() {
		return "DbusMessage[type=" + type + " serial=" + serial + " " + (path == null ? "" : path + " ") + (iface == null ? "" : iface + ".") + (member == null ? "" : member)
			+ (errorName == null ? "" : " error=" + errorName) + (replySerial < 0 ? "" : " replyTo=" + replySerial) + " sig=" + signature + " body=" + body.length + "B]";
	}

	// ------------------------------------------------------------------------------------------------
	// Writing
	// ------------------------------------------------------------------------------------------------

	/** Little-endian, alignment-aware body/header writer. */
	public static final class Writer {
		private ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);

		private void ensure(final int n) {
			if (buf.remaining() < n) {
				final ByteBuffer bigger = ByteBuffer.allocate(Math.max(buf.capacity() * 2, buf.position() + n)).order(ByteOrder.LITTLE_ENDIAN);
				buf.flip();
				bigger.put(buf);
				buf = bigger;
			}
		}

		public Writer align(final int a) {
			final int pad = (-buf.position()) & (a - 1);
			ensure(pad);
			for (int i = 0; i < pad; i++) {
				buf.put((byte) 0);
			}
			return this;
		}

		public int position() {
			return buf.position();
		}

		public Writer putByte(final int v) {
			ensure(1);
			buf.put((byte) v);
			return this;
		}

		public Writer putBool(final boolean v) {
			return putU32(v ? 1 : 0);
		}

		public Writer putU16(final int v) {
			align(2);
			ensure(2);
			buf.putShort((short) v);
			return this;
		}

		public Writer putI32(final int v) {
			align(4);
			ensure(4);
			buf.putInt(v);
			return this;
		}

		public Writer putU32(final int v) {
			return putI32(v);
		}

		public Writer putU64(final long v) {
			align(8);
			ensure(8);
			buf.putLong(v);
			return this;
		}

		public Writer putDouble(final double v) {
			align(8);
			ensure(8);
			buf.putDouble(v);
			return this;
		}

		public Writer putString(final String s) {
			final byte[] b = s.getBytes(StandardCharsets.UTF_8);
			putU32(b.length);
			ensure(b.length + 1);
			buf.put(b);
			buf.put((byte) 0);
			return this;
		}

		public Writer putObjectPath(final String s) {
			return putString(s);
		}

		public Writer putSignature(final String s) {
			final byte[] b = s.getBytes(StandardCharsets.US_ASCII);
			ensure(b.length + 2);
			buf.put((byte) b.length);
			buf.put(b);
			buf.put((byte) 0);
			return this;
		}

		/** A unix fd is sent as its index into the message's fd list. */
		public Writer putFdIndex(final int index) {
			return putU32(index);
		}

		public Writer putByteArray(final byte[] data) {
			putU32(data.length);
			ensure(data.length);
			buf.put(data);
			return this;
		}

		public Writer putStringArray(final List<String> items) {
			putU32(0); // patched below
			final int lenPos = buf.position() - 4;
			align(4);
			final int start = buf.position();
			for (final String s : items) {
				putString(s);
			}
			buf.putInt(lenPos, buf.position() - start);
			return this;
		}

		/** Opens a variant: writes the contained signature; the caller then writes exactly that value. */
		public Writer putVariantSignature(final String sig) {
			return putSignature(sig);
		}

		/** Structs are 8-aligned; contents follow. */
		public Writer beginStruct() {
			return align(8);
		}

		public byte[] toBytes() {
			return Arrays.copyOf(buf.array(), buf.position());
		}
	}

	/** Serialises a complete message. {@code body} must have been written with a fresh {@link Writer} (0-based alignment). */
	public byte[] encode() {
		final Writer w = new Writer();
		w.putByte('l').putByte(type).putByte(flags).putByte(1);
		w.putU32(body.length);
		w.putU32(serial);
		w.putU32(0); // header field array length, patched
		final int fieldsStart = w.position();
		if (path != null) {
			field(w, FIELD_PATH, "o", path);
		}
		if (iface != null) {
			field(w, FIELD_INTERFACE, "s", iface);
		}
		if (member != null) {
			field(w, FIELD_MEMBER, "s", member);
		}
		if (errorName != null) {
			field(w, FIELD_ERROR_NAME, "s", errorName);
		}
		if (replySerial >= 0) {
			w.beginStruct().putByte(FIELD_REPLY_SERIAL).putVariantSignature("u").putU32(replySerial);
		}
		if (destination != null) {
			field(w, FIELD_DESTINATION, "s", destination);
		}
		if (!signature.isEmpty()) {
			w.beginStruct().putByte(FIELD_SIGNATURE).putVariantSignature("g").putSignature(signature);
		}
		if (fds.length > 0) {
			w.beginStruct().putByte(FIELD_UNIX_FDS).putVariantSignature("u").putU32(fds.length);
		}
		final int fieldsLen = w.position() - fieldsStart;
		w.align(8);
		final byte[] head = w.toBytes();
		ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).putInt(12, fieldsLen);
		final byte[] out = Arrays.copyOf(head, head.length + body.length);
		System.arraycopy(body, 0, out, head.length, body.length);
		return out;
	}

	private static void field(final Writer w, final byte code, final String sig, final String value) {
		w.beginStruct().putByte(code).putVariantSignature(sig).putString(value);
	}

	// ------------------------------------------------------------------------------------------------
	// Reading
	// ------------------------------------------------------------------------------------------------

	/** Little-endian, alignment-aware reader over a byte array whose index 0 is 8-aligned in the message. */
	public static final class Reader {
		private final ByteBuffer buf;

		public Reader(final byte[] data) {
			this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		}

		public boolean hasRemaining() {
			return buf.hasRemaining();
		}

		public int position() {
			return buf.position();
		}

		public Reader align(final int a) {
			final int pad = (-buf.position()) & (a - 1);
			buf.position(buf.position() + pad);
			return this;
		}

		public int getByte() {
			return buf.get() & 0xFF;
		}

		public boolean getBool() {
			return getU32() != 0;
		}

		public int getU16() {
			align(2);
			return buf.getShort() & 0xFFFF;
		}

		public int getI32() {
			align(4);
			return buf.getInt();
		}

		public int getU32() {
			return getI32();
		}

		public long getU64() {
			align(8);
			return buf.getLong();
		}

		public double getDouble() {
			align(8);
			return buf.getDouble();
		}

		public String getString() {
			final int len = getU32();
			final byte[] b = new byte[len];
			buf.get(b);
			buf.get(); // NUL
			return new String(b, StandardCharsets.UTF_8);
		}

		public String getSignature() {
			final int len = getByte();
			final byte[] b = new byte[len];
			buf.get(b);
			buf.get();
			return new String(b, StandardCharsets.US_ASCII);
		}

		public byte[] getByteArray() {
			final int len = getU32();
			final byte[] b = new byte[len];
			buf.get(b);
			return b;
		}

		public List<String> getStringArray() {
			final int len = getU32();
			align(4);
			final int end = buf.position() + len;
			final List<String> out = new ArrayList<>();
			while (buf.position() < end) {
				out.add(getString());
			}
			return out;
		}

		public int[] getU32Array() {
			final int len = getU32();
			align(4);
			final int[] out = new int[len / 4];
			for (int i = 0; i < out.length; i++) {
				out[i] = buf.getInt();
			}
			return out;
		}

		/** Skips one complete value of the given single-type signature (used to step over things we do not need). */
		public void skip(final String sig) {
			switch (sig.charAt(0)) {
				case 'y' -> getByte();
				case 'b', 'i', 'u', 'h' -> getU32();
				case 'n', 'q' -> getU16();
				case 'x', 't', 'd' -> getU64();
				case 's', 'o' -> getString();
				case 'g' -> getSignature();
				case 'v' -> skip(getSignature());
				case 'a' -> {
					final int len = getU32();
					final char elem = sig.charAt(1);
					align(elem == '(' || elem == '{' || elem == 'x' || elem == 't' || elem == 'd' ? 8 : elem == 'i' || elem == 'u' || elem == 'b' || elem == 's' || elem == 'o' || elem == 'a' || elem == 'h' ? 4 : elem == 'n' || elem == 'q' ? 2 : 1);
					buf.position(buf.position() + len);
				}
				case '(' -> {
					align(8);
					int depth = 0;
					int i = 1;
					while (i < sig.length()) {
						final char c = sig.charAt(i);
						if (c == '(') {
							depth++;
						} else if (c == ')') {
							if (depth == 0) {
								break;
							}
							depth--;
						}
						final String one = singleType(sig, i);
						skip(one);
						i += one.length();
					}
				}
				default -> throw new IllegalArgumentException("cannot skip signature " + sig);
			}
		}

		/** The complete single type starting at {@code i} in a signature (e.g. {@code a(us)} from {@code a(us)ii}). */
		public static String singleType(final String sig, final int i) {
			final char c = sig.charAt(i);
			if (c == 'a') {
				return "a" + singleType(sig, i + 1);
			}
			if (c == '(' || c == '{') {
				final char close = c == '(' ? ')' : '}';
				int depth = 0;
				for (int j = i; j < sig.length(); j++) {
					if (sig.charAt(j) == c) {
						depth++;
					} else if (sig.charAt(j) == close && --depth == 0) {
						return sig.substring(i, j + 1);
					}
				}
			}
			return String.valueOf(c);
		}
	}

	/**
	 * Parses one message from a buffer that holds at least the whole message. Returns null if more bytes are
	 * needed; {@code consumed[0]} receives the message length on success.
	 */
	public static @Nullable DbusMessage decode(final byte[] data, final int offset, final int available, final int[] consumed) {
		if (available < 16) {
			return null;
		}
		final ByteBuffer hb = ByteBuffer.wrap(data, offset, available).order(ByteOrder.LITTLE_ENDIAN);
		final byte endian = hb.get(offset);
		if (endian != 'l') {
			throw new IllegalStateException("big-endian D-Bus message; unsupported");
		}
		final int bodyLen = hb.getInt(offset + 4);
		final int fieldsLen = hb.getInt(offset + 12);
		final int headerLen = (16 + fieldsLen + 7) & ~7;
		final int total = headerLen + bodyLen;
		if (available < total) {
			return null;
		}
		final DbusMessage m = new DbusMessage();
		m.type = hb.get(offset + 1);
		m.flags = hb.get(offset + 2);
		m.serial = hb.getInt(offset + 8);
		final Reader r = new Reader(Arrays.copyOfRange(data, offset + 16, offset + 16 + fieldsLen));
		while (r.hasRemaining()) {
			r.align(8);
			if (!r.hasRemaining()) {
				break;
			}
			final int code = r.getByte();
			final String sig = r.getSignature();
			switch (code) {
				case FIELD_PATH -> m.path = r.getString();
				case FIELD_INTERFACE -> m.iface = r.getString();
				case FIELD_MEMBER -> m.member = r.getString();
				case FIELD_ERROR_NAME -> m.errorName = r.getString();
				case FIELD_REPLY_SERIAL -> m.replySerial = r.getU32();
				case FIELD_DESTINATION -> m.destination = r.getString();
				case FIELD_SENDER -> m.sender = r.getString();
				case FIELD_SIGNATURE -> m.signature = r.getSignature();
				case FIELD_UNIX_FDS -> m.unixFds = r.getU32();
				default -> r.skip(sig);
			}
		}
		m.body = Arrays.copyOfRange(data, offset + headerLen, offset + total);
		consumed[0] = total;
		return m;
	}

	// ------------------------------------------------------------------------------------------------
	// Convenience constructors
	// ------------------------------------------------------------------------------------------------

	public static DbusMessage methodCall(final String path, final String iface, final String member, final String signature, final byte[] body) {
		final DbusMessage m = new DbusMessage();
		m.type = METHOD_CALL;
		m.path = path;
		m.iface = iface;
		m.member = member;
		m.signature = signature;
		m.body = body;
		return m;
	}

	public static DbusMessage methodReturn(final DbusMessage call, final String signature, final byte[] body) {
		final DbusMessage m = new DbusMessage();
		m.type = METHOD_RETURN;
		m.replySerial = call.serial;
		m.destination = call.sender;
		m.signature = signature;
		m.body = body;
		return m;
	}

	public static DbusMessage error(final DbusMessage call, final String name, final String text) {
		final DbusMessage m = new DbusMessage();
		m.type = ERROR;
		m.replySerial = call.serial;
		m.destination = call.sender;
		m.errorName = name;
		m.signature = "s";
		m.body = new Writer().putString(text).toBytes();
		return m;
	}
}
