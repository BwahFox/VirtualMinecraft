package dev.virtualminecraft.block;

import com.mojang.serialization.MapCodec;
import dev.virtualminecraft.item.DiskItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** Disk drive: right-click with a floppy or CD to insert it, right-click empty-handed to take it out. Must touch a computer. */
public class DiskDriveBlock extends BaseEntityBlock {
	public static final MapCodec<DiskDriveBlock> CODEC = simpleCodec(DiskDriveBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	public DiskDriveBlock(final Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
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
	protected RenderShape getRenderShape(final BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		return new DiskDriveBlockEntity(pos, state);
	}

	@Override
	protected InteractionResult useItemOn(final ItemStack stack, final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final BlockHitResult hit) {
		if (!DiskItem.isDisk(stack)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof DiskDriveBlockEntity drive) {
			final ItemStack one = stack.copyWithCount(1);
			if (drive.insert(serverLevel, one, player)) {
				stack.shrink(1); // creative too: a disk is one file, never duplicate it
			}
		}
		return InteractionResult.CONSUME;
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof DiskDriveBlockEntity drive) {
			final ItemStack out = drive.eject(serverLevel, player);
			if (!out.isEmpty()) {
				player.getInventory().placeItemBackInInventory(out);
			}
		}
		return InteractionResult.CONSUME;
	}
}
