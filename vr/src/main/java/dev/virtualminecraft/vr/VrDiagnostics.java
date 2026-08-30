package dev.virtualminecraft.vr;

import dev.virtualminecraft.client.pointer.PointerRay;
import dev.virtualminecraft.client.pointer.WorldPointer;
import dev.virtualminecraft.config.VmcConfig;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vivecraft.api.client.VRClientAPI;

/**
 * What the controller actually did, written to the log (ROADMAP §9 U4.2).
 * <p>
 * <b>Why this exists.</b> [name], 2026-08-29: <i>"I actually have no way of controlling my desktop once the headset
 * is on, so I won't see anything you say to me while it's active."</i> That is not a small detail — it means the
 * usual loop (try it, describe it, adjust, try again) does not close, and every number in the VR config would
 * otherwise be chosen from a guess and then defended by a memory of how it felt. So the client writes down what
 * happened instead, and the numbers get picked from evidence after the headset comes off.
 * <p>
 * The one measurement worth the extra raycast is the <b>smoothing</b> one. §9 U4 filed "whether pointing at a far
 * screen needs smoothing" under judgement, and it half is — but the half that is not is arithmetic: probe the
 * <em>raw</em> pose's ray as well as the smoothed one and compare where each lands, and the log says how many
 * pixels a still hand wanders, before and after. That turns "does it need smoothing" into a number, and leaves
 * only "does the dot now lag my hand" — which really is eyes-only — for [name].
 * <p>
 * Off with {@code vrPointerDiagnostics}, and it should be, once the numbers are settled.
 */
final class VrDiagnostics {
	private static final Logger LOGGER = LoggerFactory.getLogger("virtualminecraft-vr");

	private static boolean announced;
	private static long ticks;
	private static long windowStart;

	// Per-window accumulators. "Wander" is how far the landed pixel moves from one tick to the next: a perfectly
	// still hand would be 0, and the gap between the raw and smoothed figures is what the smoothing bought.
	private static int samples;
	private static double rawWanderSum;
	private static double rawWanderMax;
	private static double smoothWanderSum;
	private static double smoothWanderMax;
	private static double angleSum;
	private static double angleMax;
	private static double distanceSum;
	private static int onScreen;
	private static @Nullable int[] lastRawPixel;
	private static @Nullable int[] lastSmoothPixel;
	private static @Nullable Vec3 lastRawDir;

	private VrDiagnostics() {
	}

	static boolean enabled() {
		return VmcConfig.get().vrPointerDiagnostics;
	}

	/**
	 * Said once, the first time a headset is actually driving the client. Everything in it is a thing that would
	 * otherwise have to be asked: whether VR came up at all, which hand is dominant, whether the player is seated
	 * (Vivecraft refuses the floating keyboard when they are), and the world scale, which silently rescales every
	 * distance in the config above it.
	 */
	static void announce() {
		if (announced) {
			return;
		}
		announced = true;
		final VRClientAPI api = VRClientAPI.instance();
		final VmcConfig cfg = VmcConfig.get();
		LOGGER.info("VR is active: leftHanded={} seated={} worldScale={} | reach={} smoothing={} touchRange={}",
			api.isLeftHanded(), api.isSeated(), api.getWorldScale(),
			cfg.vrPointerReach, cfg.vrPointerSmoothing, cfg.vrPointerTouchRange);
	}

	/** One tick of pointing, raw pose and smoothed ray both. Cheap when nothing is under either. */
	static void sample(final PointerRay raw, final PointerRay smoothed) {
		if (!enabled()) {
			return;
		}
		ticks++;
		final WorldPointer.Hit rawHit = WorldPointer.probe(raw);
		final WorldPointer.Hit smoothHit = WorldPointer.probe(smoothed);

		final Vec3 dir = raw.direction().normalize();
		if (lastRawDir != null) {
			angleSum += Math.toDegrees(Math.acos(Math.clamp(lastRawDir.dot(dir), -1.0, 1.0)));
			angleMax = Math.max(angleMax, Math.toDegrees(Math.acos(Math.clamp(lastRawDir.dot(dir), -1.0, 1.0))));
		}
		lastRawDir = dir;
		samples++;

		if (rawHit != null) {
			onScreen++;
			distanceSum += rawHit.distance();
			if (lastRawPixel != null) {
				final double d = Math.hypot(rawHit.x() - lastRawPixel[0], rawHit.y() - lastRawPixel[1]);
				rawWanderSum += d;
				rawWanderMax = Math.max(rawWanderMax, d);
			}
			lastRawPixel = new int[] { rawHit.x(), rawHit.y() };
		} else {
			lastRawPixel = null;
		}
		if (smoothHit != null) {
			if (lastSmoothPixel != null) {
				final double d = Math.hypot(smoothHit.x() - lastSmoothPixel[0], smoothHit.y() - lastSmoothPixel[1]);
				smoothWanderSum += d;
				smoothWanderMax = Math.max(smoothWanderMax, d);
			}
			lastSmoothPixel = new int[] { smoothHit.x(), smoothHit.y() };
		} else {
			lastSmoothPixel = null;
		}

		final long window = Math.max(1, VmcConfig.get().vrPointerDiagnosticSeconds) * 20L;
		if (ticks - windowStart >= window) {
			flush();
			windowStart = ticks;
		}
	}

	private static void flush() {
		if (samples <= 1) {
			reset();
			return;
		}
		if (onScreen == 0) {
			LOGGER.info("VR pointer: on a screen 0/{} ticks, hand moved {} deg/tick mean (nothing under the ray)",
				samples, fmt(angleSum / samples));
		} else {
			LOGGER.info("VR pointer: on a screen {}/{} ticks at {} blocks | hand {} deg/tick mean, {} max"
					+ " | pixel wander raw {} mean / {} max, smoothed {} mean / {} max (smoothing={})",
				onScreen, samples, fmt(distanceSum / onScreen),
				fmt(angleSum / samples), fmt(angleMax),
				fmt(rawWanderSum / onScreen), fmt(rawWanderMax),
				fmt(smoothWanderSum / onScreen), fmt(smoothWanderMax),
				VmcConfig.get().vrPointerSmoothing);
		}
		reset();
	}

	private static void reset() {
		samples = 0;
		onScreen = 0;
		rawWanderSum = rawWanderMax = smoothWanderSum = smoothWanderMax = 0;
		angleSum = angleMax = distanceSum = 0;
	}

	/** The interact module claiming or losing a hand, and every press. */
	static void interact(final String what, final Object... args) {
		if (enabled()) {
			LOGGER.info("VR interact: " + what, args);
		}
	}

	private static long lastThrottled;

	/**
	 * The same, for something evaluated every tick on both hands. The first headset session wrote the "too close"
	 * line about forty times a second and buried everything else in the log, which for a session nobody can watch
	 * live is worse than useless — the log <em>is</em> the instrument.
	 */
	static void interactThrottled(final String what, final Object... args) {
		if (enabled() && ticks - lastThrottled >= 40) {
			lastThrottled = ticks;
			LOGGER.info("VR interact: " + what, args);
		}
	}

	private static String fmt(final double v) {
		return String.format(java.util.Locale.ROOT, "%.2f", v);
	}
}
