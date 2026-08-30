package dev.virtualminecraft.computer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.bus.BusException;
import dev.virtualminecraft.bus.Component;
import dev.virtualminecraft.bus.Components;
import dev.virtualminecraft.bus.RateLimiter;
import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.screen.ScreenViewers;
import dev.virtualminecraft.util.Nums;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * The server side of one Computer while it is loaded: the {@link LuaMachine}, its {@link LuaMachine.Host}
 * (the syscalls of ROADMAP §7h §1e), the scheduler listener, the idle/unload freeze policy and the floor
 * persistence (§2: {@code <dir>/state/kernel.dat} written by the kernel through syscall 2). The block entity is
 * the world-side face; this object survives a chunk demotion only until the freeze timer fires.
 * <p>
 * Threads: {@link LuaMachine.Host} methods run on a worker; {@link #tick}, {@link #event}, {@link #boot} and
 * {@link #dispose} on the server thread. World calls from the machine are handed to the server thread and the
 * worker waits (≤ 2 s) — CC's model, and the reason a worker is never the server thread.
 */
public final class LuaComputer implements LuaMachine.Host, MachineScheduler.Listener {
	public static final int SYS_BUS = 1;
	public static final int SYS_STATE = 2;
	public static final int SYS_MACHINE = 4;
	private static final int CONSOLE_LINES = 60;
	private static final int UNLOAD_TICKS = 200;
	/** §9 U10(b): the world's clock runs 72× real time — 24000 ticks to a day, so one tick is 3600 ms of it. */
	public static final long MS_PER_TICK = 3600L;
	/** World tick 0 is 1970-01-01 <b>06:00</b>, because a Minecraft day starts at dawn (§9 U10(b)). */
	public static final long WORLD_EPOCH_OFFSET_MS = 6L * 3600L * 1000L;
	/** How long a block entity may be gone from a still-ticking chunk before the machine behind it is let go. */
	private static final int ORPHAN_TICKS = 20;

	private final ComputerManager manager;
	private final UUID id;
	private final Path dir;
	private volatile LuaComputerBlockEntity be;
	private volatile ServerLevel level;
	private @Nullable LuaMachine machine;
	private volatile String status = "off";
	private final Deque<String> console = new ArrayDeque<>();
	private final RateLimiter logBudget;
	private long lastActivityTick;
	private int notTickingTicks;
	private int orphanTicks;
	/** The idle framebuffer park (§3): ticks with nobody watching and nothing drawn, and the last draw seen. */
	private int screenIdleTicks;
	private long lastDrawSeq = -1;
	/** Whether anyone was watching last tick; a change sends the machine a {@code viewers} event. Null = never told. */
	private @Nullable Boolean lastWatched;
	private final CountDownLatch saved = new CountDownLatch(1);
	private volatile boolean freezing;
	private final ScreenDevice screen;
	private final MachineFiles files;
	private final SoundChip sound = new SoundChip();
	/** The hardware voice (§9 U5): the mod's chip, not Lua's. */
	private final ChassisVoice chassis = new ChassisVoice(VmcConfig.get().computerChassisVolume);
	/** A rolling count of ticks in which the machine visibly did something, for the fan's colour. */
	private int busyTicks;
	private int busyWindow;
	private double humLoad;
	private String bootedFrom = "rom";
	/** The kernel yielded "flip" and waits for the next flush ({@code computerWorkerFlush = false} only). */
	private volatile boolean awaitingFlip;
	/** A worker-side flush reached a viewer since the last tick: counts as activity for the freeze policy. */
	private volatile boolean workerSent;
	private int flushTick;
	/** Why the next freeze happens: {@code idle} stays lazy across a world load, anything else thaws when the chunk ticks again. */
	private String freezeReason = "command";

	LuaComputer(final ComputerManager manager, final LuaComputerBlockEntity be, final ServerLevel level) {
		this.manager = manager;
		this.be = be;
		this.level = level;
		this.id = be.machineId();
		this.dir = manager.dir(id);
		final float perMinute = Math.max(1, VmcConfig.get().computerLogLinesPerMinute);
		this.logBudget = new RateLimiter(Math.max(5, perMinute / 6), perMinute / 60f);
		this.lastActivityTick = level.getGameTime();
		// the tier ladder (§9 U3b): the case and its parts decide the quota, the screen cap, the colours, the voices
		final MachineSpec spec = be.spec();
		this.files = new MachineFiles(dir, manager.itemsDir(), VmcConfig.configDir().resolve("virtualminecraft"), spec.diskQuotaBytes());
		this.files.refresh(level, be);
		this.screen = new ScreenDevice(id);
		final Path shot = dir.resolve("state").resolve("screen.bin");
		if (Files.isRegularFile(shot)) {
			try {
				screen.restore(Files.readAllBytes(shot));
			} catch (final IOException ignored) {
				// a cold screen is fine
			}
		}
		if (spec.hasGraphics()) {
			screen.setLimits(spec.maxW(), spec.maxH(), spec.colours());
		}
		sound.setChannels(spec.synthChannels(), spec.sampleChannels());
	}

	public MachineSpec spec() {
		return be.spec();
	}

	@Override
	public String info() {
		return be.info().toString();
	}

	@Override
	public ScreenDevice screen() {
		return screen;
	}

	@Override
	public SoundChip sound() {
		return sound;
	}

	@Override
	public MachineFiles files() {
		return files;
	}

	public String bootedFrom() {
		return bootedFrom;
	}

	public UUID id() {
		return id;
	}

	public @Nullable UUID owner() {
		return be.owner();
	}

	public String status() {
		return status;
	}

	public @Nullable LuaMachine machine() {
		return machine;
	}

	public LuaComputerBlockEntity blockEntity() {
		return be;
	}

	public Path dir() {
		return dir;
	}

	/** The last console lines (print output, errors), newest last. */
	public synchronized List<String> console() {
		return List.copyOf(console);
	}

	void rebind(final LuaComputerBlockEntity be, final ServerLevel level) {
		this.be = be;
		this.level = level;
	}

	// ---- lifecycle (server thread) ----

	/** Create the machine and hand it to the scheduler. {@code thaw} delivers a {@code resume} event when state exists. */
	void boot(final boolean thaw) {
		if (machine != null) {
			return;
		}
		// boot order (§2): the first boot.lua on a removable disk, else the ROM — "write your own OS"
		files.refresh(level, be);
		final String diskBoot = files.bootSource();
		final String src = diskBoot != null ? diskBoot : manager.romBoot();
		bootedFrom = diskBoot != null ? files.bootMount() : "rom";
		try {
			machine = new LuaMachine(this, src, diskBoot != null ? bootedFrom + "/boot.lua" : "boot.lua");
		} catch (final LuaMachine.MachineError e) {
			status = "error: " + e.getMessage();
			logLine(3, e.getMessage());
			return;
		}
		status = "running";
		lastActivityTick = level.getGameTime();
		// A thaw is a machine waking, not a machine starting: it gets the relay but not the POST beep.
		chassis.relay(true);
		if (!thaw) {
			chassis.post();
		}
		if (thaw && Files.isRegularFile(dir.resolve("state").resolve("kernel.dat"))) {
			long worldTicks = 0;
			long realTicks = 0;
			String reason = "";
			try {
				final Path meta = dir.resolve("state").resolve("meta.json");
				if (Files.isRegularFile(meta)) {
					final JsonObject m = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
					worldTicks = Math.max(0, level.getGameTime() - m.get("frozenAtTick").getAsLong());
					if (m.has("frozenAtMillis")) {
						realTicks = Math.max(0, (System.currentTimeMillis() - m.get("frozenAtMillis").getAsLong()) / 50L);
					}
					if (m.has("reason")) {
						reason = m.get("reason").getAsString();
					}
				}
			} catch (final IOException | RuntimeException ignored) {
				// no meta: still a thaw
			}
			final JsonObject p = new JsonObject();
			// The world's clock stands still while the server is down, so a machine frozen by a server stop woke up
			// believing no time had passed. Whichever clock ran longer is the honest answer: while the server is up
			// the two agree, across a stop (or a paused singleplayer world) the wall clock is the only one that moved.
			p.addProperty("frozen_for_ticks", Math.max(worldTicks, realTicks));
			p.addProperty("world_ticks", worldTicks);
			p.addProperty("real_ticks", realTicks);
			p.addProperty("reason", reason);
			p.addProperty("exact", false);
			pushEvent("resume", p);
		}
		manager.scheduler().submit(machine, this, be.spec().cpuShare());
	}

	/** Push a bus/world event to the kernel and make it runnable. */
	public void event(final String name, final JsonObject params) {
		if ("disk_inserted".equals(name)) {
			chassis.clunk(true);
		} else if ("disk_ejected".equals(name)) {
			chassis.clunk(false);
		}
		pushEvent(name, params);
		final LuaMachine m = machine;
		if (m != null) {
			manager.scheduler().wake(m);
		}
	}

	private void pushEvent(final String name, final JsonObject params) {
		final LuaMachine m = machine;
		if (m == null) {
			return;
		}
		params.addProperty("name", name);
		if (!m.pushEvent(params.toString())) {
			logLine(2, "event queue full, dropped " + name);
		}
		lastActivityTick = level.getGameTime();
	}

	/**
	 * Stop the machine. With {@code freeze}, the kernel is asked to save first (a {@code save} event; it answers by
	 * yielding {@code "saved"}) and waits up to 250 ms for it, then the meta file records the freeze; without,
	 * the kernel state is deleted so the next boot is cold.
	 */
	void dispose(final boolean freeze) {
		dispose(freeze, "command");
	}

	void dispose(final boolean freeze, final String reason) {
		freezeReason = reason;
		if (freeze) {
			requestSave();
			awaitSaved(System.nanoTime() + 250_000_000L);
		}
		finishDispose(freeze);
	}

	/** Phase 1 of a freeze: ask the kernel to save (it answers by yielding {@code "saved"}). */
	void requestSave() {
		final LuaMachine m = machine;
		if (m != null && !m.isFinished()) {
			freezing = true;
			event("save", new JsonObject());
		}
	}

	/** Phase 2: wait for the acknowledgement until {@code deadlineNanos}. */
	void awaitSaved(final long deadlineNanos) {
		final LuaMachine m = machine;
		if (m == null || m.isFinished() || !freezing) {
			return;
		}
		try {
			if (!saved.await(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)) {
				logLine(2, "kernel did not save in time; frozen without its state");
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Phase 3: stop the machine and write (or delete) the state files. */
	void finishDispose(final boolean freeze) {
		if (freeze && "command".equals(freezeReason) && manager.stopping()) {
			freezeReason = "stop";
		}
		final LuaMachine m = machine;
		if (m == null) {
			return;
		}
		manager.scheduler().remove(m);
		m.kill();
		machine = null;
		chassis.hum(false, 0);
		chassis.relay(false);
		busyTicks = 0;
		busyWindow = 0;
		humLoad = 0;
		try {
			final Path state = dir.resolve("state");
			if (freeze) {
				Files.createDirectories(state);
				final JsonObject meta = new JsonObject();
				meta.addProperty("frozenAtTick", level.getGameTime());
				meta.addProperty("frozenAtMillis", System.currentTimeMillis()); // the world's clock stops with the server; this one does not
				meta.addProperty("id", id.toString());
				meta.addProperty("reason", freezeReason);
				ComputerManager.writeAtomic(state.resolve("meta.json"), meta.toString().getBytes(StandardCharsets.UTF_8));
				if (screen.hasFramebuffer()) {
					ComputerManager.writeAtomic(state.resolve("screen.bin"), screen.snapshot());
				}
				status = "frozen";
			} else {
				Files.deleteIfExists(state.resolve("kernel.dat"));
				Files.deleteIfExists(state.resolve("meta.json"));
				Files.deleteIfExists(state.resolve("screen.bin"));
				// and the directories themselves if that emptied them: this is what left 86 empty computers/<uuid>/
				// behind during the 100-spinner test — a machine that never wrote a file still got a state/ folder.
				ComputerManager.deleteIfEmpty(state);
				ComputerManager.deleteIfEmpty(dir);
				status = "off";
			}
		} catch (final IOException e) {
			VirtualMinecraft.LOGGER.warn("Computer {}: could not write state: {}", id, e.toString());
		}
		if (freeze) {
			be.markFrozen();
		} else {
			be.machineStatusChanged();
		}
	}

	/** Once per server tick: the screen, then the freeze policies (§2). */
	/**
	 * §5: one tick of the sound chip to everyone within {@code computerSoundRange} blocks; silence sends nothing.
	 * Since §9 U5 the chassis is summed into the same buffer, which is what keeps the whole machine on one
	 * positional OpenAL source.
	 * <p>
	 * Two things here are load-bearing rather than tidy. <b>Only the software chip counts as activity</b>: the
	 * chassis hum plays for as long as the machine runs, so letting it touch {@code lastActivityTick} would mean
	 * no Computer ever idle-froze again (§2). And <b>the chassis is not even mixed when nobody is in range</b>,
	 * because a decorative sound that costs a mix on every one of a thousand loaded machines (§7l) is a sound
	 * that is not worth having; it has no state to keep in sync, so skipping it is free.
	 */
	private void mixSound(final ServerLevel lvl, final BlockPos pos) {
		final byte[] pcm = sound.mixTick();
		if (pcm != null) {
			lastActivityTick = lvl.getGameTime();
		}
		sendAudio(lvl, pos, pcm, true);
	}

	/**
	 * The chassis still has a voice when the machine does not: the power-down relay, and a disk clunking into a
	 * machine that is off or frozen. Called from {@link #tick()} on the path where there is no Lua machine.
	 */
	private void chassisTail() {
		final ServerLevel lvl = level;
		if (lvl == null || !chassis.enabled()) {
			return;
		}
		sendAudio(lvl, be.getBlockPos(), null, false);
	}

	/** Sums in the chassis (when anyone can hear it), encodes once, and sends to everyone in range. */
	private void sendAudio(final ServerLevel lvl, final BlockPos pos, final byte[] softPcm, final boolean hum) {
		final int range = Math.max(0, VmcConfig.get().computerSoundRange);
		final double r2 = (double) range * range;
		final double cx = pos.getX() + 0.5;
		final double cy = pos.getY() + 0.5;
		final double cz = pos.getZ() + 0.5;
		java.util.List<net.minecraft.server.level.ServerPlayer> near = null;
		for (final net.minecraft.server.level.ServerPlayer p : lvl.players()) {
			if (p.distanceToSqr(cx, cy, cz) <= r2) {
				if (near == null) {
					near = new java.util.ArrayList<>(4);
				}
				near.add(p);
			}
		}
		if (near == null) {
			// nobody in earshot: drop the chassis rather than mixing a tick no one receives
			chassis.silence();
			return;
		}
		if (hum) {
			chassis.hum(true, humLoad);
		}
		final byte[] pcm = ChassisVoice.mix(softPcm, chassis.mixTick());
		if (pcm == null) {
			return;
		}
		final byte[] ulaw = dev.virtualminecraft.audio.ULaw.encode(pcm, 0, pcm.length);
		dev.virtualminecraft.net.AudioPayload payload = null;
		for (final net.minecraft.server.level.ServerPlayer p : near) {
			if (VirtualMinecraft.localBridge.isLocalViewer(p.getUUID())) {
				VirtualMinecraft.localBridge.audioAt(id, ulaw, pos);
			} else {
				if (payload == null) {
					payload = new dev.virtualminecraft.net.AudioPayload(id, ulaw, pos);
				}
				dev.virtualminecraft.net.ModNetworking.send(p, payload);
			}
		}
	}

	/**
	 * Tell the machine when the last player stops looking, and when the first one starts. The ROM stops repainting
	 * its clock while unwatched, which is what lets the framebuffer go quiet enough to park — and saves the slice
	 * every machine in the world was spending on a picture nobody could see.
	 */
	private void tellViewers() {
		final boolean watched = ScreenViewers.get(manager.server()).anyone(id);
		if (lastWatched != null && lastWatched == watched) {
			return;
		}
		lastWatched = watched;
		final JsonObject p = new JsonObject();
		p.addProperty("n", watched ? ScreenViewers.get(manager.server()).of(id).size() : 0);
		event("viewers", p);
	}

	/** Ticks the picture has been still with nobody watching: {@code /vmc computer state} shows it. */
	public int screenIdleTicks() {
		return screenIdleTicks;
	}

	/**
	 * Give an idle framebuffer back to the heap (§3): nobody is watching and nothing has been drawn for
	 * {@code computerScreenParkSeconds}. The picture survives, deflated; the first draw or the first viewer
	 * brings it back. Worth up to 768 KB per loaded machine, which is the whole point.
	 */
	private void parkScreenIfIdle(final ServerLevel lvl) {
		final int seconds = VmcConfig.get().computerScreenParkSeconds;
		if (seconds <= 0 || !screen.hasFramebuffer() || screen.parked()) {
			screenIdleTicks = 0;
			return;
		}
		final long seq = screen.drawSeq();
		if (seq != lastDrawSeq || ScreenViewers.get(manager.server()).anyone(id)) {
			lastDrawSeq = seq;
			screenIdleTicks = 0;
			return;
		}
		if (++screenIdleTicks >= seconds * 20) {
			screenIdleTicks = 0;
			screen.park();
		}
	}

	void tick() {
		final LuaMachine m = machine;
		if (m == null) {
			chassisTail();
			return;
		}
		final ServerLevel lvl = level;
		final BlockPos pos = be.getBlockPos();
		if ((flushTick & 7) == 0) {
			files.refresh(lvl, be); // drives come and go; the mount table follows every 8 ticks
		}
		// resolution follows the monitor (§3); a machine without a monitor has no framebuffer
		final int[] res = be.monitorResolution(lvl);
		if (screen.resize(res[0], res[1])) {
			final JsonObject p = new JsonObject();
			p.addProperty("w", res[0]);
			p.addProperty("h", res[1]);
			event("screen", p);
		}
		boolean busy = false;
		final int fpsDivisor = Math.max(1, 20 / Nums.clamp(VmcConfig.get().streamFps, 1, 20));
		if (++flushTick % fpsDivisor == 0) {
			final boolean sent = screen.flush(ScreenViewers.get(manager.server()).of(id), VirtualMinecraft.localBridge);
			if (awaitingFlip) {
				// the frame is out (or nobody is watching): let the game loop continue at the stream rate
				awaitingFlip = false;
				manager.scheduler().wake(m);
			}
			if (sent) {
				lastActivityTick = lvl.getGameTime();
				busy = true;
			}
		}
		if (workerSent) {
			workerSent = false;
			lastActivityTick = lvl.getGameTime();
			busy = true;
		}
		if (screen.takeDrawn()) {
			be.pictureStarted(lvl); // the machine drew: monitors leave text mode (a later text write turns it back on)
		}
		tellViewers();
		parkScreenIfIdle(lvl);
		// The fan's colour follows how busy the machine *looks* — the share of recent ticks in which it drew or
		// sent something. It is not the scheduler's CPU number (nothing exposes one per machine), and calling it
		// that would be a lie; it is close enough that a machine grinding away sounds like it.
		final int diskOps = files.takeIoOps();
		if (diskOps > 0) {
			chassis.drive(files.takeIoWasWrite());
		}
		if (busy) {
			busyTicks++;
		}
		if (++busyWindow >= 40) {
			humLoad = busyTicks / 40.0;
			busyTicks = 0;
			busyWindow = 0;
		}
		mixSound(lvl, pos);
		// The block entity went away while its chunk kept ticking. Only one thing does that: a block change that
		// skips block-entity side effects (flag 256) — /setblock, /fill, /clone and every world editor built on
		// them — so preRemoveSideEffects never ran, nothing froze the machine and nothing dropped its item. The
		// player path is covered; this is the command path. Freeze it rather than kill it, so the files and the
		// saved kernel survive for /vmc gc to report. A chunk *demotion* is not this case: it stops the chunk
		// ticking first, so the branch below owns it.
		if (be.isRemoved() && lvl.shouldTickBlocksAt(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4))) {
			if (++orphanTicks >= ORPHAN_TICKS) {
				VirtualMinecraft.LOGGER.info("Computer '{}' ({}) at {}: block removed without side effects, freezing", name(), id, pos.toShortString());
				manager.remove(id, true, "removed");
				return;
			}
		} else {
			orphanTicks = 0;
		}
		// chunk stopped ticking (the VM tier's proven test) → freeze after 10 s
		if (!lvl.shouldTickBlocksAt(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4))) {
			if (++notTickingTicks >= UNLOAD_TICKS) {
				VirtualMinecraft.LOGGER.info("Computer '{}' at {}: chunk unloaded, freezing", name(), pos.toShortString());
				manager.remove(id, true, "unload");
				return;
			}
		} else {
			notTickingTicks = 0;
		}
		final VmcConfig cfg = VmcConfig.get();
		final long now = lvl.getGameTime();
		if (cfg.computerIdleFreezeSeconds > 0 && "waiting".equals(status) && now - lastActivityTick > cfg.computerIdleFreezeSeconds * 20L
			&& !ScreenViewers.get(manager.server()).anyone(id)) {
			VirtualMinecraft.LOGGER.info("Computer '{}' at {}: idle for {} s with nobody watching, freezing", name(), pos.toShortString(), cfg.computerIdleFreezeSeconds);
			manager.remove(id, true, "idle");
			return;
		}
		if (!cfg.computersRunWhileOwnerOffline && be.owner() != null && manager.server().getPlayerList().getPlayer(be.owner()) == null
			&& !ScreenViewers.get(manager.server()).anyone(id) && now - lastActivityTick > 20L * 30) {
			VirtualMinecraft.LOGGER.info("Computer '{}' at {}: owner offline, freezing", name(), pos.toShortString());
			manager.remove(id, true, "owner");
		}
	}

	// ---- MachineScheduler.Listener (worker thread) ----

	@Override
	public void onResult(final LuaMachine m, final LuaMachine.Result result) {
		if (result == LuaMachine.Result.VALUE && "saved".equals(m.yieldValue())) {
			saved.countDown();
		}
		if (result == LuaMachine.Result.VALUE && "flip".equals(m.yieldValue())) {
			if (VmcConfig.get().computerWorkerFlush) {
				// U1.2: the frame goes out now, from this worker; the kernel paces itself with frame_ms() and a timed wait
				if (screen.flush(ScreenViewers.get(manager.server()).of(id), VirtualMinecraft.localBridge)) {
					workerSent = true;
				}
			} else {
				awaitingFlip = true;
				manager.scheduler().park(m); // resumed by tick() after the next flush: the stream rate is the game's vsync
			}
		}
		manager.post(() -> {
			if (m != machine) {
				return;
			}
			switch (result) {
				case WAIT -> status = "waiting";
				case VALUE -> status = "running";
				case SLICE -> status = "running";
				case FINISHED -> {
					status = "off";
					logLine(1, "kernel exited");
					be.machineStatusChanged();
				}
				case ERROR -> {
					status = "error: " + m.error();
					logLine(3, String.valueOf(m.error()));
					be.machineStatusChanged();
				}
			}
			if (result != LuaMachine.Result.WAIT) {
				lastActivityTick = level.getGameTime();
			}
		});
	}

	// ---- LuaMachine.Host (worker thread) ----

	@Override
	public int frameMillis() {
		final VmcConfig cfg = VmcConfig.get();
		if (!cfg.computerWorkerFlush || !ScreenViewers.get(manager.server()).anyone(id)) {
			return 50; // nobody watching (or the old path): the server-tick rate, so an unwatched game costs what it did
		}
		return Math.max(1, 1000 / Nums.clamp(cfg.computerMaxFps, 1, 240));
	}

	@Override
	public String name() {
		return be.busName();
	}

	@Override
	public void log(final int level, final String message) {
		logLine(level, message);
	}

	private synchronized void logLine(final int lvl, final String message) {
		final String clean = message.length() > 512 ? message.substring(0, 512) + "…" : message;
		console.addLast((lvl >= 3 ? "! " : lvl == 2 ? "? " : "") + clean);
		while (console.size() > CONSOLE_LINES) {
			console.removeFirst();
		}
		if (lvl >= 2 && logBudget.tryAcquire(level.getGameTime())) {
			VirtualMinecraft.LOGGER.info("Computer '{}': {}", name(), clean);
		}
	}

	@Override
	public long clock(final int kind) {
		return switch (kind) {
			// The world's CLOCK, not the world's odometer. getGameTime() is total ticks the world has ever run and
			// ignores /time set entirely, so os.date() -- and the taskbar clock built on it -- drifted away from
			// the actual time of day. getDefaultClockTime() is what WorldComponent.getTime already reports (26.2
			// replaced the old day-time counter with per-dimension clocks), so the two now agree.
			case 1 -> level.getDayTime();
			// §9 U10(b), [name]: "day 0, time 0 would be equal to January 1st, 1970 at 6:00AM minecraft time".
			// A Minecraft day is 24000 ticks long and lasts a day, so a tick is 3600 ms of the world's own time,
			// and tick 0 sits six hours after midnight. That makes os.epoch() the WORLD's milliseconds since 1970
			// -- a machine in a world that has run for a year gets a date a year on, not the date on the host.
			case 2 -> WORLD_EPOCH_OFFSET_MS + level.getDayTime() * MS_PER_TICK;
			case 3 -> System.currentTimeMillis(); // the host's wall clock: os.realtime(), for real elapsed time
			default -> System.nanoTime();
		};
	}

	@Override
	public long memoryCapBytes() {
		return be.spec().memoryCapBytes();
	}

	@Override
	public String call(final int fn, final String payload) throws LuaMachine.MachineError {
		return switch (fn) {
			case SYS_BUS -> onServer(() -> busCall(payload));
			case SYS_STATE -> stateCall(payload);
			case SYS_MACHINE -> onServer(() -> machineCall(payload));
			default -> throw new LuaMachine.MachineError("no such syscall " + fn);
		};
	}

	@FunctionalInterface
	private interface ServerCall {
		String run() throws LuaMachine.MachineError;
	}

	/** Worker → server thread and back: the machine waits for the world. */
	private String onServer(final ServerCall call) throws LuaMachine.MachineError {
		final LuaMachine m = machine;
		if (m == null) {
			throw new LuaMachine.MachineError("machine is stopping");
		}
		final CompletableFuture<String> f = new CompletableFuture<>();
		manager.post(() -> {
			try {
				f.complete(call.run());
			} catch (final LuaMachine.MachineError e) {
				f.completeExceptionally(e);
			} catch (final RuntimeException e) {
				f.completeExceptionally(new LuaMachine.MachineError("internal: " + e));
			}
		});
		m.inHostCall = true;
		try {
			return f.get(2, TimeUnit.SECONDS);
		} catch (final ExecutionException e) {
			throw e.getCause() instanceof LuaMachine.MachineError me ? me : new LuaMachine.MachineError(String.valueOf(e.getCause()));
		} catch (final TimeoutException e) {
			throw new LuaMachine.MachineError("world call timed out");
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LuaMachine.MachineError("interrupted");
		} finally {
			m.inHostCall = false;
		}
	}

	/** {@code {"op":"list"}} or {@code {"op":"call","target":…,"method":…,"args":[…]}} — the VM bus's semantics, reused. */
	private String busCall(final String payload) throws LuaMachine.MachineError {
		final ServerLevel lvl = level;
		final LuaComputerBlockEntity entity = be;
		if (entity.isRemoved() || !lvl.hasChunkAt(entity.getBlockPos())) {
			throw new LuaMachine.MachineError("computer not loaded");
		}
		final JsonObject req;
		try {
			req = JsonParser.parseString(payload).getAsJsonObject();
		} catch (final RuntimeException e) {
			throw new LuaMachine.MachineError("bus: bad request");
		}
		final String op = req.has("op") ? req.get("op").getAsString() : "";
		// §9 U11b: components the registry remembers on this run whose chunks are away. `list` touches
		// everything, so it loads them all; `call` resolves against what is live first — net traffic and every
		// other near component never costs a load — and reaches for the phantoms only when the target is not
		// found. Either way the answer is what a fully-loaded run would have said, or an error that names the
		// gap — never a quietly shorter world.
		List<Component> components = Components.collect(lvl, entity);
		final List<net.minecraft.core.BlockPos> phantoms = dev.virtualminecraft.bus.BusRegistry.phantomsOnRun(lvl, entity.getBlockPos());
		try {
			if ("list".equals(op)) {
				if (!phantoms.isEmpty()) {
					components = withPhantomsLoaded(lvl, entity, phantoms);
				}
				final JsonArray out = new JsonArray();
				for (final Component c : components) {
					final JsonObject o = new JsonObject();
					o.addProperty("address", c.address().toString());
					o.addProperty("type", c.type());
					o.addProperty("location", c.location());
					final JsonObject methods = new JsonObject();
					c.methods().forEach(methods::addProperty);
					o.add("methods", methods);
					out.add(o);
				}
				return out.toString();
			}
			if ("call".equals(op)) {
				final String target = req.has("target") ? req.get("target").getAsString() : "";
				final String method = req.has("method") ? req.get("method").getAsString() : "";
				final JsonArray args = req.has("args") && req.get("args").isJsonArray() ? req.getAsJsonArray("args") : new JsonArray();
				Component c = Components.find(components, target, null);
				if (c == null && !phantoms.isEmpty()) {
					c = findAcrossPhantoms(lvl, entity, phantoms, target);
				}
				if (c == null) {
					throw new LuaMachine.MachineError("no such component: " + target);
				}
				if (!c.methods().containsKey(method)) {
					throw new LuaMachine.MachineError("no such method: " + c.type() + "." + method);
				}
				final JsonElement r = c.invoke(method, args);
				return r == null ? "null" : r.toString();
			}
			throw new LuaMachine.MachineError("bus: unknown op " + op);
		} catch (final BusException e) {
			throw new LuaMachine.MachineError(e.getMessage());
		}
	}

	/**
	 * Find one component that may be in an unloaded chunk, loading <b>a chunk at a time, nearest first, and
	 * stopping the moment the target resolves</b> (§9 U11). Reaching one chest across a bridge therefore costs
	 * one chunk, which is the bargain [name] agreed to when she let components cross: <em>"I don't mind loading
	 * maybe one or two chunks. The issue would be loading like 100 chunks."</em> Loading the whole far world to
	 * answer one question was the first cut of this, and it made a single {@code invoke} hit the cap.
	 */
	private @org.jetbrains.annotations.Nullable Component findAcrossPhantoms(final ServerLevel lvl, final LuaComputerBlockEntity entity,
			final List<net.minecraft.core.BlockPos> phantoms, final String target) throws LuaMachine.MachineError {
		requireDemandLoad(entity, phantoms);
		final List<net.minecraft.core.BlockPos> sorted = new java.util.ArrayList<>(phantoms);
		sorted.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(entity.getBlockPos())));
		final java.util.LinkedHashMap<Long, List<net.minecraft.core.BlockPos>> byChunk = new java.util.LinkedHashMap<>();
		for (final net.minecraft.core.BlockPos p : sorted) {
			byChunk.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4),
				k -> new java.util.ArrayList<>()).add(p);
		}
		final int cap = Math.max(1, dev.virtualminecraft.config.VmcConfig.get().busMaxChunkLoadsPerCall);
		int loaded = 0;
		for (final java.util.Map.Entry<Long, List<net.minecraft.core.BlockPos>> e : byChunk.entrySet()) {
			if (++loaded > cap) {
				throw new LuaMachine.MachineError("bus: looked in " + cap + " chunk(s) without finding '" + target
					+ "'; name it by address, or raise busMaxChunkLoadsPerCall");
			}
			dev.virtualminecraft.bus.BusWake.loadComponents(lvl, e.getValue());
			entity.invalidateAttached();
			final Component found = Components.find(Components.collect(lvl, entity), target, null);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	/**
	 * Load the chunks holding this run's remembered-but-away components and collect again, so the call in
	 * progress sees the same bus a fully-loaded run would (§9 U11b). Used by {@code list}, which touches
	 * everything by definition and so is the call the chunk cap is really for.
	 */
	private List<Component> withPhantomsLoaded(final ServerLevel lvl, final LuaComputerBlockEntity entity,
			final List<net.minecraft.core.BlockPos> phantoms) throws LuaMachine.MachineError {
		requireDemandLoad(entity, phantoms);
		// [name]'s rule when she let components cross bridges (§9 U11, 2026-08-28): one or two chunks is nothing,
		// a hundred is the problem. A `list` is the call that touches everything, so it is the one this stops,
		// and it says so rather than stalling the server for a second.
		final java.util.Set<Long> chunks = new java.util.LinkedHashSet<>();
		for (final net.minecraft.core.BlockPos p : phantoms) {
			chunks.add(net.minecraft.world.level.ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4));
		}
		final int cap = Math.max(1, dev.virtualminecraft.config.VmcConfig.get().busMaxChunkLoadsPerCall);
		if (chunks.size() > cap) {
			throw new LuaMachine.MachineError("bus: reaching every component here would load " + chunks.size()
				+ " chunks (limit " + cap + "); call one component directly, or raise busMaxChunkLoadsPerCall");
		}
		dev.virtualminecraft.bus.BusWake.loadComponents(lvl, phantoms);
		entity.invalidateAttached(); // the per-tick cache was computed before the chunks arrived
		return Components.collect(lvl, entity);
	}

	/** With waking switched off, hardware the machine cannot reach must error rather than simply not exist. */
	private void requireDemandLoad(final LuaComputerBlockEntity entity, final List<net.minecraft.core.BlockPos> phantoms)
			throws LuaMachine.MachineError {
		if (dev.virtualminecraft.bus.BusWake.demandLoadEnabled()) {
			return;
		}
		final StringBuilder where = new StringBuilder();
		for (final net.minecraft.core.BlockPos p : phantoms) {
			where.append(where.isEmpty() ? "" : " ").append(dev.virtualminecraft.bus.BusNetwork.offsetLocation(entity.getBlockPos(), p));
		}
		throw new LuaMachine.MachineError("bus: " + phantoms.size() + " component(s) at " + where
			+ " are in unloaded chunks and netWakeSeconds is 0");
	}

	/** Syscall 2, the floor: empty payload = load the kernel's saved state (or ""), anything else = save it. */
	private String stateCall(final String payload) throws LuaMachine.MachineError {
		final Path file = dir.resolve("state").resolve("kernel.dat");
		try {
			if (payload.isEmpty()) {
				return Files.isRegularFile(file) ? Files.readString(file) : "";
			}
			if (payload.length() > 256 * 1024) {
				throw new LuaMachine.MachineError("state too large (256 KB max)");
			}
			ComputerManager.writeAtomic(file, payload.getBytes(StandardCharsets.UTF_8));
			return "ok";
		} catch (final IOException e) {
			throw new LuaMachine.MachineError("state: " + e.getMessage());
		}
	}

	/** Syscall 4: {@code reboot}, {@code shutdown}, {@code label:<name>}. */
	private String machineCall(final String payload) throws LuaMachine.MachineError {
		if (payload.equals("reboot")) {
			manager.remove(id, false);
			be.requestBoot();
			return "ok";
		}
		if (payload.equals("shutdown")) {
			be.setPowered(false);
			manager.remove(id, false);
			return "ok";
		}
		if (payload.startsWith("label:")) {
			be.setName(payload.substring(6));
			return "ok";
		}
		if (payload.startsWith("desktop:")) {
			// Settings: what the machine boots into (auto = the tier decides; takes effect at the next boot)
			final String m = payload.substring(8);
			be.setDesktopMode(m.equals("on") || m.equals("desktop") ? 1 : m.equals("off") || m.equals("shell") ? 2 : 0);
			return "ok";
		}
		throw new LuaMachine.MachineError("machine: unknown request");
	}

	/** {@code /vmc computer lua}: evaluate on a worker, result on the server thread. */
	public CompletableFuture<String> eval(final String source) {
		final LuaMachine m = machine;
		if (m == null) {
			return CompletableFuture.completedFuture("ERROR: machine is off");
		}
		return manager.scheduler().eval(m, source);
	}

	public boolean isFreezing() {
		return freezing;
	}
}
