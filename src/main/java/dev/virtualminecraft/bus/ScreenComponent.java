package dev.virtualminecraft.bus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.virtualminecraft.block.MonitorBlockEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * A linked monitor in text mode: a character grid the guest draws into over the bus, for guests without a
 * graphics stack (or as a second display). Location = the monitor's offset from the computer, {@code dx,dy,dz}.
 * Coordinates are 1-based like OC/CC; any drawing call switches the monitor to text mode; {@code setTextMode(false)}
 * returns it to showing the VM framebuffer. Colours are 24-bit ints or {@code "#rrggbb"} strings.
 */
public final class ScreenComponent implements Component {
	public static final String TYPE = "screen";
	private static final Map<String, String> METHODS = new LinkedHashMap<>();

	static {
		METHODS.put("getSize", "getSize() -> [cols, rows]");
		METHODS.put("setSize", "setSize(cols, rows) -> [cols, rows] (1-200 x 1-100)");
		METHODS.put("getTextMode", "getTextMode() -> whether the monitor shows this grid instead of the VM screen");
		METHODS.put("setTextMode", "setTextMode(on) -> on; false goes back to the VM framebuffer");
		METHODS.put("clear", "clear() clears the grid with the current background and homes the cursor");
		METHODS.put("clearLine", "clearLine(y)");
		METHODS.put("write", "write(text) at the cursor; wraps, honours \\n, scrolls at the bottom");
		METHODS.put("set", "set(x, y, text) writes text at a position without moving the cursor");
		METHODS.put("get", "get(x, y) -> the character at a position");
		METHODS.put("fill", "fill(x, y, w, h, char)");
		METHODS.put("scroll", "scroll(n) scrolls the content up by n rows (negative = down)");
		METHODS.put("getCursorPos", "getCursorPos() -> [x, y]");
		METHODS.put("setCursorPos", "setCursorPos(x, y)");
		METHODS.put("getColors", "getColors() -> [fg, bg] as 0xRRGGBB");
		METHODS.put("setColors", "setColors(fg[, bg]) for subsequent writes; ints or '#rrggbb'");
	}

	private final MonitorBlockEntity monitor;
	private final String location;
	private final UUID address;

	private ScreenComponent(final BusHost computer, final MonitorBlockEntity monitor) {
		this.monitor = monitor;
		final BlockPos d = monitor.getBlockPos().subtract(computer.getBlockPos());
		this.location = d.getX() + "," + d.getY() + "," + d.getZ();
		this.address = Component.addressOf(computer.busId(), TYPE, location);
	}

	public static String locationOf(final BlockPos computer, final BlockPos monitor) {
		final BlockPos d = monitor.subtract(computer);
		return d.getX() + "," + d.getY() + "," + d.getZ();
	}

