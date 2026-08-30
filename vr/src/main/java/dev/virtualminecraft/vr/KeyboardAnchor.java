package dev.virtualminecraft.vr;

import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.client.input.WorldKeyboard;
import dev.virtualminecraft.config.VmcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Keyboard block's whole point (ROADMAP §9 U4.3, [name] 2026-08-29: <i>"a block that always puts the keyboard
 * in the same place, so the user can get used to where the keyboard is, kinda like how I know where everything is
 * in real life"</i>). When the keyboard gesture fires, {@link #locate} finds the nearest Keyboard block around the
 * player; while one is held, {@link #tick} re-pins Vivecraft's floating keyboard over it every client tick — every
 * tick because the pin is written in <em>room</em> coordinates and the room moves with the player, see
 * {@link VivecraftLink#anchorKeyboard}.
 * <p>
 * No Keyboard block in range means no anchor and the keyboard floats where Vivecraft puts it, which is exactly the
 * behaviour before the block existed — the block adds a place, never a requirement. The anchor also lets go by
 * itself: of the block, if it is broken mid-typing (the keyboard simply stays where it last was); of everything,
 * when the keyboard closes, since {@link #tick} guards on {@link WorldKeyboard#isOpen()} and the next open runs
 * {@link #locate} fresh.
 */
final class KeyboardAnchor {
	private static @Nullable BlockPos anchor;

	private KeyboardAnchor() {
	}

	/** Find and remember the Keyboard block nearest the player, or forget if there is none in range. */
	static void locate() {
		anchor = null;
		final Player player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		final int range = Math.max(0, VmcConfig.get().vrKeyboardRange);
		final BlockPos centre = player.blockPosition();
		double best = Double.MAX_VALUE;
		for (final BlockPos pos : BlockPos.betweenClosed(centre.offset(-range, -range, -range), centre.offset(range, range, range))) {
			if (!player.level().getBlockState(pos).is(ModContent.KEYBOARD)) {
				continue;
			}
			final double d = pos.distSqr(centre);
			if (d < best) {
				best = d;
				anchor = pos.immutable(); // betweenClosed reuses one mutable pos; keeping it would keep the last visited
			}
		}
		if (anchor != null) {
			VrDiagnostics.interact("keyboard anchored to the Keyboard block at {}", anchor);
		}
	}

	/** Re-pin the keyboard over the anchor block. Called once a client tick by {@link VrModule}. */
	static void tick() {
		if (anchor == null || !WorldKeyboard.isOpen() || !VivecraftLink.vrActive()) {
			return;
		}
		final Level level = Minecraft.getInstance().level;
		if (level == null) {
			anchor = null;
			return;
		}
		final BlockState state = level.getBlockState(anchor);
		if (!state.is(ModContent.KEYBOARD)) {
			anchor = null; // broken out from under the typist; the keyboard stays wherever it last was
			return;
		}
		final VmcConfig config = VmcConfig.get();
		final Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
		// FACING points at whoever placed the block, so +forward slides the keyboard toward the typist -- [name]'s
		// first headset read of the centred anchor was "too far away and too high" (2026-08-29), hence both dials.
		final Vec3 centre = new Vec3(
			anchor.getX() + 0.5 + facing.getStepX() * config.vrKeyboardForward,
			anchor.getY() + config.vrKeyboardHeight,
			anchor.getZ() + 0.5 + facing.getStepZ() * config.vrKeyboardForward);
		final float yaw = (float) Math.atan2(facing.getStepX(), facing.getStepZ());
		VivecraftLink.anchorKeyboard(centre, yaw, config.vrKeyboardTilt);
	}
}
