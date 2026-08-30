package dev.virtualminecraft.computer;

import dev.virtualminecraft.util.Nums;

/**
 * The machine's <em>hardware</em> voice (ROADMAP §9 U5): the case, the fan and the drives, as opposed to the
 * {@link SoundChip} Lua drives through {@code snd.*}. Until this existed a running Computer was indistinguishable
 * from a dead one until you looked at the screen, which is most of what OpenComputers' machines have and ours
 * did not.
 * <p>
 * <b>It is a second chip, and Lua cannot reach it.</b> The block entity owns it and the mod drives it, so a
 * program can neither fake a fan nor silence one. Its output is mixed into the same tick buffer before the μ-law
 * encode ({@link #mix}), so it shares the machine's single OpenAL source: no client change, no second stream,
 * and it is positional and range-limited for free.
 * <p>
 * <b>Everything is synthesised, so the mod still ships no audio assets.</b> OpenComputers' sounds are
 * OpenComputers'; the standing rule forbids redistributing anyone else's work, and hunting down CC0 recordings
 * would add licence surface when a synth is already sitting here.
 * <p>
 * Two rules the design is built against, both learned the hard way elsewhere in this project:
 * <ol>
 * <li><b>A frozen, sleeping or unheard machine goes silent.</b> A hum that keeps playing after a machine freezes
 * is the audio version of the framebuffer-park bug (§7g): it burns bandwidth and holds a source open forever.
 * {@link #silence()} is called on freeze, and the caller does not even mix a tick when nobody is in range.</li>
 * <li><b>The chassis never drowns the software chip.</b> A game's music has to win, so this chip has its own
 * master ({@code computerChassisVolume}, {@code 0} disables it outright) and every level here is set well under
 * the software chip's.</li>
 * </ol>
 */
public final class ChassisVoice {
	/** The fan bed: on while the machine runs, its colour following the CPU share. */
	private static final int CH_HUM = 0;
	/** Drive seek and access: short filtered noise bursts. */
	private static final int CH_DRIVE = 1;
	/** POST beep and the power relay. */
	private static final int CH_BEEP = 2;
	/** Insert and eject. */
	private static final int CH_CLUNK = 3;

	/** Ticks between drive clicks, so a program in a read loop sounds busy rather than like a wasp. */
	private static final int DRIVE_COOLDOWN_TICKS = 3;

	private final SoundChip chip = new SoundChip();
	private double volume;
	private boolean humming;
	private int humBucket = -1;
	private int driveCooldown;

	public ChassisVoice(final double volume) {
		setVolume(volume);
	}

	/** {@code computerChassisVolume}: 0 turns the hardware voice off entirely, which is a thing some players will want. */
	public void setVolume(final double v) {
		volume = Nums.clamp(v, 0, 1);
		chip.master(volume);
	}

	public boolean enabled() {
		return volume > 0;
	}

	/** The "it lives" sound: one square blip when a machine boots cold. */
	public void post() {
		if (!enabled()) {
			return;
		}
		chip.channel(CH_BEEP, SoundChip.WAVE_SQUARE, 880, 0.35, 0.002, 0.09, 0, 0.04, 0.5);
	}

	/** The power relay, on start and on stop: a low thunk with a click of contact noise on top. */
	public void relay(final boolean on) {
		if (!enabled()) {
			return;
		}
		chip.channel(CH_BEEP, SoundChip.WAVE_SQUARE, on ? 120 : 90, 0.30, 0.001, 0.05, 0, 0.03, 0.25);
		chip.channel(CH_CLUNK, SoundChip.WAVE_NOISE, 2600, 0.22, 0.001, 0.035, 0, 0.02, 0.5);
	}

	/** A disk going in or coming out. Both hooks already existed; this is the noise they were missing. */
	public void clunk(final boolean in) {
		if (!enabled()) {
			return;
		}
		chip.channel(CH_CLUNK, SoundChip.WAVE_NOISE, in ? 1400 : 1900, 0.30, 0.002, 0.10, 0, 0.05, 0.5);
		chip.channel(CH_BEEP, SoundChip.WAVE_TRIANGLE, in ? 150 : 200, 0.22, 0.002, 0.07, 0, 0.04, 0.5);
	}

