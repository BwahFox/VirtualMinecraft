package dev.virtualminecraft.block;

import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.bus.BusRegistry;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Half of a bus bridge (ROADMAP §9 U11): a block that joins the cable run it sits on to whatever run its
 * partner sits on, however far away that is, with nothing in between loaded.
 * <p>
 * <b>All it holds is a pair id.</b> Bridges are crafted two at a time and both halves come out of the recipe
 * already carrying the same fresh {@link UUID} ({@code PairedBridgeRecipe}), so pairing is done before either
 * is placed and there is nothing to configure in the world. Two bridges are joined exactly when their ids
 * match — which also means a stack of more than two (a shift-craft, or creative) forms one group rather than a
 * pair, and that is a usable hub rather than a bug.
 * <p>
 * The block is <em>findable</em>, like {@link ModemBlockEntity}: it notes itself in {@link BusRegistry} and the
 * registry does the rest, so a bridge never ticks and never forwards anything itself. That is the whole point —
 * the bus is server-side data, so a hop is a map lookup and the far side does not have to be simulated, or even
 * loaded, for a message to reach a machine on it.
 */
public class BridgeBlockEntity extends BlockEntity {
	private @Nullable UUID pairId;
	private boolean noted;

	public BridgeBlockEntity(final BlockPos pos, final BlockState state) {
		super(ModContent.BRIDGE_BLOCK_ENTITY, pos, state);
	}

	/** Null on a bridge placed before it was ever paired (only possible by command); it then joins nothing. */
	public @Nullable UUID pairId() {
		return pairId;
	}

	public void setPairId(final @Nullable UUID id) {
		pairId = id;
		noted = false;
		setChanged();
	}

	/** Cheap: a boolean after the first tick, exactly like the modem's. The registry entry is a hint. */
	public void serverTick(final ServerLevel level) {
		if (!noted && pairId != null) {
			BusRegistry.noteBridge(level, worldPosition, pairId);
			noted = true;
		}
	}

	// **No setRemoved override, deliberately.** A block entity's setRemoved fires when its chunk is merely
	// unloaded, not only when the block is broken — so forgetting the bridge there made it vanish from the
	// registry the moment nobody was near it, which is the exact opposite of what a bridge is for: the far half
	// has to stay addressable while asleep, or a message can never reach the machine behind it. Removal is
	// handled by BridgeBlock.affectNeighborsAfterRemoval, which fires only on a real break, and a stale entry is
	// dropped by BusRegistry's verify-on-use the next time that position is walked while loaded.
	// (ModemBlockEntity does forget itself in setRemoved and is fine, because a modem only matters when loaded.)

	@Override
	protected void loadAdditional(final ValueInput in) {
		super.loadAdditional(in);
		pairId = null;
		in.getString("pair").ifPresent(s -> {
			try {
				pairId = UUID.fromString(s);
			} catch (final IllegalArgumentException ignored) {
				// a hand-edited world: an unpaired bridge joins nothing, which is the safe way to fail
			}
		});
		noted = false;
	}

	@Override
	protected void saveAdditional(final ValueOutput out) {
		super.saveAdditional(out);
		if (pairId != null) {
			out.putString("pair", pairId.toString());
		}
	}
}
