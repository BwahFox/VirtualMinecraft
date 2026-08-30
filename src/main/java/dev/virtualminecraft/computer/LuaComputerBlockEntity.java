package dev.virtualminecraft.computer;

import com.google.gson.JsonObject;
import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.block.MonitorBlockEntity;
import dev.virtualminecraft.bus.BusHost;
import dev.virtualminecraft.bus.BusNetwork;
import dev.virtualminecraft.bus.Component;
import dev.virtualminecraft.bus.Components;
import dev.virtualminecraft.bus.RateLimiter;
import dev.virtualminecraft.bus.RedstoneComponent;
import dev.virtualminecraft.bus.ScreenComponent;
import dev.virtualminecraft.bus.Sides;
import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.net.VmInputPayload;
import dev.virtualminecraft.screen.ScreenSource;
import dev.virtualminecraft.screen.ScreenSources;
import dev.virtualminecraft.util.Nums;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.Level;

/**
 * The Computer block's entity (ROADMAP §7h §8): the world-side face of a {@link LuaComputer}. Holds what must
 * persist in the world — machine id, name, memory budget, owner, power, redstone outputs — and implements
 * {@link ScreenSource} (monitors link to it; input arrives here) and {@link BusHost} (the components work on it
 * exactly as on the Command Computer). The machine itself lives in {@link ComputerManager} and is (re)attached
 * on the first server tick after a load.
 */
public class LuaComputerBlockEntity extends BlockEntity implements ScreenSource, BusHost, net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider<BlockPos> {
	private UUID machineId = UUID.randomUUID();
	private String name = "computer";
	/** The tier ladder (ROADMAP §9 U3b): the parts in the case, one slot per kind; the case block is the tier. */
	private final net.minecraft.world.SimpleContainer parts = new net.minecraft.world.SimpleContainer(MachineSpec.Part.ALL.length) {
		@Override
		public void setChanged() {
			super.setChanged();
			partsChanged();
		}
	};
	/** 0 = the tier decides (a Basic Computer boots into the shell), 1 = the desktop, 2 = the shell. Settings and the GUI set it. */
	private int desktopMode;
	/** Set while {@link #loadParts} rewrites every slot, so the case restarts once at the end instead of per slot. */
	private boolean loadingParts;
	/** A memory number saved before the ladder existed: becomes a RAM part on the first tick. -1 = none. */
	private int legacyMemMb = -1;
	/**
	 * §9 U10(a): whether this case has been through the "a case is only a ceiling" migration. A block entity that
	 * was never saved (one just placed) needs no migration, so the field starts true and {@link #loadAdditional}
	 * turns it off for any save written before U10 — those are the cases that used to be whole machines while
	 * empty, and they are given parts on their first tick so nothing already built in a world goes dark.
	 */
	private boolean partsMigrated = true;
	/** The hardware notice currently painted on the monitors ("no processor", "no graphics card"); null = none. */
	private @Nullable String noticeShown;
	/** The client's copy of the effective memory (the server's config cap is not known here). */
	private int syncedMemMb;
	private boolean powered = true;
	private @Nullable UUID owner;
	private String ownerName = "";
	private final int[] outputs = new int[6];
	private final int[] lastInputs = { -1, -1, -1, -1, -1, -1 };
	/** Redstone wake (the VM tier's {@code wakeThreshold}): 1–15 starts a machine that is off, 0 = never. */
	private int wakeThreshold;
	/** With a threshold set, a falling edge asks the machine to shut down (a {@code power} event, then force after 5 s). */
	private boolean redstoneSleep;
	/** Ticks left of the grace a {@code power} event gets before redstone sleep pulls the plug; -1 = not sleeping. */
	private int sleepGrace = -1;
	private final Set<BlockPos> monitors = new LinkedHashSet<>();
	private @Nullable Map<UUID, String> lastComponents;
	private @Nullable LinkedHashMap<BlockPos, String> attachedCache;
	private long attachedTick = Long.MIN_VALUE;
	private @Nullable RateLimiter soundBudget;
	private @Nullable RateLimiter chatBudget;

	private @Nullable RateLimiter netBudget;
	private boolean sourceNoted;
	private boolean bootRequested;
	/** Frozen by a policy or a command: stays a file until a viewer, an event or a command thaws it (§2, "thaw is lazy"). */
	private boolean frozen;
	/** Client-side copy of the machine's status for rendering / tooltips. */
	private String status = "off";
	/**
	 * Whether any linked monitor is loaded; synced, because the case GUI has no other way to tell a healthy idle
	 * machine from one whose screen is missing. [name] read "waiting" as "will not power on" (HANDOFF (p)).
	 */
	private boolean hasMonitor;
	/** Data of a VM block from a world saved before the rename (its id was {@code computer}); migrated on the first tick. */
	private @Nullable LegacyVm legacy;

	private record LegacyVm(UUID vmId, dev.virtualminecraft.vm.VmConfig config, int[] outputs, @Nullable UUID owner, String ownerName, List<net.minecraft.world.item.ItemStack> disks) {
	}

	public LuaComputerBlockEntity(final BlockPos pos, final BlockState state) {
		super(ModContent.LUA_COMPUTER_BLOCK_ENTITY, pos, state);
	}

	// ---- identity, config ----

	public UUID machineId() {
		return machineId;
	}

