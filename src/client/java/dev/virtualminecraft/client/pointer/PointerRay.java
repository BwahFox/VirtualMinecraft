package dev.virtualminecraft.client.pointer;

import net.minecraft.world.phys.Vec3;

/**
 * A ray a {@link PointerSource} is pointing along this frame, in world space (ROADMAP §9 U4.0). Deliberately three
 * numbers and a length rather than anything of Minecraft's or Vivecraft's: this is the type that crosses the seam,
 * so it has to survive both a Minecraft update and a Vivecraft update without changing shape.
 *
 * @param origin    where the ray starts — an eye, a controller tip, a fake in a test
 * @param direction which way it points; need not be normalised
 * @param reach     how far along it to look, in blocks
 */
public record PointerRay(Vec3 origin, Vec3 direction, double reach) {
	/** The end point of the ray, which is what {@code Level.clip} wants. */
	public Vec3 end() {
		final Vec3 d = direction.normalize();
		return origin.add(d.x * reach, d.y * reach, d.z * reach);
	}
}
