package dev.virtualminecraft.block;

import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.item.DiskItem;
import dev.virtualminecraft.vm.VmManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The computer case: owns the VM (config + disk image) and up to {@link ComputerBlockEntity#DISK_SLOTS} disk
 * items. Right-click to configure and power it; right-click holding a hard drive or CD to put it in; sneak +
 * right-click with an empty hand to take the last one out.
 */
public class ComputerBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	public ComputerBlock(final Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	public RenderShape getRenderShape(final BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		return new ComputerBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(final Level level, final BlockState state, final BlockEntityType<T> type) {
		if (level.isClientSide() || type != ModContent.COMPUTER_BLOCK_ENTITY) {
			return null;
		}
		return (lvl, pos, st, be) -> ((ComputerBlockEntity) be).serverTick((ServerLevel) lvl);
	}

	@Override
	public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, final @Nullable LivingEntity placer, final ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!level.isClientSide() && placer instanceof Player p && level.getBlockEntity(pos) instanceof ComputerBlockEntity be) {
			be.setOwner(p.getUUID(), p.getName().getString());
		}
	}

	// ---- Redstone: the guest drives the six faces through the bus (bus/RedstoneComponent) ----

	@Override
	public boolean isSignalSource(final BlockState state) {
		return true;
	}

	/** {@code direction} points from the asking block towards us, so the emitting face is its opposite. */
	@Override
	public int getSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction) {
		return level.getBlockEntity(pos) instanceof ComputerBlockEntity be ? be.getOutput(direction.getOpposite()) : 0;
	}

	@Override
	public int getDirectSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction) {
		return getSignal(state, level, pos, direction);
	}

	@Override
	public void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, final BlockPos fromPos, final boolean movedByPiston) {
		if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof ComputerBlockEntity be) {
			be.onNeighborChanged(serverLevel);
		}
	}

	/** The block is gone (1.20.1's stand-in for the block entity's {@code preRemoveSideEffects}): the VM and the disks go with it. */
	@Override
	public void onRemove(final BlockState state, final Level level, final BlockPos pos, final BlockState newState, final boolean movedByPiston) {
		if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof ComputerBlockEntity be) {
			be.onBlockRemoved(pos, state);
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	// ---- Disks: hard drives and CDs go inside the case ----

	/** 1.20.1 has one {@code use}; 26.2's item-in-hand pass and empty-hand pass are the two halves below. */
	@Override
	public InteractionResult use(final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final BlockHitResult hit) {
		final ItemStack stack = player.getItemInHand(hand);
		return DiskItem.isDisk(stack) ? useItemOn(stack, state, level, pos, player, hand, hit) : useWithoutItem(state, level, pos, player, hit);
	}

	private InteractionResult useItemOn(final ItemStack stack, final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof ComputerBlockEntity be) {
			final DiskItem.Kind kind = DiskItem.kindOf(stack);
			if (kind == DiskItem.Kind.FLOPPY) {
				player.displayClientMessage(Component.translatable("virtualminecraft.msg.floppy_needs_drive"), true);
			} else if (!be.disksChangeable(serverLevel, player)) {
				// message sent by disksChangeable
			} else if (be.freeDiskSlot() < 0) {
				player.displayClientMessage(Component.translatable("virtualminecraft.msg.computer_full", ComputerBlockEntity.DISK_SLOTS), true);
			} else {
				final ItemStack one = stack.copyWithCount(1);
				if (be.insertDisk(one)) {
					// Always consumed, creative mode included: a disk is one file, and two copies of it in two slots
					// would fight over the image (QEMU refuses the second open).
					stack.shrink(1);
					player.displayClientMessage(Component.translatable("virtualminecraft.msg.disk_inserted"), true);
				}
			}
		}
		return InteractionResult.CONSUME;
	}

	private InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult) {
		final boolean ejecting = player.isShiftKeyDown() && level.getBlockEntity(pos) instanceof ComputerBlockEntity c && c.hasDisks();
		if (level.isClientSide()) {
			if (!ejecting) {
				VirtualMinecraft.clientHooks.openComputerScreen(pos);
			}
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof ComputerBlockEntity be)) {
			return InteractionResult.SUCCESS;
		}
		if (ejecting) {
			if (be.disksChangeable(serverLevel, player)) {
				final ItemStack out = be.ejectLastDisk();
				if (!out.isEmpty()) {
					player.getInventory().placeItemBackInInventory(out);
					player.displayClientMessage(Component.translatable("virtualminecraft.msg.disk_ejected"), true);
				}
			}
			return InteractionResult.CONSUME;
		}
		// Tell the guest someone is at the keyboard: {player, side, sneaking}.
		final dev.virtualminecraft.vm.VmInstance vm = VmManager.get(serverLevel.getServer()).get(be.getVmId());
		final dev.virtualminecraft.bus.VmBus bus = vm == null ? null : vm.bus();
		if (bus != null && bus.wantsEvent("player_used")) {
			final com.google.gson.JsonObject p = new com.google.gson.JsonObject();
			p.addProperty("player", player.getName().getString());
			p.addProperty("side", hitResult.getDirection().getSerializedName());
			p.addProperty("sneaking", player.isShiftKeyDown());
			bus.event("player_used", p);
		}
		return InteractionResult.SUCCESS;
	}
}
