package dev.virtualminecraft.client.pointer;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The crosshair, as a {@link PointerSource} (ROADMAP §9 U4.0). This is the source that already existed — looking at
 * a monitor moves the guest's mouse — expressed through the seam, which is how we know the seam is the right shape
 * before anything VR-facing is written against it.
 * <p>
 * <b>Its buttons are always zero, deliberately.</b> Clicking a monitor stays the server's business
 * ({@code MonitorBlock.useWithoutItem}), where it is authoritative and where it has always been; the camera is a
 * pointer, not a mouse. A VR controller is the thing that supplies buttons here, and it does so at a higher
 * priority, so it takes over the moment it is pointing at anything.
 */
public final class CameraPointerSource implements PointerSource {
	@Override
	public String name() {
		return "camera";
	}

	@Override
	public @Nullable PointerRay ray() {
		final Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.screen != null) {
			return null;
		}
		// The player's own block-interaction reach, not a constant: an attribute on 26.2, the game mode's pick range
		// here (5, or 6 in creative) -- the same number Minecraft's own pick uses on 1.20.1.
		final Vec3 eye = mc.player.getEyePosition(1.0f);
		return new PointerRay(eye, mc.player.getViewVector(1.0f), mc.gameMode == null ? 4.5 : mc.gameMode.getPickRange());
	}

	@Override
	public int priority() {
		return 0; // the floor: anything deliberate outranks merely looking at the screen
	}
}
