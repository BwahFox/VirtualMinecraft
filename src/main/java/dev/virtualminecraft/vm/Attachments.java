package dev.virtualminecraft.vm;

import dev.virtualminecraft.block.ComputerBlockEntity;
import dev.virtualminecraft.block.DiskDriveBlockEntity;
import dev.virtualminecraft.bus.BusNetwork;
import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.item.DiskData;
import dev.virtualminecraft.item.DiskItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Builds the block-device list for a computer from what is physically there: the internal disk (if
 * {@code diskGb > 0} or the file already exists), the disk items in the computer's slots, the ISO field,
 * and every disk-drive block it can reach — touching it, or out on a bus cable ({@link BusNetwork}) — with
 * one CD unit each plus a floppy unit for the first two.
 * <p>
 * Boot order ("Boot: removable first" = the old {@code bootFromCd}): ISO field → drive-block media → slot
 * items → internal disk; otherwise internal disk → slot items → ISO → drive media. Within a group, slot
 * order / side order. The returned list is in <em>command-line</em> order — internal disk first, so that
 * {@code savevm} puts the guest's RAM into it rather than into a carried hard-drive item — with
 * {@link Attachment#bootIndex()} carrying the boot order.
 */
public final class Attachments {
	/** Number of floppy units the ISA controller has. Further drive blocks are CD-only. */
	public static final int FLOPPY_UNITS = 2;

	private Attachments() {
	}

	public static List<Attachment> collect(final VmManager manager, final ServerLevel level, final ComputerBlockEntity be) {
		final VmConfig cfg = be.getConfig();
		final VmcConfig global = VmcConfig.get();
		final Path vmDir = manager.vmDir(be.getVmId());
		final Path items = manager.itemsDir();
		final Path isoDir = global.isoDir();

		final List<Attachment> internal = new ArrayList<>(1);
		final Path disk = vmDir.resolve("disk.qcow2");
		if (cfg.diskGb > 0 || Files.isRegularFile(disk)) {
			internal.add(new Attachment("hd0", Attachment.Type.HDD, disk, cfg.diskGb * 1024L * 1024L * 1024L, false, "Internal disk " + cfg.diskGb + " GB", null, -1));
		}

		final List<Attachment> fixed = new ArrayList<>();
		final List<ItemStack> slots = be.getDisks();
		for (int i = 0; i < slots.size(); i++) {
			final ItemStack s = slots.get(i);
			final DiskItem.Kind kind = DiskItem.kindOf(s);
			if (s.isEmpty() || kind == null) {
				continue;
			}
			final DiskData d = DiskItem.ensureData(s);
			final String id = "slot" + (i + 1);
			switch (kind) {
				case HARD_DRIVE -> fixed.add(new Attachment(id, Attachment.Type.HDD, items.resolve(d.fileName()), d.sizeMb() * 1024L * 1024L, false, DiskItem.describe(s), null, -1));
				case CD -> fixed.add(new Attachment(id, Attachment.Type.CD, QemuLauncher.resolveIso(d.iso(), isoDir), 0, true, DiskItem.describe(s), null, -1));
				case FLOPPY -> {
					// Floppies belong in a disk drive; the block refuses them, but a datapack could put one here.
				}
			}
		}

		final List<Attachment> isoList = new ArrayList<>(1);
		final Path iso = QemuLauncher.resolveIso(cfg.iso, isoDir);
		if (iso != null) {
			isoList.add(new Attachment("iso", Attachment.Type.CD, iso, 0, true, "ISO " + iso.getFileName(), null, -1));
		}

		// Drive blocks, in the bus order (neighbours first, then along the cable) so unit numbering is stable.
		final List<Attachment> removable = new ArrayList<>();
		int floppyUnits = 0;
		for (final Map.Entry<BlockPos, String> e : be.attached(level).entrySet()) {
			final BlockPos p = e.getKey();
			if (!level.hasChunkAt(p) || !(level.getBlockEntity(p) instanceof DiskDriveBlockEntity drive)) {
				continue;
			}
			final ItemStack media = drive.getMedia();
			final DiskItem.Kind kind = DiskItem.kindOf(media);
			final DiskData md = media.isEmpty() || kind == null ? null : DiskItem.ensureData(media);
			final String loc = e.getValue();
			final String what = media.isEmpty() ? "empty" : DiskItem.describe(media);
			removable.add(new Attachment(cdId(loc), Attachment.Type.CD, kind == DiskItem.Kind.CD && md != null ? QemuLauncher.resolveIso(md.iso(), isoDir) : null, 0, true,
				"Drive " + loc + " CD: " + (kind == DiskItem.Kind.CD ? what : "empty"), loc, -1));
			if (floppyUnits < FLOPPY_UNITS) {
				floppyUnits++;
				removable.add(new Attachment(floppyId(loc), Attachment.Type.FLOPPY, kind == DiskItem.Kind.FLOPPY && md != null && !java.nio.file.Files.isDirectory(items.resolve(md.id().toString())) ? items.resolve(md.fileName()) : null, DiskItem.FLOPPY_BYTES, false,
					"Drive " + loc + " floppy: " + (kind == DiskItem.Kind.FLOPPY ? what : "empty"), loc, -1));
			}
		}

		final List<Attachment> boot = new ArrayList<>();
		if (cfg.bootFromCd) {
			boot.addAll(isoList);
			boot.addAll(removable);
			boot.addAll(fixed);
			boot.addAll(internal);
		} else {
			boot.addAll(internal);
			boot.addAll(fixed);
			boot.addAll(isoList);
			boot.addAll(removable);
		}
		// The same image twice (a duplicated item) would make QEMU refuse the second open: keep the first only.
		final java.util.Set<Path> seen = new java.util.HashSet<>();
		final List<Attachment> out = new ArrayList<>(boot.size());
		for (final Attachment a : boot) {
			if (a.file() != null && !a.readOnly() && !seen.add(a.file())) {
				dev.virtualminecraft.VirtualMinecraft.LOGGER.warn("VM {}: {} is attached twice ({}); ignoring the duplicate", cfg.name, a.file().getFileName(), a.label());
				continue;
			}
			out.add(a.withBootIndex(out.size()));
		}
		// Command-line order: internal disk first (savevm writes the RAM into the first writable qcow2).
		out.sort((a, b) -> Boolean.compare(!"hd0".equals(a.id()), !"hd0".equals(b.id())));
		return out;
	}

	/** Drive backend id of the CD unit of the disk drive at bus location {@code location}. */
	public static String cdId(final String location) {
		return "drive-" + driveKey(location) + "-cd";
	}

	public static String floppyId(final String location) {
		return "drive-" + driveKey(location) + "-fd";
	}

	/**
	 * A bus location as a QEMU-safe id fragment. Side names pass through unchanged (so drives placed before
	 * cables existed keep their device ids across an upgrade); an offset {@code 1,0,-3} becomes
	 * {@code 1_0_m3}, because a comma in {@code -drive id=} would start a new QEMU option.
	 */
	public static String driveKey(final String location) {
		final StringBuilder sb = new StringBuilder(location.length());
		for (int i = 0; i < location.length(); i++) {
			final char c = location.charAt(i);
			sb.append(c == ',' ? '_' : c == '-' ? 'm' : c);
		}
		return sb.toString();
	}

	/** The attachment a disk item would be as a medium in a drive unit; null if it is a blank CD or not a disk. */
	public static @Nullable Attachment mediumFor(final VmManager manager, final ItemStack stack, final String location) {
		final DiskItem.Kind kind = DiskItem.kindOf(stack);
		if (kind == null || stack.isEmpty()) {
			return null;
		}
		final DiskData d = DiskItem.ensureData(stack);
		return switch (kind) {
			case CD -> {
				final Path iso = QemuLauncher.resolveIso(d.iso(), VmcConfig.get().isoDir());
				yield iso == null ? null : new Attachment(cdId(location), Attachment.Type.CD, iso, 0, true, DiskItem.describe(stack), location, -1);
			}
			case FLOPPY -> new Attachment(floppyId(location), Attachment.Type.FLOPPY, manager.itemsDir().resolve(d.fileName()), DiskItem.FLOPPY_BYTES, false, DiskItem.describe(stack), location, -1);
			case HARD_DRIVE -> null;
		};
	}

	/** Anything with a medium at all? Otherwise the "BIOS" screen is shown instead of launching. */
	public static boolean anyBootable(final List<Attachment> list) {
		for (final Attachment a : list) {
			if (a.hasMedium()) {
				return true;
			}
		}
		return false;
	}

	/** Boot-ordered view for display. */
	public static List<Attachment> bootOrder(final List<Attachment> list) {
		final List<Attachment> out = new ArrayList<>(list);
		out.sort((a, b) -> Integer.compare(a.bootIndex(), b.bootIndex()));
		return out;
	}
}
