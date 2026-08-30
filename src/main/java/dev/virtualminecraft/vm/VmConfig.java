package dev.virtualminecraft.vm;

import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.util.Nums;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Per-computer virtual machine settings. Stored in the computer block entity and edited from the client. */
public final class VmConfig {
	public String name = "computer";
	public int memMb = 2048;
	public int cpus = 2;
	/** Internal disk size; 0 = no internal disk (boot from disk items only). Existing images are kept regardless. */
	public int diskGb = 32;
	/** ISO file name (relative to the config iso directory) or absolute path. Empty = none. */
	public String iso = "";
	public boolean uefi = false;
	public boolean bootFromCd = true;
	/** QEMU {@code -vga} value: std, virtio, qxl, cirrus, none. */
	public String vga = "std";
	/** NIC model: e1000, virtio-net-pci, rtl8139 or none. A guest with a NIC reaches the internet through the
	 * *host's* address, so a dedicated server turns the lot off with {@code allowGuestNetwork} (see VmcConfig);
	 * in singleplayer the address is your own and this stays on. */
	public String nic = "e1000";
	/**
	 * Extra QEMU arguments, whitespace separated. <b>Never accepted from a client packet</b> — QEMU's own options
	 * include {@code -virtfs} (mount a host directory into the guest) and {@code -drive file=…} (any host file as a
	 * disk), so this is host filesystem access with the server's privileges. Set it from the server config or
	 * {@code /vmc args} (operators only); it is not part of the wire format at all.
	 */
	public String extraArgs = "";
	public boolean autostart = false;
	/** Redstone level (1–15) on any face that starts the computer; 0 = never wake by redstone. Rising edge only. */
	public int wakeThreshold = 0;
	/** With a wake threshold: ACPI-shutdown when every face drops back below it. */
	public boolean redstoneSleep = false;
	/** Snapshot RAM+disk (QEMU {@code savevm}) when the chunk unloads or the server stops, and resume on load instead of rebooting. */
	public boolean suspend = true;

	public VmConfig copy() {
		final VmConfig c = new VmConfig();
		c.name = name;
		c.memMb = memMb;
		c.cpus = cpus;
		c.diskGb = diskGb;
		c.iso = iso;
		c.uefi = uefi;
		c.bootFromCd = bootFromCd;
		c.vga = vga;
		c.nic = nic;
		c.extraArgs = extraArgs;
		c.autostart = autostart;
		c.wakeThreshold = wakeThreshold;
		c.redstoneSleep = redstoneSleep;
		c.suspend = suspend;
		return c;
	}

	public void sanitize() {
		if (name == null || name.isBlank()) {
			name = "computer";
		}
		name = name.strip();
		if (name.length() > 32) {
			name = name.substring(0, 32);
		}
		final VmcConfig global = VmcConfig.get();
		memMb = Nums.clamp(memMb, 64, Math.max(64, global.maxMemMbPerVm));
		cpus = Nums.clamp(cpus, 1, Math.max(1, global.maxCpusPerVm));
		diskGb = Nums.clamp(diskGb, 0, Math.max(0, global.maxDiskGbPerVm));
		if (iso == null) {
			iso = "";
		}
		if (vga == null || vga.isBlank()) {
			vga = "std";
		}
		if (nic == null || nic.isBlank()) {
			nic = "e1000";
		}
		if (!global.allowGuestNetwork) {
			nic = "none"; // the admin has turned guest networking off for the whole server
		}
		if (extraArgs == null) {
			extraArgs = "";
		}
		wakeThreshold = Nums.clamp(wakeThreshold, 0, 15);
	}

	/**
	 * A client may only name an ISO <em>inside</em> the configured ISO directory: an absolute path (or one that
	 * climbs out with {@code ..}) would let any player near a computer mount an arbitrary host file into their
	 * guest and read it. Operators and the console are not restricted (see {@code /vmc iso}).
	 */
	public static boolean isoAllowedFromClient(final String iso) {
		if (iso == null || iso.isBlank()) {
			return true;
		}
		try {
			final Path p = Path.of(iso);
			if (p.isAbsolute()) {
				return false;
			}
			final Path dir = VmcConfig.get().isoDir().normalize();
			return dir.resolve(p).normalize().startsWith(dir);
		} catch (final InvalidPathException e) {
			return false;
		}
	}

