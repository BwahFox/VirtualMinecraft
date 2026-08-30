package dev.virtualminecraft.bus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Redstone on the six faces of the computer block itself. Location {@code self}. */
public final class RedstoneComponent implements Component {
	public static final String TYPE = "redstone";
	private static final Map<String, String> METHODS = new LinkedHashMap<>();

	static {
		METHODS.put("getInput", "getInput(side) -> 0..15: signal arriving at that face");
		METHODS.put("getInputs", "getInputs() -> {side: level} for all six faces");
		METHODS.put("getOutput", "getOutput(side) -> 0..15: what this face currently emits");
		METHODS.put("getOutputs", "getOutputs() -> {side: level}");
		METHODS.put("setOutput", "setOutput(side, level) -> previous level; emits redstone from that face");
		METHODS.put("setOutputs", "setOutputs({side: level}) -> {side: previous}");
		METHODS.put("getFacing", "getFacing() -> absolute side the computer's front faces");
		METHODS.put("getWake", "getWake() -> 0..15: the input level that starts this computer when it is off (0 = never)");
		METHODS.put("setWake", "setWake(0..15) -> previous; a rising edge to this level powers the computer on");
		METHODS.put("getSleep", "getSleep() -> boolean: does a falling edge below the wake level shut it down");
		METHODS.put("setSleep", "setSleep(boolean) -> previous");
	}

	private final ServerLevel level;
	private final BusHost be;
	private final UUID address;

	public RedstoneComponent(final ServerLevel level, final BusHost be) {
		this.level = level;
		this.be = be;
		this.address = Component.addressOf(be.busId(), TYPE, "self");
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

	private Direction facing() {
		return be.facing();
	}

	@Override
	public JsonElement invoke(final String method, final JsonArray args) throws BusException {
		switch (method) {
			case "getInput":
				return new JsonPrimitive(be.getInput(level, Sides.parse(arg(args, 0), facing())));
			case "getInputs": {
				final JsonObject o = new JsonObject();
				for (final Direction d : Direction.values()) {
					o.addProperty(Sides.name(d), be.getInput(level, d));
				}
				return o;
			}
			case "getOutput":
				return new JsonPrimitive(be.getOutput(Sides.parse(arg(args, 0), facing())));
			case "getOutputs": {
				final JsonObject o = new JsonObject();
				for (final Direction d : Direction.values()) {
					o.addProperty(Sides.name(d), be.getOutput(d));
				}
				return o;
			}
			case "setOutput": {
				final Direction d = Sides.parse(arg(args, 0), facing());
				final int lvl = level(arg(args, 1));
				return new JsonPrimitive(be.setOutput(d, lvl));
			}
			case "setOutputs": {
				final JsonElement e = arg(args, 0);
				if (e == null || !e.isJsonObject()) {
					throw BusException.invalidParams("setOutputs expects {side: level}");
				}
				final JsonObject out = new JsonObject();
				for (final Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
					final Direction d = Sides.parse(new JsonPrimitive(en.getKey()), facing());
					out.addProperty(Sides.name(d), be.setOutput(d, level(en.getValue())));
				}
				return out;
			}
			case "getFacing":
				return new JsonPrimitive(Sides.name(facing()));
			case "getWake":
				return new JsonPrimitive(be.getWakeThreshold());
			case "setWake": {
				final int was = be.getWakeThreshold();
				be.setWakeThreshold(level(arg(args, 0)));
				return new JsonPrimitive(was);
			}
			case "getSleep":
				return new JsonPrimitive(be.getRedstoneSleep());
			case "setSleep": {
				final boolean was = be.getRedstoneSleep();
				final JsonElement e = arg(args, 0);
				if (e == null || !e.isJsonPrimitive()) {
					throw BusException.invalidParams("setSleep expects true or false");
				}
				be.setRedstoneSleep(e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : level(e) > 0);
				return new JsonPrimitive(was);
			}
			default:
				throw new BusException(BusException.METHOD_NOT_FOUND, "redstone has no method '" + method + "'");
		}
	}

	private static JsonElement arg(final JsonArray args, final int i) {
		return i < args.size() ? args.get(i) : null;
	}

	private static int level(final JsonElement e) throws BusException {
		if (e == null || !e.isJsonPrimitive()) {
			throw BusException.invalidParams("level required (0-15, or true/false)");
		}
		final JsonPrimitive p = e.getAsJsonPrimitive();
		if (p.isBoolean()) {
			return p.getAsBoolean() ? 15 : 0;
		}
		try {
			return Math.clamp(p.getAsInt(), 0, 15);
		} catch (final NumberFormatException ex) {
			throw BusException.invalidParams("level must be a number 0-15");
		}
	}
}
