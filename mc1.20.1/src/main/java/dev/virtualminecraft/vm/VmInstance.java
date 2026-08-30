package dev.virtualminecraft.vm;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.block.ComputerBlockEntity;
import dev.virtualminecraft.bus.VmBus;
import dev.virtualminecraft.audio.ULaw;
import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.net.AudioPayload;
import java.io.ByteArrayOutputStream;
import dev.virtualminecraft.net.ScreenInfoPayload;
import dev.virtualminecraft.net.ScreenRectPayload;
import dev.virtualminecraft.net.ViewerPayload;
import dev.virtualminecraft.net.VmInputPayload;
import dev.virtualminecraft.rfb.RfbClient;
import dev.virtualminecraft.screen.ScreenViewers;
import dev.virtualminecraft.util.Nums;
import dev.virtualminecraft.util.Threads;
import java.util.Collection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * A running QEMU process plus the RFB connection that pulls its framebuffer, and the set of players
 * currently receiving that framebuffer. All packet sending happens on the server thread in {@link #tick()}.
 */
public final class VmInstance implements RfbClient.Listener {
	private static final int MAX_BAND_BYTES = 384 * 1024;
	private static final int MAX_DIRTY_RECTS = 32;
	/** Internal qcow2 snapshot name used for suspend/resume. */
	public static final String SNAPSHOT_TAG = "vmc";
	/** Marker file in the VM directory: present iff {@link #SNAPSHOT_TAG} holds a resumable state. */
	public static final String SUSPEND_MARKER = "suspended";

	private final VmManager manager;
	private final UUID id;
	private volatile VmConfig config;
	private final Path dir;
	/** Block devices in command-line order (see {@link Attachments}); fixed for the life of the process. */
	private final List<Attachment> disks;
	/** Media swapped in later via {@link #changeMedium} (device id → medium, or absent after an eject), so a snapshot lists the right files. */
	private final Map<String, Attachment> swappedMedia = new ConcurrentHashMap<>();
	private volatile ServerLevel level;
	private volatile BlockPos pos;

	private volatile @Nullable Process process;
	private volatile @Nullable RfbClient rfb;
	private volatile @Nullable VmBus bus;
	private boolean outputsCleared = true;
	private volatile QemuLauncher.Endpoints endpoints;
	private volatile VmStatus status = VmStatus.STOPPED;
	private volatile String message = "";
	private volatile boolean stopping;
	/** Set while {@code savevm} + {@code quit} are in flight; the resulting exit becomes SUSPENDED, not STOPPED. */
	private volatile boolean suspending;
	private volatile @Nullable Thread suspendThread;
	/** Ticks in a row the owning chunk has been unloaded (see {@link #tick()}). */
	private int unloadedTicks;
	/** Set when an unload triggered the suspend: resume as soon as the block is reachable again. */
	private volatile boolean resumeWhenLoaded;
	/** Ticks in a row the owner has been offline, and the matching "resume when they are back" flag. */
	private int ownerOfflineTicks;
	private volatile boolean resumeWhenOwnerBack;
	/** Chunk unloaded for this long → suspend. Debounces players wandering at the edge of view distance. */
	private static final int UNLOADED_SUSPEND_TICKS = 200;
	/** Ticks the block may be gone from a still-ticking chunk before the VM behind it is let go (see {@link #tickOrphan}). */
	private static final int ORPHAN_TICKS = 20;
	/** Ticks in a row the block has been missing from a ticking chunk, and the "we have let go of it" flag. */
	private int orphanTicks;
	private boolean orphaned;

	private final Object audioLock = new Object();
	private final ByteArrayOutputStream audioPending = new ByteArrayOutputStream(1 << 14);
	private static final int AUDIO_MAX_BACKLOG = RfbClient.AUDIO_RATE * 2; // 1 s of s16 mono

	/** QEMU's D-Bus display, when attached: keyboard scancodes (and later scanout/audio). Null = VNC only. */
	private volatile @Nullable QemuDisplayLink display;
	private final Object dirtyLock = new Object();
	private List<int[]> dirty = new ArrayList<>();
	private boolean resized;

	private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
	private byte[] deflateBuf = new byte[MAX_BAND_BYTES + 1024];
	private long tickCounter;

	VmInstance(final VmManager manager, final UUID id, final VmConfig config, final ServerLevel level, final BlockPos pos, final List<Attachment> disks) {
		this.manager = manager;
		this.id = id;
		this.config = config;
		this.dir = manager.vmDir(id);
		this.level = level;
		this.pos = pos;
		this.disks = List.copyOf(disks);
	}

	public List<Attachment> attachments() {
		return disks;
	}

	/** The devices with what they hold right now: launch-time list with later medium swaps applied. */
	public List<Attachment> currentAttachments() {
		final List<Attachment> out = new ArrayList<>(disks.size());
		for (final Attachment a : disks) {
			final Attachment swapped = swappedMedia.get(a.deviceId());
			if (swapped == null) {
				out.add(a);
			} else {
				out.add(new Attachment(a.id(), a.type(), swapped.file(), swapped.sizeBytes(), swapped.readOnly(), swapped.label(), a.driveLocation(), a.bootIndex()));
			}
		}
		return out;
	}

	/** Whether the running machine has a (removable) device with this qdev id — i.e. a drive block that was there at launch. */
	public boolean hasDevice(final String deviceId) {
		for (final Attachment a : disks) {
			if (a.deviceId().equals(deviceId)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Swaps the medium of a removable device while the guest runs: {@code medium == null} ejects, otherwise the
	 * file is (created if missing and) inserted. QMP on a daemon thread; failures are logged.
	 */
	public void changeMedium(final String deviceId, final @Nullable Attachment medium) {
		final QemuLauncher.Endpoints ep = endpoints;
		if (ep == null || !isAlive()) {
			return;
		}
		Threads.startDaemon("vmc-qmp-medium", () -> {
			try {
				final com.google.gson.JsonObject args = new com.google.gson.JsonObject();
				args.addProperty("id", deviceId);
				if (medium == null || medium.file() == null) {
					args.addProperty("force", true);
					QmpClient.execute(ep, "eject", args);
					swappedMedia.put(deviceId, new Attachment(deviceId, Attachment.Type.CD, null, 0, true, "empty", null, -1));
					VirtualMinecraft.LOGGER.info("VM {}: ejected {}", config.name, deviceId);
					return;
				}
				if (!medium.readOnly() && medium.sizeBytes() > 0 && !Files.isRegularFile(medium.file())) {
					QemuLauncher.createDisk(VmcConfig.get(), medium.file(), medium.sizeBytes());
				}
				args.addProperty("filename", medium.file().toString());
				args.addProperty("format", medium.readOnly() ? "raw" : "qcow2");
				// Always say which mode we want: with the default "retain", a floppy unit that was empty (or ejected)
				// comes back in a state the guest's FDC reads fail on (Linux: "floppy: error 10 while reading block 0").
				args.addProperty("read-only-mode", medium.readOnly() ? "read-only" : "read-write");
				QmpClient.execute(ep, "blockdev-change-medium", args);
				swappedMedia.put(deviceId, medium);
				VirtualMinecraft.LOGGER.info("VM {}: {} now holds {}", config.name, deviceId, medium.label());
			} catch (final IOException e) {
				VirtualMinecraft.LOGGER.warn("VM {}: medium change on {} failed: {}", config.name, deviceId, e.toString());
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	public UUID id() {
		return id;
	}

	public VmStatus status() {
		return status;
	}

	public VmConfig config() {
		return config;
	}

	/** The block's config was saved while we run: only runtime flags (suspend, wake) matter until the next start. */
	public void updateConfig(final VmConfig cfg) {
		config = cfg.copy();
	}

	public boolean isSuspending() {
		return suspending;
	}

	public Path suspendMarker() {
		return dir.resolve(SUSPEND_MARKER);
	}

	public boolean hasSnapshot() {
		return Files.isRegularFile(suspendMarker());
	}

	public boolean isAlive() {
		final Process p = process;
		return p != null && p.isAlive();
	}

	void setOwner(final ServerLevel level, final BlockPos pos) {
		this.level = level;
		this.pos = pos;
	}

	// ---------------------------------------------------------------------------------------------
	// Lifecycle
	// ---------------------------------------------------------------------------------------------

	void start() {
		setStatus(VmStatus.STARTING, "Starting QEMU…");
		final Thread t = new Thread(this::startBlocking, "vmc-vm-" + config.name);
		t.setDaemon(true);
		t.start();
	}

	private void startBlocking() {
		final VmcConfig global = VmcConfig.get();
		try {
			Files.createDirectories(dir);
			for (final Attachment a : disks) {
				if (a.file() != null && !a.readOnly() && a.sizeBytes() > 0 && !Files.isRegularFile(a.file())) {
					setStatus(VmStatus.STARTING, "Creating " + a.label() + "…");
					QemuLauncher.createDisk(global, a.file(), a.sizeBytes());
				}
			}
			final int slot = Math.floorMod(id.hashCode(), 64);
			endpoints = QemuLauncher.endpoints(id, slot);
			final Path log = dir.resolve("qemu.log");
			boolean resume = hasSnapshot();
			Process p = launch(global, resume, log);
			setStatus(VmStatus.STARTING, resume ? "Resuming from snapshot…" : "Waiting for display…");

			final long deadline = System.currentTimeMillis() + 60_000;
			RfbClient client = null;
			IOException last = null;
			while (System.currentTimeMillis() < deadline && !stopping) {
				if (!p.isAlive()) {
					if (resume) {
						// The snapshot no longer matches the machine (RAM/CPU/device change, QEMU upgrade…): boot cold.
						VirtualMinecraft.LOGGER.warn("VM {}: snapshot could not be restored ({}); booting fresh", config.name, tailLog(log));
						discardSnapshot();
						resume = false;
						p = launch(global, false, log);
						setStatus(VmStatus.STARTING, "Snapshot unusable; booting fresh…");
						continue;
					}
					throw new IOException("QEMU exited with code " + p.exitValue() + ": " + tailLog(log));
				}
				final RfbClient attempt = new RfbClient(
					endpoints.vncSocket() != null ? RfbClient.unix(endpoints.vncSocket()) : RfbClient.tcp("127.0.0.1", endpoints.vncPort()),
					this, true);
				try {
					attempt.connect();
					client = attempt;
					break;
				} catch (final IOException e) {
					last = e;
					attempt.close();
					Thread.sleep(250);
				}
			}
			if (client == null) {
				throw new IOException("Could not connect to the VM display: " + (last == null ? "timeout" : last.getMessage()));
			}
			rfb = client;
			final VmBus b = new VmBus(id, config.name, endpoints.busSocket(), endpoints.busPort());
			bus = b;
			b.connect(); // QEMU's chardev socket is listening by now; tick() retries if not
			attachDisplayLink(global);
			if (resume) {
				Files.deleteIfExists(suspendMarker()); // consumed: the snapshot in the qcow2 is stale from here on
			}
			setStatus(VmStatus.RUNNING, "");
			client.run(); // blocks until disconnect
		} catch (final Throwable t) {
			if (!stopping && !suspending) {
				VirtualMinecraft.LOGGER.warn("VM {} failed: {}", config.name, t.toString());
				setStatus(VmStatus.ERROR, shortError(t));
				forceStop();
			}
		}
	}

	private Process launch(final VmcConfig global, final boolean resume, final Path log) throws IOException {
		if (endpoints.vncSocket() != null) {
			Files.deleteIfExists(endpoints.vncSocket());
			Files.deleteIfExists(endpoints.qmpSocket());
			Files.deleteIfExists(endpoints.busSocket());
		}
		final List<String> cmd = QemuLauncher.buildCommand(global, config, disks, endpoints, resume ? SNAPSHOT_TAG : null);
		VirtualMinecraft.LOGGER.info("{} VM {} ({}): {}", resume ? "Resuming" : "Starting", config.name, id, String.join(" ", cmd));
		final Process p = new ProcessBuilder(cmd)
			.redirectErrorStream(true)
			.redirectOutput(log.toFile())
			.start();
		process = p;
		return p;
	}

	/** Forgets a saved state: marker + the snapshot in every qcow2 that took part. Only call when no QEMU process has the disks open. */
	public void discardSnapshot() {
		discardSnapshotFiles(VmcConfig.get(), dir, currentAttachments());
	}

	/**
	 * Deletes the marker and the {@link #SNAPSHOT_TAG} snapshot from the internal disk and from every file the
	 * marker lists (the disk items that were attached when the state was saved); {@code extra} adds the current
	 * attachments for good measure.
	 */
	public static void discardSnapshotFiles(final VmcConfig global, final Path dir, final @Nullable List<Attachment> extra) {
		final Path marker = dir.resolve(SUSPEND_MARKER);
		final java.util.Set<Path> files = new java.util.LinkedHashSet<>();
		try {
			if (Files.isRegularFile(marker)) {
				final com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
				if (o.has("files")) {
					for (final com.google.gson.JsonElement e : o.getAsJsonArray("files")) {
						files.add(Path.of(e.getAsString()));
					}
				}
			}
		} catch (final IOException | RuntimeException e) {
			VirtualMinecraft.LOGGER.debug("Unreadable suspend marker {}: {}", marker, e.toString());
		}
		try {
			Files.deleteIfExists(marker);
		} catch (final IOException e) {
			VirtualMinecraft.LOGGER.warn("Could not delete {}: {}", marker, e.toString());
		}
		files.add(dir.resolve("disk.qcow2"));
		if (extra != null) {
			for (final Attachment a : extra) {
				if (a.holdsSnapshot()) {
					files.add(a.file());
				}
			}
		}
		for (final Path f : files) {
			if (Files.isRegularFile(f)) {
				QemuLauncher.deleteSnapshot(global, f, SNAPSHOT_TAG);
			}
		}
	}

	/**
	 * Saves RAM + disk state into the qcow2 (HMP {@code savevm}, VM paused meanwhile), writes the marker and quits
	 * QEMU. Runs on a daemon thread; {@link #tick()} turns the resulting exit into {@link VmStatus#SUSPENDED}.
	 * Returns the worker so a stopping server can wait for it, or null if there was nothing to suspend.
	 */
	public @Nullable Thread suspend() {
		final QemuLauncher.Endpoints ep = endpoints;
		if (!isAlive() || suspending || stopping || ep == null) {
			return null;
		}
		suspending = true;
		setStatus(VmStatus.RUNNING, "Suspending…");
		final Thread t = Threads.daemon("vmc-qmp-suspend", () -> {
			try {
				final long t0 = System.currentTimeMillis();
				final String out = QmpClient.hmp(ep, "savevm " + SNAPSHOT_TAG);
				if (!out.isBlank()) {
					throw new IOException(out.strip());
				}
				final com.google.gson.JsonObject marker = new com.google.gson.JsonObject();
				marker.addProperty("tag", SNAPSHOT_TAG);
				marker.addProperty("memMb", config.memMb);
				marker.addProperty("savedAt", System.currentTimeMillis());
				final com.google.gson.JsonArray files = new com.google.gson.JsonArray();
				for (final Attachment a : currentAttachments()) {
					if (a.holdsSnapshot()) {
						files.add(a.file().toString());
					}
				}
				marker.add("files", files);
				Files.writeString(suspendMarker(), marker + "\n");
				VirtualMinecraft.LOGGER.info("VM {} suspended in {} ms", config.name, System.currentTimeMillis() - t0);
			} catch (final IOException e) {
				VirtualMinecraft.LOGGER.warn("VM {}: suspend failed ({}); leaving it running", config.name, e.toString());
				suspending = false;
				if (isAlive()) {
					setStatus(VmStatus.RUNNING, "");
				}
				return;
			}
			try {
				QmpClient.execute(ep, "quit");
			} catch (final IOException ignored) {
				// QEMU may close the socket before answering; the process exit is what matters.
			}
		});
		suspendThread = t;
		t.start();
		return t;
	}

	/** Server stop: waits for {@link #suspend()} to finish and settles the status synchronously (no more ticks). */
	void finishSuspend(final long timeoutMs) {
		final Thread t = suspendThread;
		final Process p = process;
		try {
			if (t != null) {
				t.join(timeoutMs);
			}
			if (p != null && suspending) {
				p.waitFor(10, TimeUnit.SECONDS);
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (suspending && hasSnapshot() && !isAlive()) {
			closeConnections();
			setStatus(VmStatus.SUSPENDED, "Suspended");
			syncStatusToBlock();
		} else {
			VirtualMinecraft.LOGGER.warn("VM {}: could not suspend before shutdown; stopping it", config.name);
			forceStop();
		}
	}

	private void closeConnections() {
		final QemuDisplayLink d = display;
		if (d != null) {
			display = null;
			d.close();
		}
		final RfbClient r = rfb;
		if (r != null) {
			r.close();
		}
		final VmBus b = bus;
		if (b != null) {
			b.close();
		}
	}

	private static String shortError(final Throwable t) {
		final String m = t.getMessage();
		final String s = (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
		return s.length() > 200 ? s.substring(0, 200) + "…" : s;
	}

	private static String tailLog(final Path log) {
		try {
			final List<String> lines = Files.readAllLines(log);
			final int from = Math.max(0, lines.size() - 3);
			return String.join(" | ", lines.subList(from, lines.size()));
		} catch (final IOException e) {
			return "(no log)";
		}
	}

	public void acpiShutdown() {
		final QemuLauncher.Endpoints ep = endpoints;
		if (ep == null) {
			return;
		}
		Threads.startDaemon("vmc-qmp-powerdown", () -> {
			try {
				QmpClient.execute(ep, "system_powerdown");
			} catch (final IOException e) {
				VirtualMinecraft.LOGGER.warn("QMP system_powerdown failed for {}: {}", config.name, e.toString());
			}
		});
	}

	public void reset() {
		final QemuLauncher.Endpoints ep = endpoints;
		if (ep == null) {
			return;
		}
		Threads.startDaemon("vmc-qmp-reset", () -> {
			try {
				QmpClient.execute(ep, "system_reset");
			} catch (final IOException e) {
				VirtualMinecraft.LOGGER.warn("QMP system_reset failed for {}: {}", config.name, e.toString());
			}
		});
	}

	public @Nullable VmBus bus() {
		return bus;
	}

	public void forceStop() {
		stopping = true;
		final RfbClient r = rfb;
		if (r != null) {
			r.close();
		}
		final VmBus b = bus;
		if (b != null) {
			b.close();
		}
		final Process p = process;
		if (p != null && p.isAlive()) {
			p.destroy();
			Threads.startDaemon("vmc-qemu-reap", () -> {
				try {
					if (!p.waitFor(5, TimeUnit.SECONDS)) {
						p.destroyForcibly();
					}
				} catch (final InterruptedException ignored) {
					p.destroyForcibly();
				}
			});
		}
		if (status != VmStatus.ERROR) {
			setStatus(VmStatus.STOPPED, "");
		}
	}

	private void setStatus(final VmStatus s, final String msg) {
		status = s;
		message = msg;
	}

	// ---------------------------------------------------------------------------------------------
	// RFB listener (called from the RFB thread)
	// ---------------------------------------------------------------------------------------------

	@Override
	public void onResize(final RfbClient client, final int width, final int height) {
		synchronized (dirtyLock) {
			resized = true;
			dirty.clear();
			dirty.add(new int[] { 0, 0, width, height });
		}
	}

	@Override
	public void onRectUpdated(final RfbClient client, final int x, final int y, final int w, final int h) {
		synchronized (dirtyLock) {
			dirty.add(new int[] { x, y, w, h });
		}
	}

	@Override
	public void onAudio(final RfbClient client, final byte[] pcm, final int len) {
		synchronized (audioLock) {
			if (audioPending.size() + len > AUDIO_MAX_BACKLOG) {
				audioPending.reset(); // nobody is draining fast enough; drop the backlog rather than drift
			}
			audioPending.write(pcm, 0, len - (len & 1));
		}
	}

	@Override
	public void onDisconnected(final RfbClient client, final Throwable cause) {
		if (stopping || suspending) {
			return;
		}
		final Process p = process;
		if (p == null || !p.isAlive()) {
			return; // tick() reports the exit code
		}
		try {
			if (p.waitFor(1, TimeUnit.SECONDS)) {
				return; // process ended right after closing the display: normal power-off, tick() handles it
			}
		} catch (final InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
		setStatus(VmStatus.ERROR, "Display connection lost" + (cause == null ? "" : ": " + shortError(cause)));
	}

	// ---------------------------------------------------------------------------------------------
	// Server thread
	// ---------------------------------------------------------------------------------------------

	/** The players watching this machine's screen right now (see {@link ScreenViewers}). */
	private Collection<ScreenViewers.Viewer> viewers() {
		return ScreenViewers.get(level.getServer()).of(id);
	}

	/**
	 * Attaches to the D-Bus display if the launcher asked for one. Failure is not fatal: the VM keeps running on
	 * VNC keysyms, and the log says so once, because "my keyboard layout is wrong in the guest" is the symptom.
	 */
	private void attachDisplayLink(final VmcConfig global) {
		if (!QemuLauncher.wantsDbusDisplay(global) || endpoints.qmpSocket() == null) {
			return;
		}
		try {
			display = QemuDisplayLink.connect(endpoints.qmpSocket(), config.name);
			VirtualMinecraft.LOGGER.info("VM {}: D-Bus display attached (keyboard scancodes)", config.name);
			// Viewers who arrived before this were told "keysyms" in their ScreenInfo; re-announce so they switch.
			synchronized (dirtyLock) {
				resized = true;
			}
		} catch (final Throwable t) {
			VirtualMinecraft.LOGGER.warn("VM {}: D-Bus display not available, using VNC keysyms: {}", config.name, shortError(t));
		}
	}

	/** Capability bits for {@code ScreenInfoPayload}: whether this screen takes scancodes. */
	private int screenFlags() {
		final QemuDisplayLink d = display;
		return d != null && !d.isClosed() ? ScreenInfoPayload.FLAG_SCANCODES : 0;
	}

	/** The guest framebuffer's size, {@code {0, 0}} until the display is up. */
	public int[] screenSize() {
		final RfbClient r = rfb;
		if (r == null || r.isClosed()) {
			return new int[] { 0, 0 };
		}
		synchronized (r.framebufferLock()) {
			return new int[] { r.width(), r.height() };
		}
	}

	public boolean mayControl(final ServerPlayer player) {
		return status == VmStatus.RUNNING;
	}

	public void input(final List<VmInputPayload.Event> events) {
		final RfbClient r = rfb;
		if (r == null || r.isClosed()) {
			return;
		}
		final QemuDisplayLink d = display;
		try {
			for (final VmInputPayload.Event e : events) {
				if (e.type() == VmInputPayload.KEY) {
					r.sendKey(e.a(), e.b() != 0);
				} else if (e.type() == VmInputPayload.SCANCODE) {
					if (d != null && !d.isClosed()) {
						d.key(e.a(), e.b() != 0);
					}
				} else if (e.type() == VmInputPayload.POINTER) {
					r.sendPointer(e.a() & 0xFF, e.b(), e.c());
				}
			}
		} catch (final IOException ex) {
			VirtualMinecraft.LOGGER.debug("Input to VM {} failed: {}", config.name, ex.toString());
		}
	}

	void tick() {
		tickCounter++;
		final Process p = process;
		if (p != null && !p.isAlive() && (status == VmStatus.RUNNING || status == VmStatus.STARTING) && !stopping) {
			// Covers a guest-initiated power-off (VNC EOF arrives a moment before the process ends) and crashes alike.
			final int code = p.exitValue();
			if (suspending && hasSnapshot()) {
				setStatus(VmStatus.SUSPENDED, "Suspended");
			} else {
				suspending = false;
				setStatus(code == 0 ? VmStatus.STOPPED : VmStatus.ERROR, code == 0 ? "Powered off" : "QEMU exited with code " + code + ": " + tailLog(dir.resolve("qemu.log")));
			}
			closeConnections();
		}
		syncStatusToBlock();
		tickBus();
		tickOrphan();
		tickUnload();
		tickOwnerPresence();

		flushAudio();
		final int fps = Nums.clamp(VmcConfig.get().streamFps, 1, 60);
		final int interval = Math.max(1, Math.round(20f / fps));
		if (tickCounter % interval == 0) {
			flushFrames();
		}
	}

	private void flushAudio() {
		final byte[] pcm;
		synchronized (audioLock) {
			if (audioPending.size() == 0) {
				return;
			}
			pcm = audioPending.toByteArray();
			audioPending.reset();
		}
		final Collection<ScreenViewers.Viewer> viewers = viewers();
		if (viewers.isEmpty()) {
			return;
		}
		final byte[] ulaw = ULaw.encode(pcm, 0, pcm.length);
		AudioPayload payload = null;
		for (final ScreenViewers.Viewer v : viewers) {
			if (VirtualMinecraft.localBridge.isLocalViewer(v.player.getUUID())) {
				VirtualMinecraft.localBridge.audio(id, ulaw);
			} else {
				if (payload == null) {
					payload = new AudioPayload(id, ulaw);
				}
				dev.virtualminecraft.net.ModNetworking.send(v.player, payload);
			}
		}
	}

	/**
	 * The block went away while its chunk kept block-ticking. Only one thing does that: a block change that skips
	 * block-entity side effects (flag 256) — {@code /setblock}, {@code /fill}, {@code /clone} and the world editors
	 * built on them — so {@code preRemoveSideEffects} never ran and nothing stopped the VM. Left alone it keeps its
	 * QEMU process, its guest RAM and its bus for as long as the server runs, with no block to show or stop it. Same
	 * family as the Computer's orphan check ({@code LuaComputer.tick}), one tier up, where it costs a whole guest.
	 * <p>
	 * Suspend rather than kill: {@code /clone} carries the block entity's NBT (and so the VM id) to the copy, which
	 * re-{@link VmManager#attach attaches} this instance to its new position on its first tick — well inside the
	 * debounce — so a genuine orphan is a removal, and a removal deserves the same "come back where you left off"
	 * a chunk unload gets. Then let go of the instance, so a block restored with the same id starts a fresh one from
	 * the snapshot and a fresh block put in its place is not trampled by our status. The disk image stays where it
	 * is — nothing deletes it, the same as after a break with {@code deleteDiskOnBreak} off. A chunk <i>demotion</i>
	 * is not this case — it stops the chunk ticking first, so {@link #tickUnload} still owns it.
	 */
	private void tickOrphan() {
		final ServerLevel lvl = level;
		final BlockPos p = pos;
		if (lvl == null || p == null) {
			return;
		}
		if (orphaned) {
			// Keep ticking until the suspend (or the stop) has settled — tick() turns the process exit into a status.
			// (A finished suspend leaves the flag set: the status is what says it is over.)
			if (!isAlive() && status != VmStatus.RUNNING && status != VmStatus.STARTING) {
				VirtualMinecraft.LOGGER.info("VM {}: let go of the orphaned instance ({})", config.name, status);
				manager.forget(id);
			}
			return;
		}
		final boolean ticking = lvl.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.asLong(
			net.minecraft.core.SectionPos.blockToSectionCoord(p.getX()), net.minecraft.core.SectionPos.blockToSectionCoord(p.getZ())));
		if (!ticking || blockEntity() != null) {
			orphanTicks = 0;
			return;
		}
		if (++orphanTicks < ORPHAN_TICKS) {
			return;
		}
		orphaned = true;
		final boolean suspendable = isAlive() && !stopping && config.suspend;
		VirtualMinecraft.LOGGER.info("VM {} at {}: block removed without side effects, {}", config.name, p.toShortString(),
			suspendable ? "suspending" : "stopping");
		if (!suspendable || suspend() == null) {
			forceStop();
			manager.forget(id);
		}
	}

	/**
	 * Chunk-unload suspend. MC 26.2 demotes a chunk nobody is near long before it is dropped from memory and its
	 * block entities {@code setRemoved}, so we poll here instead of hooking removal. The predicate is
	 * {@code ServerLevel.shouldTickBlocksAt} ("is this chunk block-ticking", i.e. a player or ticket keeps it
	 * active) — it flips within seconds of the last player leaving, whereas {@code hasChunk}/{@code hasChunkAt}/
	 * {@code isLoaded} stay true for many minutes (the holder is still at FULL level). Once the chunk stops
	 * ticking the computer's block entity stops ticking too, so this is exactly "nobody is here". The block
	 * entity survives the demotion, so resuming is also our job: when the chunk ticks again, start a fresh
	 * instance from the snapshot.
	 */
	private void tickUnload() {
		final ServerLevel lvl = level;
		final BlockPos p = pos;
		if (lvl == null || p == null || stopping || orphaned) {
			return;
		}
		final boolean loaded = lvl.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.asLong(
			net.minecraft.core.SectionPos.blockToSectionCoord(p.getX()), net.minecraft.core.SectionPos.blockToSectionCoord(p.getZ())));
		if (isAlive() && !suspending) {
			unloadedTicks = loaded ? 0 : unloadedTicks + 1;
			if (unloadedTicks >= UNLOADED_SUSPEND_TICKS && config.suspend) {
				VirtualMinecraft.LOGGER.info("VM {}: chunk unloaded for {} ticks, suspending", config.name, unloadedTicks);
				unloadedTicks = 0;
				resumeWhenLoaded = true;
				if (suspend() == null) {
					resumeWhenLoaded = false;
				}
			}
		} else if (status == VmStatus.SUSPENDED && resumeWhenLoaded && loaded) {
			final ComputerBlockEntity be = blockEntity();
			if (be != null) {
				resumeWhenLoaded = false;
				VirtualMinecraft.LOGGER.info("VM {}: chunk loaded again, resuming", config.name);
				manager.start(be, null);
			}
		}
	}

	/** The computer block entity, if its chunk is loaded (server thread). */
	public @Nullable ComputerBlockEntity computer() {
		return blockEntity();
	}

	/**
	 * Owner-offline policy: a computer whose owner has logged off costs the server nothing while they are away.
	 * {@link #tickUnload} already covers the common case — a player leaving stops their chunks ticking — so this
	 * exists for the computers that case misses: spawn chunks, {@code /forceload}ed ones, and anything another
	 * player keeps loaded. Together they keep a server's load proportional to the players actually present rather
	 * than to the number of computers ever built.
	 * <p>
	 * <b>Never suspends a machine somebody is watching</b>, even if that somebody is not the owner: a viewer means
	 * a monitor for this VM is on a player's screen right now. For the same reason it resumes when someone starts
	 * watching, not only when the owner returns — walking up to a dark monitor wakes the machine behind it. Only
	 * VMs this policy suspended come back that way; one that was shut down on purpose stays down.
	 * <p>
	 * No-op in singleplayer (the host is always online), on blocks with no recorded owner (placed before ownership
	 * existed, or by {@code /setblock}), and when the VM's own "Suspend" toggle is off.
	 */
	private void tickOwnerPresence() {
		final VmcConfig global = VmcConfig.get();
		final ServerLevel lvl = level;
		if (!global.suspendWhenOwnerOffline || stopping || orphaned || lvl == null) {
			return;
		}
		final ComputerBlockEntity be = blockEntity();
		final UUID ownerId = be == null ? null : be.getOwner();
		if (ownerId == null) {
			ownerOfflineTicks = 0;
			return;
		}
		final boolean watched = !viewers().isEmpty();
		final boolean wanted = lvl.getServer().getPlayerList().getPlayer(ownerId) != null || watched;
		if (isAlive() && !suspending) {
			ownerOfflineTicks = wanted ? 0 : ownerOfflineTicks + 1;
			final int threshold = Math.max(1, global.ownerOfflineSuspendSeconds) * 20;
			if (ownerOfflineTicks >= threshold && config.suspend) {
				VirtualMinecraft.LOGGER.info("VM {}: owner {} offline for {}s, suspending", config.name, be.getOwnerName(), global.ownerOfflineSuspendSeconds);
				ownerOfflineTicks = 0;
				resumeWhenOwnerBack = true;
				if (suspend() == null) {
					resumeWhenOwnerBack = false;
				}
			}
		} else if (status == VmStatus.SUSPENDED && resumeWhenOwnerBack && wanted && be != null) {
			resumeWhenOwnerBack = false;
			VirtualMinecraft.LOGGER.info("VM {}: {}, resuming", config.name,
				watched ? "someone is watching it" : "owner " + be.getOwnerName() + " back");
			manager.start(be, null);
		}
	}

	private @Nullable ComputerBlockEntity blockEntity() {
		final ServerLevel lvl = level;
		final BlockPos p = pos;
		// Orphaned: whatever stands at that position now is somebody else's block — a fresh Command Computer put
		// back there has its own VM id, and syncing our status or clearing its outputs would trample it.
		if (lvl == null || p == null || orphaned || !lvl.hasChunkAt(p)) {
			return null;
		}
		return lvl.getBlockEntity(p) instanceof ComputerBlockEntity be ? be : null;
	}

	/** Handles queued guest requests while the VM is alive; clears the redstone outputs once it is gone. */
	private void tickBus() {
		final VmBus b = bus;
		final ComputerBlockEntity be = blockEntity();
		if (isAlive() && b != null && !stopping && !suspending) {
			outputsCleared = false;
			b.tick(level, be);
		} else if (!outputsCleared && be != null && !suspending) {
			// A suspended guest still believes its outputs are set; they come back with it, so leave them.
			outputsCleared = true;
			be.clearOutputs();
		}
	}

	private void syncStatusToBlock() {
		final ServerLevel lvl = level;
		final BlockPos p = pos;
		if (lvl == null || p == null || orphaned || !lvl.hasChunkAt(p)) {
			return;
		}
		if (lvl.getBlockEntity(p) instanceof ComputerBlockEntity be) {
			be.setStatus(status, message);
		}
	}

	/**
	 * Sends this tick's changes to every viewer at the level of detail each one asked for. A viewer whose level
	 * changed (or who is new, or after a resize) gets a {@code ScreenInfo} and a full frame at that level; the
	 * others get the dirty rectangles, box-filtered down to their level. Work is shared per level, so ten viewers
	 * at the same level cost one copy and one deflate.
	 */
	private void flushFrames() {
		final Collection<ScreenViewers.Viewer> viewers = viewers();
		if (viewers.isEmpty()) {
			synchronized (dirtyLock) {
				dirty.clear();
				resized = false;
			}
			return;
		}
		final RfbClient r = rfb;
		if (r == null || r.isClosed()) {
			return;
		}
		final List<int[]> rects;
		final boolean wasResized;
		synchronized (dirtyLock) {
			rects = dirty;
			dirty = new ArrayList<>();
			wasResized = resized;
			resized = false;
		}
		final int width;
		final int height;
		synchronized (r.framebufferLock()) {
			width = r.width();
			height = r.height();
		}
		if (width <= 0 || height <= 0) {
			return;
		}
		final List<int[]> coalesced = coalesce(rects, width, height);
		// Per level: bands copied once, deflated once, shared by every remote viewer at that level.
		final List<Band>[] fullBands = new List[ViewerPayload.MAX_LOD + 1];
		final List<Band>[] dirtyBands = new List[ViewerPayload.MAX_LOD + 1];
		final List<ScreenRectPayload>[] fullPayloads = new List[ViewerPayload.MAX_LOD + 1];
		final List<ScreenRectPayload>[] dirtyPayloads = new List[ViewerPayload.MAX_LOD + 1];
		for (final ScreenViewers.Viewer v : viewers) {
			final int lod = v.lod;
			final boolean local = VirtualMinecraft.localBridge.isLocalViewer(v.player.getUUID());
			if (wasResized || v.sentLod != lod) {
				v.sentLod = lod;
				v.needFull = true;
				final int flags = screenFlags();
				if (local) {
					VirtualMinecraft.localBridge.screenInfo(id, width, height, true, lod, flags);
				} else {
					dev.virtualminecraft.net.ModNetworking.send(v.player, new ScreenInfoPayload(id, width, height, true, lod, flags));
				}
			}
			if (v.needFull) {
				v.needFull = false;
				if (fullBands[lod] == null) {
					fullBands[lod] = copyBands(r, 0, 0, width, height, lod);
				}
				if (local) {
					for (final Band b : fullBands[lod]) {
						VirtualMinecraft.localBridge.screenRect(id, b.x, b.y, b.w, b.h, b.rgb);
					}
				} else {
					if (fullPayloads[lod] == null) {
						fullPayloads[lod] = deflate(fullBands[lod]);
					}
					for (final ScreenRectPayload payload : fullPayloads[lod]) {
						dev.virtualminecraft.net.ModNetworking.send(v.player, payload);
					}
				}
			} else if (!coalesced.isEmpty()) {
				if (dirtyBands[lod] == null) {
					dirtyBands[lod] = new ArrayList<>();
					for (final int[] rc : coalesced) {
						dirtyBands[lod].addAll(copyBands(r, rc[0], rc[1], rc[2], rc[3], lod));
					}
				}
				if (local) {
					for (final Band b : dirtyBands[lod]) {
						VirtualMinecraft.localBridge.screenRect(id, b.x, b.y, b.w, b.h, b.rgb);
					}
				} else {
					if (dirtyPayloads[lod] == null) {
						dirtyPayloads[lod] = deflate(dirtyBands[lod]);
					}
					for (final ScreenRectPayload payload : dirtyPayloads[lod]) {
						dev.virtualminecraft.net.ModNetworking.send(v.player, payload);
					}
				}
			}
		}
	}

	/** A horizontal slice of a dirty rectangle, small enough to fit one packet, as raw RGB8. */
	private record Band(int x, int y, int w, int h, byte[] rgb) {
	}

	private static List<int[]> coalesce(final List<int[]> rects, final int width, final int height) {
		final List<int[]> out = new ArrayList<>();
		if (rects.isEmpty()) {
			return out;
		}
		if (rects.size() <= MAX_DIRTY_RECTS) {
			for (final int[] rc : rects) {
				final int x0 = Nums.clamp(rc[0], 0, width);
				final int y0 = Nums.clamp(rc[1], 0, height);
				final int x1 = Nums.clamp(rc[0] + rc[2], 0, width);
				final int y1 = Nums.clamp(rc[1] + rc[3], 0, height);
				if (x1 > x0 && y1 > y0) {
					out.add(new int[] { x0, y0, x1 - x0, y1 - y0 });
				}
			}
			return out;
		}
		int minX = width;
		int minY = height;
		int maxX = 0;
		int maxY = 0;
		for (final int[] rc : rects) {
			minX = Math.min(minX, rc[0]);
			minY = Math.min(minY, rc[1]);
			maxX = Math.max(maxX, rc[0] + rc[2]);
			maxY = Math.max(maxY, rc[1] + rc[3]);
		}
		minX = Nums.clamp(minX, 0, width);
		minY = Nums.clamp(minY, 0, height);
		maxX = Nums.clamp(maxX, 0, width);
		maxY = Nums.clamp(maxY, 0, height);
		if (maxX > minX && maxY > minY) {
			out.add(new int[] { minX, minY, maxX - minX, maxY - minY });
		}
		return out;
	}

	/**
	 * Copies a region out of the framebuffer as RGB at a level of detail, split into bands of at most
	 * {@link #MAX_BAND_BYTES}. At level 0 this is a straight copy. At level {@code L} the source rectangle is widened
	 * outward to a multiple of {@code 2^L} so it maps onto whole output pixels, and each output pixel is the box
	 * average of its {@code 2^L × 2^L} source block (clamped at the right and bottom edges, where the last block
	 * may be partial). Output coordinates are in the level's space, which is what the viewer's texture uses.
	 */
	private List<Band> copyBands(final RfbClient r, final int x, final int y, final int w, final int h, final int lod) {
		final List<Band> out = new ArrayList<>();
		if (lod <= 0) {
			return copyBands0(r, x, y, w, h);
		}
		final int s = 1 << lod;
		// Source rectangle aligned outward; clamped to the framebuffer when we know its size.
		final int sx0 = x & ~(s - 1);
		final int sy0 = y & ~(s - 1);
		final int sx1 = ((x + w + s - 1) / s) * s;
		final int sy1 = ((y + h + s - 1) / s) * s;
		final int ox = sx0 >> lod;
		final int oy = sy0 >> lod;
		final int ow = (sx1 - sx0) >> lod;
		final int oh = (sy1 - sy0) >> lod;
		if (ow <= 0 || oh <= 0) {
			return out;
		}
		final int rowsPerBand = Math.max(1, MAX_BAND_BYTES / (ow * 3));
		for (int oy0 = oy; oy0 < oy + oh; oy0 += rowsPerBand) {
			final int bh = Math.min(rowsPerBand, oy + oh - oy0);
			final byte[] rgb = new byte[ow * bh * 3];
			synchronized (r.framebufferLock()) {
				final byte[] fb = r.framebuffer();
				final int fbw = r.width();
				final int fbh = fb.length / Math.max(1, fbw * 4);
				if (sx0 >= fbw || (oy0 << lod) >= fbh) {
					return out; // framebuffer changed size underneath us; a resize event will follow
				}
				int o = 0;
				for (int row = 0; row < bh; row++) {
					final int syStart = (oy0 + row) << lod;
					final int syEnd = Math.min(syStart + s, fbh);
					for (int col = 0; col < ow; col++) {
						final int sxStart = (ox + col) << lod;
						final int sxEnd = Math.min(sxStart + s, fbw);
						int rSum = 0;
						int gSum = 0;
						int bSum = 0;
						int n = 0;
						for (int sy = syStart; sy < syEnd; sy++) {
							int i = (sy * fbw + sxStart) * 4;
							for (int sx = sxStart; sx < sxEnd; sx++, i += 4) {
								rSum += fb[i] & 0xFF;
								gSum += fb[i + 1] & 0xFF;
								bSum += fb[i + 2] & 0xFF;
								n++;
							}
						}
						if (n == 0) {
							o += 3;
							continue;
						}
						rgb[o++] = (byte) (rSum / n);
						rgb[o++] = (byte) (gSum / n);
						rgb[o++] = (byte) (bSum / n);
					}
				}
			}
			out.add(new Band(ox, oy0, ow, bh, rgb));
		}
		return out;
	}

	/** Level 0: a straight copy, no filtering. */
	private List<Band> copyBands0(final RfbClient r, final int x, final int y, final int w, final int h) {
		final List<Band> out = new ArrayList<>();
		final int rowsPerBand = Math.max(1, MAX_BAND_BYTES / (w * 3));
		for (int y0 = y; y0 < y + h; y0 += rowsPerBand) {
			final int bh = Math.min(rowsPerBand, y + h - y0);
			final byte[] rgb = new byte[w * bh * 3];
			synchronized (r.framebufferLock()) {
				final byte[] fb = r.framebuffer();
				final int fbw = r.width();
				if (fb.length < (y0 + bh) * fbw * 4 || x + w > fbw) {
					return out; // framebuffer changed size underneath us; a resize event will follow
				}
				int o = 0;
				for (int row = 0; row < bh; row++) {
					int i = ((y0 + row) * fbw + x) * 4;
					for (int px = 0; px < w; px++, i += 4) {
						rgb[o++] = fb[i];
						rgb[o++] = fb[i + 1];
						rgb[o++] = fb[i + 2];
					}
				}
			}
			out.add(new Band(x, y0, w, bh, rgb));
		}
		return out;
	}

	private List<ScreenRectPayload> deflate(final List<Band> bands) {
		final List<ScreenRectPayload> out = new ArrayList<>(bands.size());
		for (final Band b : bands) {
			deflater.reset();
			deflater.setInput(b.rgb);
			deflater.finish();
			int len = 0;
			while (!deflater.finished()) {
				if (len == deflateBuf.length) {
					deflateBuf = Arrays.copyOf(deflateBuf, deflateBuf.length * 2);
				}
				len += deflater.deflate(deflateBuf, len, deflateBuf.length - len);
			}
			out.add(new ScreenRectPayload(id, b.x, b.y, b.w, b.h, ScreenRectPayload.FORMAT_ZLIB_RGB, Arrays.copyOf(deflateBuf, len)));
		}
		return out;
	}
}