	/** A placed item that carried a machine id (a broken computer) keeps its machine. */
	public void adoptId(final UUID id) {
		machineId = id;
		setChanged();
	}

	/** The case: 1 Basic Computer, 2 Computer, 3 Advanced Computer. */
	public int tier() {
		return getBlockState().getBlock() instanceof LuaComputerBlock b ? b.tier : 2;
	}

	public net.minecraft.world.SimpleContainer parts() {
		return parts;
	}

	public int partLevel(final MachineSpec.Part kind) {
		return dev.virtualminecraft.item.PartItem.levelOf(parts.getItem(kind.ordinal()), kind);
	}

	/** What the case and its parts add up to. Cheap: four item lookups and a few clamps. */
	public MachineSpec spec() {
		return MachineSpec.of(tier(), partLevel(MachineSpec.Part.RAM), partLevel(MachineSpec.Part.CPU), partLevel(MachineSpec.Part.GRAPHICS), partLevel(MachineSpec.Part.DRIVE),
			Math.max(1, VmcConfig.get().maxComputerMemMb));
	}

	/** §9 U10(a): memory and a processor, or this case is a box. The client can answer too — the parts are synced. */
	public boolean canBoot() {
		return spec().canBoot();
	}

	/** Why it will not boot ("no processor"), or null. The case GUI, the monitors and {@code /vmc} all say this. */
	public @Nullable String bootRefusal() {
		return spec().bootRefusal();
	}

	public int memMb() {
		return level != null && level.isClientSide() ? syncedMemMb : spec().memMb();
	}

	/** A memory number from before the ladder (a saved block, an old item): the smallest RAM part that gives it, when the slot is free and it is more than the bare case has. */
	public void installLegacyMemory(final int mb) {
		final int slot = MachineSpec.Part.RAM.ordinal();
		if (parts.getItem(slot).isEmpty() && mb > 0) {
			parts.setItem(slot, new net.minecraft.world.item.ItemStack(ModContent.PARTS[slot][MachineSpec.ramLevelFor(mb) - 1]));
		}
	}

	public int desktopMode() {
		return desktopMode;
	}

	public void setDesktopMode(final int mode) {
		desktopMode = Nums.clamp(mode, 0, 2);
		sync();
	}

	public void cycleDesktopMode() {
		setDesktopMode((desktopMode + 1) % 3);
	}

	/** Whether the ROM boots into the desktop (else the shell fills the screen). */
	public boolean bootsToDesktop() {
		return desktopMode == 1 || (desktopMode == 0 && spec().desktopByDefault());
	}

	/** {@code vmc.info()}: the spec plus what the ROM needs to know about the case. */
	public JsonObject info() {
		final JsonObject o = spec().json();
		o.addProperty("desktop", bootsToDesktop());
		o.addProperty("desktopMode", desktopMode);
		o.addProperty("name", name);
		return o;
	}

