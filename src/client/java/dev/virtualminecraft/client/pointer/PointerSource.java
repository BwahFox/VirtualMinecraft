package dev.virtualminecraft.client.pointer;

import org.jspecify.annotations.Nullable;

/**
 * Something that can point at a monitor (ROADMAP §9 U4.0, "the seam"). The camera is one; a VR controller is
 * another; a fake driven by the puppet is a third, and it is how all of this is tested without a headset.
 * <p>
 * <b>This interface is the seam, and its whole job is to not change.</b> The main mod owns the half below it —
 * ray to block to pixel to {@code InputSender} — and never mentions Vivecraft; the {@code vr} module owns the half
 * above it and never mentions a Minecraft render class. A Minecraft update touches one side, a Vivecraft update
 * the other. Keep it made of numbers.
 * <p>
 * Buttons are the RFB bits the rest of the input path already speaks: 1 left, 2 middle, 4 right. Everything is
 * polled once a client tick by {@link Pointers}; a source is never called from another thread.
 */
public interface PointerSource {
	/** A name for logs and the puppet: "camera", "vr-right", "fake". */
	String name();

	/**
	 * Where this source is pointing this frame, or null when it is not pointing at all — a controller that is not
	 * being held up, a fake nobody has aimed. A source that returns null costs nothing.
	 */
	@Nullable PointerRay ray();

	/** The buttons held right now as RFB bits (1 left, 2 middle, 4 right). */
	default int buttons() {
		return 0;
	}

	/**
	 * Wheel notches since the last call, positive up, and <em>taken</em> — a source accumulates them between ticks
	 * and hands them over once. Returning them repeatedly would scroll for ever.
	 */
	default int takeWheel() {
		return 0;
	}

	/**
	 * Priority when more than one source is pointing: highest wins, and the camera sits at 0 so anything
	 * deliberate outranks merely looking at the screen.
	 */
	default int priority() {
		return 0;
	}
}
