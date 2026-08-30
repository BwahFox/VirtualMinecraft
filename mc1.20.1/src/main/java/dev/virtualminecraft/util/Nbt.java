package dev.virtualminecraft.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 1.20.1 only. The 26.2 code reads NBT through {@code ValueInput} ("this key or that default", "this child if it is
 * there"); these are the same questions asked of a {@link CompoundTag}, so the ported block entities read the same
 * way the originals do and a diff between the two trees stays about the API and not about style.
 */
public final class Nbt {
	private Nbt() {
	}

	public static String getStringOr(final CompoundTag t, final String key, final String def) {
		return t.contains(key, Tag.TAG_STRING) ? t.getString(key) : def;
	}

	public static Optional<String> getString(final CompoundTag t, final String key) {
		return t.contains(key, Tag.TAG_STRING) ? Optional.of(t.getString(key)) : Optional.empty();
	}

	public static int getIntOr(final CompoundTag t, final String key, final int def) {
		return t.contains(key, Tag.TAG_ANY_NUMERIC) ? t.getInt(key) : def;
	}

	public static boolean getBooleanOr(final CompoundTag t, final String key, final boolean def) {
		return t.contains(key, Tag.TAG_ANY_NUMERIC) ? t.getBoolean(key) : def;
	}

	public static Optional<int[]> getIntArray(final CompoundTag t, final String key) {
		return t.contains(key, Tag.TAG_INT_ARRAY) ? Optional.of(t.getIntArray(key)) : Optional.empty();
	}

	public static Optional<CompoundTag> child(final CompoundTag t, final String key) {
		return t.contains(key, Tag.TAG_COMPOUND) ? Optional.of(t.getCompound(key)) : Optional.empty();
	}

	/** {@code ValueOutput.child}: a fresh compound stored under {@code key}, returned to be written into. */
	public static CompoundTag newChild(final CompoundTag t, final String key) {
		final CompoundTag c = new CompoundTag();
		t.put(key, c);
		return c;
	}

	/** {@code ValueOutput.list(key, ItemStack.OPTIONAL_CODEC)}: every stack, empties included, so slot order survives. */
	public static void putItems(final CompoundTag t, final String key, final Iterable<ItemStack> items) {
		final ListTag list = new ListTag();
		for (final ItemStack s : items) {
			list.add(s.save(new CompoundTag()));
		}
		t.put(key, list);
	}

	/** {@code ValueInput.listOrEmpty(key, ItemStack.OPTIONAL_CODEC)}. */
	public static List<ItemStack> getItems(final CompoundTag t, final String key) {
		final ListTag list = t.getList(key, Tag.TAG_COMPOUND);
		final List<ItemStack> out = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			out.add(ItemStack.of(list.getCompound(i)));
		}
		return out;
	}

	public static Optional<ListTag> list(final CompoundTag t, final String key) {
		return t.contains(key, Tag.TAG_LIST) ? Optional.of(t.getList(key, Tag.TAG_COMPOUND)) : Optional.empty();
	}
}
