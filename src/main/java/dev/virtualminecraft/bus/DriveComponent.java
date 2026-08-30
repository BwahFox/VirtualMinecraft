package dev.virtualminecraft.bus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.virtualminecraft.block.DiskDriveBlockEntity;
import dev.virtualminecraft.item.DiskData;
import dev.virtualminecraft.item.DiskItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * A disk-drive block the computer can reach ({@code type=drive}): what is in it, and eject it. Location is
 * the side it touches, or its {@code dx,dy,dz} offset when it sits out on a bus cable.
 */
public final class DriveComponent implements Component {
	public static final String TYPE = "drive";
	private static final Map<String, String> METHODS = new LinkedHashMap<>();

	static {
		METHODS.put("hasMedia", "hasMedia() -> true if a disk is in the drive");
		METHODS.put("getMedia", "getMedia() -> {kind, description, serial?, iso?} or null");
		METHODS.put("eject", "eject() -> true; pops the disk out onto the ground (like OpenComputers)");
	}

	private final ServerLevel level;
	private final DiskDriveBlockEntity drive;
	private final String location;
	private final UUID address;

	public DriveComponent(final ServerLevel level, final BusHost computer, final String location, final DiskDriveBlockEntity drive) {
		this.level = level;
		this.drive = drive;
		this.location = location;
		this.address = Component.addressOf(computer.busId(), TYPE, location);
	}

	public static void collect(final ServerLevel level, final BusHost computer, final List<Component> out) {
		for (final Map.Entry<BlockPos, String> e : computer.attached(level).entrySet()) {
			final BlockPos p = e.getKey();
			if (level.hasChunkAt(p) && level.getBlockEntity(p) instanceof DiskDriveBlockEntity drive) {
				out.add(new DriveComponent(level, computer, e.getValue(), drive));
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
		switch (method) {
			case "hasMedia":
				return new JsonPrimitive(drive.hasMedia());
			case "getMedia": {
				final ItemStack m = drive.getMedia();
				final DiskItem.Kind kind = DiskItem.kindOf(m);
				if (m.isEmpty() || kind == null) {
					return JsonNull.INSTANCE;
				}
				final JsonObject o = new JsonObject();
				o.addProperty("kind", kind.id);
				o.addProperty("description", DiskItem.describe(m));
				final DiskData d = DiskItem.data(m);
				if (d != null) {
					if (kind == DiskItem.Kind.CD) {
						o.addProperty("iso", d.iso());
					} else {
						o.addProperty("serial", d.serial());
					}
				}
				return o;
			}
			case "eject": {
				final ItemStack out = drive.eject(level, null);
				if (out.isEmpty()) {
					return new JsonPrimitive(false);
				}
				Block.popResource(level, drive.getBlockPos(), out);
				return new JsonPrimitive(true);
			}
			default:
				throw new BusException(BusException.METHOD_NOT_FOUND, "no such method: " + method);
		}
	}
}
