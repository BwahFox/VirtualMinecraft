package dev.virtualminecraft.block;

import com.mojang.serialization.MapCodec;
import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.bus.BusNetwork;
import dev.virtualminecraft.bus.BusRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;

/**
 * Bus cable: extends a computer's reach so components do not have to touch it (OpenComputers' adapter,
 * CC: Tweaked's wired modem). Anything touching a connected run of cable is a component of every computer
 * on that run — including disk drives, which is how a drive bay ends up across the room from the case.
 * <p>
 * The block itself holds no state and no block entity: {@link BusNetwork} floods the run on demand. The six
 * boolean properties are cosmetic (which arms to draw); what is actually connected is decided server-side by
 * the flood fill, so a cable that visually connects to a sign still contributes nothing.
 */
public class BusCableBlock extends PipeBlock {
	public static final MapCodec<BusCableBlock> CODEC = simpleCodec(BusCableBlock::new);
	/**
	 * Width of the core, <b>in pixels</b> — {@link PipeBlock} passes this straight to {@link Block#cube(double)}
	 * and {@link Block#boxZ(double, double, double)}, both of which measure in sixteenths of a block. Vanilla's
	 * own {@code ChorusPlantBlock} passes {@code 10.0F} for the same reason.
	 * <p>
	 * This was {@code 0.1875F} — the half-width as a <em>fraction</em> — from milestone 4 until session 18, which
	 * built a collision shape a fifth of a pixel across. The cable therefore had, for practical purposes, no
	 * hitbox at all: you could not break it and you walked straight through it, and a mis-aimed swing carried on
	 * into whatever was behind it. [name] found it the expensive way, with a house in the way.
	 * <p>
	 * 6 matches the model's 5..11 core exactly. The arms come out a little fatter than they are drawn (PipeBlock
	 * derives both from this one number: 6 wide and reaching to the block centre, against the model's 4 wide and
	 * 5 deep), which errs towards being easy to hit — the right direction for a thin block.
	 * <p>
	 * Public so {@code BlockShapeTest} can assert it is still a sane pixel width; nothing else reads it.
	 */
	public static final float CORE_PX = 6.0F;

	public BusCableBlock(final Properties properties) {
		super(CORE_PX, properties);
		this.registerDefaultState(this.stateDefinition.any()
			.setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
			.setValue(WEST, false).setValue(UP, false).setValue(DOWN, false));
	}

	@Override
	protected MapCodec<? extends PipeBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
	}

	/** Cosmetic only: draw an arm towards other cable, our own blocks, and anything with a block entity (containers). */
	private static boolean connectsTo(final BlockState state) {
		return state.is(ModContent.BUS_CABLE) || state.is(ModContent.COMPUTER) || state.is(ModContent.MONITOR)
			|| state.is(ModContent.DISK_DRIVE) || state.hasBlockEntity();
	}

	@Override
	public BlockState getStateForPlacement(final BlockPlaceContext context) {
		final LevelReader level = context.getLevel();
		final BlockPos pos = context.getClickedPos();
		BlockState state = this.defaultBlockState();
		for (final Direction d : Direction.values()) {
			state = state.setValue(PROPERTY_BY_DIRECTION.get(d), connectsTo(level.getBlockState(pos.relative(d))));
		}
		return state;
	}

	@Override
	protected BlockState updateShape(final BlockState state, final LevelReader level, final ScheduledTickAccess tickAccess, final BlockPos pos,
			final Direction direction, final BlockPos neighborPos, final BlockState neighborState, final RandomSource random) {
		return state.setValue(PROPERTY_BY_DIRECTION.get(direction), connectsTo(neighborState));
	}

	/**
	 * Something beside this cable changed. If it changed what the bus is made of — a block placed or broken, a
	 * machine or a component appearing or going — the run is discarded and every computer on it re-samples, so
	 * {@code component_added} / {@code component_removed} fire for blocks nowhere near the computer.
	 * <p>
	 * <b>Most neighbour updates are not that.</b> A redstone level changing next door fires this on every cable
	 * it touches, and until §9 U11 each one re-flooded the whole run and woke every machine on it — so a clock
	 * beside one cable of a thousand-cable run cost a thousand floods a tick. {@link BusRegistry} now answers
	 * "did anything I care about actually change" in a handful of lookups, and the answer is almost always no.
	 * A computer's own redstone still arrives through its own block's {@code neighborChanged}, which is where it
	 * always came from — the bus never carried it.
	 */
	@Override
	protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, final @Nullable Orientation orientation, final boolean movedByPiston) {
		if (level instanceof ServerLevel serverLevel && BusRegistry.noteNeighbourChanged(serverLevel, pos)) {
			for (final dev.virtualminecraft.bus.BusHost computer : BusNetwork.computersOnNetwork(serverLevel, pos)) {
				computer.onNeighborChanged(serverLevel);
			}
		}
	}

	/** A cable appeared: remember it, and let go of the runs it may have just joined together. */
	@Override
	protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (level instanceof ServerLevel serverLevel && !state.is(oldState.getBlock())) {
			BusRegistry.noteCable(serverLevel, pos, true);
			for (final dev.virtualminecraft.bus.BusHost computer : BusNetwork.computersOnNetwork(serverLevel, pos)) {
				computer.onNeighborChanged(serverLevel);
			}
		}
	}

	/** A cable is gone: forget it, and let go of the run it may have just cut in two. */
	@Override
	protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		BusRegistry.noteCable(level, pos, false);
		for (final dev.virtualminecraft.bus.BusHost computer : BusNetwork.computersOnNetwork(level, pos)) {
			computer.onNeighborChanged(level);
		}
	}
}
