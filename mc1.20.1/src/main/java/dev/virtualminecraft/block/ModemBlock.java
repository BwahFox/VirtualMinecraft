package dev.virtualminecraft.block;

import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.bus.BusHost;
import dev.virtualminecraft.bus.Modems;
import dev.virtualminecraft.config.VmcConfig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Wireless modem: place one against a computer (or on its bus cable) and its {@code net} component reaches
 * every other machine whose bus has a modem within {@link VmcConfig#modemRange} blocks — the cable rule with a
 * radius instead of a wire, and the same {@code net.list/send/broadcast} API (ROADMAP §9 U3).
 * <p>
 * Right-click tells the player what it can hear, because a radio that fails silently is the worst kind: it
 * reports the range, how many modems are in it, and how many machines that adds up to.
 */
public class ModemBlock extends BaseEntityBlock {

	public ModemBlock(final Properties properties) {
		super(properties);
	}

	@Override
	public RenderShape getRenderShape(final BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		return new ModemBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(final Level level, final BlockState state, final BlockEntityType<T> type) {
		if (level.isClientSide() || type != ModContent.MODEM_BLOCK_ENTITY) {
			return null;
		}
		return (lvl, pos, st, be) -> ((ModemBlockEntity) be).serverTick((ServerLevel) lvl);
	}

	/** A modem is part of a bus: placing or breaking one makes the machines it serves re-sample their components. */
	@Override
	public void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, final BlockPos fromPos, final boolean movedByPiston) {
		if (level instanceof ServerLevel serverLevel) {
			for (final BusHost host : Modems.hostsOf(serverLevel, pos)) {
				host.onNeighborChanged(serverLevel);
			}
		}
	}

	@Override
	public void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (level instanceof ServerLevel serverLevel && !state.is(oldState.getBlock())) {
			Modems.note(serverLevel, pos);
			for (final BusHost host : Modems.hostsOf(serverLevel, pos)) {
				host.onNeighborChanged(serverLevel);
			}
		}
	}

	@Override
	public InteractionResult use(final BlockState state, final Level level, final BlockPos pos, final Player player, final net.minecraft.world.InteractionHand hand, final BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (level instanceof ServerLevel serverLevel && player instanceof net.minecraft.server.level.ServerPlayer sp) {
			final List<BlockPos> heard = Modems.inRange(serverLevel, List.of(pos));
			int machines = 0;
			for (final BlockPos other : heard) {
				machines += Modems.hostsOf(serverLevel, other).size();
			}
			final List<BusHost> mine = Modems.hostsOf(serverLevel, pos);
			sp.sendSystemMessage(Component.translatable("virtualminecraft.msg.modem_status",
				VmcConfig.get().modemRange, heard.size(), machines, mine.size()));
			if (mine.isEmpty()) {
				sp.sendSystemMessage(Component.translatable("virtualminecraft.msg.modem_no_computer"));
			}
		}
		return InteractionResult.CONSUME;
	}
}
