package dev.virtualminecraft.item;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * 1.20.1 only: the item data that 26.2 keeps in data components ({@code ModContent.DISK_DATA}, {@code COMPUTER_ID},
 * {@code BRIDGE_PAIR}, {@code COMPUTER_LABEL}, {@code COMPUTER_MEM_MB}) lives in the stack's NBT here, under the
 * keys below. Every read and write of it goes through this class, so the rest of the port reads like 26.2 with
 * {@code stack.get(X)} spelled {@code StackData.x(stack)}.
 * <p>
 * Loot tables and the clerk's trades write the same keys with {@code minecraft:set_nbt}.
 */
public final class StackData {
	public static final String DISK = "disk";
	public static final String COMPUTER_ID = "computer_id";
	public static final String BRIDGE_PAIR = "bridge_pair";
	public static final String COMPUTER_LABEL = "computer_label";
	public static final String COMPUTER_MEM_MB = "computer_mem_mb";

	private StackData() {
	}

	public static @Nullable DiskData disk(final ItemStack stack) {
		final CompoundTag t = stack.getTag();
		return t != null && t.contains(DISK, Tag.TAG_COMPOUND) ? DiskData.fromTag(t.getCompound(DISK)) : null;
	}

	public static void setDisk(final ItemStack stack, final DiskData data) {
		stack.getOrCreateTag().put(DISK, data.toTag());
	}

	public static @Nullable UUID uuid(final ItemStack stack, final String key) {
		final CompoundTag t = stack.getTag();
		if (t == null || !t.contains(key, Tag.TAG_STRING)) {
			return null;
		}
		try {
			return UUID.fromString(t.getString(key));
		} catch (final IllegalArgumentException e) {
			return null;
		}
	}

	public static void setUuid(final ItemStack stack, final String key, final @Nullable UUID id) {
		if (id == null) {
			final CompoundTag t = stack.getTag();
			if (t != null) {
				t.remove(key);
			}
			return;
		}
		stack.getOrCreateTag().putString(key, id.toString());
	}

	public static @Nullable String string(final ItemStack stack, final String key) {
		final CompoundTag t = stack.getTag();
		return t != null && t.contains(key, Tag.TAG_STRING) ? t.getString(key) : null;
	}

	public static void setString(final ItemStack stack, final String key, final String value) {
		stack.getOrCreateTag().putString(key, value);
	}

	public static @Nullable Integer integer(final ItemStack stack, final String key) {
		final CompoundTag t = stack.getTag();
		return t != null && t.contains(key, Tag.TAG_ANY_NUMERIC) ? t.getInt(key) : null;
	}
}
