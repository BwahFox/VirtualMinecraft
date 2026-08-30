package dev.virtualminecraft.client.pointer;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Something that wants to answer "the player used a monitor" instead of letting it mean what it usually means
 * (ROADMAP §9 U4.3). The third piece of the seam, and the same rule as the other two: it is made of a UUID and a
 * position, so it survives both a Minecraft update and a Vivecraft one.
 * <p>
 * <b>Why this exists at all.</b> Using a live screen normally sends a click to the guest at that pixel, and that
 * is right on a desktop where the mouse and the keyboard are the same device. In VR they are not — the controller
 * is already the mouse, so the "use" button is free, and [name] chose it for the keyboard (2026-08-29, after the
 * first gesture fought with everything else): <i>"hitting Interact on the monitor when in vr will open it, and
 * when the keyboard is open, the hotbar shouldn't do anything anyway, so left and right click can remain on those
 * triggers"</i>. Only the VR module knows whether a headset is on, so only it can decide — hence a handler rather
 * than a flag in here.
 * <p>
 * Nothing registers one by default, so on a desktop client using a monitor does exactly what it always did.
 */
public interface MonitorUse {
	/**
	 * The player used a monitor showing {@code screen}. Return true to consume it — the click is then not sent and
	 * the server never hears about it.
	 */
	boolean onUse(UUID screen, BlockPos pos);

	/** The registry, such as it is: one handler, because two things claiming the use button is not a design. */
	final class Registry {
		private static @Nullable MonitorUse handler;

		private Registry() {
		}

		public static void set(final @Nullable MonitorUse h) {
			handler = h;
		}

		public static @Nullable MonitorUse get() {
			return handler;
		}
	}
}
