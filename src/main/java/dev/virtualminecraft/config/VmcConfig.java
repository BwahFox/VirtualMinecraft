package dev.virtualminecraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.virtualminecraft.VirtualMinecraft;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Global (per-installation) settings, stored in {@code config/virtualminecraft.json}. */
public final class VmcConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static VmcConfig instance;

	public String qemuBinary = "qemu-system-x86_64";
	public String qemuImgBinary = "qemu-img";
	/** Combined OVMF firmware image used when a VM has UEFI enabled. */
	public String ovmfPath = "/usr/share/edk2/x64/OVMF.4m.fd";
	/** Directory (relative to the config dir unless absolute) where ISO images are looked up. */
	public String isoDirectory = "virtualminecraft/iso";
	public boolean enableKvm = true;
	public int maxRunningVms = 4;
	/** Extra arguments appended to every QEMU command line. */
	public String extraArgs = "";
	/** Maximum framebuffer update rate streamed to players (per second). */
	public int streamFps = 20;
	/** Village house pools the software store is appended to at server start (U3c step 2); empty = never in villages. */
	public java.util.List<String> villageStorePools = java.util.List.of(
		"minecraft:village/plains/houses", "minecraft:village/desert/houses", "minecraft:village/savanna/houses",
		"minecraft:village/taiga/houses", "minecraft:village/snowy/houses");
	/** Tickets the store gets in each of those pools (a vanilla small house has 2); 0 disables. */
	public int villageStoreWeight = 3;
	/**
	 * Chance that a village house chest holds the written book that points at the Manual (§9 U3c, "books as loot":
	 * the teaser for a machine you have not met yet). 0 turns it off. It is deliberately small — the book is a
	 * nudge, not a drop, and a village with one in every chest is a village that has stopped meaning anything.
	 */
	public double manualBookChance = 0.12;
	/**
	 * {@code auto} attaches to QEMU's D-Bus display when the host is Linux and the QEMU build has it (keyboard
	 * scancodes now, shared-memory scanout and audio later); {@code vnc} never tries. VNC always carries the picture.
	 */
	public String display = "auto";
	/** Players farther than this (blocks) from a monitor stop receiving updates. */
	public int viewDistance = 48;
	/** If true, only operators may configure / start / stop VMs. */
	public boolean requireOp = false;
	/** Delete the VM disk image when the computer block is broken. */
	public boolean deleteDiskOnBreak = false;
	// ---- Per-computer ceilings a player may configure (ROADMAP section 5) ----
	// These defaults are the *singleplayer* ones — deliberately as permissive as the old hard-coded limits,
	// because there the host is the player's own PC and capping it would be rude. A fresh config written on a
	// dedicated server gets the tighter profile in applyDedicatedServerDefaults() instead.
	/** Most RAM one computer may be given. */
	public int maxMemMbPerVm = 65536;
	/** Most vCPUs one computer may be given. On a shared host keep this well under the core count: a busy guest
	 * competes with the server thread, and a starved server thread is a dropped tick for everybody. */
	public int maxCpusPerVm = 32;
	/** Largest internal disk one computer may be given, in GB. */
	public int maxDiskGbPerVm = 512;
	/** May guests have a network card at all? False forces every NIC to {@code none}. A guest with a NIC reaches
	 * the internet through this server's address, which is why a dedicated server defaults it off. */
	public boolean allowGuestNetwork = true;
	/** Suspend a computer when the player who placed it is offline (no effect in singleplayer). The single
	 * cheapest way to keep a server's cost proportional to the players actually present. */
	public boolean suspendWhenOwnerOffline = true;
	/** How long the owner must be gone before that happens. */
	public int ownerOfflineSuspendSeconds = 30;

	/** Let guests use the {@code chat} component (say things in chat, hear nearby players). */
	public boolean allowChat = true;
	/** How far a computer can be heard and can hear, in blocks; -1 = the whole server. */
	public int chatRange = 32;
	/** Budget for {@code chat.say}/{@code chat.send} per computer. */
	public int chatMessagesPerMinute = 20;
	/** Budget for {@code speaker.playNote}/{@code speaker.playSound} per computer. */
	public float speakerSoundsPerSecond = 8;
	/** Let computers on one bus (cable or adjacent) message each other with the {@code net} component (ROADMAP §9 U3). */
	public boolean allowNet = true;
	/** Budget for {@code net.send}/{@code net.broadcast} per computer (a broadcast is one). */
	public int netMessagesPerMinute = 600;
	/** Longest {@code net} message, in bytes of its JSON encoding. */
	public int netMessageMaxBytes = 4096;
	/** How far a wireless modem reaches another modem, in blocks (the same dimension, both chunks loaded). */
	public int modemRange = 64;

	// ---- The Computer (milestone 7, ROADMAP §7h) ----
	/** Worker threads that run Lua machines; 0 = max(2, cores / 4). The server thread never runs Lua. */
	public int computerThreads = 0;
	/** Longest run before a machine is interrupted and the next one gets the worker, in ms. */
	public int computerSliceMs = 5;
	/** Share of one core a single machine may use over time, in percent (with a one-second burst). */
	public int computerCpuPercent = 25;
	/** Lua heap budget of a freshly placed Computer, in MB; a computer may be configured 1..maxComputerMemMb. */
	public int computerMemMb = 4;
	public int maxComputerMemMb = 16;
	/** Computers loaded at once, server-wide; the next placement is refused with a message. 0 = unlimited. */
	public int maxLoadedComputers = 1000;
	/** Computers one player may place; 0 = unlimited. */
	public int maxComputersPerPlayer = 200;
	/** A machine with no events and nobody watching for this long is frozen to disk (0 = never). */
	public int computerIdleFreezeSeconds = 300;
	/**
	 * A framebuffer nobody is watching and nothing is drawing into is given back to the heap after this long
	 * (0 = never), the picture kept deflated. It comes back on the first draw or the first viewer.
	 */
	public int computerScreenParkSeconds = 20;
	/** Keep machines running while their owner is offline (automation); false freezes them on logout. */
	public boolean computersRunWhileOwnerOffline = true;
	/** Internal disk quota per Computer, in KB. */
	public int computerDiskKb = 8192;
	/** How far (blocks) a Computer's sound chip is heard; the VM tier's audio still follows the screen viewers. */
	public int computerSoundRange = 32;
	/**
	 * The hardware voice (§9 U5): how loud the case, fan and drives are, 0..1. This is the *chassis* chip's
	 * master, separate from anything Lua plays, so a game's music always wins -- and 0 turns the hardware voice
	 * off entirely, because some players will hate a fan hum and they are not wrong.
	 */
	public double computerChassisVolume = 0.35;
	/** A watched game's frame cap: the kernel paces its programs at this rate and the worker flushes each frame (U1.2). Unwatched machines fall back to 20. */
	public int computerMaxFps = 60;
	/** false = the pre-U1.2 path: a machine's "flip" parks it until the server-tick flush ({@code streamFps}, ≤ 20 fps). */
	public boolean computerWorkerFlush = true;
	/**
	 * §9 U9: how long a chunk is held ticking when a {@code net.send} is addressed to a machine inside it, so the
	 * machine can thaw and answer. Nothing renews the ticket but more traffic, so a computer that stops being
	 * spoken to freezes again and costs nothing. 0 disables waking entirely -- unloaded peers are then still
	 * listed and still addressable, they simply do not answer until something else loads their chunk.
	 */
	public int netWakeSeconds = 20;
	/**
	 * How many cables one run may contain before it stops finding things (§9 U9: this was a hidden constant).
	 * It is a count of cable blocks, not a distance — a straight run of 1024 reaches 1024 blocks, a winding one
	 * much less. Raised from 128 on 2026-08-29 at [name]'s word ("we'll need a larger distance than that").
	 * <p>
	 * Since §9 U11 this bounds a single <em>rebuild</em> rather than every call: the run is walked when the
	 * cable changes and looked up thereafter, so the number can be large without any program paying for it.
	 */
	public int busMaxCables = 1024;
	/**
	 * §9 U11: how often a cable run is re-walked even though nothing said it changed, in seconds (0 = never).
	 * Connectivity is stored now, so the one thing that could go stale is an edit that fires no block update —
	 * a datapack, another mod, or a world edited while the server was down. This is the insurance against that:
	 * one walk a minute per run, against the thousands a second the compute-on-demand design used to pay.
	 */
	public int busRebuildSeconds = 60;
	/**
	 * §9 U11 bridges: how many bridge hops a message or a component lookup may cross. Loops are already
	 * harmless (each run is visited once), so this is a sanity bound on how far one machine's world extends,
	 * not a correctness device.
	 */
	public int busMaxBridgeHops = 8;
	/**
	 * §9 U11b/U11: the most chunks a single bus call may demand-load to reach components in unloaded chunks.
	 * [name]'s rule when she chose to let components cross bridges (2026-08-28): <em>"I don't mind loading maybe
	 * one or two chunks. The issue would be loading like 100 chunks or something."</em> An {@code invoke} on one
	 * component loads one chunk and never comes near this; a {@code list} over a large bridged network is what
	 * this stops, and it fails loudly rather than stalling the server.
	 */
	public int busMaxChunkLoadsPerCall = 16;
	/**
	 * How many cable-attached blocks one computer may collect; its six neighbours are always on top of this.
	 * Positions holding a block entity are taken first (see {@code BusNetwork.attached}), so this cap bounds how
	 * much a computer looks at rather than deciding whether the interesting things at the far end are found.
	 */
	public int busMaxAttached = 256;
	/** Lines a machine may log to the server log per minute (print goes to the machine's own console regardless). */
	public int computerLogLinesPerMinute = 60;

	// ---- VR (§9 U4), client-side and only read by the `vr` jar's controller pointer ----
	/**
	 * How far a VR controller may point, in blocks. Not the player's block-interaction reach: pointing is not
	 * reaching, and a monitor wall across a room is the whole point. The server's own {@link #viewDistance} gate
	 * still applies on top, so raising this past it does nothing.
	 */
	public double vrPointerReach = 8.0;
	/**
	 * How much the controller ray is smoothed, 0 (raw) to 0.95 (syrup). A hand is never still, and a millimetre of
	 * tremor at four blocks is several pixels on a 320-wide screen; too much of it and the dot lags the hand.
	 * <b>This is a number to be judged with a headset on, not calculated</b> — §9 U4 puts smoothing squarely in
	 * the half only [name] can test, and this field exists so tuning it costs a restart rather than a rebuild.
	 */
	public double vrPointerSmoothing = 0.5;
	/**
	 * How close the hand has to get, in blocks, before pointing stops claiming the VR interact binding. 0 turns
	 * the rule off, which is the default and almost certainly what you want.
	 * <p>
	 * <b>This solved a problem that does not exist, and it is kept only as an escape hatch.</b> It was written to
	 * stop pointing from stealing the full-screen panel, on the assumption that the trigger was the one button
	 * both wanted. It is not: Vivecraft's interact binding is trigger + grip, and Minecraft's ordinary "use" —
	 * which is what opens the panel — is a different button entirely (A, on the Oculus defaults). They never
	 * competed. All the threshold ever did was carve out a dead zone near the screen where the trigger did
	 * nothing, which is exactly how [name] found it on 2026-08-29: <i>"touching the screen does seemingly
	 * nothing"</i>. Before that, at 0.75, the dead zone covered the whole range a hand naturally sits at and ate
	 * every click she made.
	 */
	public double vrPointerTouchRange = 0.0;
	/**
	 * Log what the controller is actually doing, once every {@link #vrPointerDiagnosticSeconds}. On for the first
	 * headset session and meant to be turned off after: it exists because the person wearing the headset cannot
	 * read a screen or type, so the only way the numbers above get chosen from evidence rather than from a guess
	 * is if the client writes down what happened. It costs one extra raycast a tick while a controller is
	 * pointing, and nothing at all when it is not.
	 */
	public boolean vrPointerDiagnostics = true;
	/** How often the diagnostic line is written, in seconds. */
	public int vrPointerDiagnosticSeconds = 5;
	/**
	 * How far away, in blocks, a Keyboard block may be for the VR keyboard gesture to anchor to it — the nearest
	 * one inside this radius of the player wins, and none at all means the keyboard floats where Vivecraft puts
	 * it, exactly as before the block existed. Deliberately small: the keyboard on <em>this</em> desk, not one in
	 * the next room.
	 */
	public int vrKeyboardRange = 5;
	/**
	 * How high above a Keyboard block's base the anchored keyboard hovers, in blocks. The keyboard is drawn around
	 * this point, so "on the desk" is a number to be judged wearing the headset, like the pointer's smoothing was
	 * — this field exists so judging it costs a restart rather than a rebuild. 0.4 was the guess; 0.15 is what
	 * [name] settled on in the headset (2026-08-29, with {@link #vrKeyboardForward} 0.35: "everything's working").
	 */
	public double vrKeyboardHeight = 0.15;
	/**
	 * How far the anchored keyboard leans back from vertical, in degrees. 0 is bolt upright; Vivecraft's own
	 * keyboard-under-a-GUI placement uses 30. Ignored in Vivecraft's physical-keyboard mode, which has its own
	 * lie-flat angle.
	 */
	public double vrKeyboardTilt = 30.0;
	/**
	 * How far the anchored keyboard sits toward the typist from the block's centre, in blocks, along the block's
	 * facing. The anchor point is the keyboard's centre, so with 0 it hovers dead over the block — which [name]'s
	 * first headset test read as "too far away" (2026-08-29). Negative pushes it behind the block.
	 */
	public double vrKeyboardForward = 0.35;

	public static synchronized VmcConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	public Path isoDir() {
		final Path p = Path.of(isoDirectory);
		return p.isAbsolute() ? p : configDir().resolve(p);
	}

	private static Path file() {
		return configDir().resolve("virtualminecraft.json");
	}

	/**
	 * What a brand-new config looks like on a dedicated server, where the RAM, cores and address being spent are
	 * not the player's own. Written once, on first start, so an admin can loosen any of it afterwards and we will
	 * never overwrite their choice. Singleplayer keeps the permissive defaults on the fields above.
	 */
	private void applyDedicatedServerDefaults() {
		maxMemMbPerVm = 4096;
		maxCpusPerVm = 4;
		maxDiskGbPerVm = 64;
		allowGuestNetwork = false;
		maxComputerMemMb = 8;
	}

	private static VmcConfig load() {
		final Path file = file();
		VmcConfig cfg = null;
		if (Files.isRegularFile(file)) {
			try (Reader r = Files.newBufferedReader(file)) {
				cfg = GSON.fromJson(r, VmcConfig.class);
			} catch (final IOException | RuntimeException e) {
				VirtualMinecraft.LOGGER.warn("Could not read {}: {}", file, e.toString());
			}
		}
		if (cfg == null) {
			cfg = new VmcConfig();
			if (FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER) {
				cfg.applyDedicatedServerDefaults();
				VirtualMinecraft.LOGGER.info("Dedicated server: wrote conservative VM limits to {} (edit to taste)", file);
			}
		}
		cfg.save();
		try {
			Files.createDirectories(cfg.isoDir());
		} catch (final IOException e) {
			VirtualMinecraft.LOGGER.warn("Could not create ISO directory {}: {}", cfg.isoDir(), e.toString());
		}
		return cfg;
	}

	public void save() {
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file())) {
				GSON.toJson(this, w);
			}
		} catch (final IOException e) {
			VirtualMinecraft.LOGGER.warn("Could not write {}: {}", file(), e.toString());
		}
	}
}
