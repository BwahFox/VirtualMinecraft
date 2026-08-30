package dev.virtualminecraft.bus;

import dev.virtualminecraft.block.ModemBlockEntity;
import dev.virtualminecraft.config.VmcConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Where the {@code net} component's <em>wireless</em> peers come from (ROADMAP §9 U3, the second half of the
 * networking decision: cable first, then a modem block with the same API). A modem gives every machine on its
 * bus reach to every machine whose bus has a modem within {@link VmcConfig#modemRange} blocks — no addressing
 * scheme, no channels, no state: exactly the cable rule with a radius instead of a wire.
 * <p>
 * <b>The registry is a set of hints, and lookups verify them</b> — the same rule as
 * {@link dev.virtualminecraft.screen.ScreenSources}, for the same reason: {@code setRemoved} does not fire
 * server-side when a chunk is demoted in 26.2, so a position stays in the set after its chunk goes away.
 * {@link #inRange} re-checks that a modem is still loaded and still a modem, and drops entries that are not,
 * so a broken or unloaded modem stops answering at once and the set self-prunes.
 * <p>
 * Server thread only, like everything else on the bus.
 */
public final class Modems {
	private static final Map<ResourceKey<Level>, Set<BlockPos>> BY_LEVEL = new ConcurrentHashMap<>();

	private Modems() {
	}

	/** A modem notes itself on its first server tick after loading, and again after it is placed. */
	public static void note(final ServerLevel level, final BlockPos pos) {
		BY_LEVEL.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
	}

	public static void forget(final ServerLevel level, final BlockPos pos) {
		final Set<BlockPos> set = BY_LEVEL.get(level.dimension());
		if (set != null) {
			set.remove(pos);
		}
	}

	/** The modems on {@code host}'s own bus: what gives it wireless reach at all. */
	public static List<BlockPos> own(final ServerLevel level, final BusHost host) {
		final List<BlockPos> out = new ArrayList<>();
		for (final BlockPos p : host.attached(level).keySet()) {
			if (level.getBlockEntity(p) instanceof ModemBlockEntity) {
				out.add(p.immutable());
			}
		}
		return out;
	}

	/**
	 * Loaded modems within {@link VmcConfig#modemRange} of any of {@code from}, nearest first, excluding
	 * {@code from} itself. Prunes stale registry entries as it goes.
	 */
	public static List<BlockPos> inRange(final ServerLevel level, final List<BlockPos> from) {
		final Set<BlockPos> set = BY_LEVEL.get(level.dimension());
		if (set == null || set.isEmpty() || from.isEmpty()) {
			return List.of();
		}
		final double range = VmcConfig.get().modemRange;
		final double maxSq = range * range;
		final List<BlockPos> out = new ArrayList<>();
		final Iterator<BlockPos> it = set.iterator();
		while (it.hasNext()) {
			final BlockPos p = it.next();
			if (!level.hasChunkAt(p) || !(level.getBlockEntity(p) instanceof ModemBlockEntity)) {
				it.remove(); // unloaded or broken: a hint that went stale
				continue;
			}
			if (from.contains(p)) {
				continue;
			}
			double best = Double.MAX_VALUE;
			for (final BlockPos mine : from) {
				best = Math.min(best, mine.distSqr(p));
			}
			if (best <= maxSq) {
				out.add(p);
			}
		}
		out.sort(Comparator.comparingDouble(p -> {
			double best = Double.MAX_VALUE;
			for (final BlockPos mine : from) {
				best = Math.min(best, mine.distSqr(p));
			}
			return best;
		}));
		return out;
	}

	/**
	 * Every machine reachable by radio from {@code host}: the machines on the bus of every modem in range of
	 * {@code host}'s own modems. {@code host} itself and machines already on its own bus are the caller's
	 * problem to filter — {@link NetComponent} dedupes by machine id, so a peer reachable both ways is listed once.
	 */
	public static List<BusHost> peers(final ServerLevel level, final BusHost host) {
		final List<BlockPos> mine = own(level, host);
		if (mine.isEmpty()) {
			return List.of();
		}
		final List<BusHost> out = new ArrayList<>();
		final Set<BlockPos> seen = new LinkedHashSet<>();
		for (final BlockPos modem : inRange(level, mine)) {
			for (final BusHost h : hostsOf(level, modem)) {
				if (seen.add(h.hostPos().immutable())) {
					out.add(h);
				}
			}
		}
		return out;
	}

	/** The machines a modem serves: the ones it touches, plus every machine on the cable run it sits on. */
	public static List<BusHost> hostsOf(final ServerLevel level, final BlockPos modem) {
		final List<BusHost> out = new ArrayList<>();
		for (final net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			final BlockPos p = modem.relative(d);
			if (level.hasChunkAt(p) && level.getBlockEntity(p) instanceof BusHost h && !out.contains(h)) {
				out.add(h);
			}
		}
		for (final BusHost h : BusNetwork.computersOnNetwork(level, modem)) {
			if (!out.contains(h)) {
				out.add(h);
			}
		}
		return out;
	}
}
