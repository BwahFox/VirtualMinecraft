package dev.virtualminecraft.block;

import dev.virtualminecraft.ModContent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Groups adjacent monitors into rectangles (milestone 5 A5). Monitors in one wall plane with the same facing and
 * the same source form a connected set; the set is carved into rectangles by one deterministic pass — take the
 * bottom-left-most free cell, grow right as far as the row runs, then grow up while every cell of the next row is
 * free — and each monitor is told its index and its rectangle's size. The pass depends only on the set, not on
 * which block triggered it, so placing, breaking and relinking all just call {@link #rebuildAround}.
 * <p>
 * Nothing is stored beyond the four ints on each block entity, so there is no group object to keep in sync.
 */
public final class MonitorMultiblock {
	/** Flood-fill cap; a wall bigger than this is split arbitrarily rather than searched forever. */
	private static final int MAX_CELLS = 4096;

	private MonitorMultiblock() {
	}

	/** Rebuilds the group containing {@code pos} and the groups of every in-plane neighbour (for removals and relinks). */
	public static void rebuildAround(final ServerLevel level, final BlockPos pos, final BlockState state) {
		final Set<BlockPos> done = new HashSet<>();
		if (state.is(ModContent.MONITOR)) {
			rebuild(level, pos, done);
		}
		final Direction facing = state.hasProperty(MonitorBlock.FACING) ? state.getValue(MonitorBlock.FACING) : null;
		for (final BlockPos n : inPlaneNeighbours(pos, facing)) {
			if (!done.contains(n) && level.getBlockEntity(n) instanceof MonitorBlockEntity) {
				rebuild(level, n, done);
			}
		}
	}

	private static List<BlockPos> inPlaneNeighbours(final BlockPos pos, final @Nullable Direction facing) {
		final List<BlockPos> out = new ArrayList<>(4);
		if (facing == null) {
			for (final Direction d : Direction.values()) {
				out.add(pos.relative(d));
			}
			return out;
		}
		final Direction right = facing.getCounterClockWise();
		out.add(pos.relative(right));
		out.add(pos.relative(right.getOpposite()));
		out.add(pos.above());
		out.add(pos.below());
		return out;
	}

	private static void rebuild(final ServerLevel level, final BlockPos start, final Set<BlockPos> done) {
		if (!(level.getBlockEntity(start) instanceof MonitorBlockEntity first)) {
			return;
		}
		final Direction facing = first.getBlockState().getValue(MonitorBlock.FACING);
		final BlockPos source = first.getSourcePos();
		final Direction right = facing.getCounterClockWise();

		// Connected set in the wall plane, keyed by (x right, y up) relative to the start block.
		final Map<Long, MonitorBlockEntity> cells = new HashMap<>();
		final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		cells.put(key(0, 0), first);
		while (!queue.isEmpty() && cells.size() < MAX_CELLS) {
			final BlockPos p = queue.poll();
			for (final BlockPos n : inPlaneNeighbours(p, facing)) {
				final int x = dot(n.subtract(start), right);
				final int y = n.getY() - start.getY();
				if (cells.containsKey(key(x, y)) || !level.hasChunkAt(n)) {
					continue;
				}
				if (level.getBlockEntity(n) instanceof MonitorBlockEntity m && m.getBlockState().getValue(MonitorBlock.FACING) == facing && Objects.equals(m.getSourcePos(), source)) {
					cells.put(key(x, y), m);
					queue.add(n);
				}
			}
		}
		for (final MonitorBlockEntity m : cells.values()) {
			done.add(m.getBlockPos());
		}

		// Carve into rectangles, bottom-left first.
		final List<long[]> order = new ArrayList<>(cells.size());
		for (final long k : cells.keySet()) {
			order.add(new long[] { y(k), x(k) });
		}
		order.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
		final Set<Long> assigned = new HashSet<>();
		for (final long[] yx : order) {
			final int x0 = (int) yx[1];
			final int y0 = (int) yx[0];
			if (assigned.contains(key(x0, y0))) {
				continue;
			}
			int w = 1;
			while (free(cells, assigned, x0 + w, y0)) {
				w++;
			}
			int h = 1;
			while (rowFree(cells, assigned, x0, y0 + h, w)) {
				h++;
			}
			for (int dy = 0; dy < h; dy++) {
				for (int dx = 0; dx < w; dx++) {
					final long k = key(x0 + dx, y0 + dy);
					assigned.add(k);
					cells.get(k).setMultiblock(dx, dy, w, h);
				}
			}
		}
	}

	private static boolean free(final Map<Long, MonitorBlockEntity> cells, final Set<Long> assigned, final int x, final int y) {
		final long k = key(x, y);
		return cells.containsKey(k) && !assigned.contains(k);
	}

	private static boolean rowFree(final Map<Long, MonitorBlockEntity> cells, final Set<Long> assigned, final int x0, final int y, final int w) {
		for (int dx = 0; dx < w; dx++) {
			if (!free(cells, assigned, x0 + dx, y)) {
				return false;
			}
		}
		return true;
	}

	private static int dot(final BlockPos d, final Direction dir) {
		return d.getX() * dir.getStepX() + d.getY() * dir.getStepY() + d.getZ() * dir.getStepZ();
	}

	private static long key(final int x, final int y) {
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	private static int x(final long k) {
		return (int) (k >> 32);
	}

	private static int y(final long k) {
		return (int) k;
	}
}
