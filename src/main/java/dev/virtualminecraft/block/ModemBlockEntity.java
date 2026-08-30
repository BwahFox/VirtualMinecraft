package dev.virtualminecraft.block;

import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.bus.BusHost;
import dev.virtualminecraft.bus.Modems;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A wireless modem: the block that turns the cable-only {@code net} component into a radio (ROADMAP §9 U3).
 * It holds no state at all — its whole job is to be <em>findable</em>, so it notes its position in
 * {@link Modems} on its first server tick after loading and forgets it when it is broken. Everything else
 * (who is in range, who is on whose bus) is computed on demand by {@link Modems}, exactly as the cable's
 * flood fill is.
 */
public class ModemBlockEntity extends BlockEntity {
	private boolean noted;

	public ModemBlockEntity(final BlockPos pos, final BlockState state) {
		super(ModContent.MODEM_BLOCK_ENTITY, pos, state);
	}

	/** Cheap: a boolean after the first tick. The registry entry is a hint; {@link Modems} verifies it. */
	public void serverTick(final ServerLevel level) {
		if (!noted) {
			Modems.note(level, worldPosition);
			noted = true;
		}
	}

	@Override
	public void setRemoved() {
		if (level instanceof ServerLevel serverLevel) {
			Modems.forget(serverLevel, worldPosition);
		}
		super.setRemoved();
	}

	/** The machines this modem serves (it touches them, or shares a cable run with them). */
	public List<BusHost> hosts(final ServerLevel level) {
		return Modems.hostsOf(level, worldPosition);
	}

	/** The other modems this one can hear, nearest first. */
	public List<BlockPos> reachable(final ServerLevel level) {
		return Modems.inRange(level, List.of(worldPosition));
	}
}
