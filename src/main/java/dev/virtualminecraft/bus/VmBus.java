package dev.virtualminecraft.bus;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.block.ComputerBlockEntity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * One computer's side of the guest-to-world bus: line-delimited JSON-RPC 2.0 over {@link BusLink}.
 * Requests are parsed on the server thread in {@link #tick} (at most {@link #MAX_REQUESTS_PER_TICK} per
 * tick) so components may touch the world; events are pushed as JSON-RPC notifications with method
 * {@code event}. The guest is untrusted: only registered component methods can run, lines are size-capped,
 * and the queue is bounded.
 */
public final class VmBus {
	public static final int PROTOCOL_VERSION = 1;
	private static final int MAX_REQUESTS_PER_TICK = 64;
	private static final long RECONNECT_INTERVAL_MS = 2000;
	private static final Gson GSON = new Gson();

	private final UUID vmId;
	private final String name;
	private final @Nullable Path socket;
	private final int port;
	private volatile @Nullable BusLink link;
	private volatile long lastConnectAttempt;
	private volatile int connectFailures;
	private final Set<String> subscriptions = new HashSet<>();
	private boolean subscribeAll;
	private long requestsHandled;
	private long eventsSent;

	public VmBus(final UUID vmId, final String name, final @Nullable Path socket, final int port) {
		this.vmId = vmId;
		this.name = name;
		this.socket = socket;
		this.port = port;
	}

	public boolean isConnected() {
		final BusLink l = link;
		return l != null && l.isOpen();
	}

	public String describe() {
		return (isConnected() ? "connected" : "disconnected (" + connectFailures + " failed attempts)")
			+ ", " + requestsHandled + " requests, " + eventsSent + " events, subscriptions=" + (subscribeAll ? "*" : subscriptions);
	}

	/** Connects (or reconnects, throttled). Any thread. */
	public void connect() {
		final long now = System.currentTimeMillis();
		if (isConnected() || now - lastConnectAttempt < RECONNECT_INTERVAL_MS) {
			return;
		}
		lastConnectAttempt = now;
		try {
			link = BusLink.connect(socket, port, name);
			connectFailures = 0;
			VirtualMinecraft.LOGGER.info("Bus connected for VM {}", name);
		} catch (final IOException e) {
			connectFailures++;
			if (connectFailures == 1 || connectFailures % 30 == 0) {
				VirtualMinecraft.LOGGER.warn("Bus connect failed for VM {}: {}", name, e.toString());
			}
		}
	}

	public void close() {
		final BusLink l = link;
		if (l != null) {
			l.close();
		}
	}

