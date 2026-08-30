package dev.virtualminecraft.computer;

import dev.virtualminecraft.util.Nums;
import java.util.HashMap;
import java.util.Map;

/**
 * The Computer's sound chip (ROADMAP §7h §5): four synthesizer channels (square with duty, triangle, sawtooth,
 * sine, noise; ADSR envelope; pitch slide) and two sample channels (8-bit unsigned PCM at any rate ≤ 48 kHz,
 * ≤ 64 KB each, ≤ 16 loaded), a master volume, mixed on the server tick into 16-bit mono at
 * {@link #RATE} Hz for the existing μ-law stream. The worker sets parameters through the synchronized setters;
 * the server thread calls {@link #mixTick()} once per tick and gets {@code null} while everything is silent —
 * silence sends nothing. Channels are 0-based here; the Lua library is 1-based (1–4 synth, 5–6 samples).
 */
public final class SoundChip {
	public static final int RATE = 22050;
	public static final int SYNTH_CHANNELS = 4;
	public static final int SAMPLE_CHANNELS = 2;
	public static final int CHANNELS = SYNTH_CHANNELS + SAMPLE_CHANNELS;
	public static final int MAX_SAMPLES = 16;
	public static final int MAX_SAMPLE_BYTES = 64 << 10;
	public static final int WAVE_SQUARE = 0;
	public static final int WAVE_TRIANGLE = 1;
	public static final int WAVE_SAW = 2;
	public static final int WAVE_SINE = 3;
	public static final int WAVE_NOISE = 4;
	private static final double SAMPLES_PER_TICK = RATE / 20.0;
	/** Per-channel amplitude so six channels at full volume just about clip. */
	private static final double AMPLITUDE = 5400;
	/**
	 * Ticks of silence still sent after the last active one: the client starts a source only with two buffers
	 * queued, so a stream that stops at every rest would restart (and underrun) at every note. A second of
	 * silence bridges the rests inside a phrase; a machine that stays quiet sends nothing.
	 */
	static final int TAIL_TICKS = 20;

	private record Sample(byte[] data, int rate) {
	}

	private static final class Channel {
		// synth
		int wave;
		double freq;
		double slideTarget;
		double slidePerSample;
		double duty = 0.5;
		double vol;
		double attack;
		double decay;
		double sustain = 1;
		double release;
		/** 0 off, 1 attack, 2 decay, 3 sustain, 4 release. */
		int stage;
		double env;
		double phase;
		double noiseValue;
		long noiseState = 0x9E3779B97F4A7C15L;
		// sample
		Sample sample;
		double pos;
		double step;
		boolean loop;

		/** A channel above the case's tier count is wired to nothing: notes and samples sent to it play silently (never an error). */
		boolean enabled = true;

		boolean active() {
			return stage != 0 || sample != null;
		}
	}

	private final Channel[] channels = new Channel[CHANNELS];
	private final Map<Integer, Sample> samples = new HashMap<>();
	private double master = 1;
	private double carry;
	private long mixedSamples;
	private int tailTicks;

	public SoundChip() {
		for (int i = 0; i < CHANNELS; i++) {
			channels[i] = new Channel();
		}
	}

	/** The tier ladder (ROADMAP §9 U3b): how many synth voices and sample channels this chip plays. Others go quiet. */
	public synchronized void setChannels(final int synth, final int sample) {
		for (int i = 0; i < CHANNELS; i++) {
			final boolean on = i < SYNTH_CHANNELS ? i < synth : i - SYNTH_CHANNELS < sample;
			channels[i].enabled = on;
			if (!on) {
				channels[i].stage = 0;
				channels[i].env = 0;
				channels[i].sample = null;
			}
		}
	}

	public synchronized int enabledSynth() {
		int n = 0;
		for (int i = 0; i < SYNTH_CHANNELS; i++) {
			if (channels[i].enabled) {
				n++;
			}
		}
		return n;
	}

	private Channel synth(final int c) {
		if (c < 0 || c >= SYNTH_CHANNELS) {
			throw new IllegalArgumentException("synth channel must be 1.." + SYNTH_CHANNELS);
		}
		return channels[c];
	}

	private Channel sampler(final int c) {
		if (c < SYNTH_CHANNELS || c >= CHANNELS) {
			throw new IllegalArgumentException("sample channel must be " + (SYNTH_CHANNELS + 1) + ".." + CHANNELS);
		}
		return channels[c];
	}

