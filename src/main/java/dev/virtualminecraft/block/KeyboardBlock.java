package dev.virtualminecraft.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A keyboard on a desk (ROADMAP §9 U4.3's second half, asked for by [name] herself, 2026-08-29: <i>"a block that
 * always puts the keyboard in the same place, so the user can get used to where the keyboard is, kinda like how I
 * know where everything is in real life"</i>). In VR, opening the keyboard at a screen anchors Vivecraft's floating
 * keyboard over the nearest one of these instead of wherever your head happened to be — which is what lets typing
 * build muscle memory: a keyboard that moves with you never can.
 * <p>
 * The block itself has <b>no behaviour</b>, exactly like the cash register: everything that reads it lives in the
 * {@code vr} module ({@code KeyboardAnchor} finds the nearest one when the keyboard gesture fires), so a world
 * with keyboards on every desk behaves identically on a desktop client — there it is furniture, and furniture is
 * fine. {@code FACING} is which way the keys face: the space bar ends up on the side toward whoever placed it.
 */
public final class KeyboardBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<KeyboardBlock> CODEC = simpleCodec(KeyboardBlock::new);

	/** The slab of keys, <b>in pixels</b> (see {@code BlockShapeTest} for why that unit is worth a test). */
	public static final double WIDTH_PX = 14.0;
	public static final double DEPTH_PX = 8.0;
	public static final double HEIGHT_PX = 2.0;

	private static final VoxelShape SHAPE_NS = Block.box(
		(16.0 - WIDTH_PX) / 2.0, 0.0, (16.0 - DEPTH_PX) / 2.0,
		(16.0 + WIDTH_PX) / 2.0, HEIGHT_PX, (16.0 + DEPTH_PX) / 2.0);
	private static final VoxelShape SHAPE_EW = Block.box(
		(16.0 - DEPTH_PX) / 2.0, 0.0, (16.0 - WIDTH_PX) / 2.0,
		(16.0 + DEPTH_PX) / 2.0, HEIGHT_PX, (16.0 + WIDTH_PX) / 2.0);

	public KeyboardBlock(final Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
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
	protected VoxelShape getShape(final BlockState state, final BlockGetter level, final net.minecraft.core.BlockPos pos, final CollisionContext ctx) {
		return state.getValue(FACING).getAxis() == Direction.Axis.Z ? SHAPE_NS : SHAPE_EW;
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}
}
