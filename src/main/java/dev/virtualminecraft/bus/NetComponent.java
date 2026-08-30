package dev.virtualminecraft.bus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.virtualminecraft.config.VmcConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Computers talking to each other (ROADMAP §9 U3, option C: cable first, then a wireless modem block with the
 * same API). A computer's <em>peers</em> are the other machines on its bus — anything that is a
 * {@link BusHost} among {@link BusHost#attached}: the six neighbours and everything on the cable run, either tier
 * — <em>plus</em>, when its bus holds a {@link dev.virtualminecraft.block.ModemBlock modem}, every machine whose
 * bus holds a modem within {@link VmcConfig#modemRange} blocks ({@link Modems}). No new reach code, no network
 * state: the cable flood fill and a distance decide who is connected, and both are recomputed per call.
 * <p>
 * {@code send(to, message)} and {@code broadcast(message)} deliver a {@code net_message {from, sender, message}}
 * event (from = the address, sender = the name) to the peer(s) through {@link BusHost#emitEvent}, so a message thaws a frozen Computer (an event is a
 * reason to exist again) and reaches a VM guest only if it subscribed. {@code to} is a peer's address (its
 * machine id) or its name (first match, case-insensitive). The message is any JSON value, bounded by
 * {@link VmcConfig#netMessageMaxBytes} once encoded, and a sender is budgeted by
 * {@link VmcConfig#netMessagesPerMinute} — a runaway program cannot flood its neighbours, and a broadcast to N
 * peers costs one unit, not N. {@code allowNet = false} removes the component from every {@code list}.
 */
public final class NetComponent implements Component {
	public static final String TYPE = "net";
	public static final String EVENT = "net_message";
	private static final Map<String, String> METHODS = new LinkedHashMap<>();

	static {
		METHODS.put("address", "address() -> this computer's own address (its machine id); what peers see as `from`");
		METHODS.put("list", "list() -> [{address, name, location, loaded}] every computer reachable over this bus (location = a side, an offset, or 'wireless'), nearest first; loaded = false means its chunk is away and a send will wake it");
		METHODS.put("send", "send(to, message) -> true; `to` = a peer's address or name; the peer gets a net_message {from, sender, message} event");
		METHODS.put("broadcast", "broadcast(message) -> number of peers it reached");
	}

	private final ServerLevel level;
	private final BusHost be;
	private final UUID address;

	public NetComponent(final ServerLevel level, final BusHost be) {
		this.level = level;
		this.be = be;
		this.address = Component.addressOf(be.busId(), TYPE, "self");
	}

	/** The component only exists when the server allows it, so {@code list} tells the guest the truth. */
	public static void collect(final ServerLevel level, final BusHost computer, final List<Component> out) {
		if (VmcConfig.get().allowNet) {
			out.add(new NetComponent(level, computer));
		}
	}

	@Override
	public UUID address() {
		return address;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public String location() {
		return "self";
	}

	@Override
	public Map<String, String> methods() {
		return METHODS;
	}

	@Override
	public JsonElement invoke(final String method, final JsonArray args) throws BusException {
		switch (method) {
			case "address":
				return new JsonPrimitive(be.busId().toString());
			case "list": {
				final JsonArray out = new JsonArray();
				for (final Peer p : peers()) {
					final JsonObject o = new JsonObject();
					o.addProperty("address", p.id.toString());
					o.addProperty("name", p.name);
					o.addProperty("location", p.location);
					o.addProperty("loaded", p.host != null);
					out.add(o);
				}
				if (BusNetwork.runHitCap(level, be.getBlockPos())) {
					// A run that stops at the cap looks exactly like a run with a gap in it. Say so, rather than
					// letting someone spend an evening looking for a cable that is fine (§9 U9).
					final JsonObject note = new JsonObject();
					note.addProperty("note", "this cable run hit the " + BusNetwork.maxCables()
						+ "-cable limit (busMaxCables), so it may reach further than this list shows");
					out.add(note);
				}
				return out;
			}
			case "send": {
				final String to = string(arg(args, 0), "a peer's address or name");
				final JsonElement message = message(arg(args, 1));
				final Peer peer = find(to);
				if (peer == null) {
					throw new BusException(BusException.COMPONENT_ERROR, "no computer called '" + to + "' within reach (net.list() says who is)");
				}
				budget();
				if (peer.host != null) {
					peer.host.emitEvent(EVENT, envelope(message));
				} else if (!BusWake.deliver(level, new BusRegistry.Reachable(peer.pos, peer.id, peer.name, false), EVENT, envelope(message))) {
					// queued behind a wake, or waking is off. Either way the caller should not be told it arrived.
					if (VmcConfig.get().netWakeSeconds <= 0) {
						throw new BusException(BusException.COMPONENT_ERROR, "'" + peer.name
							+ "' is out of reach: its chunk is not loaded, and waking is disabled (netWakeSeconds = 0)");
					}
					return new JsonPrimitive(true); // it will land as soon as the machine wakes
				}
				return new JsonPrimitive(true);
			}
			case "broadcast": {
				final JsonElement message = message(arg(args, 0));
				final List<Peer> targets = peers();
				budget();
				// Broadcast never wakes: one call pulling a dozen chunks into memory is the footgun this whole
				// feature would otherwise be (§9 U9). It reaches who is there, and says how many that was.
				int reached = 0;
				for (final Peer p : targets) {
					if (p.host != null) {
						p.host.emitEvent(EVENT, envelope(message));
						reached++;
					}
				}
				return new JsonPrimitive(reached);
			}
			default:
				throw new BusException(BusException.METHOD_NOT_FOUND, "net has no method '" + method + "'");
		}
	}

	/**
	 * A peer. {@code host} is null when its chunk is not loaded — it is still addressable, because
	 * {@link BusRegistry} remembers where it is and what it is called, and a {@code send} wakes it.
	 */
	private record Peer(@org.jetbrains.annotations.Nullable BusHost host, UUID id, String name, BlockPos pos, String location) {
	}

	/**
	 * Every other machine this one can reach: the bus first, in {@link BusHost#attached} order (neighbours, then
	 * the cable run nearest first), then the ones heard over the radio, nearest modem first. A machine reachable
	 * both ways is listed once, as a cable peer — the wire is the more specific answer.
	 */
	private List<Peer> peers() {
		final List<Peer> out = new ArrayList<>();
		final Set<UUID> seen = new LinkedHashSet<>();
		seen.add(be.busId());
		for (final Map.Entry<BlockPos, String> e : be.attached(level).entrySet()) {
			if (level.getBlockEntity(e.getKey()) instanceof BusHost h && h != be && seen.add(h.busId())) {
				out.add(new Peer(h, h.busId(), h.busName(), e.getKey().immutable(), e.getValue()));
			}
		}
		// §9 U9: the same run as remembered, which is how a machine in an unloaded chunk gets into this list at
		// all. Loaded ones were already found above, so what this adds is exactly the sleeping ones.
		for (final BusRegistry.Reachable r : BusRegistry.onRun(level, be.getBlockPos())) {
			if (seen.add(r.id())) {
				out.add(new Peer(BusRegistry.awakeHost(level, r.pos()), r.id(), r.name(), r.pos(),
					BusNetwork.offsetLocation(be.getBlockPos(), r.pos())));
			}
		}
		for (final BusHost h : Modems.peers(level, be)) {
			if (h != be && seen.add(h.busId())) {
				out.add(new Peer(h, h.busId(), h.busName(), h.getBlockPos().immutable(), "wireless"));
			}
		}
		return out;
	}

	/**
	 * A peer by address or by name. An address is exact and always wins. A <b>name</b> is not unique — every
	 * machine is called "computer" until someone labels it — so a name that matches more than one machine is
	 * refused rather than guessed at. Silently picking the first was worse than an error: with two machines it
	 * looked like it worked, and with three it sent to whichever happened to be nearest, which is how a browser
	 * came to time out while the server it meant to ask sat there having answered somebody else (2026-08-29).
	 */
	private Peer find(final String to) throws BusException {
		final List<Peer> peers = peers();
		for (final Peer p : peers) {
			if (p.id.toString().equalsIgnoreCase(to)) {
				return p;
			}
		}
		Peer named = null;
		int matches = 0;
		for (final Peer p : peers) {
			if (p.name.equalsIgnoreCase(to)) {
				matches++;
				if (named == null) {
					named = p;
				}
			}
		}
		if (matches > 1) {
			final StringBuilder sb = new StringBuilder("'").append(to).append("' is the name of ").append(matches)
				.append(" machines within reach; use one of their addresses instead:");
			for (final Peer p : peers) {
				if (p.name.equalsIgnoreCase(to)) {
					sb.append("\n  ").append(p.id).append("  (").append(p.location).append(')');
				}
			}
			throw new BusException(BusException.COMPONENT_ERROR, sb.toString());
		}
		return named;
	}

	private JsonObject envelope(final JsonElement message) {
		final JsonObject p = new JsonObject();
		p.addProperty("from", be.busId().toString());
		p.addProperty("sender", be.busName());
		p.add("message", message.deepCopy());
		return p;
	}

	private void budget() throws BusException {
		if (!be.netBudget().tryAcquire(level.getGameTime())) {
			throw new BusException(BusException.RATE_LIMITED,
				"this computer is sending too much (max " + VmcConfig.get().netMessagesPerMinute + "/min); retry in " + be.netBudget().retryInSeconds() + "s");
		}
	}

	private static JsonElement arg(final JsonArray args, final int i) {
		return i < args.size() ? args.get(i) : null;
	}

	private static String string(final JsonElement e, final String what) throws BusException {
		if (e == null || !e.isJsonPrimitive()) {
			throw BusException.invalidParams(what + " required");
		}
		return e.getAsString();
	}

	/** Any JSON value but null, within the size limit once encoded (what travels is the encoding). */
	private static JsonElement message(final JsonElement e) throws BusException {
		if (e == null || e.isJsonNull()) {
			throw BusException.invalidParams("a message is required");
		}
		final int max = VmcConfig.get().netMessageMaxBytes;
		final int size = e.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		if (size > max) {
			throw BusException.invalidParams("the message is " + size + " bytes; the limit is " + max);
		}
		return e;
	}
}
