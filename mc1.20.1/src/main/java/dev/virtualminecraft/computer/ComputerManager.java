package dev.virtualminecraft.computer;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.util.Nums;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * One per server (like {@code VmManager}): owns the {@link MachineScheduler}, the live {@link LuaComputer}s by
 * machine id, the queue of work the worker threads hand to the server thread, and the on-disk layout
 * {@code <world>/virtualminecraft/computers/<uuid>/}. Everything here except {@link #post} is server-thread only.
 */
public final class ComputerManager {
	private static final Map<MinecraftServer, ComputerManager> INSTANCES = new WeakHashMap<>();

	private final MinecraftServer server;
	private final Map<UUID, LuaComputer> live = new ConcurrentHashMap<>();
	/** Machine ids whose block has ticked since the server started: what {@code /vmc gc} can honestly call "in the world". */
	private final Map<UUID, String> seen = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
	private @Nullable MachineScheduler scheduler;
	private @Nullable String romBoot;

	private ComputerManager(final MinecraftServer server) {
		this.server = server;
	}

	public static synchronized ComputerManager get(final MinecraftServer server) {
		return INSTANCES.computeIfAbsent(server, ComputerManager::new);
	}

	public static void shutdownServer(final MinecraftServer server) {
		final ComputerManager m;
		synchronized (ComputerManager.class) {
			m = INSTANCES.get(server);
		}
		if (m != null) {
			m.shutdownAll();
		}
	}

	public MinecraftServer server() {
		return server;
	}

	public MachineScheduler scheduler() {
		MachineScheduler s = scheduler;
		if (s == null) {
			final VmcConfig cfg = VmcConfig.get();
			final int threads = cfg.computerThreads > 0 ? cfg.computerThreads : Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
			s = new MachineScheduler(threads, Math.max(1, cfg.computerSliceMs) * 1_000_000L, Nums.clamp(cfg.computerCpuPercent, 1, 400) / 100.0);
			scheduler = s;
			VirtualMinecraft.LOGGER.info("Computer scheduler: {} workers, {} ms slices, {} % share", threads, cfg.computerSliceMs, cfg.computerCpuPercent);
		}
		return s;
	}

	/** The ROM's boot chunk, read from the jar once. */
	public String romBoot() {
		String r = romBoot;
		if (r == null) {
			try (InputStream in = ComputerManager.class.getResourceAsStream("/virtualminecraft/rom/boot.lua")) {
				r = in == null ? "error('ROM missing')" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (final IOException e) {
				r = "error('ROM unreadable: " + e.getMessage().replace("'", "") + "')";
			}
			romBoot = r;
		}
		return r;
	}

	public Path baseDir() {
		return server.getWorldPath(LevelResource.ROOT).resolve("virtualminecraft").resolve("computers");
	}

	/** The mod's own folder in the world: {@code computers/}, {@code items/} and a directory per VM. */
	public Path vmRoot() {
		return server.getWorldPath(LevelResource.ROOT).resolve("virtualminecraft");
	}

	public Path dir(final UUID id) {
		return baseDir().resolve(id.toString());
	}

	/** Where disk items keep their files: the same folder the VM tier's qcow2 images live in. */
	public Path itemsDir() {
		return server.getWorldPath(LevelResource.ROOT).resolve("virtualminecraft").resolve("items");
	}

	/** Worker → server thread: run this on the next tick. */
	public void post(final Runnable task) {
		serverTasks.add(task);
	}

	public @Nullable LuaComputer get(final UUID id) {
		return live.get(id);
	}

	public Collection<LuaComputer> all() {
		return live.values();
	}

	public int liveCount() {
		return live.size();
	}

	/** The block entity ticks for the first time after a load (or was placed): start or thaw its machine. */
	public LuaComputer attach(final LuaComputerBlockEntity be, final ServerLevel level) {
		LuaComputer c = live.get(be.machineId());
		if (c == null) {
			// §9 U10(a): the parts are the machine. An empty case never becomes one, whoever asked.
			final String dead = be.bootRefusal();
			if (dead != null) {
				throw new IllegalStateException(dead);
			}
			final String refusal = placementRefusal(be.owner());
			if (refusal != null) {
				be.setPowered(false);
				VirtualMinecraft.LOGGER.info("Computer at {} not started: {}", be.getBlockPos().toShortString(), refusal);
				throw new IllegalStateException(refusal);
			}
			c = new LuaComputer(this, be, level);
			live.put(be.machineId(), c);
			c.boot(true);
		} else {
			c.rebind(be, level);
		}
		return c;
	}

	/** Freeze (or discard) a machine and forget it. */
	public void remove(final UUID id, final boolean freeze) {
		remove(id, freeze, "command");
	}

	public void remove(final UUID id, final boolean freeze, final String reason) {
		final LuaComputer c = live.remove(id);
		if (c != null) {
			c.dispose(freeze, reason);
		}
	}

	private boolean stopping;

	boolean stopping() {
		return stopping;
	}

	/** The freeze reason recorded on disk for a machine, or null if it is not frozen. */
	public @Nullable String frozenReason(final UUID id) {
		final Path meta = dir(id).resolve("state").resolve("meta.json");
		if (!Files.isRegularFile(meta)) {
			return null;
		}
		try {
			final com.google.gson.JsonObject m = com.google.gson.JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
			return m.has("reason") ? m.get("reason").getAsString() : "command";
		} catch (final IOException | RuntimeException e) {
			return "command";
		}
	}

	public void tick() {
		Runnable r;
		int n = 0;
		while ((r = serverTasks.poll()) != null && n++ < 4096) {
			try {
				r.run();
			} catch (final RuntimeException e) {
				VirtualMinecraft.LOGGER.error("Computer task failed", e);
			}
		}
		for (final LuaComputer c : live.values()) {
			c.tick();
		}
	}

	/** Server stop: freeze every machine (each waits briefly for its kernel to save), then stop the pool. */
	public void shutdownAll() {
		stopping = true;
		final List<LuaComputer> all = new ArrayList<>(live.values());
		live.clear();
		final long t0 = System.nanoTime();
		// two-phase so a hundred busy kernels save in parallel: ask all, wait once (≤ 2 s), then stop all
		for (final LuaComputer c : all) {
			c.requestSave();
		}
		final long deadline = System.nanoTime() + 2_000_000_000L;
		for (final LuaComputer c : all) {
			c.awaitSaved(deadline);
		}
		for (final LuaComputer c : all) {
			c.finishDispose(true);
		}
		// dispose() posts follow-ups; drain them so state files are complete before the world saves
		Runnable r;
		while ((r = serverTasks.poll()) != null) {
			r.run();
		}
		if (!all.isEmpty()) {
			VirtualMinecraft.LOGGER.info("Froze {} computers in {} ms", all.size(), (System.nanoTime() - t0) / 1_000_000L);
		}
		if (scheduler != null) {
			scheduler.shutdown();
			scheduler = null;
		}
	}

	/** The placement cap (§1d): null if a computer may be placed, else the sentence for the player. */
	public @Nullable String placementRefusal(final @Nullable UUID owner) {
		final VmcConfig cfg = VmcConfig.get();
		if (cfg.maxLoadedComputers > 0 && live.size() >= cfg.maxLoadedComputers) {
			return "This server already has " + live.size() + " computers running";
		}
		if (owner != null && cfg.maxComputersPerPlayer > 0) {
			int mine = 0;
			for (final LuaComputer c : live.values()) {
				if (owner.equals(c.owner())) {
					mine++;
				}
			}
			if (mine >= cfg.maxComputersPerPlayer) {
				return "You already have " + mine + " computers; the limit is " + cfg.maxComputersPerPlayer;
			}
		}
		return null;
	}

	/**
	 * A block ticked: remember where, so {@code /vmc gc} can say which directories belong to something real. Both
	 * tiers use it — a machine id and a VM id are both uuids and share the map.
	 */
	public void note(final UUID id, final String where) {
		seen.put(id, where);
	}

	/** Delete a directory if nothing is left in it (recursing into empty children first). Returns true if it went. */
	static boolean deleteIfEmpty(final Path dir) {
		if (!Files.isDirectory(dir)) {
			return false;
		}
		try (var children = Files.list(dir)) {
			for (final Path c : children.toList()) {
				deleteIfEmpty(c);
			}
		} catch (final IOException e) {
			return false;
		}
		try (var children = Files.list(dir)) {
			if (children.findAny().isPresent()) {
				return false;
			}
		} catch (final IOException e) {
			return false;
		}
		try {
			Files.delete(dir);
			return true;
		} catch (final IOException e) {
			return false;
		}
	}

	private static long treeSize(final Path root) {
		try (var walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).mapToLong(f -> {
				try {
					return Files.size(f);
				} catch (final IOException e) {
					return 0;
				}
			}).sum();
		} catch (final IOException e) {
			return 0;
		}
	}

	/** What a {@link Garbage} line is about. Kept apart because they live in three different places on disk. */
	public enum Kind {
		/** {@code computers/<uuid>/} — an in-JVM machine's files. */
		MACHINE,
		/** {@code items/<uuid>[.qcow2]} — a disk item's contents. */
		ITEM,
		/** {@code <uuid>/} beside the other two — a Command Computer's VM: its qcow2 disks, and the big ones. */
		VM
	}

	/** One line of the {@code /vmc gc} report. */
	public record Garbage(String id, Kind kind, long bytes, long ageDays, String where) {
		public boolean isItem() {
			return kind == Kind.ITEM;
		}
	}

	/**
	 * What is on disk under {@code computers/} and {@code items/}, and what we can say about it (the leftover
	 * {@code /vmc gc}). "Where" is the block that has ticked with this id since the server started; a machine in an
	 * unloaded chunk looks exactly like an orphan from here, which is why only the empty directories are deleted
	 * without being named.
	 */
	public List<Garbage> gcScan() {
		final Map<String, Garbage> out = new TreeMap<>();
		final Set<String> itemsInUse = new java.util.HashSet<>();
		for (final LuaComputer c : live.values()) {
			itemsInUse.addAll(c.files().itemIds());
		}
		for (final net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
			for (final java.util.List<net.minecraft.world.item.ItemStack> part : java.util.List.of(p.getInventory().items, p.getInventory().armor, p.getInventory().offhand))
			for (final net.minecraft.world.item.ItemStack st : part) {
				final var d = dev.virtualminecraft.item.StackData.disk(st);
				if (d != null) {
					itemsInUse.add(d.id().toString());
				}
			}
		}
		scanDir(baseDir(), Kind.MACHINE, itemsInUse, out);
		scanDir(itemsDir(), Kind.ITEM, itemsInUse, out);
		// The VM tier's directories sit beside the other two rather than under a folder of their own, so this sweep
		// has to skip its siblings by name. Until session 19 they were invisible to gc, and two of them left behind
		// by a /fill were 2.4 GB the mod's own collector could not see (HANDOFF next-work 1c).
		scanDir(vmRoot(), Kind.VM, itemsInUse, out);
		return new ArrayList<>(out.values());
	}

	private void scanDir(final Path root, final Kind kind, final Set<String> itemsInUse, final Map<String, Garbage> out) {
		if (!Files.isDirectory(root)) {
			return;
		}
		final boolean items = kind == Kind.ITEM;
		final long now = System.currentTimeMillis();
		try (var children = Files.list(root)) {
			for (final Path c : children.toList()) {
				final String name = c.getFileName().toString();
				if (kind == Kind.VM && (name.equals("computers") || name.equals("items") || !Files.isDirectory(c))) {
					continue; // the two folders that have their own sweep, and any stray file at the top level
				}
				final String id = items ? name.replaceFirst("\\.qcow2$", "") : name;
				long modified = now;
				try {
					modified = Files.getLastModifiedTime(c).toMillis();
				} catch (final IOException ignored) {
					// unreadable: report it as fresh rather than as old rubbish
				}
				final long bytes = items ? (Files.isRegularFile(c) ? treeSize(c.getParent().resolve(name)) : treeSize(c)) : treeSize(c);
				String where = "not loaded";
				if (items) {
					where = itemsInUse.contains(id) ? "in a drive or a pocket" : "not loaded";
				} else {
					try {
						final UUID uuid = UUID.fromString(id);
						final String seenAt = seen.get(uuid);
						if (seenAt != null) {
							where = seenAt;
						}
						if (kind == Kind.VM) {
							final var vm = dev.virtualminecraft.vm.VmManager.get(server).get(uuid);
							if (vm != null && vm.isAlive()) {
								where = seenAt == null ? "running" : seenAt + ", running";
							}
						}
					} catch (final IllegalArgumentException notAUuid) {
						where = kind == Kind.VM ? "not a vm id" : "not a machine id";
					}
				}
				out.put(switch (kind) {
					case MACHINE -> "c";
					case ITEM -> "i";
					case VM -> "v";
				} + id, new Garbage(id, kind, bytes, (now - modified) / 86_400_000L, where));
			}
		} catch (final IOException e) {
			VirtualMinecraft.LOGGER.warn("gc: cannot list {}: {}", root, e.toString());
		}
	}

	/** Delete every directory under {@code computers/} that holds no files at all. Returns how many went. */
	public int gcEmpty() {
		int n = 0;
		if (!Files.isDirectory(baseDir())) {
			return 0;
		}
		try (var children = Files.list(baseDir())) {
			for (final Path c : children.toList()) {
				if (Files.isDirectory(c) && treeSize(c) == 0 && deleteIfEmpty(c)) {
					n++;
				}
			}
		} catch (final IOException e) {
			VirtualMinecraft.LOGGER.warn("gc: {}", e.toString());
		}
		return n;
	}

	/** Delete one machine's directory or one disk item's file, named by the operator. Refused while it is live. */
	public String gcDrop(final String id) {
		UUID uuid = null;
		try {
			uuid = UUID.fromString(id);
		} catch (final IllegalArgumentException ignored) {
			return "'" + id + "' is not a uuid";
		}
		if (live.containsKey(uuid)) {
			return "That machine is running; power it off first";
		}
		// A VM id is as valid a thing to name here as a machine id: its directory is the biggest thing the mod
		// writes, and it was the one gc could not see (HANDOFF next-work 1c).
		final var vm = dev.virtualminecraft.vm.VmManager.get(server).get(uuid);
		if (vm != null && vm.isAlive()) {
			return "That VM is running; stop it first (/vmc stop <pos>)";
		}
		final Path machine = dir(uuid);
		final Path item = itemsDir().resolve(uuid + ".qcow2");
		final Path itemDir = itemsDir().resolve(uuid.toString());
		final Path vmDir = vmRoot().resolve(uuid.toString());
		long freed = 0;
		final List<String> gone = new ArrayList<>();
		// Named, not just listed: three of these four are a bare uuid on disk, and "Deleted <uuid>, <uuid>" would
		// not tell the operator which of them actually went.
		for (final var p : List.of(Map.entry("computers/", machine), Map.entry("items/", item), Map.entry("items/", itemDir), Map.entry("vm ", vmDir))) {
			if (Files.exists(p.getValue())) {
				freed += treeSize(p.getValue());
				try {
					MachineFiles.deleteTree(p.getValue());
					gone.add(p.getKey() + p.getValue().getFileName());
				} catch (final IOException e) {
					return "could not delete " + p.getValue() + ": " + e.getMessage();
				}
			}
		}
		if (gone.isEmpty()) {
			return "Nothing on disk for " + id;
		}
		seen.remove(uuid);
		return "Deleted " + String.join(", ", gone) + " (" + freed / 1024 + " KB)";
	}

	static void writeAtomic(final Path file, final byte[] data) throws IOException {
		Files.createDirectories(file.getParent());
		final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, data);
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
	}
}
