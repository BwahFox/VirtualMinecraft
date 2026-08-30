package dev.virtualminecraft.block;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Block shapes, outside Minecraft — the 1.20.1 edition of the 26.2 harness of the same name. Read that one for
 * the story; the short version is that the bus cable shipped for two milestones with a collision shape a fifth
 * of a pixel across because {@code PipeBlock}'s constructor argument was passed in the wrong unit.
 * <p>
 * <b>The unit is different on 1.20.1</b>, which is exactly why this test exists here in its own right: this
 * game's {@link net.minecraft.world.level.block.PipeBlock} wants the core's half-width as a <em>fraction</em> of
 * a block ({@code makeShapes} builds {@code Block.box((0.5 - a) * 16, ...)} from it), where 26.2's wants pixels.
 * {@link BusCableBlock#APOTHEM} is the fraction and {@link BusCableBlock#CORE_PX} the pixels, and the check that
 * matters is the same on both: the shape the block builds lands on the model's 5..11 core.
 * <p>
 * This needs no world, no registries and no client — the shape helpers are arithmetic. (On 1.20.1 that means
 * {@link Shapes#box} in block units rather than {@code Block.box}, whose class pulls the registries in with it.)
 */
public final class BlockShapeTest {
	private static int failures;

	private BlockShapeTest() {
	}

	private static void check(final boolean ok, final String what) {
		System.out.println((ok ? "  PASS " : "  FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}

	/** The smallest box a shape contains, or null when the shape is empty. */
	private static AABB bounds(final VoxelShape shape) {
		return shape.isEmpty() ? null : shape.bounds();
	}

	/** {@code Block.box}: pixel coordinates in, a shape out. */
	private static VoxelShape box(final double x0, final double y0, final double z0, final double x1, final double y1, final double z1) {
		return Shapes.box(x0 / 16.0, y0 / 16.0, z0 / 16.0, x1 / 16.0, y1 / 16.0, z1 / 16.0);
	}

	/** What 1.20.1's {@code PipeBlock.makeShapes} builds for the core from an apothem. */
	private static VoxelShape pipeCore(final float apothem) {
		final float f = 0.5F - apothem;
		final float g = 0.5F + apothem;
		return box(f * 16.0F, f * 16.0F, f * 16.0F, g * 16.0F, g * 16.0F, g * 16.0F);
	}

	/** ...and for the north arm: from the block face to the far side of the core. */
	private static VoxelShape pipeArm(final float apothem) {
		final float f = 0.5F - apothem;
		final float g = 0.5F + apothem;
		return box(f * 16.0F, f * 16.0F, 0.0F, g * 16.0F, g * 16.0F, g * 16.0F);
	}

	public static void main(final String[] args) {
		System.out.println("BlockShapeTest (1.20.1)");

		// The 26.2 trap in reverse: pixels passed where a fraction is wanted. 0.5 - 6 is negative, so the "core"
		// is a box 96 pixels across centred on the block -- not a sliver this time, but a cable you could not walk
		// past. Kept as the reason APOTHEM exists.
		final AABB wrong = bounds(pipeCore(BusCableBlock.CORE_PX));
		check(wrong != null && wrong.getXsize() > 2.0, "pixels passed where a fraction is wanted builds nonsense ("
			+ (wrong == null ? "empty" : String.format("%.1f", wrong.getXsize())) + " blocks across) — the bug this file guards against on 1.20.1");

		// What the cable uses: the apothem, which must land on the model's 5..11 core exactly.
		final AABB core = bounds(pipeCore(BusCableBlock.APOTHEM));
		check(core != null, "the cable's core shape is not empty");
		if (core != null) {
			check(Math.abs(core.minX - 5.0 / 16.0) < 1e-6 && Math.abs(core.maxX - 11.0 / 16.0) < 1e-6,
				"the core is the model's 5..11 cube (" + String.format("%.4f..%.4f", core.minX, core.maxX) + ")");
			check(core.getXsize() > 0.3 && core.getYsize() > 0.3 && core.getZsize() > 0.3,
				"the core is thick enough to aim at (" + String.format("%.3f", core.getXsize()) + " blocks)");
		}

		// An arm reaches from the block face to the centre, so a run of cable is one continuous target rather
		// than a string of beads with gaps between them.
		final AABB arm = bounds(pipeArm(BusCableBlock.APOTHEM));
		check(arm != null && arm.getZsize() > 0.4, "an arm reaches from the block face to its centre ("
			+ (arm == null ? "empty" : String.format("%.3f", arm.getZsize())) + " blocks)");

		// The guards: the pixel width is a pixel width, and the apothem is the fraction it implies.
		check(BusCableBlock.CORE_PX >= 4.0F && BusCableBlock.CORE_PX <= 16.0F,
			"BusCableBlock.CORE_PX is a sane pixel width, not a fraction (" + BusCableBlock.CORE_PX + ")");
		check(Math.abs(BusCableBlock.APOTHEM - BusCableBlock.CORE_PX / 32.0F) < 1e-6 && BusCableBlock.APOTHEM > 0.1F && BusCableBlock.APOTHEM < 0.5F,
			"BusCableBlock.APOTHEM is CORE_PX as 1.20.1's half-width fraction (" + BusCableBlock.APOTHEM + ")");

		// The Keyboard block (§9 U4.3): same trap, same guard — its constants are pixels, and the slab they build
		// must be a thing a crosshair can find on a desk, not a sliver and not a full cube pretending to be flat.
		final AABB keys = bounds(box(
			(16.0 - KeyboardBlock.WIDTH_PX) / 2.0, 0.0, (16.0 - KeyboardBlock.DEPTH_PX) / 2.0,
			(16.0 + KeyboardBlock.WIDTH_PX) / 2.0, KeyboardBlock.HEIGHT_PX, (16.0 + KeyboardBlock.DEPTH_PX) / 2.0));
		check(keys != null, "the keyboard's shape is not empty");
		if (keys != null) {
			check(keys.getXsize() > 0.5 && keys.getZsize() > 0.25, "the keyboard is wide enough to aim at ("
				+ String.format("%.3f x %.3f", keys.getXsize(), keys.getZsize()) + " blocks)");
			check(keys.getYsize() > 0.06 && keys.getYsize() < 0.5, "the keyboard is a slab on a desk, not a box ("
				+ String.format("%.3f", keys.getYsize()) + " blocks tall)");
		}
		check(KeyboardBlock.WIDTH_PX >= 4.0 && KeyboardBlock.WIDTH_PX <= 16.0
			&& KeyboardBlock.DEPTH_PX >= 4.0 && KeyboardBlock.DEPTH_PX <= 16.0
			&& KeyboardBlock.HEIGHT_PX >= 1.0 && KeyboardBlock.HEIGHT_PX <= 8.0,
			"KeyboardBlock's constants are sane pixel sizes, not fractions ("
			+ KeyboardBlock.WIDTH_PX + " x " + KeyboardBlock.DEPTH_PX + " x " + KeyboardBlock.HEIGHT_PX + ")");

		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
