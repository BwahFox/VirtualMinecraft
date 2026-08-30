package dev.virtualminecraft.computer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.block.DiskDriveBlockEntity;
import dev.virtualminecraft.item.DiskData;
import dev.virtualminecraft.item.DiskItem;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The Computer's storage (ROADMAP §7h §4): mounts seen by programs as path prefixes — {@code /rom} (the jar,
 * read-only), {@code /disk} (the machine's own directory, {@code computerDiskKb} quota), {@code /fd0}, {@code /fd1}
 * (floppy items in drive blocks, 1.44 MB, writable), {@code /cd0}, {@code /cd1} (CD items: read-only, either a
 * burned directory under {@code items/} or a directory the admin put under {@code config/virtualminecraft/cds/}),
 * and {@code /import} (read-only, only if {@code config/virtualminecraft/import/} exists). Files are files: a
 * directory tree on the host, names limited to {@code [A-Za-z0-9._-]}, depth ≤ 16, symlinks not followed, the
 * quota counted on every write.
 * <p>
 * <b>One format per medium:</b> a floppy is a qcow2 image for the VM tier or a directory for this tier, never both.
 * A floppy that has a qcow2 and no directory is <i>foreign</i> here (listed, unreadable, {@code format} makes it
 * ours); the VM tier skips floppies that have a directory.
 * <p>
 * Threads: the syscalls run on the worker; {@link #refresh} on the server thread swaps the mount table atomically.
 */
public final class MachineFiles {
	public static final String CD_DIR_PREFIX = "cds:";
	/** A floppy's {@code iso} naming a template: {@code floppies:<name>}, a directory copied onto the disk the first time it is mounted. */
	public static final String FLOPPY_DIR_PREFIX = "floppies:";
	public static final int MAX_DEPTH = 16;
	public static final int MAX_NAME = 64;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
	private static final long FLOPPY_QUOTA = 1_474_560L;
	private static final long CD_QUOTA = 700L * 1024 * 1024;
	private static final long MAX_FILE = 8L * 1024 * 1024;

	/** One mount. {@code root} is null for the ROM (served from the classpath). */
	public record Mount(String name, @Nullable Path root, boolean readOnly, long quota, boolean foreign, String label, @Nullable String itemId) {
	}

	private final Path itemsDir;
	private final Path configBase;
	private volatile Map<String, Mount> mounts = new LinkedHashMap<>();
	private final Map<String, Long> usedCache = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * @param machineDir  {@code <world>/virtualminecraft/computers/<uuid>/}
	 * @param itemsDir    {@code <world>/virtualminecraft/items/}
	 * @param configBase  the mod's config directory ({@code config/virtualminecraft/}), for {@code cds/} and {@code import/}
	 * @param diskQuota   bytes allowed on {@code /disk}
	 */
	public MachineFiles(final Path machineDir, final Path itemsDir, final Path configBase, final long diskQuota) {
		this.itemsDir = itemsDir;
		this.configBase = configBase;
		final Map<String, Mount> m = new LinkedHashMap<>();
		m.put("rom", new Mount("rom", null, true, 0, false, "ROM", null));
		// §9 U10(a): no drive in the case, no /disk. The mount simply is not there, which is what every "is there
		// a disk" test in the ROM already asks, and the machine still boots -- it just has nowhere of its own.
		if (diskQuota > 0) {
			m.put("disk", new Mount("disk", machineDir.resolve("disk"), false, Math.max(64 * 1024L, diskQuota), false, "Internal disk", null));
		}
		final Path imp = configBase.resolve("import");
		if (Files.isDirectory(imp)) {
			m.put("import", new Mount("import", imp, true, 0, false, "Host import folder", null));
		}
		mounts = m;
	}

	/** Directories an admin drops under here become Computer CDs via {@code /vmc give cd <name>}. */
	public static Path cdsDir(final Path configBase) {
		return configBase.resolve("cds");
	}

	public Map<String, Mount> mounts() {
		return mounts;
	}

	/** The disk-item ids this machine currently has mounted: what {@code /vmc gc} may not call an orphan. */
	public java.util.Set<String> itemIds() {
		final java.util.Set<String> out = new java.util.HashSet<>();
		for (final Mount m : mounts.values()) {
			if (m.itemId() != null) {
				out.add(m.itemId());
			}
		}
		return out;
	}

	/** Server thread: rebuild the drive mounts from the disk drives on the machine's bus (nearest first). */
	public void refresh(final ServerLevel level, final LuaComputerBlockEntity be) {
		final Map<String, Mount> m = new LinkedHashMap<>();
		final Map<String, Mount> old = mounts;
		m.put("rom", old.get("rom"));
		if (old.get("disk") != null) {
			m.put("disk", old.get("disk"));
		}
		if (old.containsKey("import")) {
			m.put("import", old.get("import"));
		}
		int fd = 0;
		int cd = 0;
		for (final Map.Entry<BlockPos, String> e : be.attached(level).entrySet()) {
			final BlockPos p = e.getKey();
			if (!level.hasChunkAt(p) || !(level.getBlockEntity(p) instanceof DiskDriveBlockEntity drive)) {
				continue;
			}
			final ItemStack media = drive.getMedia();
			final DiskItem.Kind kind = DiskItem.kindOf(media);
			final DiskData d = DiskItem.data(media);
			if (kind == DiskItem.Kind.FLOPPY && fd < 2) {
				final String name = "fd" + fd++;
				if (d == null) {
					continue;
				}
				final Path dir = itemsDir.resolve(d.id().toString());
				if (d.iso().startsWith(FLOPPY_DIR_PREFIX) && !Files.isDirectory(dir)) {
					seedFloppy(dir, d.iso().substring(FLOPPY_DIR_PREFIX.length()), configBase); // a template: this copy's first mount
				}
				final boolean foreign = !Files.isDirectory(dir) && Files.isRegularFile(itemsDir.resolve(d.fileName()));
				m.put(name, new Mount(name, dir, false, FLOPPY_QUOTA, foreign, DiskItem.describe(media), d.id().toString()));
			} else if (kind == DiskItem.Kind.CD && cd < 2) {
				final String name = "cd" + cd++;
				if (d == null) {
					continue;
				}
				final String iso = d.iso();
				Path dir;
				boolean readOnly = true;
				if (iso.startsWith(CD_DIR_PREFIX)) {
					final String n = iso.substring(CD_DIR_PREFIX.length());
					dir = NAME.matcher(n).matches() ? cdsDir(configBase).resolve(n) : null;
					if (dir != null && !Files.isDirectory(dir)) {
						dir = bundledCd(n); // not in the world's config dir: fall back to the copy inside the mod
					}
				} else if (iso.isBlank()) {
					dir = itemsDir.resolve(d.id().toString());
					readOnly = Files.isDirectory(dir); // burned once it has content; blank until then
				} else {
					dir = null; // a real ISO: the VM's business
				}
				if (dir == null) {
					m.put(name, new Mount(name, null, true, 0, true, DiskItem.describe(media), d.id().toString()));
				} else {
					m.put(name, new Mount(name, dir, readOnly, CD_QUOTA, false, DiskItem.describe(media), d.id().toString()));
				}
			}
		}
		mounts = m;
	}

	/** The first {@code boot.lua} on a removable disk, in mount order — [name]'s "write your own OS" (§2). */
	public @Nullable String bootSource() {
		for (final String name : new String[] { "fd0", "fd1", "cd0", "cd1" }) {
			final Mount mt = mounts.get(name);
			if (mt == null || mt.root() == null || mt.foreign()) {
				continue;
			}
			final Path f = mt.root().resolve("boot.lua");
			if (Files.isRegularFile(f)) {
				try {
					return Files.readString(f);
				} catch (final IOException ignored) {
					// unreadable: fall through to the ROM
				}
			}
		}
		return null;
	}

	public @Nullable String bootMount() {
		for (final String name : new String[] { "fd0", "fd1", "cd0", "cd1" }) {
			final Mount mt = mounts.get(name);
			if (mt != null && mt.root() != null && !mt.foreign() && Files.isRegularFile(mt.root().resolve("boot.lua"))) {
				return name;
			}
		}
		return null;
	}

	// ---- paths ----

	private record Resolved(Mount mount, String rel, @Nullable Path path) {
	}

	/** {@code /mount/a/b} → mount + relative path; refuses anything that is not a plain, shallow, safe name. */
	private Resolved resolve(final String path, final boolean forWrite) throws LuaMachine.MachineError {
		String p = path == null ? "" : path.trim();
		while (p.startsWith("/")) {
			p = p.substring(1);
		}
		while (p.endsWith("/")) {
			p = p.substring(0, p.length() - 1);
		}
		final String[] parts = p.isEmpty() ? new String[0] : p.split("/+");
		if (parts.length == 0) {
			throw new LuaMachine.MachineError("path is empty (start with a mount: /disk, /fd0, ...)");
		}
		if (parts.length - 1 > MAX_DEPTH) {
			throw new LuaMachine.MachineError("path too deep");
		}
		final Mount mt = mounts.get(parts[0]);
		if (mt == null) {
			throw new LuaMachine.MachineError("no such mount: /" + parts[0]);
		}
		final StringBuilder rel = new StringBuilder();
		for (int i = 1; i < parts.length; i++) {
			if (!NAME.matcher(parts[i]).matches() || parts[i].equals(".") || parts[i].equals("..")) {
				throw new LuaMachine.MachineError("bad name: " + parts[i]);
			}
			if (i > 1) {
				rel.append('/');
			}
			rel.append(parts[i]);
		}
		if (forWrite && (mt.readOnly() || mt.foreign())) {
			throw new LuaMachine.MachineError("/" + mt.name() + " is read-only" + (mt.foreign() ? " (foreign disk; format it first)" : ""));
		}
		if (mt.foreign()) {
			throw new LuaMachine.MachineError("/" + mt.name() + " is a foreign disk (format it for this computer)");
		}
		// the world path Minecraft hands out can contain a "./" segment: compare normalized to normalized
		final Path root = mt.root() == null ? null : mt.root().toAbsolutePath().normalize();
		final Path target = root == null ? null : root.resolve(rel.toString()).normalize();
		if (target != null && !target.startsWith(root)) {
			throw new LuaMachine.MachineError("bad path");
		}
		return new Resolved(mt, rel.toString(), target);
	}

	// ---- operations (worker thread) ----

	public String mountsJson() {
		final JsonArray out = new JsonArray();
		for (final Mount mt : mounts.values()) {
			final JsonObject o = new JsonObject();
			o.addProperty("name", mt.name());
			o.addProperty("label", mt.label());
			o.addProperty("readOnly", mt.readOnly());
			o.addProperty("foreign", mt.foreign());
			o.addProperty("quota", mt.quota());
			o.addProperty("used", mt.root() == null || mt.foreign() ? 0 : used(mt));
			if (mt.itemId() != null) {
				o.addProperty("item", mt.itemId());
			}
			out.add(o);
		}
		return out.toString();
	}

	public String list(final String path) throws LuaMachine.MachineError {
		final Resolved r = resolve(path, false);
		final JsonArray out = new JsonArray();
		try {
			if (r.path() == null) {
				for (final String[] e : romList(r.rel())) {
					final JsonObject o = new JsonObject();
					o.addProperty("name", e[0]);
					o.addProperty("dir", e[1].equals("d"));
					o.addProperty("size", Long.parseLong(e[2]));
					out.add(o);
				}
				return out.toString();
			}
			if (!Files.isDirectory(r.path())) {
				if (r.rel().isEmpty() && !Files.exists(r.path())) {
					return "[]"; // a mount whose directory nobody has written yet (a blank CD, a fresh disk) is empty, not broken
				}
				throw new LuaMachine.MachineError("not a directory: " + path);
			}
			try (Stream<Path> s = Files.list(r.path())) {
				final List<Path> entries = s.sorted(Comparator.comparing(x -> x.getFileName().toString())).toList();
				for (final Path e : entries) {
					if (Files.isSymbolicLink(e)) {
						continue;
					}
					final JsonObject o = new JsonObject();
					o.addProperty("name", e.getFileName().toString());
					o.addProperty("dir", Files.isDirectory(e));
					o.addProperty("size", Files.isDirectory(e) ? 0 : Files.size(e));
					out.add(o);
				}
			}
			return out.toString();
		} catch (final IOException e) {
			throw new LuaMachine.MachineError("list: " + e.getMessage());
		}
	}

	/** {@code {"dir":bool,"size":n}} or {@code "null"}. */
	/** Whether the first segment of a path names a mount that exists (a machine with no drive has no {@code /disk}). */
	private boolean mountExists(final String path) {
		String p = path == null ? "" : path.trim();
		while (p.startsWith("/")) {
			p = p.substring(1);
		}
		final int slash = p.indexOf('/');
		return mounts.containsKey(slash < 0 ? p : p.substring(0, slash));
	}

	public String stat(final String path) throws LuaMachine.MachineError {
		if (!mountExists(path)) {
			return "null"; // §9 U10(a): fs.exists("/disk/autostart.lua") on a driveless machine is false, not a crash
		}
		final Resolved r = resolve(path, false);
		try {
			if (r.path() == null) {
				if (r.rel().isEmpty()) {
					return "{\"dir\":true,\"size\":0}";
				}
				final byte[] b = romRead(r.rel());
				if (b != null) {
					return "{\"dir\":false,\"size\":" + b.length + "}";
				}
				return romList(r.rel()).isEmpty() && !romIsDir(r.rel()) ? "null" : "{\"dir\":true,\"size\":0}";
			}
			if (!Files.exists(r.path()) || Files.isSymbolicLink(r.path())) {
				return "null";
			}
			return "{\"dir\":" + Files.isDirectory(r.path()) + ",\"size\":" + (Files.isDirectory(r.path()) ? 0 : Files.size(r.path())) + "}";
		} catch (final IOException e) {
			throw new LuaMachine.MachineError("stat: " + e.getMessage());
		}
	}

	/**
	 * Drive activity for the chassis voice (§9 U5). The worker thread bumps these and the server thread drains
	 * them once a tick, which is why it is a counter and not a callback: a sound started from a worker would be
	 * a second thread touching the chip between two of its own mixes.
	 */
	private final java.util.concurrent.atomic.AtomicInteger ioOps = new java.util.concurrent.atomic.AtomicInteger();
	private volatile boolean ioWrite;

	private void noteIo(final boolean write) {
		ioOps.incrementAndGet();
		if (write) {
			ioWrite = true;
		}
	}

	/** How many file operations happened since the last call, and clears the count. */
	public int takeIoOps() {
		return ioOps.getAndSet(0);
	}

	/** Whether any of them wrote, and clears the flag. A write and a read sound different. */
	public boolean takeIoWasWrite() {
		final boolean w = ioWrite;
		ioWrite = false;
		return w;
	}

	public byte[] read(final String path) throws LuaMachine.MachineError {
		noteIo(false);
		final Resolved r = resolve(path, false);
		try {
			if (r.path() == null) {
				final byte[] b = romRead(r.rel());
				if (b == null) {
					throw new LuaMachine.MachineError("no such file: " + path);
				}
				return b;
			}
			if (!Files.isRegularFile(r.path()) || Files.isSymbolicLink(r.path())) {
				throw new LuaMachine.MachineError("no such file: " + path);
			}
			if (Files.size(r.path()) > MAX_FILE) {
				throw new LuaMachine.MachineError("file too large");
			}
			return Files.readAllBytes(r.path());
		} catch (final IOException e) {
			throw new LuaMachine.MachineError("read: " + e.getMessage());
		}
	}

	public void write(final String path, final byte[] data, final boolean append) throws LuaMachine.MachineError {
		noteIo(true);
		final Resolved r = resolve(path, true);
		if (r.rel().isEmpty()) {
			throw new LuaMachine.MachineError("cannot write a mount");
		}
		try {
			final Path f = r.path();
			final long existing = Files.isRegularFile(f) ? Files.size(f) : 0;
			final long after = used(r.mount()) - (append ? 0 : existing) + data.length;
			if (r.mount().quota() > 0 && after > r.mount().quota()) {
				throw new LuaMachine.MachineError("disk full (/" + r.mount().name() + ": " + r.mount().quota() / 1024 + " KB)");
			}
			Files.createDirectories(f.getParent());
			if (append) {
				Files.write(f, data, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			} else {
				Files.write(f, data);
			}
			usedCache.remove(r.mount().name());
		} catch (final IOException e) {
			throw new LuaMachine.MachineError("write: " + e.getMessage());
		}
	}

	public void mkdir(final String path) throws LuaMachine.MachineError {
		noteIo(true);
		final Resolved r = resolve(path, true);
		try {
			Files.createDirectories(r.path());
		} catch (final IOException e) {
			throw new LuaMachine.MachineError("mkdir: " + e.getMessage());
		}
	}

	public void remove(final String path) throws LuaMachine.MachineError {
		noteIo(true);
		final Resolved r = resolve(path, true);
		if (r.rel().isEmpty()) {
			throw new LuaMachine.MachineError("cannot remove a mount (format it instead)");
		}
		try {
			deleteTree(r.path());
			usedCache.remove(r.mount().name());
		} catch (final IOException e) {
			throw new LuaMachine.MachineError("remove: " + e.getMessage());
		}
	}

	public void rename(final String from, final String to) throws LuaMachine.MachineError {
		final Resolved a = resolve(from, true);
		final Resolved b = resolve(to, true);
		if (a.mount() != b.mount()) {
			throw new LuaMachine.MachineError("rename across mounts is a copy");
		}
		if (a.rel().isEmpty() || b.rel().isEmpty()) {
			throw new LuaMachine.MachineError("cannot rename a mount");
		}
		try {
			Files.createDirectories(b.path().getParent());
			Files.move(a.path(), b.path(), StandardCopyOption.REPLACE_EXISTING);
		} catch (final IOException e) {
			throw new LuaMachine.MachineError("rename: " + e.getMessage());
		}
	}

	/** Wipe a mount and make it ours: a foreign floppy loses its qcow2 image; a blank CD becomes writable for a burn. */
	public void format(final String mountName) throws LuaMachine.MachineError {
		final Mount mt = mounts.get(mountName.replace("/", ""));
		if (mt == null || mt.root() == null) {
			throw new LuaMachine.MachineError("no such mount");
		}
		if (mt.name().equals("rom") || mt.name().equals("import")) {
			throw new LuaMachine.MachineError("/" + mt.name() + " cannot be formatted");
		}
		if (mt.name().startsWith("cd") && mt.readOnly() && Files.isDirectory(mt.root())) {
			throw new LuaMachine.MachineError("a burned CD cannot be formatted");
		}
		try {
			if (mt.itemId() != null && mt.name().startsWith("fd")) {
				Files.deleteIfExists(itemsDir.resolve(mt.itemId() + ".qcow2"));
			}
			deleteTree(mt.root());
			Files.createDirectories(mt.root());
			usedCache.remove(mt.name());
			// the mount was foreign or read-only-blank: rebuild the entry as ours
			final Map<String, Mount> m = new LinkedHashMap<>(mounts);
			m.put(mt.name(), new Mount(mt.name(), mt.root(), false, mt.quota(), false, mt.label(), mt.itemId()));
			mounts = m;
		} catch (final IOException e) {
			throw new LuaMachine.MachineError("format: " + e.getMessage());
		}
	}

	/** Copy a mount's tree onto a blank CD in {@code cdN}; the CD is read-only from then on. */
	public void burn(final String srcMount, final String cdMount) throws LuaMachine.MachineError {
		final Mount src = mounts.get(srcMount.replace("/", ""));
		final Mount cd = mounts.get(cdMount.replace("/", ""));
		if (src == null || src.root() == null || src.foreign()) {
			throw new LuaMachine.MachineError("no such source mount");
		}
		if (cd == null || !cd.name().startsWith("cd") || cd.root() == null) {
			throw new LuaMachine.MachineError("no blank CD in " + cdMount);
		}
		if (cd.readOnly() && Files.isDirectory(cd.root())) {
			throw new LuaMachine.MachineError("that CD is already burned");
		}
		try {
			if (used(src) > CD_QUOTA) {
				throw new LuaMachine.MachineError("too much data for a CD");
			}
			Files.createDirectories(cd.root());
			copyTree(src.root(), cd.root());
			final Map<String, Mount> m = new LinkedHashMap<>(mounts);
			m.put(cd.name(), new Mount(cd.name(), cd.root(), true, CD_QUOTA, false, cd.label(), cd.itemId()));
			mounts = m;
		} catch (final IOException e) {
			throw new LuaMachine.MachineError("burn: " + e.getMessage());
		}
	}

	private long used(final Mount mt) {
		if (mt.root() == null) {
			return 0;
		}
		return usedCache.computeIfAbsent(mt.name(), k -> {
			if (!Files.isDirectory(mt.root())) {
				return 0L;
			}
			try (Stream<Path> s = Files.walk(mt.root(), MAX_DEPTH + 1)) {
				return s.filter(p -> Files.isRegularFile(p) && !Files.isSymbolicLink(p)).mapToLong(p -> {
					try {
						return Files.size(p);
					} catch (final IOException e) {
						return 0L;
					}
				}).sum();
			} catch (final IOException e) {
				return 0L;
			}
		});
	}

	static void deleteTree(final Path p) throws IOException {
		if (!Files.exists(p)) {
			return;
		}
		try (Stream<Path> s = Files.walk(p)) {
			for (final Path x : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(x);
			}
		}
	}

	private static void copyTree(final Path from, final Path to) throws IOException {
		try (Stream<Path> s = Files.walk(from, MAX_DEPTH + 1)) {
			for (final Path x : s.toList()) {
				final Path rel = from.relativize(x);
				final Path dst = to.resolve(rel.toString());
				if (Files.isDirectory(x)) {
					Files.createDirectories(dst);
				} else if (Files.isRegularFile(x) && !Files.isSymbolicLink(x)) {
					Files.createDirectories(dst.getParent());
					Files.copy(x, dst, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	// ---- the ROM, from the classpath (a directory in dev, a jar in play) ----

	private static @Nullable Path romRoot;

	/**
	 * The CDs that ship inside the mod ({@code /virtualminecraft/cds/}). Same classpath trick as the ROM, and it
	 * needs no new machinery: {@link #romRoot()} already hands back a real {@link Path} — a zipfs path in a jar,
	 * a directory in dev — so {@code Files.list}/{@code readAllBytes} work on it unchanged. A world's own
	 * {@code config/virtualminecraft/cds/<name>} still wins, so anyone can add to or override the shipped set.
	 */
	/** {@code config/virtualminecraft/floppies/}: a world's own floppy templates, which override the shipped ones. */
	public static Path floppiesDir(final Path configBase) {
		return configBase.resolve("floppies");
	}

	/** A floppy template that ships inside the mod ({@code virtualminecraft/floppies/<name>}), like {@link #bundledCd}. */
	public static @Nullable Path bundledFloppy(final String name) {
		if (!NAME.matcher(name).matches()) {
			return null;
		}
		final Path rom = romRoot();
		if (rom == null || rom.getParent() == null) {
			return null;
		}
		final Path dir = rom.getParent().resolve("floppies").resolve(name);
		return Files.isDirectory(dir) ? dir : null;
	}

	/** The template a name resolves to: the world's config dir first, then the copy inside the mod; null if neither. */
	public static @Nullable Path floppyTemplate(final String name, final Path configBase) {
		if (!NAME.matcher(name).matches()) {
			return null;
		}
		final Path own = floppiesDir(configBase).resolve(name);
		return Files.isDirectory(own) ? own : bundledFloppy(name);
	}

	/**
	 * Copies a floppy template onto a disk that does not exist yet -- the write that turns "a floppy with Starter on
	 * it" into this player's own, editable Starter. A missing template leaves the disk blank rather than failing:
	 * the item still works, it is just empty, and the log says which name was not found.
	 */
	public static void seedFloppy(final Path diskDir, final String name, final Path configBase) {
		final Path from = floppyTemplate(name, configBase);
		try {
			Files.createDirectories(diskDir);
			if (from == null) {
				dev.virtualminecraft.VirtualMinecraft.LOGGER.warn("floppy template '{}' not found; the floppy is blank", name);
				return;
			}
			copyTree(from, diskDir);
		} catch (final IOException e) {
			dev.virtualminecraft.VirtualMinecraft.LOGGER.warn("floppy template '{}': {}", name, e.toString());
		}
	}

	public static @Nullable Path bundledCd(final String name) {
		if (!NAME.matcher(name).matches()) {
			return null;
		}
		final Path rom = romRoot();
		if (rom == null || rom.getParent() == null) {
			return null;
		}
		final Path dir = rom.getParent().resolve("cds").resolve(name);
		return Files.isDirectory(dir) ? dir : null;
	}

	private static synchronized @Nullable Path romRoot() {
		if (romRoot != null) {
			return romRoot;
		}
		try {
			final URL url = MachineFiles.class.getResource("/virtualminecraft/rom/boot.lua");
			if (url == null) {
				return null;
			}
			final URI uri = url.toURI();
			if ("jar".equals(uri.getScheme())) {
				final String spec = uri.toString();
				final URI jar = URI.create(spec.substring(0, spec.indexOf("!/")));
				FileSystem fs;
				try {
					fs = FileSystems.getFileSystem(jar);
				} catch (final RuntimeException e) {
					fs = FileSystems.newFileSystem(jar, Map.of());
				}
				romRoot = fs.getPath("/virtualminecraft/rom");
			} else {
				romRoot = Path.of(uri).getParent();
			}
		} catch (final Exception e) {
			VirtualMinecraft.LOGGER.warn("ROM directory not found: {}", e.toString());
		}
		return romRoot;
	}

	static byte @Nullable [] romRead(final String rel) throws IOException {
		final Path root = romRoot();
		if (root == null) {
			return null;
		}
		final Path p = root.resolve(rel);
		return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
	}

	static boolean romIsDir(final String rel) {
		final Path root = romRoot();
		return root != null && Files.isDirectory(root.resolve(rel));
	}

	/** {name, "d"|"f", size} per entry. */
	static List<String[]> romList(final String rel) throws IOException {
		final List<String[]> out = new ArrayList<>();
		final Path root = romRoot();
		if (root == null) {
			return out;
		}
		final Path dir = root.resolve(rel);
		if (!Files.isDirectory(dir)) {
			return out;
		}
		try (Stream<Path> s = Files.list(dir)) {
			for (final Path e : s.sorted(Comparator.comparing(x -> x.getFileName().toString())).toList()) {
				final String name = e.getFileName().toString().replace("/", "");
				out.add(new String[] { name, Files.isDirectory(e) ? "d" : "f", String.valueOf(Files.isDirectory(e) ? 0 : Files.size(e)) });
			}
		}
		return out;
	}

	public static String utf8(final byte[] b) {
		return new String(b, StandardCharsets.UTF_8);
	}
}
