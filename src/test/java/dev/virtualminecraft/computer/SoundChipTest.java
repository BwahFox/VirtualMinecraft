package dev.virtualminecraft.computer;

/** The sound chip outside Minecraft: waveforms, envelope, samples, silence. {@code ./gradlew soundChipTest}. */
public final class SoundChipTest {
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

	public static void main(final String[] args) {
		final SoundChip chip = new SoundChip();
		check(chip.mixTick() == null, "silent chip mixes nothing");
		chip.channel(0, SoundChip.WAVE_SQUARE, 440, 1, 0, 0, 1, 0.05, 0.5);
		final byte[] t1 = chip.mixTick();
		check(t1 != null && (t1.length == 2204 || t1.length == 2206), "a note yields one tick of PCM16 (" + (t1 == null ? 0 : t1.length / 2) + " samples)");
		check(rms(t1) > 3000, "square at full volume is loud (rms " + (int) rms(t1) + ")");
		chip.noteOff(0);
		int ticks = 0;
		for (; ticks < 40; ticks++) {
			if (rms(chip.mixTick()) == 0) {
				break;
			}
		}
		check(ticks >= 1 && ticks <= 3, "50 ms release ends within 3 ticks (" + ticks + ")");
		int tail = 0;
		while (chip.mixTick() != null) {
			tail++;
		}
		check(tail == SoundChip.TAIL_TICKS - 1, "then a second of silence is still sent (" + tail + " ticks), then nothing");
		chip.channel(1, SoundChip.WAVE_SINE, 220, 0.5, 0.1, 0, 1, 0, 0.5);
		final double first = rms(chip.mixTick());
		final double later = rms(chip.mixTick());
		check(later > first * 1.5, "attack ramps up over 100 ms (" + (int) first + " -> " + (int) later + ")");
		chip.stop(-1);
		check(rms(chip.mixTick()) == 0, "stop(all) silences the chip");
		for (int i = 0; i < SoundChip.TAIL_TICKS; i++) {
			chip.mixTick();
		}
		check(chip.mixTick() == null, "and the tail runs out");
		final byte[] blip = new byte[2205];
		for (int i = 0; i < blip.length; i++) {
			blip[i] = (byte) (128 + 100 * Math.sin(i * 0.2));
		}
		chip.loadSample(1, blip, 22050);
		chip.playSample(4, 1, 1, false);
		final double s1 = rms(chip.mixTick());
		final byte[] s2 = chip.mixTick();
		final byte[] s3 = chip.mixTick();
		check(s1 > 1000 && rms(s2) > 1000 && rms(s3) == 0, "a 100 ms sample plays for two ticks then stops (" + (int) s1 + ")");
		chip.playSample(5, 1, 1, true);
		boolean looping = true;
		for (int i = 0; i < 10; i++) {
			looping &= chip.mixTick() != null;
		}
		check(looping, "a looping sample keeps playing");
		chip.stop(5);
		boolean refused = false;
		try {
			chip.loadSample(2, new byte[SoundChip.MAX_SAMPLE_BYTES + 1], 8000);
		} catch (final IllegalArgumentException e) {
			refused = true;
		}
		check(refused, "a sample over 64 KB is refused");
		boolean badChannel = false;
		try {
			chip.channel(4, 0, 440, 1, 0, 0, 1, 0, 0.5);
		} catch (final IllegalArgumentException e) {
			badChannel = true;
		}
		check(badChannel, "a sample channel refuses synth notes");
		// cost: 1000 machines × a busy chip
		final SoundChip busy = new SoundChip();
		for (int c = 0; c < 4; c++) {
			busy.channel(c, c, 110 * (c + 1), 0.5, 0.01, 0.1, 0.7, 0.2, 0.3);
		}
		busy.loadSample(1, blip, 22050);
		busy.playSample(4, 1, 0.5, true);
		busy.playSample(5, 1, 0.5, true);
		final long t0 = System.nanoTime();
		for (int i = 0; i < 1000; i++) {
			busy.mixTick();
		}
		final double perTickUs = (System.nanoTime() - t0) / 1000.0 / 1000.0;
		check(perTickUs < 100, String.format("six busy channels mix in %.1f us per tick (< 100)", perTickUs));
		// the tier ladder (U3b): a Basic Computer has 3 voices and no sample channels -- the others play silence, never an error
		final SoundChip basic = new SoundChip();
		basic.setChannels(3, 0);
		basic.channel(2, SoundChip.WAVE_SQUARE, 440, 1, 0, 0, 1, 0.05, 0.5);
		check(basic.active(), "voice 3 plays on a 3-voice chip");
		basic.stop(-1);
		basic.channel(3, SoundChip.WAVE_SQUARE, 440, 1, 0, 0, 1, 0.05, 0.5);
		check(!basic.active(), "voice 4 is silent on a 3-voice chip");
		basic.loadSample(1, new byte[800], 8000);
		basic.playSample(4, 1, 1, false);
		check(!basic.active() && basic.enabledSynth() == 3, "a sample channel the case does not have plays nothing");
		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
