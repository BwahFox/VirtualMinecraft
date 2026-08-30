package dev.virtualminecraft.item;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/**
 * The {@code disk} item data (1.20.1: an NBT compound on the stack, see {@link StackData}; 26.2: the
 * {@code virtualminecraft:disk} component): which image file a disk item stands for.
 * <p>
 * {@code id} names the file {@code <world>/virtualminecraft/items/<id>.qcow2} for floppies and hard drives
 * (created lazily the first time a computer starts with the disk attached). A CD carries no file of its own:
 * {@code iso} is a name in the ISO directory (or an absolute path), exactly like the computer's ISO field.
 * {@code sizeMb} is the virtual size for image-backed kinds (fixed 1.44 MB for floppies).
 */
public record DiskData(UUID id, int sizeMb, String iso) {
	/**
	 * The id a disk carries before it is anybody's: a floppy template out of a loot table or a trade (U3c step 2,
	 * 2026-08-28). Two such items must never share a file, so {@link DiskItem#ensureData} mints a real id the
	 * moment one is inserted somewhere. Blank items from the creative tab carry no data at all instead.
	 */
	public static final UUID TEMPLATE = new UUID(0L, 0L);

	public boolean isTemplate() {
		return TEMPLATE.equals(id);
	}

	/** File name of the image for image-backed kinds. */
	public String fileName() {
		return id + ".qcow2";
	}

	/** Short id shown on tooltips and in the BIOS list. */
	public String serial() {
		return id.toString().substring(0, 8);
	}

	public CompoundTag toTag() {
		final CompoundTag t = new CompoundTag();
		t.putString("id", id.toString());
		t.putInt("sizeMb", sizeMb);
		t.putString("iso", iso);
		return t;
	}

	/** Same tolerance as 26.2's codec: {@code sizeMb} and {@code iso} are optional; a bad {@code id} is a template. */
	public static DiskData fromTag(final CompoundTag t) {
		UUID id = TEMPLATE;
		try {
			id = UUID.fromString(t.getString("id"));
		} catch (final IllegalArgumentException ignored) {
		}
		return new DiskData(id, t.getInt("sizeMb"), t.getString("iso"));
	}
}
