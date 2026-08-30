package dev.virtualminecraft.bus;

import com.google.gson.JsonElement;
import net.minecraft.core.Direction;

/** Side names accepted from the guest: absolute (north…down), relative to the block's facing (front/back/left/right), or 0–5. */
public final class Sides {
	private Sides() {
	}

	public static Direction parse(final JsonElement e, final Direction facing) throws BusException {
		if (e == null || e.isJsonNull()) {
			throw BusException.invalidParams("side required");
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			final int i = e.getAsInt();
			if (i < 0 || i > 5) {
				throw BusException.invalidParams("side must be 0-5 (down, up, north, south, west, east)");
			}
			return Direction.from3DDataValue(i);
		}
		final String s = e.getAsString().strip().toLowerCase();
		switch (s) {
			case "front":
				return facing;
			case "back":
				return facing.getOpposite();
			case "left":
				return facing.getCounterClockWise();
			case "right":
				return facing.getClockWise();
			case "top":
				return Direction.UP;
			case "bottom":
				return Direction.DOWN;
			default:
				break;
		}
		final Direction d = Direction.byName(s);
		if (d == null) {
			throw BusException.invalidParams("unknown side '" + s + "' (north/south/east/west/up/down, front/back/left/right, or 0-5)");
		}
		return d;
	}

	public static String name(final Direction d) {
		return d.getSerializedName();
	}
}