	/** The GUI's power button (and sneak + right-click): off if running, else boot or thaw. */
	public void togglePower(final net.minecraft.server.level.ServerPlayer player) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		final ComputerManager manager = ComputerManager.get(serverLevel.getServer());
		if (powered) {
			setPowered(false);
			manager.remove(machineId, false);
			player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Computer powered off"));
		} else {
			final String dead = bootRefusal();
			if (dead != null) {
				// §9 U10(a): say what is missing, not "powered on" followed by nothing happening
				player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("This case has " + dead));
				return;
			}
			final String refusal = manager.placementRefusal(owner);
			if (refusal != null) {
				player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(refusal));
			} else {
				player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(thaw(serverLevel) ? "Computer powered on" : "Refused by the computer cap"));
			}
		}
	}

	/** A part went in or out: the parts are the hardware, so a live machine restarts on them (a memory cap cannot shrink under a heap). */
	private void partsChanged() {
		if (loadingParts || !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		sync();
		final ComputerManager manager = ComputerManager.get(serverLevel.getServer());
		if (powered && manager.get(machineId) != null) {
			manager.remove(machineId, false);
			// §9 U10(a): pulling the processor or the memory out of a running machine does not restart it, it
			// stops it. Say which happened -- "restarting" while it never comes back is the worst of both.
			final String dead = bootRefusal();
			if (dead == null) {
				requestBoot();
			}
			dev.virtualminecraft.VirtualMinecraft.LOGGER.info("Computer '{}' {} for its parts: {}", name, dead == null ? "restarting" : "stopped", spec().describe());
			for (final net.minecraft.server.level.ServerPlayer p : serverLevel.players()) {
				if (p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(worldPosition)) <= 64.0) {
					p.sendOverlayMessage(dead == null
						? net.minecraft.network.chat.Component.translatable("virtualminecraft.msg.restart_for_parts", name)
						: net.minecraft.network.chat.Component.literal(name + ": " + dead));
				}
			}
		}
	}

	// ---- the case's GUI (ExtendedMenuProvider) ----

	@Override
	public net.minecraft.network.chat.Component getDisplayName() {
		return getBlockState().getBlock().getName();
	}

	@Override
	public net.minecraft.world.inventory.AbstractContainerMenu createMenu(final int id, final net.minecraft.world.entity.player.Inventory inventory, final net.minecraft.world.entity.player.Player player) {
		return new ComputerMenu(id, inventory, this);
	}

	@Override
	public BlockPos getScreenOpeningData(final net.minecraft.server.level.ServerPlayer player) {
		return worldPosition;
	}

	public void setName(final String n) {
		name = n.length() > 32 ? n.substring(0, 32) : n;
		sync();
	}

	public @Nullable UUID owner() {
		return owner;
	}

	public String ownerName() {
		return ownerName;
	}

	public void setOwner(final UUID id, final String n) {
		owner = id;
		ownerName = n;
		setChanged();
	}

	public boolean powered() {
		return powered;
	}

	/** Both sides: is there a monitor showing this machine? False is what the case GUI reports as "no monitor". */
	public boolean hasMonitor() {
		return hasMonitor;
	}

	public void setPowered(final boolean on) {
		powered = on;
		sync();
	}

	public String status() {
		if (level instanceof ServerLevel sl) {
			final LuaComputer c = ComputerManager.get(sl.getServer()).get(machineId);
			if (c != null) {
				return c.status();
			}
			if (!powered) {
				return "off";
			}
			final String refusal = bootRefusal();
			return refusal != null ? refusal : frozen ? "frozen" : "booting";
		}
		return status;
	}

	/** The machine wants a fresh boot on the next tick (reboot). */
	void requestBoot() {
		bootRequested = true;
		powered = true;
	}

	void machineStatusChanged() {
		sync();
	}

	/** The manager froze the machine; do not boot it again until something wants it. */
	void markFrozen() {
		frozen = true;
		sync();
	}

	public boolean isFrozen() {
		return frozen;
	}

	/** Boot or thaw now (a command, a player, an event, a viewer). Returns false if refused by the cap. */
	public boolean thaw(final ServerLevel level) {
		if (!canBoot()) {
			return false; // §9 U10(a): nothing to thaw in an empty case
		}
		frozen = false;
		powered = true;
		try {
			ComputerManager.get(level.getServer()).attach(this, level);
			return true;
		} catch (final IllegalStateException refused) {
			return false;
		}
	}

	private void sync() {
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
		}
	}

	public @Nullable LuaComputer computer() {
		return level instanceof ServerLevel sl ? ComputerManager.get(sl.getServer()).get(machineId) : null;
	}

	// ---- ticking ----

	void serverTick(final ServerLevel level) {
		if (legacy != null) {
			migrateLegacy(level);
			return;
		}
		final ComputerManager manager = ComputerManager.get(level.getServer());
		if (!sourceNoted) {
			sourceNoted = true;
			if (legacyMemMb > 0) {
				installLegacyMemory(legacyMemMb);
				legacyMemMb = -1;
			}
			ScreenSources.note(this, level);
			manager.note(machineId, level.dimension().identifier().getPath() + " " + worldPosition.toShortString()); // for /vmc gc
			dev.virtualminecraft.bus.BusRegistry.record(level, this); // register the moment we exist (§9 U9)
			adoptOrphanedMonitors(level);
			sampleInputs(level, false);
			// A machine frozen for being idle stays a file until something wants it, across world loads too (§2);
			// one frozen by an unload, a server stop or a command comes back as soon as its chunk ticks.
			if (powered && manager.get(machineId) == null && "idle".equals(manager.frozenReason(machineId))) {
				frozen = true;
			}
		}
		// §9 U10(a): outside the first-tick block on purpose -- a case whose NBT is rewritten under it (a
		// /data merge, a schematic paste) is the same old case and still has to be fitted.
		if (!partsMigrated) {
			migrateParts();
		}
		// §9 U9: remember this machine, its cables and its run's components, so a peer whose chunk unloads
		// stays addressable and a woken machine still knows its far hardware (§9 U11b). Every five seconds --
		// it costs a flood fill, and a cable run rarely changes. (Until session 23 this beat sat inside the
		// first-tick block above, so it fired on roughly one machine in a hundred; the registry was being
		// filled by lookup side effects instead.)
		if ((level.getGameTime() + worldPosition.hashCode()) % 100 == 0) {
			dev.virtualminecraft.bus.BusRegistry.record(level, this);
		}
		// Once a second, and before the powered/frozen early returns: an off machine's case still wants to say
		// whether a screen is attached, and that is exactly when a player is looking at the case.
		if ((level.getGameTime() + worldPosition.hashCode()) % 20 == 0) {
			final boolean any = !linkedMonitors(level).isEmpty();
			if (any != hasMonitor) {
				hasMonitor = any;
				sync();
			}
			// §9 U10(a): and whether the case is missing hardware it cannot work without. Same beat, same reason —
			// this is exactly when a player is standing in front of it wondering why nothing happens.
			final String want = powered ? hardwareNotice() : null;
			if (!java.util.Objects.equals(want, noticeShown)) {
				noticeShown = want;
				if (want != null) {
					showNotice(level, want);
				} else {
					for (final MonitorBlockEntity m : linkedMonitors(level)) {
						m.setTextMode(false);
					}
					sync();
				}
			}
		}
		if (sleepGrace >= 0 && powered && --sleepGrace < 0) {
			dev.virtualminecraft.VirtualMinecraft.LOGGER.info("Computer '{}' at {}: redstone sleep, powering off", name, worldPosition.toShortString());
			setPowered(false);
			manager.remove(machineId, false);
		}
		if (!powered) {
			return;
		}
		if (frozen) {
			// lazy thaw: a viewer looking at one of our monitors is enough (events and commands thaw elsewhere)
			if (dev.virtualminecraft.screen.ScreenViewers.get(level.getServer()).anyone(machineId)) {
				thaw(level);
			}
			return;
		}
		// §9 U10(a): the parts are the machine, so an empty case never becomes one. It says why on its monitors
		// and in its GUI instead of sitting at "waiting" — HANDOFF (p) records what that one cost.
		if (!canBoot()) {
			return;
		}
		if (manager.get(machineId) == null || bootRequested) {
			bootRequested = false;
			try {
				manager.attach(this, level);
			} catch (final IllegalStateException refused) {
				// over the cap: powered off with a log line; a player powering it on by hand gets the sentence
			}
		}
	}

	/**
	 * §9 U10(a), the migration [name] accepted when she chose the dead box: a case saved before the correction was a
	 * complete machine while empty, so on its first tick it is handed the cheapest parts worth what it used to get
	 * for free. Only empty slots are filled — a case that already had a Graphics Card III keeps it — and the flag is
	 * written from here on, so this happens exactly once per case in a world.
	 */
	private void migrateParts() {
		partsMigrated = true;
		final int[] levels = MachineSpec.migrationLevels(tier());
		final StringBuilder fitted = new StringBuilder();
		loadingParts = true; // one restart for the case as it ends up, not one per slot
		try {
			for (final MachineSpec.Part kind : MachineSpec.Part.ALL) {
				final int slot = kind.ordinal();
				if (!parts.getItem(slot).isEmpty()) {
					continue;
				}
				final int level = Nums.clamp(levels[slot], 1, MachineSpec.LEVELS);
				parts.setItem(slot, new net.minecraft.world.item.ItemStack(ModContent.PARTS[slot][level - 1]));
				fitted.append(fitted.isEmpty() ? "" : ", ").append(kind).append(' ').append(level);
			}
		} finally {
			loadingParts = false;
		}
		setChanged();
		if (!fitted.isEmpty()) {
			dev.virtualminecraft.VirtualMinecraft.LOGGER.info("Computer '{}' at {}: fitting an old case with {} (U10)", name, worldPosition.toShortString(), fitted);
		}
		partsChanged();
	}

	/**
	 * The one thing this case is missing that a player has to fix, or null when it is whole (§9 U10(a)). A processor
	 * or memory is fatal — the machine never boots. A graphics card is not: the machine runs perfectly well with
	 * nothing to draw on, and only a monitor standing there needs telling. A missing drive is not a notice at all:
	 * the machine boots the ROM and says so itself, in the shell, where it can be read.
	 */
	private @Nullable String hardwareNotice() {
		final String refusal = bootRefusal();
		if (refusal != null) {
			return refusal;
		}
		return !spec().hasGraphics() && hasMonitor ? "no graphics card" : null;
	}

	/**
	 * What a case with hardware missing puts on its monitors: the reason, in the firmware voice the Command Computer
	 * already uses for the same job. Dark glass would be indistinguishable from a machine that is switched off.
	 */
	private void showNotice(final ServerLevel level, final String reason) {
		final boolean dead = bootRefusal() != null;
		for (final MonitorBlockEntity m : linkedMonitors(level)) {
			if (!m.isOrigin()) {
				continue; // the origin draws for the whole rectangle
			}
			final dev.virtualminecraft.bus.TextGrid g = m.textGrid();
			g.resize(dev.virtualminecraft.bus.TextGrid.DEFAULT_COLS, dev.virtualminecraft.bus.TextGrid.DEFAULT_ROWS);
			g.curFg = 0xFFFFFF;
			g.curBg = 0x000000;
			g.clear();
			int y = 1;
			g.set(1, y++, name);
			g.set(1, y++, MachineSpec.TIER_NAMES[tier() - 1]);
			y++;
			g.curFg = 0xFF5555;
			g.set(1, y++, reason.toUpperCase(java.util.Locale.ROOT));
			g.curFg = 0xFFFFFF;
			y++;
			if (dead) {
				g.set(1, y++, "Open the case and fit at least a");
				g.set(1, y++, "processor and memory. A graphics");
				g.set(1, y++, "card gives it a picture and a");
				g.set(1, y++, "drive gives it a /disk.");
			} else {
				g.set(1, y++, "The machine is running. Fit a");
				g.set(1, y++, "graphics card and it will have");
				g.set(1, y++, "somewhere to draw.");
			}
			m.markTextChanged(true);
		}
		sync();
	}

	/**
	 * First tick: take over any touching monitor still bound to a machine that no longer exists. On the first tick
	 * rather than in {@code setPlacedBy} because {@code /setblock} and {@code /fill} never call that one, and those
	 * are exactly how a computer gets replaced under a screen that is still standing.
	 */
	private void adoptOrphanedMonitors(final ServerLevel level) {
		final int adopted = MonitorBlockEntity.adoptOrphansAround(level, worldPosition);
		if (adopted > 0) {
			dev.virtualminecraft.VirtualMinecraft.LOGGER.info("Computer '{}' at {} adopted {} orphaned monitor(s)", name, worldPosition.toShortString(), adopted);
		}
	}

	/** Re-place this block as a Command Computer carrying the VM data that was saved under the old id. */
	private void migrateLegacy(final ServerLevel level) {
		final LegacyVm l = legacy;
		legacy = null;
		if (l == null) {
			return;
		}
		final BlockState old = getBlockState();
		final BlockState replacement = ModContent.COMPUTER.defaultBlockState().setValue(dev.virtualminecraft.block.ComputerBlock.FACING, old.getValue(LuaComputerBlock.FACING));
		dev.virtualminecraft.VirtualMinecraft.LOGGER.info("Migrating old VM block at {} (vm {}) to command_computer", worldPosition.toShortString(), l.vmId());
		level.setBlock(worldPosition, replacement, 3);
		if (level.getBlockEntity(worldPosition) instanceof dev.virtualminecraft.block.ComputerBlockEntity be) {
			be.adoptLegacy(l.vmId(), l.config(), l.outputs(), l.owner(), l.ownerName(), l.disks());
		}
	}

	// ---- BusHost ----

	@Override
	public UUID busId() {
		return machineId;
	}

	@Override
	public String busName() {
		return name;
	}

	@Override
	public Direction facing() {
		return getBlockState().getValue(LuaComputerBlock.FACING);
	}

	@Override
	public LinkedHashMap<BlockPos, String> attached(final ServerLevel level) {
		final long now = level.getGameTime();
		LinkedHashMap<BlockPos, String> cache = attachedCache;
		if (cache == null || attachedTick != now) {
			cache = BusNetwork.attached(level, worldPosition);
			attachedCache = cache;
			attachedTick = now;
		}
		return cache;
	}

	/** A bus call demand-loaded chunks mid-tick (§9 U11b): the cache above predates them, so recompute. */
	public void invalidateAttached() {
		attachedCache = null;
	}

	/** A disk went in or out on our bus: rebuild the mount table now, so the {@code disk_inserted} event and the
	 * mount it announces arrive together (the timer in {@link LuaComputer#tick} would be up to 8 ticks late). */
	@Override
	public void mediaChanged(final ServerLevel level) {
		final LuaComputer c = computer();
		if (c != null) {
			c.files().refresh(level, this);
		}
	}

	@Override
	public List<MonitorBlockEntity> linkedMonitors(final ServerLevel level) {
		final List<MonitorBlockEntity> out = new ArrayList<>();
		final Iterator<BlockPos> it = monitors.iterator();
		while (it.hasNext()) {
			final BlockPos p = it.next();
			if (!level.hasChunkAt(p)) {
				continue;
			}
			if (level.getBlockEntity(p) instanceof MonitorBlockEntity m && worldPosition.equals(m.getSourcePos())) {
				out.add(m);
			} else {
				it.remove();
			}
		}
		return out;
	}

	@Override
	public RateLimiter soundBudget() {
		RateLimiter r = soundBudget;
		if (r == null) {
			final float perSecond = Math.max(0.1f, VmcConfig.get().speakerSoundsPerSecond);
			r = new RateLimiter(perSecond, perSecond);
			soundBudget = r;
		}
		return r;
	}

	@Override
	public RateLimiter chatBudget() {
		RateLimiter r = chatBudget;
		if (r == null) {
			final float perMinute = Math.max(1f, VmcConfig.get().chatMessagesPerMinute);
			r = new RateLimiter(Math.max(2f, perMinute / 6f), perMinute / 60f);
			chatBudget = r;
		}
		return r;
	}

	@Override
	public RateLimiter netBudget() {
		RateLimiter r = netBudget;
		if (r == null) {
			final float perMinute = Math.max(1f, VmcConfig.get().netMessagesPerMinute);
			r = new RateLimiter(Math.max(10f, perMinute / 6f), perMinute / 60f);
			netBudget = r;
		}
		return r;
	}

	@Override
	public int getOutput(final Direction side) {
		return outputs[side.get3DDataValue()];
	}

	@Override
	public int setOutput(final Direction side, final int level) {
		final int lvl = Nums.clamp(level, 0, 15);
		final int prev = outputs[side.get3DDataValue()];
		if (prev == lvl) {
			return prev;
		}
		outputs[side.get3DDataValue()] = lvl;
		setChanged();
		if (this.level != null && !this.level.isClientSide()) {
			this.level.updateNeighborsAt(worldPosition, getBlockState().getBlock(), null);
			this.level.updateNeighborsAt(worldPosition.relative(side), getBlockState().getBlock(), null);
		}
		return prev;
	}

	public void clearOutputs() {
		boolean any = false;
		for (final Direction d : Direction.values()) {
			any |= outputs[d.get3DDataValue()] != 0;
		}
		if (!any) {
			return;
		}
		Arrays.fill(outputs, 0);
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.updateNeighborsAt(worldPosition, getBlockState().getBlock(), null);
			for (final Direction d : Direction.values()) {
				level.updateNeighborsAt(worldPosition.relative(d), getBlockState().getBlock(), null);
			}
		}
	}

	@Override
	public int getInput(final ServerLevel level, final Direction side) {
		return level.getSignal(worldPosition.relative(side), side);
	}

	@Override
	public int getWakeThreshold() {
		return wakeThreshold;
	}

	@Override
	public void setWakeThreshold(final int threshold) {
		wakeThreshold = Nums.clamp(threshold, 0, 15);
		if (wakeThreshold == 0) {
			sleepGrace = -1;
		}
		sync();
	}

	@Override
	public boolean getRedstoneSleep() {
		return redstoneSleep;
	}

	@Override
	public void setRedstoneSleep(final boolean on) {
		redstoneSleep = on;
		if (!on) {
			sleepGrace = -1;
		}
		sync();
	}

	@Override
	public boolean busReady() {
		// `frozen` is the flag the case shows; computer() is the running machine. Both have to be right: a
		// thawing machine reports neither frozen nor running for a tick or two.
		return !frozen && computer() != null;
	}

	@Override
	public void emitEvent(final String name, final JsonObject params) {
		if (frozen && powered && level instanceof ServerLevel sl && !"save".equals(name)) {
			thaw(sl); // an event is a reason to exist again
		}
		final LuaComputer c = computer();
		if (c != null) {
			c.event(name, params);
		}
	}

	public void onNeighborChanged(final ServerLevel level) {
		attachedCache = null;
		sampleInputs(level, true);
		sampleComponents(level);
	}

	private void sampleComponents(final ServerLevel level) {
		if (computer() == null) {
			lastComponents = null;
			return;
		}
		final Map<UUID, String> now = new HashMap<>();
		for (final Component c : Components.collect(level, this)) {
			now.put(c.address(), c.type() + "@" + c.location());
		}
		final Map<UUID, String> before = lastComponents;
		lastComponents = now;
		if (before == null) {
			return;
		}
		for (final Map.Entry<UUID, String> e : now.entrySet()) {
			if (!before.containsKey(e.getKey())) {
				emitEvent("component_added", componentEvent(e.getKey(), e.getValue()));
			}
		}
		for (final Map.Entry<UUID, String> e : before.entrySet()) {
			if (!now.containsKey(e.getKey())) {
				emitEvent("component_removed", componentEvent(e.getKey(), e.getValue()));
			}
		}
	}

	private static JsonObject componentEvent(final UUID address, final String typeAtLocation) {
		final JsonObject p = new JsonObject();
		final int at = typeAtLocation.indexOf('@');
		p.addProperty("address", address.toString());
		p.addProperty("type", typeAtLocation.substring(0, at));
		p.addProperty("location", typeAtLocation.substring(at + 1));
		return p;
	}

	private void sampleInputs(final ServerLevel level, final boolean fireEvents) {
		int max = 0;
		int maxBefore = 0;
		boolean sampledBefore = true;
		for (final Direction d : Direction.values()) {
			final int now = getInput(level, d);
			final int before = lastInputs[d.get3DDataValue()];
			lastInputs[d.get3DDataValue()] = now;
			max = Math.max(max, now);
			maxBefore = Math.max(maxBefore, before);
			sampledBefore &= before >= 0;
			if (fireEvents && before >= 0 && before != now) {
				final JsonObject p = new JsonObject();
				p.addProperty("address", Component.addressOf(machineId, RedstoneComponent.TYPE, "self").toString());
				p.addProperty("side", Sides.name(d));
				p.addProperty("level", now);
				p.addProperty("previous", before);
				emitEvent("redstone_changed", p);
			}
		}
		// Redstone wake / sleep, the VM tier's rule verbatim: edges only, so a line that is already high when the
		// chunk loads does not restart a machine that was shut down on purpose. (A change on a machine that is
		// merely *frozen* thaws it through emitEvent above; this is the powered-off case.)
		if (!fireEvents || !sampledBefore || wakeThreshold <= 0) {
			return;
		}
		final boolean above = max >= wakeThreshold;
		final boolean wasAbove = maxBefore >= wakeThreshold;
		if (above && !wasAbove) {
			sleepGrace = -1;
			if (!powered) {
				thaw(level);
			}
		} else if (!above && wasAbove && redstoneSleep && powered) {
			// ask first: the program saves and shuts itself down, and the grace pulls the plug if it does not
			final JsonObject p = new JsonObject();
			p.addProperty("reason", "redstone");
			emitEvent("power", p);
			sleepGrace = SLEEP_GRACE_TICKS;
		}
	}

	/** How long a {@code power} event has to shut the machine down itself before redstone sleep does it. */
	private static final int SLEEP_GRACE_TICKS = 100;

	// ---- ScreenSource ----

	// ScreenSource / BusHost position accessors under their own names: see ScreenSource's note on why these must not
	// be called getBlockPos / getBlockState / getLevel (the 1.20.1 remap would leave the interface methods unimplemented).
	@Override
	public BlockPos screenPos() {
		return getBlockPos();
	}

	@Override
	public BlockPos hostPos() {
		return getBlockPos();
	}

	@Override
	public BlockState hostState() {
		return getBlockState();
	}

	@Override
	public @Nullable Level hostLevel() {
		return getLevel();
	}

	@Override
	public UUID screenId() {
		return machineId;
	}

	@Override
	public String screenName() {
		return name;
	}

	@Override
	public void registerMonitor(final BlockPos pos) {
		monitors.add(pos.immutable());
	}

	@Override
	public void monitorTouched(final ServerLevel level, final MonitorBlockEntity monitor, final int cellX, final int cellY, final ServerPlayer player) {
		emitEvent("screen_touch", ScreenComponent.touchEvent(machineId, worldPosition, monitor.getBlockPos(), cellX, cellY, player.getName().getString()));
	}

	/** A live picture exists once the machine has drawn into its framebuffer (S2); until then the monitor is text mode. */
	@Override
	public boolean screenActive() {
		if (level instanceof ServerLevel) {
			// A case that cannot boot still lights its glass: showRefusal has written the reason into the text
			// grid, and dark glass would say "switched off" instead (§9 U10(a)).
			if (powered && hardwareNotice() != null) {
				return true;
			}
			final LuaComputer c = computer();
			return c != null && c.screen().active();
		}
		return status != null && status.startsWith("picture");
	}

	@Override
	public int[] screenSize() {
		final LuaComputer c = computer();
		return c == null ? new int[] { 0, 0 } : new int[] { c.screen().width(), c.screen().height() };
	}

	/** The resolution the linked monitors ask for: the largest linked rectangle, 256 px per block, fitted to 1024×768. */
	int[] monitorResolution(final ServerLevel level) {
		int bestW = 0;
		int bestH = 0;
		for (final MonitorBlockEntity m : linkedMonitors(level)) {
			final MonitorBlockEntity o = m.origin() != null ? m.origin() : m;
			if (o.mbW() * o.mbH() > bestW * bestH) {
				bestW = o.mbW();
				bestH = o.mbH();
			}
		}
		final MachineSpec spec = spec();
		if (!spec.hasGraphics()) {
			return new int[] { 0, 0 }; // §9 U10(a): no card, no framebuffer -- the same path as no monitor
		}
		return ScreenDevice.resolutionFor(bestW, bestH, spec.maxW(), spec.maxH());
	}

	/** The machine has drawn: its monitors leave text mode so the picture shows. */
	void pictureStarted(final ServerLevel level) {
		for (final MonitorBlockEntity m : linkedMonitors(level)) {
			if (m.isTextMode()) {
				m.setTextMode(false);
			}
		}
		sync(); // the client decides "click = pointer" or "click = open the view" from its copy of the status
	}

	@Override
	public void screenPaste(final ServerPlayer player, final String text) {
		final JsonObject p = new JsonObject();
		p.addProperty("player", player.getName().getString());
		p.addProperty("text", text);
		emitEvent("paste", p);
	}

	/** Keyboard and pointer from the full-screen view become {@code key} / {@code pointer} events for the kernel. */
	@Override
	public void screenInput(final ServerPlayer player, final List<VmInputPayload.Event> events) {
		final LuaComputer c = computer();
		if (c == null) {
			return;
		}
		for (final VmInputPayload.Event e : events) {
			final JsonObject p = new JsonObject();
			p.addProperty("player", player.getName().getString());
			switch (e.type()) {
				case VmInputPayload.KEY -> {
					p.addProperty("sym", e.a());
					p.addProperty("down", e.b() != 0);
					c.event("key", p);
				}
				case VmInputPayload.SCANCODE -> {
					p.addProperty("code", e.a());
					p.addProperty("down", e.b() != 0);
					c.event("scancode", p);
				}
				case VmInputPayload.CHAR -> {
					p.addProperty("cp", e.a());
					c.event("char", p);
				}
				case VmInputPayload.POINTER -> {
					final int mask = e.a();
					if ((mask & 0x78) != 0) {
						// RFB-style wheel buttons 4..7 as press/release pairs; only the press counts
						p.addProperty("dy", (mask & 0x08) != 0 ? 1 : (mask & 0x10) != 0 ? -1 : 0);
						p.addProperty("dx", (mask & 0x20) != 0 ? -1 : (mask & 0x40) != 0 ? 1 : 0);
						p.addProperty("x", e.b());
						p.addProperty("y", e.c());
						c.event("wheel", p);
					} else {
						p.addProperty("buttons", mask);
						p.addProperty("x", e.b());
						p.addProperty("y", e.c());
						c.event("pointer", p);
					}
				}
				default -> {
				}
			}
		}
	}

	// ---- persistence ----

	@Override
	protected void saveAdditional(final ValueOutput output) {
		super.saveAdditional(output);
		final LegacyVm l = legacy;
		if (l != null) {
			// not migrated yet (no tick since load): write the VM's data back out unchanged
			output.putString("vmId", l.vmId().toString());
			l.config().save(output.child("config"));
			output.putIntArray("outputs", l.outputs().clone());
			if (l.owner() != null) {
				output.putString("owner", l.owner().toString());
				output.putString("ownerName", l.ownerName());
			}
			final ValueOutput.TypedOutputList<net.minecraft.world.item.ItemStack> list = output.list("disks", net.minecraft.world.item.ItemStack.OPTIONAL_CODEC);
			for (final net.minecraft.world.item.ItemStack st : l.disks()) {
				list.add(st);
			}
			return;
		}
		output.putString("machineId", machineId.toString());
		output.putString("name", name);
		output.putInt("memMb", spec().memMb());
		output.putInt("desktop", desktopMode);
		output.putInt("wakeThreshold", wakeThreshold);
		output.putBoolean("redstoneSleep", redstoneSleep);
		parts.storeAsItemList(output.list("parts", net.minecraft.world.item.ItemStack.OPTIONAL_CODEC));
		output.putBoolean("partsMigrated", partsMigrated);
		output.putBoolean("powered", powered);
		output.putIntArray("outputs", outputs.clone());
		output.putString("status", (screenActive() ? "picture " : "") + status());
		output.putBoolean("hasMonitor", hasMonitor);
		if (owner != null) {
			output.putString("owner", owner.toString());
			output.putString("ownerName", ownerName);
		}
	}

	@Override
	protected void loadAdditional(final ValueInput input) {
		super.loadAdditional(input);
		if (input.getString("vmId").isPresent()) {
			// An old VM block (ROADMAP §7h rule 11): keep everything the VM block entity would have read.
			UUID vmId = UUID.randomUUID();
			try {
				vmId = UUID.fromString(input.getString("vmId").orElseThrow());
			} catch (final IllegalArgumentException ignored) {
			}
			final dev.virtualminecraft.vm.VmConfig cfg = new dev.virtualminecraft.vm.VmConfig();
			input.child("config").ifPresent(cfg::load);
			final int[] outs = new int[6];
			input.getIntArray("outputs").ifPresent(a -> System.arraycopy(a, 0, outs, 0, Math.min(6, a.length)));
			UUID legacyOwner = null;
			try {
				legacyOwner = input.getString("owner").map(UUID::fromString).orElse(null);
			} catch (final IllegalArgumentException ignored) {
			}
			final List<net.minecraft.world.item.ItemStack> disks = new ArrayList<>();
			for (final net.minecraft.world.item.ItemStack st : input.listOrEmpty("disks", net.minecraft.world.item.ItemStack.OPTIONAL_CODEC)) {
				disks.add(st);
			}
			legacy = new LegacyVm(vmId, cfg, outs, legacyOwner, input.getStringOr("ownerName", ""), disks);
			powered = false; // never boot a Lua machine on a VM's data
			return;
		}
		input.getString("machineId").ifPresent(s -> {
			try {
				machineId = UUID.fromString(s);
			} catch (final IllegalArgumentException ignored) {
			}
		});
		name = input.getStringOr("name", "computer");
		syncedMemMb = Nums.clamp(input.getIntOr("memMb", VmcConfig.get().computerMemMb), 1, 64);
		desktopMode = Nums.clamp(input.getIntOr("desktop", 0), 0, 2);
		wakeThreshold = Nums.clamp(input.getIntOr("wakeThreshold", 0), 0, 15);
		redstoneSleep = input.getBooleanOr("redstoneSleep", false);
		final java.util.Optional<net.minecraft.world.level.storage.ValueInput.TypedInputList<net.minecraft.world.item.ItemStack>> saved = input.list("parts", net.minecraft.world.item.ItemStack.OPTIONAL_CODEC);
		if (saved.isPresent()) {
			loadParts(saved.get());
		} else {
			// a world from before the ladder: the block's memory becomes a RAM part on its first tick
			legacyMemMb = syncedMemMb;
		}
		// §9 U10(a): no flag means the save predates "a case is only a ceiling", when an empty case was a whole
		// machine. Those cases get parts on their first tick; anything saved since says so and is left alone.
		partsMigrated = input.getBooleanOr("partsMigrated", false);
		powered = input.getBooleanOr("powered", true);
		status = input.getStringOr("status", "off");
		hasMonitor = input.getBooleanOr("hasMonitor", false);
		owner = null;
		input.getString("owner").ifPresent(o -> {
			try {
				owner = UUID.fromString(o);
			} catch (final IllegalArgumentException ignored) {
			}
		});
		ownerName = input.getStringOr("ownerName", "");
		Arrays.fill(outputs, 0);
		input.getIntArray("outputs").ifPresent(a -> {
			for (int i = 0; i < Math.min(6, a.length); i++) {
				outputs[i] = Nums.clamp(a[i], 0, 15);
			}
		});
	}

	/**
	 * The saved list is the four slots with the empty ones dropped ({@code SimpleContainer.storeAsItemList}), and
	 * its {@code fromItemList} counterpart packs what it reads from slot 0 up — so a case holding only a graphics
	 * card saved it and read it back into the RAM slot, where {@code levelOf} sees a part of the wrong kind and
	 * reports nothing. The part was visible in the case and did nothing. A part item knows its own kind, so the
	 * slot never has to be written down: put each one where it belongs and the old saves are read correctly too.
	 */
	private void loadParts(final net.minecraft.world.level.storage.ValueInput.TypedInputList<net.minecraft.world.item.ItemStack> saved) {
		loadingParts = true; // one restart for the case as it ends up, not one per slot touched on the way
		try {
			parts.clearContent();
			for (final net.minecraft.world.item.ItemStack stack : saved) {
				if (stack.isEmpty()) {
					continue;
				}
				if (stack.getItem() instanceof dev.virtualminecraft.item.PartItem part && parts.getItem(part.part().ordinal()).isEmpty()) {
					parts.setItem(part.part().ordinal(), stack);
				} else {
					parts.addItem(stack); // not a part, or its slot is taken: keep it rather than lose it
				}
			}
		} finally {
			loadingParts = false;
		}
		partsChanged();
	}

	@Override
	public void preRemoveSideEffects(final BlockPos pos, final BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof ServerLevel serverLevel) {
			// freeze so the machine comes back wherever the item is placed next
			ComputerManager.get(serverLevel.getServer()).remove(machineId, true);
			net.minecraft.world.Containers.dropContents(serverLevel, pos, parts);
			LuaComputerBlock.dropWithId(serverLevel, pos, machineId, name, tier());
		}
	}

	@Override
	public CompoundTag getUpdateTag(final HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public @Nullable ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