	/** Provider: one component per loaded monitor linked to this computer. */
	public static void collect(final ServerLevel level, final BusHost computer, final List<Component> out) {
		for (final MonitorBlockEntity m : computer.linkedMonitors(level)) {
			if (m.isOrigin()) { // one screen per rectangle of monitors
				out.add(new ScreenComponent(computer, m));
			}
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
		return location;
	}

	@Override
	public Map<String, String> methods() {
		return METHODS;
	}

	@Override
	public JsonElement invoke(final String method, final JsonArray args) throws BusException {
		final TextGrid g = monitor.textGrid();
		switch (method) {
			case "getSize":
				return pair(g.cols, g.rows);
			case "setSize": {
				g.resize(intArg(args, 0, "cols"), intArg(args, 1, "rows"));
				monitor.markTextChanged(true);
				return pair(g.cols, g.rows);
			}
			case "getTextMode":
				return new JsonPrimitive(monitor.isTextMode());
			case "setTextMode": {
				final JsonElement e = arg(args, 0);
				final boolean on = e == null || e.isJsonNull() || (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : e.getAsInt() != 0);
				monitor.setTextMode(on);
				return new JsonPrimitive(on);
			}
			case "clear":
				g.clear();
				monitor.markTextChanged(true);
				return new JsonPrimitive(true);
			case "clearLine":
				g.clearLine(intArg(args, 0, "y") - 1);
				monitor.markTextChanged(true);
				return new JsonPrimitive(true);
			case "write":
				g.write(stringArg(args, 0, "text"));
				monitor.markTextChanged(true);
				return pair(g.cursorX + 1, g.cursorY + 1);
			case "set": {
				final int x = intArg(args, 0, "x") - 1;
				final int y = intArg(args, 1, "y") - 1;
				g.set(x, y, stringArg(args, 2, "text"));
				monitor.markTextChanged(true);
				return new JsonPrimitive(true);
			}
			case "get": {
				final int cp = g.get(intArg(args, 0, "x") - 1, intArg(args, 1, "y") - 1);
				return new JsonPrimitive(new String(Character.toChars(cp)));
			}
			case "fill": {
				final String ch = stringArg(args, 4, "char");
				g.fill(intArg(args, 0, "x") - 1, intArg(args, 1, "y") - 1, intArg(args, 2, "w"), intArg(args, 3, "h"), ch.isEmpty() ? ' ' : ch.codePointAt(0));
				monitor.markTextChanged(true);
				return new JsonPrimitive(true);
			}
			case "scroll":
				g.scroll(intArg(args, 0, "n"));
				monitor.markTextChanged(true);
				return new JsonPrimitive(true);
			case "getCursorPos":
				return pair(g.cursorX + 1, g.cursorY + 1);
			case "setCursorPos": {
				g.cursorX = Math.clamp(intArg(args, 0, "x") - 1, 0, g.cols - 1);
				g.cursorY = Math.clamp(intArg(args, 1, "y") - 1, 0, g.rows - 1);
				return pair(g.cursorX + 1, g.cursorY + 1);
			}
			case "getColors":
				return pair(g.curFg, g.curBg);
			case "setColors": {
				g.curFg = colorArg(args, 0, g.curFg);
				g.curBg = colorArg(args, 1, g.curBg);
				return pair(g.curFg, g.curBg);
			}
			default:
				throw new BusException(BusException.METHOD_NOT_FOUND, "screen has no method '" + method + "'");
		}
	}

	private static JsonArray pair(final int a, final int b) {
		final JsonArray out = new JsonArray();
		out.add(a);
		out.add(b);
		return out;
	}

	private static @Nullable JsonElement arg(final JsonArray args, final int i) {
		return i < args.size() ? args.get(i) : null;
	}

	private static int intArg(final JsonArray args, final int i, final String name) throws BusException {
		final JsonElement e = arg(args, i);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw BusException.invalidParams(name + " must be a number");
		}
		return e.getAsInt();
	}

	private static String stringArg(final JsonArray args, final int i, final String name) throws BusException {
		final JsonElement e = arg(args, i);
		if (e == null || e.isJsonNull()) {
			throw BusException.invalidParams(name + " required");
		}
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	private static int colorArg(final JsonArray args, final int i, final int current) throws BusException {
		final JsonElement e = arg(args, i);
		if (e == null || e.isJsonNull()) {
			return current;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			return e.getAsInt() & 0xFFFFFF;
		}
		final String s = e.getAsString().strip();
		try {
			return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s.startsWith("0x") ? s.substring(2) : s, 16) & 0xFFFFFF;
		} catch (final NumberFormatException ex) {
			throw BusException.invalidParams("colour must be 0xRRGGBB or '#rrggbb'");
		}
	}

	/** Builds the {@code screen_touch} event params for a monitor hit. */
	public static JsonObject touchEvent(final UUID vmId, final BlockPos computer, final BlockPos monitor, final int x, final int y, final String player) {
		final JsonObject p = new JsonObject();
		final String loc = locationOf(computer, monitor);
		p.addProperty("address", Component.addressOf(vmId, TYPE, loc).toString());
		p.addProperty("location", loc);
		p.addProperty("x", x);
		p.addProperty("y", y);
		p.addProperty("player", player);
		return p;
	}
}
