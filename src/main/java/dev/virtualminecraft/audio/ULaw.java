package dev.virtualminecraft.audio;

/** G.711 μ-law: 16-bit PCM ↔ 8 bits per sample. Cheap, good enough for "computer speaker" audio; Opus can come later. */
public final class ULaw {
	private static final int BIAS = 0x84;
	private static final int CLIP = 32635;
	private static final short[] DECODE = new short[256];

	static {
		for (int i = 0; i < 256; i++) {
			int u = ~i & 0xFF;
			int t = ((u & 0x0F) << 3) + BIAS;
			t <<= (u & 0x70) >> 4;
			DECODE[i] = (short) ((u & 0x80) != 0 ? BIAS - t : t - BIAS);
		}
	}

	private ULaw() {
	}

	public static byte encodeSample(int pcm) {
		final int sign = (pcm >> 8) & 0x80;
		if (sign != 0) {
			pcm = -pcm;
		}
		if (pcm > CLIP) {
			pcm = CLIP;
		}
		pcm += BIAS;
		int exponent = 7;
		for (int mask = 0x4000; (pcm & mask) == 0 && exponent > 0; exponent--, mask >>= 1) {
		}
		final int mantissa = (pcm >> (exponent + 3)) & 0x0F;
		return (byte) ~(sign | (exponent << 4) | mantissa);
	}

	/** Encodes little-endian signed 16-bit PCM. */
	public static byte[] encode(final byte[] s16le, final int off, final int len) {
		final int n = len / 2;
		final byte[] out = new byte[n];
		for (int i = 0; i < n; i++) {
			final int lo = s16le[off + i * 2] & 0xFF;
			final int hi = s16le[off + i * 2 + 1];
			out[i] = encodeSample((hi << 8) | lo);
		}
		return out;
	}

	/** Decodes to signed 16-bit samples. */
	public static short[] decode(final byte[] ulaw) {
		final short[] out = new short[ulaw.length];
		for (int i = 0; i < ulaw.length; i++) {
			out[i] = DECODE[ulaw[i] & 0xFF];
		}
		return out;
	}
}
