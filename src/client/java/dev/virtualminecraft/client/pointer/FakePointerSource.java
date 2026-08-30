package dev.virtualminecraft.client.pointer;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A pointer with nothing behind it (ROADMAP §9 U4.0, and the reason the seam was built first): the puppet aims it,
 * presses its buttons and turns its wheel, and everything downstream — the clip, the monitor lookup, the pixel
 * maths, the rate limit, the payload, the server's distance check — runs exactly as it will with a controller in
 * front of it. <b>No headset, no Vivecraft, no OpenXR.</b>
 * <p>
 * [name], 2026-08-29: <i>"not sure how you're gonna test that, might require me to manually test everything which is
 * fine"</i>. This is the half that does not: by the time the headset goes on, the input plumbing is already
 * known-good and what is left to judge is stereo, scale and comfort, which are hers and always will be.
 * <p>
 * It outranks the camera, so aiming it takes over and {@code vraim off} hands control back.
 */
public final class FakePointerSource implements PointerSource {
	public static final FakePointerSource INSTANCE = new FakePointerSource();

	private @Nullable Vec3 origin;
	private @Nullable Vec3 direction;
	private double reach = WorldPointer.DEFAULT_REACH;
	private int buttons;
	private int wheel;

	private FakePointerSource() {
	}

	/** Point from {@code origin} towards {@code target}; the reach is the distance between them plus a little. */
	public void aimAt(final Vec3 from, final Vec3 target) {
		origin = from;
		direction = target.subtract(from);
		reach = Math.max(0.1, direction.length() + 0.5);
	}

	public void stop() {
		origin = null;
		direction = null;
		buttons = 0;
		wheel = 0;
	}

	public void setButton(final int rfbBit, final boolean down) {
		buttons = down ? (buttons | rfbBit) : (buttons & ~rfbBit);
	}

	public void addWheel(final int notches) {
		wheel += notches;
	}

	public boolean aiming() {
		return origin != null && direction != null;
	}

	@Override
	public String name() {
		return "fake";
	}

	@Override
	public @Nullable PointerRay ray() {
		return origin == null || direction == null ? null : new PointerRay(origin, direction, reach);
	}

	@Override
	public int buttons() {
		return buttons;
	}

	@Override
	public int takeWheel() {
		final int w = wheel;
		wheel = 0;
		return w;
	}

	@Override
	public int priority() {
		return 10; // above the camera: aiming it is deliberate
	}
}
