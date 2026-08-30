package dev.virtualminecraft.vm;

import dev.virtualminecraft.config.VmcConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Builds QEMU command lines and handles disk image creation. */
public final class QemuLauncher {
	private QemuLauncher() {
	}

	private static volatile Boolean dbusDisplayModule;

	/** Whether this QEMU build has the {@code dbus} display (a separate module on Debian/Arch); probed once. */
	public static boolean dbusDisplayAvailable(final VmcConfig global) {
		Boolean known = dbusDisplayModule;
		if (known == null) {
			boolean found = false;
			try {
				final Process p = new ProcessBuilder(global.qemuBinary, "-display", "help").redirectErrorStream(true).start();
				final String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				p.waitFor();
				for (final String line : out.split("\n")) {
					if (line.trim().equals("dbus")) {
						found = true;
					}
				}
			} catch (final Exception e) {
				found = false;
			}
			known = found;
			dbusDisplayModule = known;
		}
		return known;
	}

	/** Config says so, the host is Linux with FFM, and QEMU has the module. */
	public static boolean wantsDbusDisplay(final VmcConfig global) {
		return !"vnc".equalsIgnoreCase(global.display) && !isWindows() && dev.virtualminecraft.dbus.Libc.available() && dbusDisplayAvailable(global);
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	public static boolean kvmAvailable() {
		if (isWindows()) {
			return false;
		}
		final Path kvm = Path.of("/dev/kvm");
		return Files.isReadable(kvm) && Files.isWritable(kvm);
	}

	/** {@code -vnc}/{@code -qmp} endpoint specs; on Windows QEMU has no unix sockets so we fall back to loopback TCP. */
	public record Endpoints(String vncSpec, String qmpSpec, String busSpec, Path vncSocket, Path qmpSocket, Path busSocket, int vncPort, int qmpPort, int busPort) {
	}

	/** Unix socket paths are limited to ~104 bytes, so sockets live in a short temp directory rather than the world folder. */
	private static final int MAX_UNIX_PATH = 100;
	/** The q35 machine's built-in AHCI has six ports; a second controller is added for anything beyond. */
	private static final int BUILTIN_SATA_PORTS = 6;

	public static Endpoints endpoints(final UUID id, final int slot) {
		if (!isWindows()) {
			final Path dir = socketDir(id);
			if (dir != null) {
				final Path vnc = dir.resolve("vnc.sock");
				final Path qmp = dir.resolve("qmp.sock");
				final Path bus = dir.resolve("bus.sock");
				if (vnc.toString().length() <= MAX_UNIX_PATH && qmp.toString().length() <= MAX_UNIX_PATH && bus.toString().length() <= MAX_UNIX_PATH) {
					return new Endpoints("unix:" + vnc, "unix:" + qmp + ",server=on,wait=off", "socket,id=vmcbus,path=" + bus + ",server=on,wait=off",
						vnc, qmp, bus, -1, -1, -1);
				}
			}
		}
		final int vncDisplay = 90 + slot; // port 5990+slot
		final int qmpPort = 4590 + slot;
		final int busPort = 4690 + slot;
		return new Endpoints("127.0.0.1:" + vncDisplay, "tcp:127.0.0.1:" + qmpPort + ",server=on,wait=off",
			"socket,id=vmcbus,host=127.0.0.1,port=" + busPort + ",server=on,wait=off", null, null, null, 5900 + vncDisplay, qmpPort, busPort);
	}

	private static Path socketDir(final UUID id) {
		final String runtime = System.getenv("XDG_RUNTIME_DIR");
		final Path base = runtime != null && !runtime.isBlank() ? Path.of(runtime) : Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
		final String shortId = id.toString().replace("-", "").substring(0, 12);
		final Path dir = base.resolve("virtualminecraft").resolve(shortId);
		try {
			Files.createDirectories(dir);
			return dir;
		} catch (final IOException e) {
			return null;
		}
	}

	/**
	 * Builds the command line. {@code disks} is in command-line order with boot indices assigned (see
	 * {@link Attachments}); {@code loadvmTag} != null restores that internal qcow2 snapshot (RAM + disk) instead
	 * of booting.
	 * <p>
	 * Every block device is an explicit {@code -drive if=none,id=X} backend plus a {@code -device …,id=dev-X}
	 * front end with its own {@code bootindex}, so boot order follows the inserted items rather than
	 * {@code -boot order=}. HDDs and CDs sit on SATA ({@code ide.N}, then a second AHCI {@code vmcahci.N}),
	 * floppies on an ISA controller ({@code isa-fdc}, whose {@code bootindexA/B} carry the floppy boot order).
	 * Removable units may start empty; QMP {@code blockdev-change-medium} / {@code eject} on the device id
	 * swap media while the guest runs.
	 */
	public static List<String> buildCommand(final VmcConfig global, final VmConfig cfg, final List<Attachment> disks, final Endpoints ep, final String loadvmTag) {
		final boolean kvm = global.enableKvm && kvmAvailable();
		final List<String> cmd = new ArrayList<>();
		cmd.add(global.qemuBinary);
		cmd.add("-name");
		cmd.add("vmc-" + cfg.name.replaceAll("[^A-Za-z0-9_-]", "_"));
		cmd.add("-machine");
		cmd.add("q35,accel=" + (kvm ? "kvm" : "tcg"));
		cmd.add("-cpu");
		cmd.add(kvm ? "host" : "max");
		cmd.add("-smp");
		cmd.add(Integer.toString(cfg.cpus));
		cmd.add("-m");
		cmd.add(cfg.memMb + "M");
		if (cfg.uefi && global.ovmfPath != null && !global.ovmfPath.isBlank()) {
			cmd.add("-bios");
			cmd.add(global.ovmfPath);
		}
		addDisks(cmd, disks);
		cmd.add("-boot");
		cmd.add("menu=on,splash-time=1500");
		cmd.add("-vga");
		cmd.add(cfg.vga);
		cmd.add("-display");
		// The D-Bus display carries scancodes (and later scanout + audio); VNC stays for the picture and as the
		// fallback. Nothing attaches to it until the VM is up, and if attaching fails the VM just runs on VNC.
		cmd.add(wantsDbusDisplay(global) ? "dbus,p2p=on" : "none");
		cmd.add("-audiodev");
		cmd.add("none,id=snd0");
		cmd.add("-device");
		cmd.add("ich9-intel-hda");
		cmd.add("-device");
		cmd.add("hda-output,audiodev=snd0");
		cmd.add("-vnc");
		cmd.add(ep.vncSpec() + ",audiodev=snd0");
		cmd.add("-qmp");
		cmd.add(ep.qmpSpec());
		cmd.add("-usb");
		cmd.add("-device");
		cmd.add("usb-tablet");
		// Guest <-> world bus: a virtio-serial port the guest sees as /dev/virtio-ports/vmc.bus (see bus/VmBus).
		cmd.add("-device");
		cmd.add("virtio-serial-pci,id=vmcserial");
		cmd.add("-chardev");
		cmd.add(ep.busSpec());
		cmd.add("-device");
		cmd.add("virtserialport,bus=vmcserial.0,chardev=vmcbus,name=vmc.bus");
		if (!"none".equalsIgnoreCase(cfg.nic)) {
			cmd.add("-nic");
			cmd.add("user,model=" + cfg.nic);
		} else {
			cmd.add("-nic");
			cmd.add("none");
		}
		cmd.add("-rtc");
		cmd.add("base=localtime");
		cmd.add("-monitor");
		cmd.add("none");
		cmd.add("-serial");
		cmd.add("none");
		cmd.add("-parallel");
		cmd.add("none");
		if (loadvmTag != null) {
			cmd.add("-loadvm");
			cmd.add(loadvmTag);
		}
		addArgs(cmd, global.extraArgs);
		addArgs(cmd, cfg.extraArgs);
		return cmd;
	}

	private static void addDisks(final List<String> cmd, final List<Attachment> disks) {
		int sata = 0;
		int floppyUnit = 0;
		boolean extraAhci = false;
		final int[] floppyBoot = { -1, -1 };
		for (final Attachment a : disks) {
			if (a.type() == Attachment.Type.FLOPPY && floppyUnit < Attachments.FLOPPY_UNITS) {
				floppyBoot[floppyUnit++] = a.bootIndex();
			}
		}
		if (floppyUnit > 0) {
			// fallback=144: a unit that is empty at boot must still be a 1.44 MB drive, or a floppy inserted later fails to read.
			final StringBuilder fdc = new StringBuilder("isa-fdc,id=fdc0,fallback=144");
			if (floppyBoot[0] >= 0) {
				fdc.append(",bootindexA=").append(floppyBoot[0]);
			}
			if (floppyBoot[1] >= 0) {
				fdc.append(",bootindexB=").append(floppyBoot[1]);
			}
			cmd.add("-device");
			cmd.add(fdc.toString());
		}
		floppyUnit = 0;
		for (final Attachment a : disks) {
			switch (a.type()) {
				case HDD -> {
					cmd.add("-drive");
					cmd.add("if=none,id=" + a.id() + ",format=qcow2,file=" + a.file());
					cmd.add("-device");
					cmd.add("ide-hd,id=" + a.deviceId() + ",drive=" + a.id() + ",bus=" + sataBus(cmd, sata++, extraAhci) + bootIndex(a));
					extraAhci |= sata > BUILTIN_SATA_PORTS;
				}
				case CD -> {
					cmd.add("-drive");
					cmd.add("if=none,id=" + a.id() + ",media=cdrom" + (a.file() != null ? ",file=" + a.file() + ",format=raw,read-only=on" : ""));
					cmd.add("-device");
					cmd.add("ide-cd,id=" + a.deviceId() + ",drive=" + a.id() + ",bus=" + sataBus(cmd, sata++, extraAhci) + bootIndex(a));
					extraAhci |= sata > BUILTIN_SATA_PORTS;
				}
				case FLOPPY -> {
					if (floppyUnit >= Attachments.FLOPPY_UNITS) {
						continue;
					}
					cmd.add("-drive");
					cmd.add("if=none,id=" + a.id() + (a.file() != null ? ",format=qcow2,file=" + a.file() : ""));
					cmd.add("-device");
					cmd.add("floppy,id=" + a.deviceId() + ",drive=" + a.id() + ",unit=" + floppyUnit++);
				}
			}
		}
	}

	/** SATA port for the n-th SATA device; adds the second AHCI controller the first time it is needed. */
	private static String sataBus(final List<String> cmd, final int n, final boolean extraAhciAdded) {
		if (n < BUILTIN_SATA_PORTS) {
			return "ide." + n;
		}
		if (!extraAhciAdded) {
			cmd.add(cmd.size() - 2, "-device");
			cmd.add(cmd.size() - 2, "ahci,id=vmcahci");
		}
		return "vmcahci." + (n - BUILTIN_SATA_PORTS);
	}

	private static String bootIndex(final Attachment a) {
		return a.bootIndex() >= 0 ? ",bootindex=" + a.bootIndex() : "";
	}

	private static void addArgs(final List<String> cmd, final String args) {
		if (args == null || args.isBlank()) {
			return;
		}
		for (final String a : args.strip().split("\\s+")) {
			if (!a.isEmpty()) {
				cmd.add(a);
			}
		}
	}

	public static Path resolveIso(final String iso, final Path isoDir) {
		if (iso == null || iso.isBlank()) {
			return null;
		}
		final Path direct = Path.of(iso);
		if (direct.isAbsolute()) {
			return Files.isRegularFile(direct) ? direct : null;
		}
		final Path rel = isoDir.resolve(iso).normalize();
		if (!rel.startsWith(isoDir.normalize())) {
			return null;
		}
		return Files.isRegularFile(rel) ? rel : null;
	}

	/** Removes an internal snapshot from a qcow2 that no QEMU process has open. Failure is logged, not thrown. */
	public static void deleteSnapshot(final VmcConfig global, final Path disk, final String tag) {
		try {
			final Process p = new ProcessBuilder(global.qemuImgBinary, "snapshot", "-d", tag, disk.toString())
				.redirectErrorStream(true)
				.start();
			final String output = new String(p.getInputStream().readAllBytes());
			if (!p.waitFor(30, TimeUnit.SECONDS)) {
				p.destroyForcibly();
			} else if (p.exitValue() != 0) {
				dev.virtualminecraft.VirtualMinecraft.LOGGER.debug("qemu-img snapshot -d {} {}: {}", tag, disk, output.strip());
			}
		} catch (final IOException e) {
			dev.virtualminecraft.VirtualMinecraft.LOGGER.warn("qemu-img snapshot -d failed for {}: {}", disk, e.toString());
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public static void createDisk(final VmcConfig global, final Path disk, final int sizeGb) throws IOException, InterruptedException {
		createDisk(global, disk, sizeGb * 1024L * 1024L * 1024L);
	}

	/** Creates a qcow2 of {@code sizeBytes} (qemu-img accepts a plain byte count). */
	public static void createDisk(final VmcConfig global, final Path disk, final long sizeBytes) throws IOException, InterruptedException {
		Files.createDirectories(disk.getParent());
		final Process p = new ProcessBuilder(global.qemuImgBinary, "create", "-f", "qcow2", disk.toString(), Long.toString(sizeBytes))
			.redirectErrorStream(true)
			.start();
		final String output = new String(p.getInputStream().readAllBytes());
		if (!p.waitFor(30, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new IOException("qemu-img timed out");
		}
		if (p.exitValue() != 0) {
			throw new IOException("qemu-img failed (" + p.exitValue() + "): " + output.strip());
		}
	}
}
