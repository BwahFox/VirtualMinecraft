package dev.virtualminecraft.item;

import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.util.Nums;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * A disk you can carry: floppy, CD or hard drive. The item is a handle on a file (see {@link DiskData}); the
 * file is what the guest OS sees, so an operating system installed on a hard-drive item boots on whichever
 * computer the item is put into. Floppies and CDs go into a disk-drive block (hot-swappable while the guest
 * runs); hard drives go inside the computer (right-click it while holding one; sneak + empty hand ejects).
 */
public class DiskItem extends Item {
	public enum Kind {
		FLOPPY("floppy", 1, true),
		CD("cd", 0, true),
		HARD_DRIVE("hard_drive", 32 * 1024, false);

		public final String id;
		/** Default virtual size in MB (floppies are really 1440 KB; see {@link #FLOPPY_BYTES}). */
		public final int defaultSizeMb;
		/** Removable media live in a disk drive; fixed media live inside the computer. */
		public final boolean removable;

		Kind(final String id, final int defaultSizeMb, final boolean removable) {
			this.id = id;
			this.defaultSizeMb = defaultSizeMb;
			this.removable = removable;
		}
	}

	public static final long FLOPPY_BYTES = 1474560L;
	public static final int MAX_HDD_GB = 512;

	public final Kind kind;

	public DiskItem(final Kind kind, final Properties properties) {
		super(properties);
		this.kind = kind;
	}

	public static @Nullable Kind kindOf(final ItemStack stack) {
		return stack.getItem() instanceof DiskItem d ? d.kind : null;
	}

	public static boolean isDisk(final ItemStack stack) {
		return stack.getItem() instanceof DiskItem;
	}

	public static @Nullable DiskData data(final ItemStack stack) {
		return StackData.disk(stack);
	}

	/**
	 * Server side: gives a fresh-from-the-creative-tab disk its identity. Called the moment a disk is inserted
	 * somewhere, so two copies of a blank item never share a file.
	 */
	public static DiskData ensureData(final ItemStack stack) {
		final DiskData existing = data(stack);
		if (existing != null && !existing.isTemplate()) {
			return existing;
		}
		if (existing != null) {
			// a template floppy (loot, a trade): this copy becomes its own disk now, seeded from the template on mount
			final DiskData d = new DiskData(UUID.randomUUID(), existing.sizeMb(), existing.iso());
			StackData.setDisk(stack, d);
			return d;
		}
		final Kind kind = kindOf(stack);
		final DiskData d = new DiskData(UUID.randomUUID(), kind == null ? 0 : kind.defaultSizeMb, "");
		StackData.setDisk(stack, d);
		return d;
	}

	/** A hard drive of the given size (GB), a CD for an ISO, or a floppy — used by {@code /vmc give}. */
	public static ItemStack create(final Kind kind, final int sizeGb, final String iso) {
		final Item item = switch (kind) {
			case FLOPPY -> ModContent.FLOPPY;
			case CD -> ModContent.CD;
			case HARD_DRIVE -> ModContent.HARD_DRIVE;
		};
		final ItemStack stack = new ItemStack(item);
		final int sizeMb = kind == Kind.HARD_DRIVE ? Nums.clamp(sizeGb, 1, MAX_HDD_GB) * 1024 : kind.defaultSizeMb;
		StackData.setDisk(stack, new DiskData(UUID.randomUUID(), sizeMb, kind == Kind.HARD_DRIVE ? "" : iso.strip()));
		return stack;
	}

	/** One-line description for GUIs and the BIOS screen: "Hard drive 32 GB", "CD archlinux.iso", "Floppy". */
	public static String describe(final ItemStack stack) {
		final Kind kind = kindOf(stack);
		final DiskData d = data(stack);
		if (kind == null) {
			return stack.getHoverName().getString();
		}
		final String base = switch (kind) {
			case FLOPPY -> d == null || d.iso().isBlank() ? "Floppy" : "Floppy " + baseName(d.iso());
			case CD -> d == null || d.iso().isBlank() ? "CD (blank)" : "CD " + baseName(d.iso());
			case HARD_DRIVE -> "Hard drive " + sizeText(d == null ? kind.defaultSizeMb : d.sizeMb());
		};
		final String custom = stack.getHoverName().getString();
		final String plain = Component.translatable(stack.getItem().getDescriptionId()).getString();
		return custom.equals(plain) ? base : base + " \"" + custom + "\"";
	}

	static String sizeText(final int sizeMb) {
		return sizeMb >= 1024 && sizeMb % 1024 == 0 ? (sizeMb / 1024) + " GB" : sizeMb + " MB";
	}

	static String baseName(final String path) {
		final int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return i >= 0 ? path.substring(i + 1) : path;
	}

	@Override
	public void appendHoverText(final ItemStack stack, final @Nullable Level world, final List<Component> tooltip, final TooltipFlag flag) {
		final java.util.function.Consumer<Component> lines = tooltip::add;
		final DiskData d = data(stack);
		switch (kind) {
			case FLOPPY -> lines.accept(Component.literal(d == null || d.iso().isBlank() ? "1.44 MB" : "1.44 MB, " + baseName(d.iso()) + " on it").withStyle(net.minecraft.ChatFormatting.GRAY));
			case CD -> lines.accept(Component.literal(d == null || d.iso().isBlank() ? "Blank — use /vmc give cd <iso>" : "ISO: " + baseName(d.iso())).withStyle(net.minecraft.ChatFormatting.GRAY));
			case HARD_DRIVE -> lines.accept(Component.literal(sizeText(d == null ? kind.defaultSizeMb : d.sizeMb())).withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		if (kind != Kind.CD) {
			lines.accept(Component.literal(d == null ? "New (blank)" : d.isTemplate() ? "New" : "Serial " + d.serial()).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		}
	}
}
