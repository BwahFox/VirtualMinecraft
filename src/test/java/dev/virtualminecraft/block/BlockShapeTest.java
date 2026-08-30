package dev.virtualminecraft.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Block shapes, outside Minecraft. There was no harness for these at all, which is how the bus cable shipped
 * from milestone 4 (2026-08-25) to session 18 with a collision shape a fifth of a pixel across: you walked
 * through the block, you could not break it, and every swing at it carried on into whatever was behind. It is
 * invisible in a screenshot — the block renders from a model, and the model was right — and invisible to every
 * other harness, so the only way to find it was to lose part of a house to it.
 * <p>
 * The trap is units. {@link net.minecraft.world.level.block.PipeBlock}'s constructor argument goes straight to
 * {@link Block#cube(double)} and {@link Block#boxZ(double, double, double)}, which measure in <b>pixels</b>
 * (sixteenths of a block), not in fractions of one. Vanilla's {@code ChorusPlantBlock} passes {@code 10.0F};
 * we were passing {@code 0.1875F}.
 * <p>
 * This needs no world, no registries and no client — the shape helpers are arithmetic.
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

	public static void main(final String[] args) {
		System.out.println("BlockShapeTest");

		// What the old value actually built. Block.cube(0.1875) is a cube 0.1875 PIXELS across — about a
		// hundredth of a block. It is not empty, so nothing ever errored; it was simply too thin to be hit.
		final AABB sliver = bounds(Block.cube(0.1875));
		final double sliverWidth = sliver == null ? 0.0 : sliver.getXsize();
		check(sliverWidth < 0.02, "a fraction passed where pixels are wanted builds a sliver ("
			+ String.format("%.4f", sliverWidth) + " blocks across) — this is the bug, kept as the reason for this file");

		// What the cable uses now: 6 pixels, which must land on the model's 5..11 core exactly.
		final AABB core = bounds(Block.cube(BusCableBlock.CORE_PX));
		check(core != null, "the cable's core shape is not empty");
		if (core != null) {
			check(Math.abs(core.minX - 5.0 / 16.0) < 1e-6 && Math.abs(core.maxX - 11.0 / 16.0) < 1e-6,
				"the core is the model's 5..11 cube (" + String.format("%.4f..%.4f", core.minX, core.maxX) + ")");
			check(core.getXsize() > 0.3 && core.getYsize() > 0.3 && core.getZsize() > 0.3,
				"the core is thick enough to aim at (" + String.format("%.3f", core.getXsize()) + " blocks)");
		}

		// An arm reaches from the block face to the centre, so a run of cable is one continuous target rather
		// than a string of beads with gaps between them.
		final AABB arm = bounds(Block.boxZ(BusCableBlock.CORE_PX, 0.0, 8.0));
		check(arm != null && arm.getZsize() > 0.4, "an arm reaches from the block face to its centre ("
			+ (arm == null ? "empty" : String.format("%.3f", arm.getZsize())) + " blocks)");

		// The guard that actually stops this coming back: whatever the constant becomes, it is a pixel width.
		// Under 4 px is a block you will fight to hit; over 16 is not a cable.
		check(BusCableBlock.CORE_PX >= 4.0F && BusCableBlock.CORE_PX <= 16.0F,
			"BusCableBlock.CORE_PX is a sane pixel width, not a fraction (" + BusCableBlock.CORE_PX + ")");

		// The Keyboard block (§9 U4.3): same trap, same guard — its constants are pixels, and the slab they build
		// must be a thing a crosshair can find on a desk, not a sliver and not a full cube pretending to be flat.
		final AABB keys = bounds(Block.box(
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