	/**
	 * The drive is thinking: a short filtered noise burst per batch of file operations. Reads and writes get
	 * different colours, because a machine writing sounds different from a machine reading and that is exactly
	 * the sort of thing you learn to hear without noticing you learned it.
	 */
	public void drive(final boolean write) {
		if (!enabled() || driveCooldown > 0) {
			return;
		}
		driveCooldown = DRIVE_COOLDOWN_TICKS;
		chip.channel(CH_DRIVE, SoundChip.WAVE_NOISE, write ? 5200 : 3800, 0.16, 0.001, 0.045, 0, 0.02, 0.5);
	}

	/**
	 * The fan. {@code load} is the machine's CPU share, 0..1: it moves the noise's colour (a slide, which does
	 * not restart the envelope) and nudges the level in four coarse buckets, so a machine that is working
	 * <em>sounds</em> like it is working without the level stepping audibly every tick.
	 */
	public void hum(final boolean on, final double load) {
		if (!enabled()) {
			return;
		}
		if (!on) {
			if (humming) {
				chip.noteOff(CH_HUM);
				humming = false;
				humBucket = -1;
			}
			return;
		}
		final int bucket = (int) Nums.clamp(Math.floor(Nums.clamp(load, 0, 1) * 4), 0, 3);
		if (!humming) {
			// a long attack is the fan spinning up, and the long release below is it spinning down
			chip.channel(CH_HUM, SoundChip.WAVE_NOISE, humFreq(bucket), humVol(bucket), 0.8, 0, 1, 1.0, 0.5);
			humming = true;
			humBucket = bucket;
			return;
		}
		if (bucket != humBucket) {
			humBucket = bucket;
			// channel() on a sounding channel keeps its envelope stage and phase (SoundChip line 137), so this
			// changes the level without a click; the pitch glides rather than jumping.
			chip.channel(CH_HUM, SoundChip.WAVE_NOISE, humFreq(bucket), humVol(bucket), 0.8, 0, 1, 1.0, 0.5);
			chip.slide(CH_HUM, humFreq(bucket), 0.5);
		}
	}

	private static double humFreq(final int bucket) {
		return 220 + bucket * 90;
	}

	private static double humVol(final int bucket) {
		return 0.05 + bucket * 0.018;
	}

	/** Everything off, now: freeze, shutdown, or nobody left in earshot. */
	public void silence() {
		humming = false;
		humBucket = -1;
		driveCooldown = 0;
		chip.stop(-1);
	}

	/** One server tick of the chassis as 16-bit LE mono PCM, or null while it is silent. */
	public byte[] mixTick() {
		if (driveCooldown > 0) {
			driveCooldown--;
		}
		return enabled() ? chip.mixTick() : null;
	}

	/** For tests. */
	SoundChip chip() {
		return chip;
	}

	/**
	 * Sums two ticks of 16-bit LE mono PCM into one, clamping rather than wrapping. Either may be null, and they
	 * may differ in length by a sample: each chip keeps its own fractional-sample carry, and they are only in
	 * lockstep while both have been sounding continuously.
	 */
	public static byte[] mix(final byte[] a, final byte[] b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		final byte[] longer = a.length >= b.length ? a : b;
		final byte[] shorter = a.length >= b.length ? b : a;
		final byte[] out = longer.clone();
		final int n = shorter.length / 2;
		for (int i = 0; i < n; i++) {
			final int x = (short) ((out[2 * i] & 0xFF) | (out[2 * i + 1] << 8));
			final int y = (short) ((shorter[2 * i] & 0xFF) | (shorter[2 * i + 1] << 8));
			final int v = Nums.clamp(x + y, -32768, 32767);
			out[2 * i] = (byte) v;
			out[2 * i + 1] = (byte) (v >> 8);
		}
		return out;
	}
}