	/** Starts (or retriggers) a note; times in seconds, volume 0..1, duty 0..1 for square. */
	public synchronized void channel(final int c, final int wave, final double freq, final double vol, final double attack, final double decay, final double sustain, final double release, final double duty) {
		final Channel ch = synth(c);
		if (!ch.enabled) {
			return;
		}
		ch.wave = Nums.clamp(wave, WAVE_SQUARE, WAVE_NOISE);
		ch.freq = Nums.clamp(freq, 0, RATE / 2.0);
		ch.slideTarget = ch.freq;
		ch.slidePerSample = 0;
		ch.vol = Nums.clamp(vol, 0, 1);
		ch.attack = Math.max(0, attack);
		ch.decay = Math.max(0, decay);
		ch.sustain = Nums.clamp(sustain, 0, 1);
		ch.release = Math.max(0, release);
		ch.duty = Nums.clamp(duty, 0.01, 0.99);
		if (ch.stage == 0) {
			ch.env = 0;
			ch.phase = 0;
		}
		ch.stage = 1;
	}

	/** Enters the release stage. */
	public synchronized void noteOff(final int c) {
		final Channel ch = synth(c);
		if (ch.stage != 0) {
			ch.stage = 4;
		}
	}

	/** Slides the pitch to {@code freq} over {@code seconds}. */
	public synchronized void slide(final int c, final double freq, final double seconds) {
		final Channel ch = synth(c);
		ch.slideTarget = Nums.clamp(freq, 0, RATE / 2.0);
		final double n = Math.max(1, seconds * RATE);
		ch.slidePerSample = (ch.slideTarget - ch.freq) / n;
	}

	public synchronized void loadSample(final int id, final byte[] data, final int rate) {
		if (data.length > MAX_SAMPLE_BYTES) {
			throw new IllegalArgumentException("sample larger than " + (MAX_SAMPLE_BYTES >> 10) + " KB");
		}
		if (!samples.containsKey(id) && samples.size() >= MAX_SAMPLES) {
			throw new IllegalArgumentException("no more than " + MAX_SAMPLES + " samples loaded");
		}
		samples.put(id, new Sample(data, Nums.clamp(rate, 1000, 48000)));
	}

	public synchronized void playSample(final int c, final int id, final double vol, final boolean loop) {
		final Channel ch = sampler(c);
		final Sample s = samples.get(id);
		if (s == null) {
			throw new IllegalArgumentException("no sample " + id);
		}
		if (!ch.enabled) {
			return;
		}
		ch.sample = s;
		ch.pos = 0;
		ch.step = s.rate / (double) RATE;
		ch.vol = Nums.clamp(vol, 0, 1);
		ch.loop = loop;
	}

	/** Silences one channel (0-based) or, with -1, all of them. */
	public synchronized void stop(final int c) {
		if (c < 0) {
			for (final Channel ch : channels) {
				ch.stage = 0;
				ch.env = 0;
				ch.sample = null;
			}
			return;
		}
		if (c >= CHANNELS) {
			throw new IllegalArgumentException("channel must be 1.." + CHANNELS);
		}
		channels[c].stage = 0;
		channels[c].env = 0;
		channels[c].sample = null;
	}

	public synchronized void master(final double v) {
		master = Nums.clamp(v, 0, 1);
	}

	public synchronized double master() {
		return master;
	}

	public synchronized boolean active() {
		for (final Channel ch : channels) {
			if (ch.active()) {
				return true;
			}
		}
		return false;
	}

	public synchronized long mixedSamples() {
		return mixedSamples;
	}

	public synchronized int loadedSamples() {
		return samples.size();
	}

