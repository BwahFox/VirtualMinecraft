package dev.virtualminecraft.bus;

import dev.virtualminecraft.ModContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Where a computer's components may sit: the six blocks touching it, plus everything touching a run of bus
 * cable connected to it (OpenComputers' adapter, CC: Tweaked's wired modem). Pure geometry — it answers
 * "which positions belong to this computer" and the component providers ({@link InventoryComponent},
 * {@link DriveComponent}) and {@link dev.virtualminecraft.vm.Attachments} do the rest.
 * <p>
 * <b>Locations.</b> A block touching the computer keeps its side name ({@code north}) so addresses built
 * before cables existed stay valid; a block reached through cable is located by its offset from the
 * computer, {@code dx,dy,dz}, the same form {@link ScreenComponent} already uses for monitors. Addresses
 * derive from the location, so moving a block changes its address — deliberately: it is a different socket.
 * <p>
 * <b>Bounds.</b> The fill stops at {@link #MAX_CABLES} cables and {@link #MAX_ATTACHED} attached blocks. It
 * never <em>loads</em> a chunk, but since §9 U11b it follows cables {@link BusRegistry} remembers through
 * unloaded ones, so a half-loaded run reads as one run; candidates are still only inspected where the world
 * is loaded, and the remembered components in the gap are {@link BusRegistry#phantomsOnRun} — demand-loaded
 * by the bus call that actually wants them. Server thread only.
 */
public final class BusNetwork {
	/** The default reach of a cable run; {@link dev.virtualminecraft.config.VmcConfig#busMaxCables} overrides it. */
	public static final int MAX_CABLES = 128;
	/** The default attachment cap; {@link dev.virtualminecraft.config.VmcConfig#busMaxAttached} overrides it. */
	public static final int MAX_ATTACHED = 64;

	/**
	 * The caps are configurable since §9 U9, because they were invisible: a run that stopped at 128 looked
	 * exactly like a run with a gap in it, with no message anywhere. {@code net.list()} now says when a fill hit
	 * one of these, which is the other half of that fix.
	 */
	public static int maxCables() {
		return Math.max(1, dev.virtualminecraft.config.VmcConfig.get().busMaxCables);
	}

	public static int maxAttached() {
		return Math.max(1, dev.virtualminecraft.config.VmcConfig.get().busMaxAttached);
	}

	/** True if the run touching {@code from} is as long as it is allowed to get — i.e. it may have been cut short. */
	public static boolean runHitCap(final ServerLevel level, final BlockPos from) {
		for (final BusRegistry.Net net : BusRegistry.netsAt(level, from)) {
			if (net.cappedCables()) {
				return true;
			}
		}
		return false;
	}

	private BusNetwork() {
	}

	public static boolean isCable(final ServerLevel level, final BlockPos pos) {
		return level.hasChunkAt(pos) && level.getBlockState(pos).is(ModContent.BUS_CABLE);
	}

	/**
	 * Positions that may hold a component for the computer at {@code computer}, in a stable order (the six
	 * neighbours in {@link Direction} order first, then cable-attached blocks nearest first), mapped to the
	 * location string a component there should use.
	 */
	public static LinkedHashMap<BlockPos, String> attached(final ServerLevel level, final BlockPos computer) {
		final LinkedHashMap<BlockPos, String> out = new LinkedHashMap<>();
		for (final Direction d : Direction.values()) {
			final BlockPos p = computer.relative(d);
			if (level.hasChunkAt(p)) {
				out.put(p, Sides.name(d));
			}
		}
		// §9 U11: the run's candidate positions come from the stored network, which was walked when the cable
		// last changed. Before this the list was a fresh flood fill on every call, which is what made a long
		// cable something a program paid for for ever rather than something a player paid for once.
		final List<BlockPos> candidates = BusRegistry.attachedOnRun(level, computer);
		if (candidates.isEmpty()) {
			return out;
		}
		// **Block entities first, then distance.** Every non-air block beside a cable is a candidate here, and in
		// solid ground that is four per cable — so a plain distance sort spent the whole cap on stone within a few
		// blocks of the computer, and a disk drive a hundred blocks down the wire was never reached. Nothing is
		// excluded (a container without a block entity is still eligible); the things that can actually *be*
		// components simply get the seats first. Found by U9's two-machine test, 2026-08-29.
		// The registry keeps the two groups apart, so what is sorted here is already in the right order of
		// interest; a position in an unloaded chunk counts as interesting, because it is remembered as a
		// component and U11b will demand-load it.
		final Set<BlockPos> interesting = new LinkedHashSet<>();
		final List<BlockPos> remote = new ArrayList<>(candidates.size());
		for (final BlockPos p : candidates) {
			if (p.equals(computer) || out.containsKey(p)) {
				continue;
			}
			remote.add(p);
			if (!level.hasChunkAt(p) || level.getBlockEntity(p) != null) {
				interesting.add(p);
			}
		}
		remote.sort(Comparator.<BlockPos>comparingInt(p -> interesting.contains(p) ? 0 : 1)
			.thenComparingDouble(p -> p.distSqr(computer)).thenComparingInt(BlockPos::getX)
			.thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
		int n = 0;
		for (final BlockPos p : remote) {
			if (n++ >= maxAttached()) {
				break;
			}
			out.put(p, offsetLocation(computer, p));
		}
		return out;
	}

	/** {@code dx,dy,dz} — the form {@link ScreenComponent} uses for monitors. */
	public static String offsetLocation(final BlockPos origin, final BlockPos pos) {
		final BlockPos d = pos.subtract(origin);
		return d.getX() + "," + d.getY() + "," + d.getZ();
	}

	/** Side name when {@code pos} touches {@code computer}, else its offset. Matches what {@link #attached} hands out. */
	public static String locationOf(final BlockPos computer, final BlockPos pos) {
		for (final Direction d : Direction.values()) {
			if (computer.relative(d).equals(pos)) {
				return Sides.name(d);
			}
		}
		return offsetLocation(computer, pos);
	}

	/**
	 * The computer a block at {@code pos} belongs to: one it touches (in {@link Direction} order), else the
	 * nearest one reachable through cable. Used by blocks that serve exactly one computer (disk drives).
	 */
	public static @Nullable BusHost computerFor(final ServerLevel level, final BlockPos pos) {
		for (final Direction d : Direction.values()) {
			final BlockPos p = pos.relative(d);
			if (level.hasChunkAt(p) && level.getBlockEntity(p) instanceof BusHost c) {
				return c;
			}
		}
		BusHost best = null;
		BlockPos bestPos = null;
		for (final BusHost c : computersOnNetwork(level, pos)) {
			final BlockPos p = c.hostPos();
			if (bestPos == null || p.distSqr(pos) < bestPos.distSqr(pos) || (p.distSqr(pos) == bestPos.distSqr(pos) && p.compareTo(bestPos) < 0)) {
				best = c;
				bestPos = p;
			}
		}
		return best;
	}

	/**
	 * Every loaded computer reachable through the cable run touching {@code pos} — a lookup against the stored
	 * network since §9 U11. Used to tell computers that something on their network changed, and by
	 * {@link #computerFor}. A cable run may serve several computers.
	 */
	public static List<BusHost> computersOnNetwork(final ServerLevel level, final BlockPos pos) {
		final List<BusHost> out = new ArrayList<>();
		for (final BusRegistry.Reachable r : BusRegistry.onRun(level, pos)) {
			final BusHost h = BusRegistry.live(level, r.pos());
			if (h != null) {
				out.add(h);
			}
		}
		return out;
	}
}
