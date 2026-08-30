package dev.virtualminecraft.bus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * A container the computer can reach (chest, barrel, hopper, furnace, anything exposing Fabric's item
 * storage): touching it, or touching a bus cable connected to it. Location = the side of the computer it
 * touches, or its {@code dx,dy,dz} offset when it is out on a cable (see {@link BusNetwork}). Slots are <b>1-based</b> like OpenComputers and
 * CC: Tweaked so existing scripts port over. Transfers go through the Transfer API inside a transaction, so
 * stacking, slot filters (furnace fuel, hopper facing) and modded containers behave exactly as a hopper would.
 */
public final class InventoryComponent implements Component {
	public static final String TYPE = "inventory";
	private static final Map<String, String> METHODS = new LinkedHashMap<>();

	static {
		METHODS.put("size", "size() -> number of slots");
		METHODS.put("name", "name() -> display name of the container");
		METHODS.put("list", "list() -> {slot: {name, count, displayName, maxCount}} for non-empty slots (1-based)");
		METHODS.put("getItemDetail", "getItemDetail(slot) -> full item description or null");
		METHODS.put("getItemLimit", "getItemLimit(slot) -> max items that slot can hold");
		METHODS.put("pushItems", "pushItems(to, fromSlot[, limit[, toSlot]]) -> items moved; 'to' = address, type@side or side of another inventory");
		METHODS.put("pullItems", "pullItems(from, fromSlot[, limit[, toSlot]]) -> items moved into this inventory");
	}

	private final ServerLevel level;
	private final BusHost computer;
	private final String location;
	private final BlockPos pos;
	private final SlottedStorage<ItemVariant> storage;
	private final UUID address;

	private InventoryComponent(final ServerLevel level, final BusHost computer, final String location, final BlockPos pos, final SlottedStorage<ItemVariant> storage) {
		this.level = level;
		this.computer = computer;
		this.location = location;
		this.pos = pos;
		this.storage = storage;
		this.address = Component.addressOf(computer.busId(), TYPE, location);
	}

	/** Provider: one component per reachable block that exposes a slotted item storage (adjacent or on cable). */
	public static void collect(final ServerLevel level, final BusHost computer, final List<Component> out) {
		for (final Map.Entry<BlockPos, String> e : computer.attached(level).entrySet()) {
			final BlockPos p = e.getKey();
			if (!level.hasChunkAt(p)) {
				continue;
			}
			// The face we touch only exists for a neighbour; a container out on cable gets the unsided view.
			final Direction face = faceTowards(computer.getBlockPos(), p);
			final SlottedStorage<ItemVariant> s = findStorage(level, p, face);
			if (s != null) {
				out.add(new InventoryComponent(level, computer, e.getValue(), p, s));
			}
		}
	}

	/** The side of {@code pos} that {@code from} touches, or null when they are not neighbours. */
	private static @Nullable Direction faceTowards(final BlockPos from, final BlockPos pos) {
		for (final Direction d : Direction.values()) {
			if (from.relative(d).equals(pos)) {
				return d.getOpposite();
			}
		}
		return null;
	}

