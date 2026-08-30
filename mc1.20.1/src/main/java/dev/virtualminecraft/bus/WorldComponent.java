package dev.virtualminecraft.bus;

import dev.virtualminecraft.util.Nums;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the computer can see of the world around it (OpenComputers' {@code detect} plus a geolyzer-lite):
 * the block on a face, any block within {@link #MAX_RANGE}, the clock, the weather, the biome, and who is
 * standing nearby. Read-only — nothing here changes the world — so it is not rate limited or gated.
 * <p>
 * Coordinates are <b>relative to the computer</b>, like everything else on the bus. Blocks in unloaded
 * chunks read as {@code null} rather than loading them: a guest polling coordinates must never be able to
 * drag chunks into memory.
 */
public final class WorldComponent implements Component {
	public static final String TYPE = "world";
	/** Farthest a guest may look, per axis. Deliberately short: this is a sensor, not a map. */
	public static final int MAX_RANGE = 32;
	/** Farthest {@code getPlayers} looks. */
	public static final int MAX_PLAYER_RANGE = 64;
	private static final Map<String, String> METHODS = new LinkedHashMap<>();

	static {
		METHODS.put("getPosition", "getPosition() -> {x, y, z, dimension, facing}: where the computer is");
		METHODS.put("detect", "detect(side) -> block description on that face of the computer");
		METHODS.put("getBlock", "getBlock(dx, dy, dz) -> block description relative to the computer (max 32), null if not loaded");
		METHODS.put("getTime", "getTime() -> {time, day, ticks, gameTime, daylight}: time is 0-23999 (0 = dawn, 6000 = noon)");
		METHODS.put("getWeather", "getWeather() -> {weather, raining, thundering, rainingHere}");
		METHODS.put("getBiome", "getBiome([dx, dy, dz]) -> biome id at that position (default: the computer)");
		METHODS.put("getLight", "getLight([dx, dy, dz]) -> 0-15 light level");
		METHODS.put("getPlayers", "getPlayers([radius]) -> [{name, x, y, z, distance}] within radius (max 64) of the computer");
	}

	private final ServerLevel level;
	private final BusHost be;
	private final UUID address;

	public WorldComponent(final ServerLevel level, final BusHost be) {
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

	@Override
	public JsonElement invoke(final String method, final JsonArray args) throws BusException {
		final BlockPos self = be.getBlockPos();
		switch (method) {
			case "getPosition": {
				final JsonObject o = new JsonObject();
				o.addProperty("x", self.getX());
				o.addProperty("y", self.getY());
				o.addProperty("z", self.getZ());
				o.addProperty("dimension", level.dimension().location().toString());
				o.addProperty("facing", Sides.name(be.facing()));
				return o;
			}
			case "detect": {
				final Direction d = Sides.parse(arg(args, 0), be.facing());
				return describe(self.relative(d));
			}
			case "getBlock": {
				return describe(self.offset(rangeArg(args, 0), rangeArg(args, 1), rangeArg(args, 2)));
			}
			case "getTime": {
				final long ticks = level.getDayTime();
				final JsonObject o = new JsonObject();
				o.addProperty("time", Math.floorMod(ticks, 24000L));
				o.addProperty("day", Math.floorDiv(ticks, 24000L));
				o.addProperty("ticks", ticks);
				o.addProperty("gameTime", level.getGameTime());
				o.addProperty("daylight", level.isDay());
				return o;
			}
			case "getWeather": {
				final JsonObject o = new JsonObject();
				o.addProperty("weather", level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear");
				o.addProperty("raining", level.isRaining());
				o.addProperty("thundering", level.isThundering());
				o.addProperty("rainingHere", level.isRainingAt(self.above()));
				return o;
			}
			case "getBiome": {
				final BlockPos p = args.isEmpty() ? self : self.offset(rangeArg(args, 0), rangeArg(args, 1), rangeArg(args, 2));
				if (!level.hasChunkAt(p)) {
					return JsonNull.INSTANCE;
				}
				return new JsonPrimitive(level.getBiome(p).unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains"));
			}
			case "getLight": {
				final BlockPos p = args.isEmpty() ? self : self.offset(rangeArg(args, 0), rangeArg(args, 1), rangeArg(args, 2));
				if (!level.hasChunkAt(p)) {
					return JsonNull.INSTANCE;
				}
				return new JsonPrimitive(level.getMaxLocalRawBrightness(p));
			}
			case "getPlayers": {
				final double radius = args.isEmpty() ? MAX_PLAYER_RANGE : Nums.clamp(number(arg(args, 0), "radius"), 0, MAX_PLAYER_RANGE);
				final double r2 = radius * radius;
				final JsonArray out = new JsonArray();
				for (final ServerPlayer p : level.players()) {
					if (p.isSpectator()) {
						continue;
					}
					final double d2 = p.distanceToSqr(self.getX() + 0.5, self.getY() + 0.5, self.getZ() + 0.5);
					if (d2 > r2) {
						continue;
					}
					final JsonObject o = new JsonObject();
					o.addProperty("name", p.getName().getString());
					o.addProperty("x", p.getX() - self.getX());
					o.addProperty("y", p.getY() - self.getY());
					o.addProperty("z", p.getZ() - self.getZ());
					o.addProperty("distance", Math.round(Math.sqrt(d2) * 100) / 100.0);
					out.add(o);
				}
				return out;
			}
			default:
				throw new BusException(BusException.METHOD_NOT_FOUND, "world has no method '" + method + "'");
		}
	}

	/** {@code null} for an unloaded position — never loads a chunk on a guest's say-so. */
	private JsonElement describe(final BlockPos p) {
		if (!level.hasChunkAt(p)) {
			return JsonNull.INSTANCE;
		}
		final BlockState s = level.getBlockState(p);
		final JsonObject o = new JsonObject();
		o.addProperty("name", BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
		o.addProperty("displayName", s.getBlock().getName().getString());
		o.addProperty("air", s.isAir());
		o.addProperty("solid", s.isSolid());
		o.addProperty("light", s.getLightEmission());
		o.addProperty("hardness", s.getDestroySpeed(level, p));
		if (!s.getFluidState().isEmpty()) {
			o.addProperty("fluid", BuiltInRegistries.FLUID.getKey(s.getFluidState().getType()).toString());
		}
		final BlockPos d = p.subtract(be.getBlockPos());
		o.addProperty("dx", d.getX());
		o.addProperty("dy", d.getY());
		o.addProperty("dz", d.getZ());
		return o;
	}

	private static JsonElement arg(final JsonArray args, final int i) {
		return i < args.size() ? args.get(i) : null;
	}

	private static int rangeArg(final JsonArray args, final int i) throws BusException {
		final int v = (int) number(arg(args, i), "coordinate");
		if (v < -MAX_RANGE || v > MAX_RANGE) {
			throw BusException.invalidParams("coordinates are relative to the computer and limited to +/-" + MAX_RANGE);
		}
		return v;
	}

	private static double number(final JsonElement e, final String what) throws BusException {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw BusException.invalidParams(what + " must be a number");
		}
		return e.getAsDouble();
	}
}
