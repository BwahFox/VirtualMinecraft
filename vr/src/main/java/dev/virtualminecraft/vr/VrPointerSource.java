package dev.virtualminecraft.vr;

import dev.virtualminecraft.client.pointer.PointerRay;
import dev.virtualminecraft.client.pointer.PointerSource;
import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.util.Nums;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.vivecraft.api.data.VRBodyPartData;

/**
 * The dominant-hand controller, as a {@link PointerSource} (ROADMAP §9 U4.2 — "pointing, the first wow"). This is
 * the whole of the VR module's half of the pointer seam, and it really is this short: Vivecraft hands out
 * {@code getPos()} and {@code getDir()} as {@code Vec3} already, so a {@link PointerRay} is a constructor call.
 * Everything after it — the clip, the monitor lookup, the pixel maths, the rate limit, the payload, the server's
 * distance check — is the main mod's and was proved with {@code FakePointerSource} before a headset existed.
 * <p>
 * <b>Buttons do not come from here.</b> Vivecraft has nothing to poll; they arrive through
 * {@link MonitorInteractModule}, which pushes them into {@link #setButton}. That is deliberate — see that class.
 * <p>
 * <b>Smoothing.</b> A held hand is never still, and at four blocks a millimetre of tremor is several pixels on a
 * 320-wide screen. The ray is therefore an exponential moving average of the raw pose, at a strength that is
 * {@code vrPointerSmoothing} in the config rather than a constant here: §9 U4 puts "whether pointing at a far
 * screen needs smoothing" squarely in the half only a headset can judge, and this way judging it costs a restart
 * rather than a rebuild. 0 is the raw pose.
 * <p>
 * <b>Scroll comes from {@link VrScroll}</b> — two bindable key mappings, not a thumbstick axis, because Vivecraft
 * turns any registered KeyMapping into a VR action and that made the old "no public thumbstick axis" note moot.
 * The notches accumulate here and {@code takeWheel()} hands them over once, like any other source.
 */
public final class VrPointerSource implements PointerSource {
	public static final VrPointerSource INSTANCE = new VrPointerSource();

	private @Nullable Vec3 smoothedOrigin;
	private @Nullable Vec3 smoothedDirection;
	private int buttons;
	private int wheel;

	private VrPointerSource() {
	}

	@Override
	public String name() {
		return "vr-controller";
	}

	/**
	 * The dominant hand's ray, smoothed. Null whenever there is no headset driving the client, which is what keeps
	 * a desktop client with this jar installed behaving exactly like one without it: the camera source is then the
	 * only one pointing, and it is the crosshair hover that has always been there.
	 * <p>
	 * Called once a client tick by {@code Pointers.tick()} and nowhere else — it advances the smoothing, so a
	 * second caller in the same tick would move the average twice. {@link MonitorInteractModule} deliberately uses
	 * {@link #rawRay} instead.
	 */
	@Override
	public @Nullable PointerRay ray() {
		final PointerRay raw = rawRay(null);
		if (raw == null) {
			smoothedOrigin = null;
			smoothedDirection = null;
			wheel = 0; // notches nobody will take must not burst out when a headset comes back
			return null;
		}
		final double a = Nums.clamp(VmcConfig.get().vrPointerSmoothing, 0.0, 0.95);
		if (smoothedOrigin == null || smoothedDirection == null || a <= 0.0) {
			smoothedOrigin = raw.origin();
			smoothedDirection = raw.direction();
		} else {
			smoothedOrigin = lerp(smoothedOrigin, raw.origin(), 1.0 - a);
			smoothedDirection = lerp(smoothedDirection, raw.direction(), 1.0 - a).normalize();
		}
		final PointerRay smoothed = new PointerRay(smoothedOrigin, smoothedDirection, raw.reach());
		VrDiagnostics.announce();
		VrDiagnostics.sample(raw, smoothed);
		return smoothed;
	}

	/**
	 * One hand's ray straight off the pose, with no smoothing and no state touched. This is what the interact
	 * module hit-tests with: it wants to know whether a monitor is under the hand right now, which is a question
	 * about the hand and not about the dot the player is watching, and Vivecraft asks it mid-tick — before
	 * {@link #ray()} has run — so sharing the smoothed value would be a tick stale as well as wrong.
	 *
	 * @param hand which controller, or null for the dominant one
	 */
	static @Nullable PointerRay rawRay(final @Nullable InteractionHand hand) {
		final VRBodyPartData data = VivecraftLink.hand(hand);
		if (data == null) {
			return null;
		}
		final Vec3 dir = data.getDir();
		if (dir == null || dir.lengthSqr() < 1.0e-9) {
			return null;
		}
		return new PointerRay(data.getPos(), dir, Math.max(0.5, VmcConfig.get().vrPointerReach));
	}

	private static Vec3 lerp(final Vec3 from, final Vec3 to, final double t) {
		return new Vec3(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t);
	}

	@Override
	public int buttons() {
		return buttons;
	}

	/** Set or clear one RFB button bit (1 left, 4 right); {@link MonitorInteractModule} is the only caller. */
	void setButton(final int rfbBit, final boolean down) {
		buttons = down ? (buttons | rfbBit) : (buttons & ~rfbBit);
	}

	/** Queue wheel notches (positive up); {@link VrScroll} is the only caller. */
	void addWheel(final int notches) {
		wheel += notches;
	}

	@Override
	public int takeWheel() {
		final int taken = wheel;
		wheel = 0;
		return taken;
	}

	@Override
	public int priority() {
		// Above the puppet's fake (10) and far above the camera (0). In VR the controller *is* the pointer, and
		// looking at a screen must stop moving the mouse the moment a hand can do it instead -- two pointers
		// fighting over one guest cursor is the one outcome nobody would call a feature.
		return 20;
	}
}
