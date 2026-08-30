package dev.virtualminecraft.block;

import com.mojang.serialization.MapCodec;
import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.bus.BusHost;
import dev.virtualminecraft.bus.BusNetwork;
import dev.virtualminecraft.bus.BusRegistry;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The bus bridge (ROADMAP §9 U11): joins the cable run it sits on to the run its partner sits on, however far
 * apart the two are. This is what makes [name]'s goal reachable — <em>two computers at opposite world borders
 * able to communicate</em> — and it costs nothing in between, because the bus is server-side data: the hop is a
 * lookup in {@link BusRegistry}, so no chunk along the route is loaded and no relay has to tick.
 * <p>
 * Bridges are crafted <b>two at a time, already paired</b>: the recipe stamps one fresh id on the output stack
 * and both halves carry it. Place one at each end, touching cable, and the two runs are joined. Right-click one
 * to be told where its partner is and whether it is answering.
 */
public class BridgeBlock extends BaseEntityBlock {
	public static final MapCodec<BridgeBlock> CODEC = simpleCodec(BridgeBlock::new);

	public BridgeBlock(final Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		return new BridgeBlockEntity(pos, state);
	}

	@Override
	protected net.minecraft.world.level.block.RenderShape getRenderShape(final BlockState state) {
		return net.minecraft.world.level.block.RenderShape.MODEL;
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(final Level level, final BlockState state, final BlockEntityType<T> type) {
		if (level.isClientSide() || type != ModContent.BRIDGE_BLOCK_ENTITY) {
			return null;
		}
		return (lvl, pos, st, be) -> ((BridgeBlockEntity) be).serverTick((ServerLevel) lvl);
	}

	/** The pair id travels on the item, so the placed half is linked before it ever ticks. */
	@Override
	public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, final @Nullable LivingEntity placer, final ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		final UUID pair = stack.get(ModContent.BRIDGE_PAIR);
		if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof BridgeBlockEntity be) {
			be.setPairId(pair);
			if (pair != null) {
				BusRegistry.noteBridge(serverLevel, pos, pair);
			}
			for (final BusHost host : BusNetwork.computersOnNetwork(serverLevel, pos)) {
				host.onNeighborChanged(serverLevel);
			}
		}
	}

	/** A bridge is part of a bus: placing or breaking one changes what the machines on it can reach. */
	@Override
	protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, final @Nullable Orientation orientation, final boolean movedByPiston) {
		if (level instanceof ServerLevel serverLevel) {
			for (final BusHost host : BusNetwork.computersOnNetwork(serverLevel, pos)) {
				host.onNeighborChanged(serverLevel);
			}
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		BusRegistry.forgetBridge(level, pos);
		for (final BusHost host : BusNetwork.computersOnNetwork(level, pos)) {
			host.onNeighborChanged(level);
		}
	}

	/**
	 * Right-click <b>holding another bridge</b>: the two become a pair ([name], 2026-08-29 — <em>"we probably
	 * should be able to set an id by right clicking if the user wants to"</em>). The placed block's id is copied
	 * onto the held item, minting one first if the block has none, so the item can be carried anywhere and
	 * placed already linked.
	 * <p>
	 * This is what makes bridges usable without the recipe at all — a bridge out of the creative tab carries no
	 * id and would otherwise join nothing — and it is how you re-pair bridges that are already in the world
	 * without crafting replacements.
	 */
	@Override
	protected InteractionResult useItemOn(final ItemStack stack, final BlockState state, final Level level, final BlockPos pos,
			final Player player, final net.minecraft.world.InteractionHand hand, final BlockHitResult hit) {
		if (!stack.is(ModContent.BUS_BRIDGE_ITEM)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof BridgeBlockEntity be)
				|| !(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
			return InteractionResult.SUCCESS;
		}
		UUID pair = be.pairId();
		if (pair == null) {
			pair = UUID.randomUUID();
			be.setPairId(pair);
			BusRegistry.noteBridge(serverLevel, pos, pair);
		}
		stack.set(ModContent.BRIDGE_PAIR, pair);
		for (final BusHost host : BusNetwork.computersOnNetwork(serverLevel, pos)) {
			host.onNeighborChanged(serverLevel);
		}
		sp.sendSystemMessage(Component.translatable("virtualminecraft.msg.bridge_paired",
			pair.toString().substring(0, 8)).withStyle(ChatFormatting.AQUA));
		return InteractionResult.SUCCESS;
	}

	/** Right-click: where the other half is; sneak+right-click with an empty hand gives it a brand-new id. */
	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof BridgeBlockEntity be)
				|| !(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
			return InteractionResult.SUCCESS;
		}
		if (player.isShiftKeyDown()) {
			// "This one is its own thing now": a fresh id, which leaves whatever it was paired with alone.
			final UUID minted = UUID.randomUUID();
			be.setPairId(minted);
			BusRegistry.noteBridge(serverLevel, pos, minted);
			for (final BusHost host : BusNetwork.computersOnNetwork(serverLevel, pos)) {
				host.onNeighborChanged(serverLevel);
			}
			sp.sendSystemMessage(Component.translatable("virtualminecraft.msg.bridge_new_id",
				minted.toString().substring(0, 8)).withStyle(ChatFormatting.AQUA));
			return InteractionResult.SUCCESS;
		}
		final UUID pair = be.pairId();
		if (pair == null) {
			sp.sendSystemMessage(Component.translatable("virtualminecraft.msg.bridge_unpaired").withStyle(ChatFormatting.RED));
			return InteractionResult.SUCCESS;
		}
		final List<BlockPos> partners = BusRegistry.bridgePartners(serverLevel, pos, pair);
		if (partners.isEmpty()) {
			sp.sendSystemMessage(Component.translatable("virtualminecraft.msg.bridge_alone",
				pair.toString().substring(0, 8)).withStyle(ChatFormatting.YELLOW));
			return InteractionResult.SUCCESS;
		}
		final StringBuilder where = new StringBuilder();
		for (final BlockPos p : partners) {
			where.append(where.isEmpty() ? "" : ", ").append(p.toShortString());
		}
		sp.sendSystemMessage(Component.translatable("virtualminecraft.msg.bridge_linked",
			pair.toString().substring(0, 8), where.toString(),
			BusRegistry.reachableNets(serverLevel, pos).size()));
		return InteractionResult.SUCCESS;
	}
}
