package dev.virtualminecraft.computer;

/**
 * The hardware voice outside Minecraft (ROADMAP §9 U5): that each noise actually makes sound, that the fan stops
 * when told to, that volume 0 is really off, and that summing two chips clamps instead of wrapping.
 * {@code ./gradlew chassisVoiceTest}.
 */
public final class ChassisVoiceTest {
	private static int failures;

	private static void check(final boolean ok, final String what) {
		System.out.println("  " + (ok ? "PASS " : "FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}

	private static double rms(final byte[] pcm) {
		if (pcm == null) {
			return 0;
		}
		double sum = 0;
		final int n = pcm.length / 2;
		for (int i = 0; i < n; i++) {
			final short v = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
			sum += (double) v * v;
		}
		return Math.sqrt(sum / Math.max(1, n));
	}

	/** Mixes ticks until the voice goes quiet, and says how many that took. */
	private static int drain(final ChassisVoice v, final int limit) {
		for (int i = 0; i < limit; i++) {
			if (v.mixTick() == null) {
				return i;
			}
		}
		return limit;
	}

	private static double loudest(final ChassisVoice v, final int ticks) {
		double best = 0;
		for (int i = 0; i < ticks; i++) {
			best = Math.max(best, rms(v.mixTick()));
		}
		return best;
	}

	public static void main(final String[] args) {
		System.out.println("Chassis voice");

		final ChassisVoice v = new ChassisVoice(1.0);
		check(v.mixTick() == null, "a chassis nobody has poked is silent");

		v.post();
		check(loudest(v, 3) > 200, "the POST beep is audible");
		check(drain(v, 200) < 200, "and it ends");

		v.relay(true);
		check(loudest(v, 3) > 200, "the power relay is audible");
		drain(v, 200);

		v.clunk(true);
		final double in = loudest(v, 4);
		drain(v, 200);
		v.clunk(false);
		final double out = loudest(v, 4);
		drain(v, 200);
		check(in > 200 && out > 200, "insert and eject both make a noise");

		// The drive's cooldown: a program in a read loop asks every tick and gets a click every few.
		v.drive(false);
		final double firstClick = loudest(v, 1);
		int clicks = 0;
		for (int i = 0; i < 12; i++) {
			final double before = rms(v.mixTick());
			v.drive(false);
			final double after = rms(v.mixTick());
			if (after > before * 1.5 + 50) {
				clicks++;
			}
		}
		check(firstClick > 100, "a drive access clicks");
		check(clicks <= 6, "and asking every tick does not click every tick (" + clicks + " in 12)");
		v.silence();
		drain(v, 200);

		// The fan: on until told otherwise, then it spins down and stops.
		v.hum(true, 0.0);
		double humLevel = 0;
		for (int i = 0; i < 40; i++) {
			humLevel = rms(v.mixTick());
		}
		check(humLevel > 50, "the fan hum keeps sounding while it is on (rms " + (int) humLevel + ")");
		v.hum(true, 1.0);
		double busyLevel = 0;
		for (int i = 0; i < 40; i++) {
			busyLevel = rms(v.mixTick());
		}
		check(busyLevel > humLevel, "a busy machine's fan is louder than an idle one's ("
			+ (int) humLevel + " -> " + (int) busyLevel + ")");
		v.hum(false, 0);
		check(drain(v, 400) < 400, "and it stops when the machine does");

		// Rule 1 of the design: a frozen machine goes silent, whatever was sounding.
		v.hum(true, 0.5);
		v.post();
		v.mixTick();
		v.silence();
		check(drain(v, 200) < 200, "silence() ends everything at once");

		// Rule 2: the chassis must never drown the software chip.
		final ChassisVoice loud = new ChassisVoice(1.0);
		loud.hum(true, 1.0);
		double chassisPeak = 0;
		for (int i = 0; i < 60; i++) {
			chassisPeak = Math.max(chassisPeak, rms(loud.mixTick()));
		}
		final SoundChip music = new SoundChip();
		music.channel(0, SoundChip.WAVE_SQUARE, 440, 1, 0, 0, 1, 0.05, 0.5);
		final double musicLevel = rms(music.mixTick());
		check(chassisPeak * 4 < musicLevel, "the fan at full is far under one note of the software chip ("
			+ (int) chassisPeak + " vs " + (int) musicLevel + ")");

		// Volume 0 is really off, which is the config a player who hates fan noise will set.
		final ChassisVoice off = new ChassisVoice(0);
		check(!off.enabled(), "volume 0 reports itself disabled");
		off.post();
		off.relay(true);
		off.hum(true, 1);
		off.clunk(true);
		off.drive(true);
		boolean quiet = true;
		for (int i = 0; i < 30; i++) {
			if (rms(off.mixTick()) != 0) {
				quiet = false;
			}
		}
		check(quiet, "and nothing it is asked to play makes a sound");

		// mix(): nulls, clamping, and the odd-sample-out between two chips with their own carry.
		check(ChassisVoice.mix(null, null) == null, "mixing two silences is silence");
		final byte[] one = { 0x00, 0x40 };                       // +16384
		check(java.util.Arrays.equals(ChassisVoice.mix(one, null), one), "mixing with silence returns the other");
		check(java.util.Arrays.equals(ChassisVoice.mix(null, one), one), "...either way round");
		final byte[] sum = ChassisVoice.mix(one, one);
		check(((short) ((sum[0] & 0xFF) | (sum[1] << 8))) == 32767, "two loud halves clamp to the ceiling, not past it");
		final byte[] low = { 0x00, (byte) 0xC0 };                // -16384
		final byte[] sumLow = ChassisVoice.mix(low, low);
		check(((short) ((sumLow[0] & 0xFF) | (sumLow[1] << 8))) == -32768, "and to the floor, not past it");
		final byte[] longer = { 0x00, 0x10, 0x00, 0x10 };
		final byte[] shorter = { 0x00, 0x10 };
		final byte[] uneven = ChassisVoice.mix(longer, shorter);
		check(uneven.length == 4, "a tick that is one sample longer keeps its length");
		check(((short) ((uneven[0] & 0xFF) | (uneven[1] << 8))) == 0x2000, "its overlap is summed");
		check(((short) ((uneven[2] & 0xFF) | (uneven[3] << 8))) == 0x1000, "and its tail is left alone");

		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
		if (failures > 0) {
			System.exit(1);
		}
	}
}