	/**
	 * The config to actually store when a client asks to change one: everything the player may set, with the
	 * fields they may not ({@link #extraArgs}, an out-of-tree {@link #iso}) carried over from what is already
	 * there. The only path from a packet to this object, so the checks live here rather than in the caller.
	 */
	public static VmConfig fromClientEdit(final VmConfig current, final VmConfig incoming) {
		final VmConfig c = incoming.copy();
		c.extraArgs = current.extraArgs;
		if (!isoAllowedFromClient(c.iso)) {
			c.iso = current.iso;
		}
		c.sanitize();
		return c;
	}

	public void save(final ValueOutput out) {
		out.putString("name", name);
		out.putInt("memMb", memMb);
		out.putInt("cpus", cpus);
		out.putInt("diskGb", diskGb);
		out.putString("iso", iso);
		out.putBoolean("uefi", uefi);
		out.putBoolean("bootFromCd", bootFromCd);
		out.putString("vga", vga);
		out.putString("nic", nic);
		out.putString("extraArgs", extraArgs);
		out.putBoolean("autostart", autostart);
		out.putInt("wakeThreshold", wakeThreshold);
		out.putBoolean("redstoneSleep", redstoneSleep);
		out.putBoolean("suspend", suspend);
	}

	public void load(final ValueInput in) {
		name = in.getStringOr("name", name);
		memMb = in.getIntOr("memMb", memMb);
		cpus = in.getIntOr("cpus", cpus);
		diskGb = in.getIntOr("diskGb", diskGb);
		iso = in.getStringOr("iso", iso);
		uefi = in.getBooleanOr("uefi", uefi);
		bootFromCd = in.getBooleanOr("bootFromCd", bootFromCd);
		vga = in.getStringOr("vga", vga);
		nic = in.getStringOr("nic", nic);
		extraArgs = in.getStringOr("extraArgs", extraArgs);
		autostart = in.getBooleanOr("autostart", autostart);
		wakeThreshold = in.getIntOr("wakeThreshold", wakeThreshold);
		redstoneSleep = in.getBooleanOr("redstoneSleep", redstoneSleep);
		suspend = in.getBooleanOr("suspend", suspend);
		sanitize();
	}

	public void write(final FriendlyByteBuf buf) {
		buf.writeUtf(name, 64);
		buf.writeVarInt(memMb);
		buf.writeVarInt(cpus);
		buf.writeVarInt(diskGb);
		buf.writeUtf(iso, 1024);
		buf.writeBoolean(uefi);
		buf.writeBoolean(bootFromCd);
		buf.writeUtf(vga, 32);
		buf.writeUtf(nic, 32);
		// extraArgs is deliberately absent from the wire: see the field's javadoc.
		buf.writeBoolean(autostart);
		buf.writeByte(wakeThreshold);
		buf.writeBoolean(redstoneSleep);
		buf.writeBoolean(suspend);
	}

	public static VmConfig read(final FriendlyByteBuf buf) {
		final VmConfig c = new VmConfig();
		c.name = buf.readUtf(64);
		c.memMb = buf.readVarInt();
		c.cpus = buf.readVarInt();
		c.diskGb = buf.readVarInt();
		c.iso = buf.readUtf(1024);
		c.uefi = buf.readBoolean();
		c.bootFromCd = buf.readBoolean();
		c.vga = buf.readUtf(32);
		c.nic = buf.readUtf(32);
		c.autostart = buf.readBoolean();
		c.wakeThreshold = buf.readByte();
		c.redstoneSleep = buf.readBoolean();
		c.suspend = buf.readBoolean();
		c.sanitize();
		return c;
	}
}