	private static @Nullable SlottedStorage<ItemVariant> findStorage(final ServerLevel level, final BlockPos p, final @Nullable Direction face) {
		// Like CC: Tweaked: the unsided view first (all slots of a furnace), then the face we touch.
		final Storage<ItemVariant> internal = ItemStorage.SIDED.find(level, p, null);
		if (internal instanceof SlottedStorage<ItemVariant> s) {
			return s;
		}
		if (face == null) {
			return null;
		}
		final Storage<ItemVariant> external = ItemStorage.SIDED.find(level, p, face);
		return external instanceof SlottedStorage<ItemVariant> s ? s : null;
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
			case "size":
				return new JsonPrimitive(storage.getSlotCount());
			case "name":
				return new JsonPrimitive(displayName());
			case "list": {
				final JsonObject o = new JsonObject();
				final int n = storage.getSlotCount();
				for (int i = 0; i < n; i++) {
					final SingleSlotStorage<ItemVariant> slot = storage.getSlot(i);
					if (!slot.isResourceBlank() && slot.getAmount() > 0) {
						o.add(Integer.toString(i + 1), basicDetails(slot.getResource().toStack((int) Math.min(Integer.MAX_VALUE, slot.getAmount()))));
					}
				}
				return o;
			}
			case "getItemDetail": {
				final SingleSlotStorage<ItemVariant> slot = storage.getSlot(slotArg(args, 0, storage));
				if (slot.isResourceBlank() || slot.getAmount() <= 0) {
					return JsonNull.INSTANCE;
				}
				return fullDetails(slot.getResource().toStack((int) Math.min(Integer.MAX_VALUE, slot.getAmount())));
			}
			case "getItemLimit":
				return new JsonPrimitive(storage.getSlot(slotArg(args, 0, storage)).getCapacity());
			case "pushItems": {
				final InventoryComponent other = otherInventory(arg(args, 0));
				final int fromSlot = slotArg(args, 1, storage);
				final long limit = limitArg(args, 2);
				final int toSlot = args.size() > 3 && !args.get(3).isJsonNull() ? slotArg(args, 3, other.storage) : -1;
				return new JsonPrimitive(move(storage, fromSlot, other.storage, toSlot, limit));
			}
			case "pullItems": {
				final InventoryComponent other = otherInventory(arg(args, 0));
				final int fromSlot = slotArg(args, 1, other.storage);
				final long limit = limitArg(args, 2);
				final int toSlot = args.size() > 3 && !args.get(3).isJsonNull() ? slotArg(args, 3, storage) : -1;
				return new JsonPrimitive(move(other.storage, fromSlot, storage, toSlot, limit));
			}
			default:
				throw new BusException(BusException.METHOD_NOT_FOUND, "inventory has no method '" + method + "'");
		}
	}

	private String displayName() {
		final BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof Nameable n) {
			return n.getDisplayName().getString();
		}
		return level.getBlockState(pos).getBlock().getName().getString();
	}

	private InventoryComponent otherInventory(final @Nullable JsonElement target) throws BusException {
		if (target == null || target.isJsonNull()) {
			throw BusException.invalidParams("target inventory required (address, inventory@side or side)");
		}
		final String t = target.getAsString();
		final Component c = Components.find(Components.collect(level, computer), t, TYPE);
		if (!(c instanceof InventoryComponent inv)) {
			throw new BusException(BusException.NO_SUCH_COMPONENT, "no inventory '" + t + "'");
		}
		if (inv.address.equals(address)) {
			throw BusException.invalidParams("source and target are the same inventory");
		}
		return inv;
	}

	/** Moves up to {@code limit} items from one slot into a specific slot ({@code toSlot >= 0}) or anywhere in {@code to}. */
	private static long move(final SlottedStorage<ItemVariant> from, final int fromSlot, final SlottedStorage<ItemVariant> to, final int toSlot, final long limit) {
		if (limit <= 0) {
			return 0;
		}
		final SingleSlotStorage<ItemVariant> src = from.getSlot(fromSlot);
		final Storage<ItemVariant> dst = toSlot >= 0 ? to.getSlot(toSlot) : to;
		try (Transaction tx = Transaction.openOuter()) {
			final long moved = StorageUtil.move(src, dst, v -> true, limit, tx);
			tx.commit();
			return moved;
		}
	}

	// ---- item descriptions ----

	static JsonObject basicDetails(final ItemStack stack) {
		final JsonObject o = new JsonObject();
		o.addProperty("name", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		o.addProperty("count", stack.getCount());
		o.addProperty("displayName", stack.getHoverName().getString());
		o.addProperty("maxCount", stack.getMaxStackSize());
		return o;
	}

	static JsonObject fullDetails(final ItemStack stack) {
		final JsonObject o = basicDetails(stack);
		if (stack.isDamageableItem()) {
			o.addProperty("damage", stack.getDamageValue());
			o.addProperty("maxDamage", stack.getMaxDamage());
		}
		if (stack.has(DataComponents.CUSTOM_NAME)) {
			o.addProperty("customName", stack.getHoverName().getString());
		}
		final JsonArray tags = new JsonArray();
		stack.typeHolder().tags().forEach(t -> tags.add(t.location().toString()));
		o.add("tags", tags);
		return o;
	}

	// ---- argument helpers ----

	private static @Nullable JsonElement arg(final JsonArray args, final int i) {
		return i < args.size() ? args.get(i) : null;
	}

	/** 1-based slot argument → 0-based index, range-checked against {@code s}. */
	private static int slotArg(final JsonArray args, final int i, final SlottedStorage<ItemVariant> s) throws BusException {
		final JsonElement e = arg(args, i);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw BusException.invalidParams("slot number required (1-" + s.getSlotCount() + ")");
		}
		final int slot = e.getAsInt();
		if (slot < 1 || slot > s.getSlotCount()) {
			throw BusException.invalidParams("slot out of range (1-" + s.getSlotCount() + ")");
		}
		return slot - 1;
	}

	private static long limitArg(final JsonArray args, final int i) throws BusException {
		final JsonElement e = arg(args, i);
		if (e == null || e.isJsonNull()) {
			return Long.MAX_VALUE;
		}
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw BusException.invalidParams("limit must be a number");
		}
		return e.getAsLong();
	}
}
