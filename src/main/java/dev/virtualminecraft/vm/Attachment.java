package dev.virtualminecraft.vm;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * One block device on the QEMU command line: the computer's internal disk, a disk item in one of its slots,
 * the configured ISO, or a unit of an adjacent disk-drive block (which may be empty at launch and get a
 * medium later via QMP).
 *
 * @param id        drive backend id; the qdev id is {@code dev-<id>} (what QMP {@code eject}/{@code blockdev-change-medium} take)
 * @param type      HDD / CD / FLOPPY — decides the QEMU device model
 * @param file      image or ISO; null = removable device with no medium
 * @param sizeBytes virtual size used to create a missing qcow2 (0 = never create)
 * @param readOnly  ISO media
 * @param label     human text for logs and the BIOS screen
 * @param driveLocation bus location of the owning disk-drive block (a side name, or a {@code dx,dy,dz}
 *                      offset when it sits out on a bus cable); null for everything else
 * @param bootIndex QEMU {@code bootindex} (0 = first); -1 = not bootable / not assigned
 */
public record Attachment(String id, Type type, @Nullable Path file, long sizeBytes, boolean readOnly, String label, @Nullable String driveLocation, int bootIndex) {
	public enum Type {
		HDD,
		CD,
		FLOPPY
	}

	public Attachment withBootIndex(final int index) {
		return new Attachment(id, type, file, sizeBytes, readOnly, label, driveLocation, index);
	}

	/** qdev id of the device (the drive backend is {@link #id()}). */
	public String deviceId() {
		return "dev-" + id;
	}

	public boolean hasMedium() {
		return file != null;
	}

	/** Writable qcow2 images take part in {@code savevm}; ISOs and empty units do not. */
	public boolean holdsSnapshot() {
		return file != null && !readOnly;
	}
}
