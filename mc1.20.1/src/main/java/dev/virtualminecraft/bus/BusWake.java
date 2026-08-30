package dev.virtualminecraft.bus;

import com.google.gson.JsonObject;
import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.config.VmcConfig;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Waking a computer that a message is addressed to (ROADMAP §9 U9, the second half of [name]'s option 1).
 * <p>
 * {@link BusRegistry} makes an unloaded machine <em>addressable</em>. This is what makes it <em>answer</em>: a
 * chunk ticket with a timeout, so the chunk starts ticking, the block entity attaches, the frozen machine thaws
 * the way it always did, and the queued event is delivered the moment it is there.
 * <p>
 * <b>What this deliberately is not.</b> It is not a chunk loader. The ticket has a lifetime measured in seconds
 * ({@link VmcConfig#netWakeSeconds}) and nothing renews it but more traffic, so a machine that stops being
 * spoken to goes back to being frozen and free — which is the whole difference between this and the
 * "chunk ticket while running" design the ROADMAP weighed against it. An idle computer at the end of a cable
 * still costs nothing; a busy one costs a chunk while it is busy, which is the price [name] chose to pay.
 * <p>
 * <b>Only {@code send} wakes; {@code broadcast} does not.</b> A broadcast goes to every peer, and letting one
 * call pull a dozen chunks into memory is exactly the footgun this feature would otherwise be. Broadcast reaches
 * the machines that are loaded and reports that number honestly.
 * <p>
 * Server thread only.
 */
public final class BusWake {
	/** One waiting delivery: a message that arrived before its machine did. */
	private record Pending(ResourceKey<Level> dimension, BlockPos pos, UUID id, String event, JsonObject params, long expiresAtTick) {
	}

	private static final Deque<Pending> QUEUE = new ArrayDeque<>();
	/** Chunks to hold, so a machine that thaws has a moment to boot before its chunk is taken away again. */
	private static TicketType<ChunkPos> ticket;
	private static long ticketTicks = -1;
	/** The lighter hold for component chunks (§9 U11b): loaded, never simulated — a chest answers without ticking. */
	private static TicketType<ChunkPos> holdTicket;
	private static long holdTicketTicks = -1;
	/** Chunks held for components, chunk key → game time the hold runs out; renewed while the run keeps being walked. */
	private static final Map<ResourceKey<Level>, Map<Long, Long>> HELD = new ConcurrentHashMap<>();

	private BusWake() {
	}

	private static long ticketLifetime() {
		return Math.max(20L, VmcConfig.get().netWakeSeconds * 20L);
	}

	private static TicketType<ChunkPos> ticket() {
		final long want = ticketLifetime();
		if (ticket == null || ticketTicks != want) {
			// 1.20.1: a ticket type carries its timeout; the level it is added at (addRegionTicket's distance, below)
			// decides whether the chunk merely loads or also ticks, and ticking is the point: a chunk that is loaded
			// but not ticking never runs the block entity, so the machine would sit there frozen doing nothing.
			ticket = TicketType.create("vmc_wake", java.util.Comparator.comparingLong(ChunkPos::toLong), (int) want);
			ticketTicks = want;
		}
		return ticket;
	}

	private static TicketType<ChunkPos> holdTicket() {
		final long want = ticketLifetime();
		if (holdTicket == null || holdTicketTicks != want) {
			holdTicket = TicketType.create("vmc_hold", java.util.Comparator.comparingLong(ChunkPos::toLong), (int) want);
			holdTicketTicks = want;
		}
		return holdTicket;
	}

	// ------------------------------------------------------------------------------- components (§9 U11b)

	/** Whether a bus call may load the chunk a far component sits in. Same switch as waking, on purpose. */
	public static boolean demandLoadEnabled() {
		return VmcConfig.get().netWakeSeconds > 0;
	}

	/**
	 * Load the chunks holding {@code positions} <em>now</em>, so the bus call in progress completes with the
	 * components live — the demand-load of §9 U11b. This is the vanilla synchronous load every command uses
	 * ({@code /setblock} into an unloaded chunk does exactly this), run on the server thread; the Lua worker
	 * is already waiting on this call with {@code onServer}'s two-second timeout, which is what fails the call
	 * rather than hanging the machine if a load ever takes absurdly long. Radius 0 and no simulation: a chest
	 * answers {@code getBlockEntity} from a merely-loaded chunk, and nothing here should start ticking mobs.
	 */
	public static void loadComponents(final ServerLevel level, final List<BlockPos> positions) {
		if (positions.isEmpty() || !demandLoadEnabled()) {
			return;
		}
		final Set<Long> chunks = new LinkedHashSet<>();
		for (final BlockPos p : positions) {
			chunks.add(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4));
		}
		final Map<Long, Long> held = HELD.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
		for (final long key : chunks) {
			final ChunkPos cp = new ChunkPos(key);
			level.getChunkSource().addRegionTicket(holdTicket(), cp, 0, cp); // distance 0 = level 33: loaded, not ticking
			level.getChunk(cp.x, cp.z);
			held.put(key, level.getGameTime() + ticketLifetime());
		}
		// The one line that tells an admin why chunks far from any player just loaded.
		VirtualMinecraft.LOGGER.info("Bus: loaded {} chunk(s) to reach components at {}",
			chunks.size(), positions.stream().map(BlockPos::toShortString).collect(java.util.stream.Collectors.joining("; ")));
		for (final BlockPos p : positions) {
			if (level.getBlockEntity(p) == null) {
				BusRegistry.forgetAttachment(level, p); // remembered, loaded, and not there: stop loading for it
			}
		}
	}

	/** A walked-over component whose chunk we are holding: renew the hold, so steady use never pays a reload. */
	public static void touchHold(final ServerLevel level, final BlockPos pos) {
		final Map<Long, Long> held = HELD.get(level.dimension());
		if (held == null) {
			return;
		}
		final long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
		final Long expires = held.get(key);
		if (expires == null) {
			return;
		}
		if (level.getGameTime() >= expires) {
			held.remove(key); // the ticket already ran out on its own; if the chunk unloads, demand-load returns
			return;
		}
		level.getChunkSource().addRegionTicket(holdTicket(), new ChunkPos(key), 0, new ChunkPos(key)); // re-adding resets its clock
		held.put(key, level.getGameTime() + ticketLifetime());
	}

	/**
	 * Deliver {@code event} to a peer, waking it if its chunk is away. Returns true if it went straight out,
	 * false if it was queued behind a wake.
	 */
	public static boolean deliver(final ServerLevel level, final BusRegistry.Reachable peer, final String event, final JsonObject params) {
		final BusHost awake = BusRegistry.awakeHost(level, peer.pos());
		if (awake != null && awake.busReady()) {
			awake.emitEvent(event, params);
			return true;
		}
		if (VmcConfig.get().netWakeSeconds <= 0) {
			return false; // waking is switched off: the peer is addressable but stays asleep
		}
		final ChunkPos cp = new ChunkPos(peer.pos().getX() >> 4, peer.pos().getZ() >> 4);
		// distance 1 = level 32 at the machine's own chunk (block-ticking, so the block entity runs) and 33 (loaded,
		// still) one chunk out: the machine simulates and a cable run's far end does not.
		level.getChunkSource().addRegionTicket(ticket(), cp, 1, cp);
		// A chunk that is loaded but not ticking still has its block entity, and emitEvent thaws a frozen machine
		// -- but thawing takes a tick or two, and an event handed to a machine mid-thaw is dropped. So poke it
		// awake and let the queue below deliver once it says it is ready.
		final BusHost there = BusRegistry.live(level, peer.pos());
		if (there != null) {
			if (there.busReady()) {
				there.emitEvent(event, params);
				return true;
			}
			there.emitEvent("wake", new JsonObject()); // thaws it; the real message follows from tick()
		}
		QUEUE.add(new Pending(level.dimension(), peer.pos().immutable(), peer.id(), event, params,
			level.getGameTime() + Math.max(20L, VmcConfig.get().netWakeSeconds * 20L)));
		return false;
	}

	/**
	 * Once a tick: hand queued messages to machines that have since woken, and give up on the ones that never
	 * did. A machine takes a few ticks to attach and thaw, so this is a short retry rather than a single attempt.
	 */
	public static void tick(final MinecraftServer server) {
		if (QUEUE.isEmpty()) {
			return;
		}
		final int n = QUEUE.size();
		for (int i = 0; i < n; i++) {
			final Pending p = QUEUE.poll();
			if (p == null) {
				break;
			}
			final ServerLevel level = server.getLevel(p.dimension());
			if (level == null) {
				continue; // the dimension went away; so did the message
			}
			final BusHost host = BusRegistry.live(level, p.pos);
			if (host != null && !host.busReady()) {
				host.emitEvent("wake", new JsonObject()); // still coming up; keep nudging until it is ready
			}
			if (host != null && host.busReady() && host.busId().equals(p.id)) {
				host.emitEvent(p.event, p.params);
				// Rare by nature -- it only happens to a machine that was asleep -- and it is the one line that
				// tells an admin why a chunk woke up, so it is worth saying out loud.
				VirtualMinecraft.LOGGER.info("Bus: woke '{}' at {} to deliver a {}", host.busName(), p.pos.toShortString(), p.event);
				continue;
			}
			if (level.getGameTime() >= p.expiresAtTick) {
				VirtualMinecraft.LOGGER.info("Bus: {} at {} never woke; dropping a {} for it",
					p.id, p.pos.toShortString(), p.event);
				continue;
			}
			QUEUE.add(p);
		}
	}

	/** How many messages are waiting on a machine to wake up. For {@code /vmc} and tests. */
	public static int pending() {
		return QUEUE.size();
	}

	public static void reset() {
		QUEUE.clear();
		HELD.clear();
	}
}
