package dev.virtualminecraft.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jetbrains.annotations.Nullable;

/**
 * The clerk's workstation (ROADMAP §9 U3c step 2, [name] 2026-08-28): a block that does nothing for a player and
 * everything for a villager -- an unemployed one that finds it becomes a Clerk and sells software. Its own block so
 * no vanilla profession is displaced (the fletching table is the fletcher's) and no other mod can claim it.
 */
public final class CashRegisterBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<CashRegisterBlock> CODEC = simpleCodec(CashRegisterBlock::new);

	public CashRegisterBlock(final Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	public @Nullable BlockState getStateForPlacement(final BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
		builder.add(FACING);
	}
}