	/** Server thread. {@code level}/{@code be} may be null when the computer's chunk is not loaded. */
	public void tick(final @Nullable ServerLevel level, final @Nullable ComputerBlockEntity be) {
		final BusLink l = link;
		if (l == null || !l.isOpen()) {
			if (l != null) {
				// The guest side went away (QEMU restarted the chardev?): forget subscriptions, reconnect.
				subscriptions.clear();
				subscribeAll = false;
			}
			connect();
			return;
		}
		final int dropped = l.takeDropped();
		if (dropped > 0) {
			l.send(error(null, BusException.RATE_LIMITED, dropped + " request(s) dropped: too many queued"));
		}
		for (int i = 0; i < MAX_REQUESTS_PER_TICK; i++) {
			final String line = l.poll();
			if (line == null) {
				break;
			}
			final String reply = handle(line, level, be);
			if (reply != null) {
				l.send(reply);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Events (server thread)
	// ---------------------------------------------------------------------------------------------

	public boolean wantsEvent(final String eventName) {
		return isConnected() && (subscribeAll || subscriptions.contains(eventName));
	}

	/** Pushes an event notification if the guest subscribed to it. {@code params} gets a {@code name} field. */
	public void event(final String eventName, final JsonObject params) {
		if (!wantsEvent(eventName)) {
			return;
		}
		params.addProperty("name", eventName);
		final JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("method", "event");
		msg.add("params", params);
		final BusLink l = link;
		if (l != null && l.send(GSON.toJson(msg))) {
			eventsSent++;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Request handling (server thread)
	// ---------------------------------------------------------------------------------------------

	private @Nullable String handle(final String line, final @Nullable ServerLevel level, final @Nullable ComputerBlockEntity be) {
		requestsHandled++;
		if (line == BusLink.OVERSIZED) {
			return error(null, BusException.INVALID_REQUEST, "line longer than " + BusLink.MAX_LINE + " bytes dropped");
		}
		final JsonObject req;
		try {
			final JsonElement e = JsonParser.parseString(line);
			if (!e.isJsonObject()) {
				return error(null, BusException.INVALID_REQUEST, "expected a JSON object");
			}
			req = e.getAsJsonObject();
		} catch (final JsonSyntaxException | IllegalStateException ex) {
			return error(null, BusException.PARSE_ERROR, "invalid JSON: " + ex.getMessage());
		}
		final JsonElement id = req.get("id");
		final boolean notification = id == null || id.isJsonNull();
		try {
			final JsonElement m = req.get("method");
			if (m == null || !m.isJsonPrimitive() || !m.getAsJsonPrimitive().isString()) {
				throw new BusException(BusException.INVALID_REQUEST, "missing method");
			}
			final JsonElement result = dispatch(m.getAsString(), req.get("params"), level, be);
			return notification ? null : result(id, result);
		} catch (final BusException ex) {
			return notification ? null : error(id, ex.code, ex.getMessage());
		} catch (final RuntimeException ex) {
			VirtualMinecraft.LOGGER.warn("Bus request on VM {} failed: {}", name, ex.toString());
			return notification ? null : error(id, BusException.COMPONENT_ERROR, "internal error: " + ex.getClass().getSimpleName());
		}
	}

	private JsonElement dispatch(final String method, final @Nullable JsonElement params, final @Nullable ServerLevel level, final @Nullable ComputerBlockEntity be) throws BusException {
		switch (method) {
			case "ping":
				return new JsonPrimitive("pong");
			case "info": {
				final JsonObject o = new JsonObject();
				o.addProperty("name", name);
				o.addProperty("id", vmId.toString());
				o.addProperty("protocol", PROTOCOL_VERSION);
				o.addProperty("loaded", be != null);
				return o;
			}
			case "list": {
				final JsonArray out = new JsonArray();
				for (final Component c : components(level, be)) {
					final JsonObject o = new JsonObject();
					o.addProperty("address", c.address().toString());
					o.addProperty("type", c.type());
					o.addProperty("location", c.location());
					final JsonObject methods = new JsonObject();
					for (final Map.Entry<String, String> en : c.methods().entrySet()) {
						methods.addProperty(en.getKey(), en.getValue());
					}
					o.add("methods", methods);
					out.add(o);
				}
				return out;
			}
			case "invoke": {
				final String target;
				final String name;
				final JsonArray args;
				if (params != null && params.isJsonArray()) {
					final JsonArray a = params.getAsJsonArray();
					if (a.size() < 2) {
						throw BusException.invalidParams("invoke expects [address, method, args...]");
					}
					target = a.get(0).getAsString();
					name = a.get(1).getAsString();
					args = new JsonArray();
					for (int i = 2; i < a.size(); i++) {
						args.add(a.get(i));
					}
				} else if (params != null && params.isJsonObject()) {
					final JsonObject o = params.getAsJsonObject();
					if (!o.has("address") || !o.has("method")) {
						throw BusException.invalidParams("invoke expects {address, method, args}");
					}
					target = o.get("address").getAsString();
					name = o.get("method").getAsString();
					args = argsOf(o.get("args"));
				} else {
					throw BusException.invalidParams("invoke expects [address, method, args...] or {address, method, args}");
				}
				return invoke(target, name, args, level, be);
			}
			case "subscribe":
			case "unsubscribe": {
				final boolean on = method.equals("subscribe");
				if (params == null || params.isJsonNull()) {
					throw BusException.invalidParams(method + " expects an event name, a list of names, or \"*\"");
				}
				for (final JsonElement e : params.isJsonArray() ? params.getAsJsonArray() : List.of(params)) {
					final String ev = e.getAsString();
					if (ev.equals("*")) {
						subscribeAll = on;
						if (!on) {
							subscriptions.clear();
						}
					} else if (on) {
						subscriptions.add(ev);
					} else {
						subscriptions.remove(ev);
					}
				}
				final JsonArray out = new JsonArray();
				if (subscribeAll) {
					out.add("*");
				}
				subscriptions.forEach(out::add);
				if (be != null && level != null) {
					be.onNeighborChanged(level); // prime the hot-plug/redstone baselines for the new subscription set
				}
				return out;
			}
			default: {
				// "<type>.<method>" / "<address>.<method>" shortcut with params = args.
				final int dot = method.lastIndexOf('.');
				if (dot > 0 && dot < method.length() - 1) {
					return invoke(method.substring(0, dot), method.substring(dot + 1), argsOf(params), level, be);
				}
				throw new BusException(BusException.METHOD_NOT_FOUND, "unknown method '" + method + "' (ping, info, list, invoke, subscribe, unsubscribe, <type>.<method>, <type>@<side>.<method>)");
			}
		}
	}

	private static JsonArray argsOf(final @Nullable JsonElement params) {
		if (params == null || params.isJsonNull()) {
			return new JsonArray();
		}
		if (params.isJsonArray()) {
			return params.getAsJsonArray();
		}
		final JsonArray a = new JsonArray();
		a.add(params);
		return a;
	}

	private static List<Component> components(final @Nullable ServerLevel level, final @Nullable ComputerBlockEntity be) throws BusException {
		if (level == null || be == null || be.isRemoved()) {
			throw new BusException(BusException.NOT_LOADED, "the computer block is not loaded");
		}
		return Components.collect(level, be);
	}

	private static JsonElement invoke(final String target, final String method, final JsonArray args, final @Nullable ServerLevel level, final @Nullable ComputerBlockEntity be) throws BusException {
		final Component c = Components.find(components(level, be), target, null);
		if (c == null) {
			throw new BusException(BusException.NO_SUCH_COMPONENT, "no component '" + target + "' (use an address, type@side, or a type)");
		}
		if (!c.methods().containsKey(method)) {
			throw new BusException(BusException.METHOD_NOT_FOUND, c.type() + " has no method '" + method + "'");
		}
		return c.invoke(method, args);
	}

	private static String result(final JsonElement id, final JsonElement result) {
		final JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id);
		o.add("result", result);
		return GSON.toJson(o);
	}

	private static String error(final @Nullable JsonElement id, final int code, final String message) {
		final JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id);
		final JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		o.add("error", err);
		return GSON.toJson(o);
	}
}
