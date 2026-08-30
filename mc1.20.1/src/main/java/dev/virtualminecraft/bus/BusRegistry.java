package dev.virtualminecraft.bus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.computer.ComputerManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Where a bus goes when nobody is looking (ROADMAP §9 U9, option 1 — [name]'s call, 2026-08-29).
 * <p>
 * <b>The problem.</b> {@link BusNetwork}'s flood fill never enters an unloaded chunk, and {@link Modems} only
 * finds loaded modems, so a machine whose chunk is not loaded is <em>invisible to the network</em>. Nothing can
 * ask it for anything — not even to wake up. The freeze/thaw half was already solved (a machine freezes on
 * unload and an event thaws it); the missing half was that the event could never be addressed to it.
 * <p>
 * <b>The answer.</b> Remember where the cables and the computers are, so peers resolve from <em>saved</em> data
 * instead of from loaded chunks. Nothing is force-loaded to keep this true and nothing ticks that was not going
 * to tick: this is a map, not a chunk loader. {@link BusWake} is the separate, deliberate step that briefly
 * makes a chunk tick when a message is actually addressed to a machine inside it.
 * <p>
 * <b>Hints, verified on use</b> — the same rule as {@link Modems} and {@code ScreenSources}, for the same
 * reason: {@code setRemoved} does not fire server-side when a chunk is demoted, so an entry outlives the block
 * it describes. Every lookup that can see a position checks it, and drops what is no longer there. The
 * consequence is that a stale entry is at worst a peer that does not answer, never a wrong delivery.
 * <p>
 * <b>How it fills up.</b> A loaded computer records its own run every few seconds, so any cable a live machine
 * can see is registered without anyone walking the world; cable placement and breaking keep the edges honest in
 * between. A run that no loaded computer has ever touched is not in here, which is correct — nothing could have
 * reached it before either.
 * <p>
 * Server thread only, like everything else on the bus.
 */
public final class BusRegistry {
	/** What a saved computer position knows about itself. */
	public record Host(BlockPos pos, UUID id, String name) {
	}

	/** A peer as {@link NetComponent} sees it: where it is, and whether it can be spoken to right now. */
	public record Reachable(BlockPos pos, UUID id, String name, boolean loaded) {
	}

	/**
	 * A cable run as a <em>stored fact</em> (§9 U11, [name]: <em>"the only thing the cable needs to do is just
	 * tell the server which computers are connected to each other."</em>). Built by one walk when the cable
	 * changes; every {@code net.list()}, component lookup and delivery after that is a lookup against this
	 * object rather than another flood. Not persisted — it is derived from the cables, hosts and attachments
	 * that are, and rebuilding it costs one walk.
	 */
	public static final class Net {
		final int id;
		/** Every cable position on the run — also how the run is un-labelled when it is discarded. */
		final Set<Long> cables = new LinkedHashSet<>();
		/** The machines hanging off it, including whichever one asked. */
		final Set<Long> hosts = new LinkedHashSet<>();
		/** Positions beside the run holding a block entity, or remembered as having held one while unloaded. */
		final Set<Long> attachments = new LinkedHashSet<>();
		/** Non-air, no block entity, seen while loaded: eligible but almost never a component. Kept so a block
		 * exposing storage through the API alone still counts, exactly as the old per-call flood had it. */
		final Set<Long> plain = new LinkedHashSet<>();
		/** Bridge blocks sitting on this run (§9 U11): the doors to whatever their partners are attached to. */
		final Set<Long> bridges = new LinkedHashSet<>();
		/** The walk stopped at {@code busMaxCables} — the run may go further. {@code net.list()} says so. */
		boolean cappedCables;
		/** Game time it was built, for the slow revalidation beat. */
		long builtAtTick;

		Net(final int id) {
			this.id = id;
		}

		public int id() {
			return id;
		}

		public boolean cappedCables() {
			return cappedCables;
		}

		public int cableCount() {
			return cables.size();
		}
	}

	private static final class Level1 {
		final Set<Long> cables = ConcurrentHashMap.newKeySet();
		final Map<Long, Host> hosts = new ConcurrentHashMap<>();
		/** Positions beside a run's cables that held a block entity when a machine last looked — the chests,
		 * drives and monitors a woken machine must still be able to reach (§9 U11b). Same trust as cables. */
		final Set<Long> attachments = ConcurrentHashMap.newKeySet();
		/** §9 U11: cable position → the run it belongs to. Absent means "not walked since it last changed". */
		final Map<Long, Net> netOf = new ConcurrentHashMap<>();
		/** §9 U11 bridges: bridge position → the pair id it carries. Two bridges sharing one join their runs. */
		final Map<Long, UUID> bridges = new ConcurrentHashMap<>();
		int nextNetId = 1;
	}

	/** How many run walks have happened this server run, and how many were thrown away. For {@code /vmc bus}. */
	private static volatile int rebuilds;
	private static volatile int discards;

	private static final Map<ResourceKey<Level>, Level1> BY_LEVEL = new ConcurrentHashMap<>();
	private static volatile boolean dirty;
	private static volatile boolean loaded;

	private BusRegistry() {
	}

	private static Level1 of(final ServerLevel level) {
		return BY_LEVEL.computeIfAbsent(level.dimension(), k -> new Level1());
	}

	// ------------------------------------------------------------------------------------------- recording

	/**
	 * A cable was placed or broken (§9 U11). The registry's cable set is corrected and the run(s) this position
	 * touches are discarded, so the next lookup walks once and everything after that is a lookup.
	 * <p>
	 * Until session 24 nothing called this: it and {@code forgetCable} were written for U9 and never wired to
	 * the block, so the cable set was filled only by the machines' beat and by verify-on-use. That was survivable
	 * while every call re-flooded the world; with connectivity stored it is the whole mechanism.
	 */
	public static void noteCable(final ServerLevel level, final BlockPos pos, final boolean present) {
		final Level1 l = of(level);
		if (present ? l.cables.add(pos.asLong()) : l.cables.remove(pos.asLong())) {
			dirty = true;
		}
		discardAround(level, l, pos);
	}

	/** A machine appeared or went away: the run it hangs off has different membership now. */
	public static void noteHostChanged(final ServerLevel level, final BlockPos pos) {
		discardAround(level, of(level), pos);
	}

	public static void forgetHost(final ServerLevel level, final BlockPos pos) {
		final Level1 l = of(level);
		if (l.hosts.remove(pos.asLong()) != null) {
			dirty = true;
		}
		discardAround(level, l, pos);
	}

	/** A remembered component that turned out not to be there when its chunk was loaded for it. */
	public static void forgetAttachment(final ServerLevel level, final BlockPos pos) {
		if (of(level).attachments.remove(pos.asLong())) {
			dirty = true;
		}
	}

	/**
	 * A loaded machine says "this is me". Called on a beat; it also makes sure its run has been walked, which
	 * costs nothing once it has, and is the safety net that rebuilds a run some edit discarded.
	 */
	public static void record(final ServerLevel level, final BusHost host) {
		final Level1 l = of(level);
		final long key = host.getBlockPos().asLong();
		final Host was = l.hosts.get(key);
		final Host now = new Host(host.getBlockPos().immutable(), host.busId(), host.busName());
		if (was == null || !was.equals(now)) {
			l.hosts.put(key, now);
			dirty = true;
		}
		final int seconds = dev.virtualminecraft.config.VmcConfig.get().busRebuildSeconds;
		for (final Net net : netsAt(level, host.getBlockPos())) {
			// A run walked before this machine existed does not know about it — this is how a newly placed
			// computer joins one, without the block classes having to say so.
			boolean stale = !net.hosts.contains(key);
			// Slow revalidation: a cable removed without a block update (a datapack, another mod, an edit while
			// the server was down) leaves a run claiming a connection that is gone, and nothing else would ever
			// catch it. One walk a minute per run, against the thousands a second the old design paid, is cheap.
			stale |= seconds > 0 && level.getGameTime() - net.builtAtTick >= seconds * 20L;
			if (stale) {
				discard(l, net);
			}
		}
		netsAt(level, host.getBlockPos()); // rebuild whatever was just thrown away
	}

	// ------------------------------------------------------------------------------------------- networks

	/**
	 * The runs touching {@code pos} — the stored fact of §9 U11. A labelled cable answers immediately, which is
	 * the steady state; an unlabelled one is walked once and answers by lookup from then on. Empty when there is
	 * no cable here at all (a machine using only its six neighbours).
	 * <p>
	 * <b>A run is a connected component of cable, and nothing else.</b> A block that merely <em>touches</em>
	 * cable — a computer, a disk drive — can touch two separate runs, and then it is on both rather than
	 * welding them into one. That keeps the answer independent of which block happened to ask first, and it is
	 * what the old per-call flood did: a computer saw both runs, a cable on one of them did not see the other.
	 * Getting this wrong is not academic — seeding a walk from a <em>gap</em> made a freshly cut cable heal
	 * itself, because the two ends were both neighbours of the hole.
	 */
	public static List<Net> netsAt(final ServerLevel level, final BlockPos pos) {
		final Level1 l = of(level);
		if (isCableAnywhere(level, l, pos)) {
			return List.of(netFor(level, l, pos));
		}
		final List<Net> out = new ArrayList<>(2);
		for (final Direction d : Direction.values()) {
			final BlockPos p = pos.relative(d);
			if (!isCableAnywhere(level, l, p)) {
				continue;
			}
			final Net n = netFor(level, l, p);
			if (!out.contains(n)) {
				out.add(n);
			}
		}
		return out;
	}

	/**
	 * Every run reachable from {@code pos}: the ones it touches, then whatever their bridges open onto, and so
	 * on (§9 U11 bridges, [name]'s call 2026-08-28 — a bridge is crafted as a linked pair and joins the two runs
	 * its halves sit on). Nearest hop first.
	 * <p>
	 * <b>Bridged runs stay separate objects.</b> Joining them into one would throw away the property that makes
	 * this cheap — each segment is walked and capped on its own, and a bridge costs a map lookup rather than a
	 * longer flood. It is also what lets the far side be entirely unloaded: the partner's position is saved, so
	 * the hop is a table lookup and nothing in between is ever touched.
	 * <p>
	 * Loops are harmless — a run already seen is not visited twice — so {@code busMaxBridgeHops} is a bound on
	 * how far one machine's world may extend, not a correctness device. Same dimension only, for now: the
	 * offsets components are addressed by have no meaning across levels.
	 */
	public static List<Net> reachableNets(final ServerLevel level, final BlockPos from) {
		final List<Net> out = new ArrayList<>(netsAt(level, from));
		final Level1 l = of(level);
		if (l.bridges.isEmpty() || out.isEmpty()) {
			return out;
		}
		final Set<Net> seen = new LinkedHashSet<>(out);
		ArrayDeque<Net> frontier = new ArrayDeque<>(out);
		final int maxHops = Math.max(0, dev.virtualminecraft.config.VmcConfig.get().busMaxBridgeHops);
		for (int hop = 0; hop < maxHops && !frontier.isEmpty(); hop++) {
			final ArrayDeque<Net> next = new ArrayDeque<>();
			for (final Net net : frontier) {
				for (final long bridgePos : List.copyOf(net.bridges)) {
					final UUID pair = l.bridges.get(bridgePos);
					if (pair == null) {
						net.bridges.remove(bridgePos);
						continue;
					}
					for (final Map.Entry<Long, UUID> e : l.bridges.entrySet()) {
						if (e.getKey() == bridgePos || !pair.equals(e.getValue())) {
							continue;
						}
						for (final Net far : netsAt(level, BlockPos.of(e.getKey()))) {
							if (seen.add(far)) {
								out.add(far);
								next.add(far);
							}
						}
					}
				}
			}
			frontier = next;
		}
		return out;
	}

	/** The other bridges carrying {@code pair}, for the block's own right-click report. */
	public static List<BlockPos> bridgePartners(final ServerLevel level, final BlockPos self, final UUID pair) {
		final List<BlockPos> out = new ArrayList<>();
		for (final Map.Entry<Long, UUID> e : of(level).bridges.entrySet()) {
			if (!pair.equals(e.getValue()) || e.getKey() == self.asLong()) {
				continue;
			}
			out.add(BlockPos.of(e.getKey()));
		}
		out.sort((a, b) -> Double.compare(a.distSqr(self), b.distSqr(self)));
		return out;
	}

	/** A bridge block says where it is and which pair it belongs to. */
	public static void noteBridge(final ServerLevel level, final BlockPos pos, final UUID pairId) {
		final Level1 l = of(level);
		if (!pairId.equals(l.bridges.put(pos.asLong(), pairId))) {
			dirty = true;
		}
		discardAround(level, l, pos);
	}

	/** A bridge block is gone: the runs it joined are two worlds again. */
	public static void forgetBridge(final ServerLevel level, final BlockPos pos) {
		final Level1 l = of(level);
		if (l.bridges.remove(pos.asLong()) != null) {
			dirty = true;
		}
		discardAround(level, l, pos);
	}

	/** The run a known cable is on, walking it if it has changed since anyone last asked. */
	private static Net netFor(final ServerLevel level, final Level1 l, final BlockPos cable) {
		final Net known = l.netOf.get(cable.asLong());
		return known != null ? known : build(level, l, cable);
	}

	/**
	 * One walk: breadth-first from a cable over its whole run — live cables where the world is loaded, saved
	 * ones where it is not, so a half-loaded run reads as one run — recording who is on it and what hangs off it.
	 * The only flood fill left in the mod, and it runs when the cable changes rather than when someone asks.
	 */
	private static Net build(final ServerLevel level, final Level1 l, final BlockPos from) {
		final Net net = new Net(l.nextNetId++);
		net.builtAtTick = level.getGameTime();
		final Set<Long> visited = new LinkedHashSet<>();
		final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		// Count *cables*, not visited positions. Every cable has five neighbours that are not cable, so bounding
		// on `visited` stopped a 99-cable run after about twenty-five of them -- the far end of a long wire
		// simply vanished, which is the exact failure this class exists to fix.
		visited.add(from.asLong());
		addCable(l, net, from);
		queue.add(from.immutable());
		while (!queue.isEmpty() && net.cables.size() < BusNetwork.maxCables()) {
			final BlockPos p = queue.poll();
			for (final Direction d : Direction.values()) {
				final BlockPos n = p.relative(d);
				final long k = n.asLong();
				if (visited.contains(k)) {
					continue;
				}
				visited.add(k);
				if (isCableAnywhere(level, l, n)) {
					addCable(l, net, n);
					queue.add(n.immutable());
					continue;
				}
				classify(level, l, net, n);
			}
		}
		net.cappedCables = net.cables.size() >= BusNetwork.maxCables() && !queue.isEmpty();
		for (final long c : net.cables) {
			l.netOf.put(c, net);
		}
		rebuilds++;
		return net;
	}

	/**
	 * A cable joins the run. Anything the registry remembered <em>at</em> that position is wrong by definition —
	 * a cable is not a machine and not a component — so this is also where a host entry left behind by a
	 * {@code /fill} that paved over a computer finally gets cleaned up.
	 */
	private static void addCable(final Level1 l, final Net net, final BlockPos pos) {
		final long k = pos.asLong();
		net.cables.add(k);
		if (l.hosts.remove(k) != null | l.attachments.remove(k)) {
			dirty = true;
		}
	}

	/** A non-cable position beside the run: a machine, a component, or just a block that is in the way. */
	private static void classify(final ServerLevel level, final Level1 l, final Net net, final BlockPos pos) {
		final long k = pos.asLong();
		if (!level.hasChunkAt(pos)) {
			// Unloaded: trust what was remembered, which is the entire point of the registry.
			if (l.bridges.containsKey(k)) {
				net.bridges.add(k); // a bridge answers from saved data, or a sleeping far network is unreachable
			}
			if (l.hosts.containsKey(k)) {
				net.hosts.add(k);
			} else if (l.attachments.contains(k)) {
				net.attachments.add(k);
			}
			return;
		}
		if (level.getBlockEntity(pos) instanceof BusHost h) {
			net.hosts.add(k);
			final Host now = new Host(pos.immutable(), h.busId(), h.busName());
			if (!now.equals(l.hosts.put(k, now))) {
				dirty = true;
			}
			return;
		}
		if (l.hosts.remove(k) != null) {
			dirty = true; // a machine that is gone, and we could see it go
		}
		if (level.getBlockEntity(pos) instanceof dev.virtualminecraft.block.BridgeBlockEntity b) {
			net.bridges.add(k);
			final UUID pair = b.pairId();
			if (pair != null && !pair.equals(l.bridges.put(k, pair))) {
				dirty = true;
			}
		} else if (l.bridges.remove(k) != null) {
			dirty = true; // a bridge that is gone, and we could see it go
		}
		// Verified on use: what is there now wins over what was remembered (§9 U11b).
		final boolean component = level.getBlockEntity(pos) != null;
		if (component ? l.attachments.add(k) : l.attachments.remove(k)) {
			dirty = true;
		}
		if (component) {
			net.attachments.add(k);
		} else if (!level.getBlockState(pos).isAir()) {
			net.plain.add(k);
		}
	}

	/** Throw a run away, so the next lookup walks it again. Always safe: the worst it costs is one walk. */
	private static void discard(final Level1 l, final @org.jetbrains.annotations.Nullable Net net) {
		if (net == null) {
			return;
		}
		for (final long c : net.cables) {
			l.netOf.remove(c, net);
		}
		discards++;
	}

	/** Discard whatever runs this position touches — a place may merge two, a break may split one. */
	private static void discardAround(final ServerLevel level, final Level1 l, final BlockPos pos) {
		discard(l, l.netOf.get(pos.asLong()));
		for (final Direction d : Direction.values()) {
			discard(l, l.netOf.get(pos.relative(d).asLong()));
		}
	}

	/**
	 * A cable was told a neighbour changed. Returns true if that neighbour update actually changed what the
	 * registry believes is around it — a block placed or broken, a machine or component appearing or going —
	 * as opposed to the redstone level next door flickering, which is most of them.
	 * <p>
	 * This is the check that stops a redstone clock beside one cable from re-flooding a thousand-cable run
	 * several times a tick, which is what the old {@code neighborChanged} did.
	 */
	public static boolean noteNeighbourChanged(final ServerLevel level, final BlockPos cable) {
		final Level1 l = of(level);
		final Net net = l.netOf.get(cable.asLong());
		boolean changed = beliefChanged(level, l, net, cable);
		for (final Direction d : Direction.values()) {
			changed |= beliefChanged(level, l, net, cable.relative(d));
		}
		if (changed) {
			discardAround(level, l, cable);
		}
		return changed;
	}

	/** Whether the world at a loaded position disagrees with what the registry has stored about it. */
	private static boolean beliefChanged(final ServerLevel level, final Level1 l,
			final @org.jetbrains.annotations.Nullable Net net, final BlockPos pos) {
		if (!level.hasChunkAt(pos)) {
			return false;
		}
		final long k = pos.asLong();
		final boolean wasCable = l.cables.contains(k);
		if (BusNetwork.isCable(level, pos) != wasCable) {
			return true;
		}
		if (wasCable) {
			return false;
		}
		if ((level.getBlockEntity(pos) instanceof BusHost) != l.hosts.containsKey(k)) {
			return true;
		}
		final boolean hasBlockEntity = level.getBlockEntity(pos) != null;
		if (hasBlockEntity != l.attachments.contains(k)) {
			return true;
		}
		if (hasBlockEntity) {
			return false;
		}
		// Air ↔ solid, with no block entity either way. It still matters: a composter has no block entity and is
		// a real inventory through Fabric's transfer API, so a block appearing beside a cable can add a component.
		// Level changes — redstone dust, a lit lamp, a flipped repeater — are non-air both sides and stop here.
		return net != null && level.getBlockState(pos).isAir() == net.plain.contains(k);
	}

	// ------------------------------------------------------------------------------------------- lookup

	/**
	 * Every computer on the cable run touching {@code from}, loaded or not, nearest first — a lookup against the
	 * stored run since §9 U11, where it used to be a flood per call.
	 * <p>
	 * Positions in a loaded chunk are verified against the world as they are read: a host position that no longer
	 * holds a machine is dropped here. A position in an unloaded chunk is taken on trust, because trusting it is
	 * the entire point.
	 */
	public static List<Reachable> onRun(final ServerLevel level, final BlockPos from) {
		final Level1 l = of(level);
		final List<Reachable> out = new ArrayList<>();
		final Set<Long> seen = new LinkedHashSet<>();
		for (final Net net : reachableNets(level, from)) {
			for (final long k : List.copyOf(net.hosts)) {
				final BlockPos p = BlockPos.of(k);
				if (p.equals(from) || !seen.add(k)) {
					continue;
				}
				final Reachable r = hostAt(level, l, p);
				if (r != null) {
					out.add(r);
				} else {
					net.hosts.remove(k); // gone, and we could see it go
				}
			}
		}
		out.sort((a, b) -> Double.compare(a.pos.distSqr(from), b.pos.distSqr(from)));
		return out;
	}

	/**
	 * The remembered components on this run that cannot answer right now: attachment positions whose chunks are
	 * away. This is what {@code list} and a missed {@code invoke} demand-load (§9 U11b) — and any such position
	 * that <em>is</em> loaded renews its {@link BusWake} hold here, so a program that keeps using a far chest
	 * keeps its chunk instead of paying a fresh load every {@code netWakeSeconds}.
	 */
	public static List<BlockPos> phantomsOnRun(final ServerLevel level, final BlockPos from) {
		final Level1 l = of(level);
		final List<BlockPos> out = new ArrayList<>();
		for (final Net net : reachableNets(level, from)) {
			for (final long k : List.copyOf(net.attachments)) {
				final BlockPos p = BlockPos.of(k);
				if (!level.hasChunkAt(p)) {
					out.add(p);
				} else if (level.getBlockEntity(p) == null || level.getBlockEntity(p) instanceof BusHost) {
					net.attachments.remove(k); // it stopped being a component while we could see it
					if (l.attachments.remove(k)) {
						dirty = true;
					}
				} else {
					BusWake.touchHold(level, p);
				}
			}
		}
		return out;
	}

	/** The positions beside this run that may hold a component, block entities first. Empty when there is no cable. */
	public static List<BlockPos> attachedOnRun(final ServerLevel level, final BlockPos from) {
		final List<BlockPos> out = new ArrayList<>();
		final List<Net> nets = reachableNets(level, from);
		for (final Net net : nets) {
			for (final long k : net.attachments) {
				out.add(BlockPos.of(k));
			}
		}
		for (final Net net : nets) {
			for (final long k : net.plain) {
				out.add(BlockPos.of(k));
			}
		}
		return out;
	}

	/** A cable at {@code pos} by the same rule the walk uses: the world where it is loaded, the registry where not. */
	public static boolean cableAt(final ServerLevel level, final BlockPos pos) {
		return isCableAnywhere(level, of(level), pos);
	}

	/**
	 * A cable here, according to the world if we can see it and the registry if we cannot. A loaded position
	 * that is no longer a cable is forgotten on the spot.
	 */
	private static boolean isCableAnywhere(final ServerLevel level, final Level1 l, final BlockPos pos) {
		if (level.hasChunkAt(pos)) {
			final boolean live = BusNetwork.isCable(level, pos);
			if (!live && l.cables.remove(pos.asLong())) {
				dirty = true; // it was broken while we were not looking
			} else if (live && l.cables.add(pos.asLong())) {
				dirty = true; // placed before this registry existed
			}
			return live;
		}
		return l.cables.contains(pos.asLong());
	}

	/** The machine at a position, from the world when it is loaded and from the registry when it is not. */
	private static @org.jetbrains.annotations.Nullable Reachable hostAt(final ServerLevel level, final Level1 l, final BlockPos pos) {
		if (level.hasChunkAt(pos)) {
			if (level.getBlockEntity(pos) instanceof BusHost h) {
				record(level, h);
				return new Reachable(pos.immutable(), h.busId(), h.busName(), awake(level, pos));
			}
			if (l.hosts.remove(pos.asLong()) != null) {
				dirty = true; // the machine is gone and we could see it go
			}
			return null;
		}
		final Host saved = l.hosts.get(pos.asLong());
		return saved == null ? null : new Reachable(saved.pos, saved.id, saved.name, false);
	}

	/** The loaded {@link BusHost} at a remembered position, or null while its chunk is away. */
	public static @org.jetbrains.annotations.Nullable BusHost live(final ServerLevel level, final BlockPos pos) {
		return level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof BusHost h ? h : null;
	}

	/**
	 * Whether a machine there is actually <em>running</em>, which is a stricter question than whether its chunk
	 * is loaded. A chunk can be loaded and not simulate, and it is not-simulating that freezes a machine
	 * ({@code LuaComputer} watches {@code shouldTickBlocksAt}, not the chunk map) — so a peer in a loaded but
	 * still chunk is asleep for every purpose that matters here, and needs the same wake as one that is fully
	 * unloaded.
	 */
	public static boolean awake(final ServerLevel level, final BlockPos pos) {
		return level.hasChunkAt(pos)
			&& level.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
	}

	/** The {@link BusHost} there if it is loaded <em>and</em> ticking; null if it is missing or asleep. */
	public static @org.jetbrains.annotations.Nullable BusHost awakeHost(final ServerLevel level, final BlockPos pos) {
		return awake(level, pos) ? live(level, pos) : null;
	}

	// ------------------------------------------------------------------------------------------- persistence

	private static Path file(final MinecraftServer server) {
		// vmRoot(), not baseDir(): baseDir() is computers/, one directory per machine, and the bus map is not a
		// machine's business -- it describes the world between them.
		return ComputerManager.get(server).vmRoot().resolve("bus.json");
	}

	/** Reads the registry once per server start; a missing or unreadable file is an empty registry, not a failure. */
	public static synchronized void load(final MinecraftServer server) {
		if (loaded) {
			return;
		}
		loaded = true;
		BY_LEVEL.clear();
		final Path p = file(server);
		if (!Files.isRegularFile(p)) {
			return;
		}
		try {
			final JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
			for (final Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
				final ResourceKey<Level> dim = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
					new net.minecraft.resources.ResourceLocation(e.getKey()));
				final Level1 l = new Level1();
				final JsonObject o = e.getValue().getAsJsonObject();
				if (o.has("cables")) {
					for (final com.google.gson.JsonElement c : o.getAsJsonArray("cables")) {
						l.cables.add(c.getAsLong());
					}
				}
				if (o.has("bridges")) {
					for (final com.google.gson.JsonElement b : o.getAsJsonArray("bridges")) {
						final JsonObject bo = b.getAsJsonObject();
						l.bridges.put(bo.get("pos").getAsLong(), UUID.fromString(bo.get("pair").getAsString()));
					}
				}
				if (o.has("attachments")) {
					for (final com.google.gson.JsonElement a : o.getAsJsonArray("attachments")) {
						l.attachments.add(a.getAsLong());
					}
				}
				if (o.has("hosts")) {
					for (final com.google.gson.JsonElement h : o.getAsJsonArray("hosts")) {
						final JsonObject ho = h.getAsJsonObject();
						final long key = ho.get("pos").getAsLong();
						l.hosts.put(key, new Host(BlockPos.of(key), UUID.fromString(ho.get("id").getAsString()),
							ho.has("name") ? ho.get("name").getAsString() : "computer"));
					}
				}
				BY_LEVEL.put(dim, l);
			}
			VirtualMinecraft.LOGGER.info("Bus registry: {} dimensions, {} cables, {} computers, {} attachments",
				BY_LEVEL.size(), BY_LEVEL.values().stream().mapToInt(l -> l.cables.size()).sum(),
				BY_LEVEL.values().stream().mapToInt(l -> l.hosts.size()).sum(),
				BY_LEVEL.values().stream().mapToInt(l -> l.attachments.size()).sum());
		} catch (final IOException | RuntimeException ex) {
			VirtualMinecraft.LOGGER.warn("Bus registry unreadable ({}); starting empty", ex.toString());
			BY_LEVEL.clear();
		}
	}

	/** Writes it if anything changed. Called on a beat and at server stop. */
	public static synchronized void save(final MinecraftServer server) {
		if (!dirty) {
			return;
		}
		dirty = false;
		final JsonObject root = new JsonObject();
		for (final Map.Entry<ResourceKey<Level>, Level1> e : BY_LEVEL.entrySet()) {
			final Level1 l = e.getValue();
			if (l.cables.isEmpty() && l.hosts.isEmpty() && l.attachments.isEmpty() && l.bridges.isEmpty()) {
				continue;
			}
			final JsonObject o = new JsonObject();
			final JsonArray cables = new JsonArray();
			for (final long c : l.cables) {
				cables.add(c);
			}
			o.add("cables", cables);
			final JsonArray attachments = new JsonArray();
			for (final long a : l.attachments) {
				attachments.add(a);
			}
			o.add("attachments", attachments);
			final JsonArray bridges = new JsonArray();
			for (final Map.Entry<Long, UUID> b : l.bridges.entrySet()) {
				final JsonObject bo = new JsonObject();
				bo.addProperty("pos", b.getKey());
				bo.addProperty("pair", b.getValue().toString());
				bridges.add(bo);
			}
			o.add("bridges", bridges);
			final JsonArray hosts = new JsonArray();
			for (final Map.Entry<Long, Host> h : l.hosts.entrySet()) {
				final JsonObject ho = new JsonObject();
				ho.addProperty("pos", h.getKey());
				ho.addProperty("id", h.getValue().id().toString());
				ho.addProperty("name", h.getValue().name());
				hosts.add(ho);
			}
			o.add("hosts", hosts);
			root.add(e.getKey().location().toString(), o);
		}
		try {
			final Path p = file(server);
			Files.createDirectories(p.getParent());
			writeAtomic(p, root.toString().getBytes(StandardCharsets.UTF_8));
		} catch (final IOException ex) {
			VirtualMinecraft.LOGGER.warn("Could not write the bus registry: {}", ex.toString());
		}
	}

	/** ComputerManager's own writeAtomic is package-private to the computer package; this is the same dance. */
	private static void writeAtomic(final Path file, final byte[] data) throws IOException {
		final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, data);
		try {
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (final java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Server stop: the next start reads the file again. Runs are derived, so they simply go. */
	public static synchronized void reset() {
		BY_LEVEL.clear();
		loaded = false;
		dirty = false;
		rebuilds = 0;
		discards = 0;
	}

	/** For {@code /vmc} and tests: how much is remembered. */
	public static String describe() {
		int cables = 0;
		int hosts = 0;
		int attachments = 0;
		int bridges = 0;
		final Set<Net> nets = new LinkedHashSet<>();
		for (final Level1 l : BY_LEVEL.values()) {
			cables += l.cables.size();
			hosts += l.hosts.size();
			attachments += l.attachments.size();
			bridges += l.bridges.size();
			nets.addAll(l.netOf.values());
		}
		return cables + " cables, " + hosts + " computers, " + attachments + " attachments, " + bridges
			+ " bridges in " + BY_LEVEL.size()
			+ " dimensions; " + nets.size() + " runs walked (" + rebuilds + " builds, " + discards + " discards)";
	}

	/** For {@code /vmc bus} and tests: how many run walks have happened, which U11 is meant to keep near-constant. */
	public static int rebuildCount() {
		return rebuilds;
	}
}