	/**
	 * One server tick of output as 16-bit little-endian mono PCM, or null when every channel has been silent for
	 * {@link #TAIL_TICKS}.
	 */
	public synchronized byte[] mixTick() {
		final boolean active = active();
		if (!active && tailTicks == 0) {
			carry = 0;
			return null;
		}
		carry += SAMPLES_PER_TICK;
		final int n = (int) carry;
		carry -= n;
		final byte[] out = new byte[n * 2];
		if (!active) {
			tailTicks--;
			mixedSamples += n;
			return out;
		}
		tailTicks = TAIL_TICKS;
		final double[] mix = new double[n];
		for (final Channel ch : channels) {
			if (ch.sample != null) {
				mixSample(ch, mix);
			} else if (ch.stage != 0) {
				mixSynth(ch, mix);
			}
		}
		for (int i = 0; i < n; i++) {
			final int v = (int) Nums.clamp(Math.round(mix[i] * master), -32768, 32767);
			out[2 * i] = (byte) v;
			out[2 * i + 1] = (byte) (v >> 8);
		}
		mixedSamples += n;
		return out;
	}

	private static void mixSynth(final Channel ch, final double[] mix) {
		final double attackStep = ch.attack > 0 ? 1.0 / (ch.attack * RATE) : 1;
		final double decayStep = ch.decay > 0 ? (1 - ch.sustain) / (ch.decay * RATE) : 1;
		final double releaseStep = ch.release > 0 ? 1.0 / (ch.release * RATE) : 1;
		for (int i = 0; i < mix.length; i++) {
			// envelope
			switch (ch.stage) {
				case 1 -> {
					ch.env += attackStep;
					if (ch.env >= 1) {
						ch.env = 1;
						ch.stage = 2;
					}
				}
				case 2 -> {
					ch.env -= decayStep;
					if (ch.env <= ch.sustain) {
						ch.env = ch.sustain;
						// A note that decays to a sustain of zero is over, and holding it in sustain instead is
						// not harmless: `active()` stays true, so `mixTick()` returns a buffer of silence every
						// tick for ever, and the machine keeps sending ~2 KB of u-law to every player in range
						// until something else stops the channel. snd.beep passes sustain 0, so one beep was
						// enough to do it. (Found writing the chassis voice's test, U5, session 21.)
						ch.stage = ch.sustain <= 0 ? 0 : 3;
						if (ch.stage == 0) {
							return;
						}
					}
				}
				case 3 -> {
					// sustain: hold
				}
				case 4 -> {
					ch.env -= releaseStep;
					if (ch.env <= 0) {
						ch.env = 0;
						ch.stage = 0;
						return;
					}
				}
				default -> {
					return;
				}
			}
			// pitch slide
			if (ch.slidePerSample != 0) {
				ch.freq += ch.slidePerSample;
				if ((ch.slidePerSample > 0 && ch.freq >= ch.slideTarget) || (ch.slidePerSample < 0 && ch.freq <= ch.slideTarget)) {
					ch.freq = ch.slideTarget;
					ch.slidePerSample = 0;
				}
			}
			// oscillator
			final double p = ch.phase;
			final double v = switch (ch.wave) {
				case WAVE_SQUARE -> p < ch.duty ? 1 : -1;
				case WAVE_TRIANGLE -> 1 - 4 * Math.abs(p - 0.5);
				case WAVE_SAW -> 2 * p - 1;
				case WAVE_SINE -> Math.sin(2 * Math.PI * p);
				default -> ch.noiseValue;
			};
			mix[i] += v * ch.env * ch.vol * AMPLITUDE;
			ch.phase += ch.freq / RATE;
			if (ch.phase >= 1) {
				ch.phase -= Math.floor(ch.phase);
				if (ch.wave == WAVE_NOISE) {
					// xorshift64: a new random level each cycle, so the pitch sets the noise colour
					long x = ch.noiseState;
					x ^= x << 13;
					x ^= x >>> 7;
					x ^= x << 17;
					ch.noiseState = x;
					ch.noiseValue = ((x >>> 11) & 0xFFFF) / 32768.0 - 1;
				}
			}
		}
	}

	private static void mixSample(final Channel ch, final double[] mix) {
		final byte[] data = ch.sample.data;
		for (int i = 0; i < mix.length; i++) {
			final int idx = (int) ch.pos;
			if (idx >= data.length) {
				if (ch.loop && data.length > 0) {
					ch.pos -= data.length;
					i--;
					continue;
				}
				ch.sample = null;
				return;
			}
			mix[i] += ((data[idx] & 0xFF) - 128) / 128.0 * ch.vol * AMPLITUDE;
			ch.pos += ch.step;
		}
		if (!ch.loop && ch.pos >= data.length) {
			ch.sample = null; // ended exactly on a tick boundary: do not stay "active" for a silent tick
		}
	}
}
