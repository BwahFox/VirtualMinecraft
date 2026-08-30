package dev.virtualminecraft.client.audio;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.audio.ULaw;
import dev.virtualminecraft.net.AudioPayload;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

/**
 * Plays VM audio through Minecraft's OpenAL context as a streaming source per VM, positioned at the computer block
 * (or head-locked while the VM screen is open). Called on the client thread; OpenAL Soft is thread-safe and the
 * context is process-global, so we can share it with the sound engine.
 */
public final class VmAudio {
	private static final int MAX_QUEUED_BUFFERS = 12; // ~0.6 s at 50 ms chunks
	private static final int IDLE_TICKS = 20 * 10;
	private static final float MAX_DISTANCE = 32f;

	private static final class Source {
		int id = -1;
		long lastData;
		Vec3 position = Vec3.ZERO;
		boolean relative;
	}

	private static final Map<UUID, Source> SOURCES = new HashMap<>();
	private static long tick;
	/**
	 * Failure handling: an OpenAL error drops the source it happened on and holds off for {@link #RETRY_TICKS}, doubling
	 * each consecutive failure; only {@link #MAX_FAILURES} in a row disable audio, and a world change clears everything.
	 * (Until 2026-08-26 one error was a permanent latch for the session — the suspect for "sound dead after a reload".)
	 */
	private static final int RETRY_TICKS = 40;
	private static final int MAX_FAILURES = 6;
	private static int failures;
	private static long retryAt;

	private VmAudio() {
	}

	public static void onPayload(final AudioPayload p) {
		if (p.pos() != null) {
			setPosition(p.vm(), Vec3.atCenterOf(p.pos()));
		}
		push(p.vm(), p.ulaw());
	}

	private static boolean alBroken() {
		return failures >= MAX_FAILURES || tick < retryAt;
	}

