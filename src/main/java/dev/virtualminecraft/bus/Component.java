package dev.virtualminecraft.bus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.Map;
import java.util.UUID;

/**
 * Something a guest can talk to over the bus: a typed object with an address and a fixed set of methods.
 * Instances are created fresh for every request on the server thread, so they may hold references to
 * block entities freely. Other mods can add their own by registering a {@link ComponentProvider} in
 * {@link Components}.
 */
public interface Component {
	/** Stable per-computer address (derived from the VM id, the type and the location — same across restarts). */
	UUID address();

	/** e.g. {@code redstone}, {@code inventory}, {@code screen}. */
	String type();

	/** Where it is relative to the computer: {@code self}, or a side name for an adjacent block. */
	String location();

	/** Method name → human readable signature/doc, returned by {@code list}. Only these names may be invoked. */
	Map<String, String> methods();

	/** Runs a method. Called on the server thread. {@code args} is never null (may be empty). */
	JsonElement invoke(String method, JsonArray args) throws BusException;

	static UUID addressOf(final UUID vmId, final String type, final String location) {
		return UUID.nameUUIDFromBytes((vmId + "/" + type + "/" + location).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
}