	public static void push(final UUID vm, final byte[] ulaw) {
		if (alBroken() || ulaw.length == 0) {
			return;
		}
		final Source s = SOURCES.computeIfAbsent(vm, u -> new Source());
		s.lastData = tick;
		try {
			// Somebody else's pending error (the game's own sound engine shares the context) must not be pinned on us.
			final int stale = AL10.alGetError();
			if (stale != AL10.AL_NO_ERROR) {
				VirtualMinecraft.LOGGER.debug("OpenAL had a pending error {} before VM audio touched it", stale);
			}
			if (s.id < 0) {
				s.id = AL10.alGenSources();
				AL10.alSourcei(s.id, 53248, 53251); // AL_DISTANCE_MODEL = AL_LINEAR_DISTANCE_CLAMPED (as vanilla)
				AL10.alSourcef(s.id, AL10.AL_MAX_DISTANCE, MAX_DISTANCE);
				AL10.alSourcef(s.id, AL10.AL_ROLLOFF_FACTOR, 1f);
				AL10.alSourcef(s.id, AL10.AL_REFERENCE_DISTANCE, 0f);
				applyPlacement(s);
			}
			// Drop processed buffers, and if we are falling behind, drop the backlog too.
			// A source that ran dry (a pause, a song's end, any gap longer than the queue) is AL_STOPPED, and a stopped
			// source reports *every* buffer as processed — including ones queued after it stopped. Left alone, each
			// push here would unqueue the previous buffer, the queue could never reach the two the start rule below
			// wants, and the source stayed silent until the idle release ten seconds later ("sound dead after a
			// pause / reload", 0i). Rewinding it to AL_INITIAL makes new buffers pending again.
			final int state = AL10.alGetSourcei(s.id, AL10.AL_SOURCE_STATE);
			final int processed = state == AL10.AL_STOPPED ? AL10.alGetSourcei(s.id, AL10.AL_BUFFERS_QUEUED) : AL10.alGetSourcei(s.id, AL10.AL_BUFFERS_PROCESSED);
			if (processed > 0) {
				final int[] ids = new int[processed];
				AL10.alSourceUnqueueBuffers(s.id, ids);
				AL10.alDeleteBuffers(ids);
			}
			if (state == AL10.AL_STOPPED) {
				AL10.alSourceRewind(s.id);
			}
			if (AL10.alGetSourcei(s.id, AL10.AL_BUFFERS_QUEUED) >= MAX_QUEUED_BUFFERS) {
				return;
			}
			final short[] pcm = ULaw.decode(ulaw);
			final ShortBuffer buf = MemoryUtil.memAllocShort(pcm.length);
			try {
				buf.put(pcm).flip();
				final int b = AL10.alGenBuffers();
				AL10.alBufferData(b, AL10.AL_FORMAT_MONO16, buf, AudioPayload.SAMPLE_RATE);
				AL10.alSourceQueueBuffers(s.id, b);
			} finally {
				MemoryUtil.memFree(buf);
			}
			AL10.alSourcef(s.id, AL10.AL_GAIN, volume());
			if (AL10.alGetSourcei(s.id, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING && AL10.alGetSourcei(s.id, AL10.AL_BUFFERS_QUEUED) >= 2) {
				AL10.alSourcePlay(s.id);
			}
			final int err = AL10.alGetError();
			if (err != AL10.AL_NO_ERROR) {
				fail(s, "OpenAL error 0x" + Integer.toHexString(err) + " while streaming VM audio");
			} else {
				failures = 0;
			}
		} catch (final Throwable t) {
			fail(s, "VM audio failed: " + t);
		}
	}

	/** Drop the source the error happened on and back off; give up only after {@link #MAX_FAILURES} in a row. */
	private static void fail(final Source s, final String what) {
		release(s);
		failures++;
		if (failures >= MAX_FAILURES) {
			VirtualMinecraft.LOGGER.warn("{}; {} failures in a row, disabling VM audio until the next world", what, failures);
		} else {
			retryAt = tick + ((long) RETRY_TICKS << (failures - 1));
			VirtualMinecraft.LOGGER.warn("{}; retrying in {} s", what, (retryAt - tick) / 20);
		}
	}

	private static float volume() {
		final Minecraft mc = Minecraft.getInstance();
		return mc.options.getSoundSourceVolume(SoundSource.MASTER) * mc.options.getSoundSourceVolume(SoundSource.BLOCKS);
	}

	/** World position the sound comes from (the computer block). */
	public static void setPosition(final UUID vm, final Vec3 pos) {
		final Source s = SOURCES.get(vm);
		if (s == null) {
			final Source n = new Source();
			n.position = pos;
			n.lastData = tick;
			SOURCES.put(vm, n);
			return;
		}
		if (!s.position.equals(pos)) {
			s.position = pos;
			if (!s.relative) {
				applyPlacement(s);
			}
		}
	}

	/** Head-locked, full volume: used while the VM screen is open. */
	public static void setRelative(final UUID vm, final boolean relative) {
		final Source s = SOURCES.computeIfAbsent(vm, u -> new Source());
		if (s.relative != relative) {
			s.relative = relative;
			applyPlacement(s);
		}
	}

	private static void applyPlacement(final Source s) {
		if (s.id < 0 || alBroken()) {
			return;
		}
		AL10.alSourcei(s.id, AL10.AL_SOURCE_RELATIVE, s.relative ? 1 : 0);
		if (s.relative) {
			AL10.alSource3f(s.id, AL10.AL_POSITION, 0f, 0f, 0f);
		} else {
			AL10.alSource3f(s.id, AL10.AL_POSITION, (float) s.position.x, (float) s.position.y, (float) s.position.z);
		}
	}

	public static void clientTick() {
		tick++;
		if (alBroken()) {
			return;
		}
		final Iterator<Map.Entry<UUID, Source>> it = SOURCES.entrySet().iterator();
		while (it.hasNext()) {
			final Source s = it.next().getValue();
			if (tick - s.lastData > IDLE_TICKS) {
				release(s);
				it.remove();
			} else if (s.id >= 0) {
				AL10.alSourcef(s.id, AL10.AL_GAIN, volume());
			}
		}
	}

	private static void release(final Source s) {
		if (s.id >= 0) {
			try {
				AL10.alSourceStop(s.id);
				final int queued = AL10.alGetSourcei(s.id, AL10.AL_BUFFERS_QUEUED);
				if (queued > 0) {
					final int[] ids = new int[queued];
					AL10.alSourceUnqueueBuffers(s.id, ids);
					AL10.alDeleteBuffers(ids);
				}
				AL10.alDeleteSources(s.id);
			} catch (final Throwable ignored) {
			}
			s.id = -1;
		}
	}

	/** Development aid for the puppet: one line per source. */
	public static String debugState() {
		final StringBuilder sb = new StringBuilder(alBroken() ? "AL broken (failures=" + failures + " retryIn=" + Math.max(0, retryAt - tick) + ") " : "");
		sb.append("sources=").append(SOURCES.size());
		for (final Map.Entry<UUID, Source> e : SOURCES.entrySet()) {
			final Source s = e.getValue();
			sb.append(" [").append(e.getKey().toString(), 0, 8).append(" id=").append(s.id);
			if (s.id >= 0) {
				sb.append(" queued=").append(AL10.alGetSourcei(s.id, AL10.AL_BUFFERS_QUEUED))
					.append(" state=").append(AL10.alGetSourcei(s.id, AL10.AL_SOURCE_STATE))
					.append(" gain=").append(AL10.alGetSourcef(s.id, AL10.AL_GAIN));
			}
			sb.append(" rel=").append(s.relative).append(" pos=").append(s.position).append(" idle=").append(tick - s.lastData).append(']');
		}
		return sb.toString();
	}

	/** Leaving a world: release everything, and a fresh world gets a fresh chance at the sound device. */
	public static void clear() {
		for (final Source s : SOURCES.values()) {
			release(s);
		}
		SOURCES.clear();
		failures = 0;
		retryAt = 0;
	}
}
