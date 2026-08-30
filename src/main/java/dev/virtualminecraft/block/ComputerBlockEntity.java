package dev.virtualminecraft.block;

import dev.virtualminecraft.screen.ScreenSource;
import dev.virtualminecraft.screen.ScreenSources;
import com.google.gson.JsonObject;
import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.bus.BusNetwork;
import dev.virtualminecraft.bus.Component;
import dev.virtualminecraft.bus.Components;
import dev.virtualminecraft.bus.RateLimiter;
import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.bus.RedstoneComponent;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import dev.virtualminecraft.bus.Sides;
import dev.virtualminecraft.bus.ScreenComponent;
import dev.virtualminecraft.bus.VmBus;
import dev.virtualminecraft.util.Nums;
import dev.virtualminecraft.vm.VmInstance;
import dev.virtualminecraft.vm.Attachment;
import dev.virtualminecraft.vm.Attachments;
import dev.virtualminecraft.item.DiskItem;
import dev.virtualminecraft.bus.TextGrid;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.Arrays;
import net.minecraft.core.Direction;
import dev.virtualminecraft.vm.VmConfig;
import dev.virtualminecraft.vm.VmManager;
import dev.virtualminecraft.vm.VmStatus;
import net.minecraft.core.BlockPos;
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

public class ComputerBlockEntity extends BlockEntity implements ScreenSource, dev.virtualminecraft.bus.BusHost {
	private UUID vmId = UUID.randomUUID();
	private VmConfig config = new VmConfig();
	private VmStatus status = VmStatus.STOPPED;
	private String statusMessage = "";
	private boolean autostartChecked;
	/** Once per load: tell {@link ScreenSources} where this screen id lives. */
	private boolean sourceNoted;
	/** Redstone level emitted from each face, indexed by {@link Direction#get3DDataValue()}. Set by the guest over the bus. */
	private final int[] outputs = new int[6];
	/** Last observed input per face, for {@code redstone_changed} events; -1 = not sampled yet. */
	private final int[] lastInputs = { -1, -1, -1, -1, -1, -1 };
	/** Monitors that registered themselves as linked to this computer (server only; rebuilt as monitors tick). */
	private final java.util.Set<BlockPos> monitors = new java.util.LinkedHashSet<>();
	/** Hard drives / CDs inside the case, in boot order. Synced to clients (config screen); dropped when the block breaks. */
	public static final int DISK_SLOTS = 3;
	private final NonNullList<ItemStack> disks = NonNullList.withSize(DISK_SLOTS, ItemStack.EMPTY);
	/** The "BIOS" text screen is on the linked monitors; cleared when the VM next reaches RUNNING. */
	private boolean firmwareShown;
	/** Who placed this computer. Null on blocks placed before ownership existed, and on {@code /setblock}.
	 * Today it only drives the owner-offline suspend policy; permissions and quotas come with milestone 6. */
	private @Nullable UUID owner;
	private String ownerName = "";
	/** Where this computer's components may sit (see {@link BusNetwork}); rebuilt at most once per tick. */
	private @Nullable LinkedHashMap<BlockPos, String> attachedCache;
	private long attachedTick = Long.MIN_VALUE;
	/** Budgets for the components that reach out at players; transient, so a reload forgives a spammer. */
	private @Nullable RateLimiter soundBudget;
	private @Nullable RateLimiter chatBudget;

	private @Nullable RateLimiter netBudget;

	/**
	 * Positions that may hold components: the six neighbours plus everything on a connected bus cable run.
	 * Cached for the tick because every {@code list}/{@code invoke} asks and the cable fill is a flood fill.
	 */
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

	/** Sounds per second the guest's speaker may play (see {@link dev.virtualminecraft.bus.SpeakerComponent}). */
	public RateLimiter soundBudget() {
		RateLimiter r = soundBudget;
		if (r == null) {
			final float perSecond = Math.max(0.1f, VmcConfig.get().speakerSoundsPerSecond);
			r = new RateLimiter(perSecond, perSecond);
			soundBudget = r;
		}
		return r;
	}

	/** Chat messages the guest may send, as a small burst plus a slow refill. */
	public RateLimiter chatBudget() {
		RateLimiter r = chatBudget;
		if (r == null) {
			final float perMinute = Math.max(1f, VmcConfig.get().chatMessagesPerMinute);
			r = new RateLimiter(Math.max(2f, perMinute / 6f), perMinute / 60f);
			chatBudget = r;
		}
		return r;
	}

	/** {@code net} messages the guest may send: a burst of ten seconds' worth, then the per-minute rate. */
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
	public void registerMonitor(final BlockPos pos) {
		monitors.add(pos.immutable());
	}

	// ---- ScreenSource: what a monitor needs from us ----

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
		return vmId;
	}

	@Override
	public String screenName() {
		return config.name;
	}

	@Override
	public boolean screenActive() {
		return status == VmStatus.RUNNING;
	}

	@Override
	public int[] screenSize() {
		final VmInstance vm = level instanceof ServerLevel sl ? VmManager.get(sl.getServer()).get(vmId) : null;
		return vm == null ? new int[] { 0, 0 } : vm.screenSize();
	}

	@Override
	public void screenInput(final ServerPlayer player, final java.util.List<dev.virtualminecraft.net.VmInputPayload.Event> events) {
		final VmInstance vm = level instanceof ServerLevel sl ? VmManager.get(sl.getServer()).get(vmId) : null;
		if (vm != null && vm.mayControl(player)) {
			vm.input(events);
		}
	}

	/** A touch on a text-mode monitor becomes a {@code screen_touch} bus event for the guest. */
	@Override
	public void monitorTouched(final ServerLevel level, final MonitorBlockEntity monitor, final int cellX, final int cellY, final ServerPlayer player) {
		final VmInstance vm = VmManager.get(level.getServer()).get(vmId);
		final VmBus bus = vm == null ? null : vm.bus();
		if (bus != null) {
			bus.event("screen_touch", ScreenComponent.touchEvent(vmId, worldPosition, monitor.getBlockPos(), cellX, cellY, player.getName().getString()));
		}
	}

	/** Loaded monitors still linked to this computer; stale entries are dropped on the way. */
	public java.util.List<MonitorBlockEntity> linkedMonitors(final ServerLevel level) {
		final java.util.List<MonitorBlockEntity> out = new java.util.ArrayList<>();
		final java.util.Iterator<BlockPos> it = monitors.iterator();
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

	/** Components seen at the last neighbour change (address → "type@location"), for hot-plug events; null = not tracking. */
	private @Nullable Map<UUID, String> lastComponents;

	public ComputerBlockEntity(final BlockPos pos, final BlockState state) {
		super(ModContent.COMPUTER_BLOCK_ENTITY, pos, state);
	}

	/**
	 * Migration from worlds saved before the Computer existed (the VM block's id was {@code computer}; it is now
	 * {@code command_computer}): {@link dev.virtualminecraft.computer.LuaComputerBlockEntity} recognises the old
	 * NBT, re-places the block as a Command Computer and hands the data over here.
	 */
	public void adoptLegacy(final UUID vmId, final VmConfig config, final int[] outputs, final @Nullable UUID owner, final String ownerName, final java.util.List<ItemStack> disks) {
		this.vmId = vmId;
		this.config = config;
		System.arraycopy(outputs, 0, this.outputs, 0, Math.min(6, outputs.length));
		this.owner = owner;
		this.ownerName = ownerName;
		for (int i = 0; i < Math.min(DISK_SLOTS, disks.size()); i++) {
			this.disks.set(i, disks.get(i));
		}
		setChanged();
	}

	// ---- BusHost: what the components need, independent of the tier ----
	@Override
	public UUID busId() {
		return vmId;
	}

	@Override
	public String busName() {
		return config.name;
	}

	@Override
	public Direction facing() {
		return getBlockState().getValue(ComputerBlock.FACING);
	}

	public UUID getVmId() {
		return vmId;
	}

	public @Nullable UUID getOwner() {
		return owner;
	}

	public String getOwnerName() {
		return ownerName;
	}

	public void setOwner(final UUID id, final String name) {
		this.owner = id;
		this.ownerName = name == null ? "" : name;
		setChanged();
	}

	public VmConfig getConfig() {
		return config;
	}

	public VmStatus getStatus() {
		return status;
	}

	public String getStatusMessage() {
		return statusMessage;
	}

	public boolean isRunning() {
		return status == VmStatus.RUNNING || status == VmStatus.STARTING;
	}

	@Override
	public int getWakeThreshold() {
		return config.wakeThreshold;
	}

	@Override
	public void setWakeThreshold(final int threshold) {
		final VmConfig cfg = config.copy();
		cfg.wakeThreshold = Nums.clamp(threshold, 0, 15);
		setConfig(cfg);
	}

	@Override
	public boolean getRedstoneSleep() {
		return config.redstoneSleep;
	}

	@Override
	public void setRedstoneSleep(final boolean on) {
		final VmConfig cfg = config.copy();
		cfg.redstoneSleep = on;
		setConfig(cfg);
	}

	public void setConfig(final VmConfig cfg) {
		cfg.sanitize();
		this.config = cfg.copy();
		if (level instanceof ServerLevel serverLevel) {
			final VmInstance vm = VmManager.get(serverLevel.getServer()).get(vmId);
			if (vm != null) {
				vm.updateConfig(config); // runtime flags (suspend) follow the GUI; hardware settings apply at the next start
			}
		}
		sync();
	}

	/** Server side: update the displayed status; syncs to clients when it changed. */
	public void setStatus(final VmStatus status, final @Nullable String message) {
		final String msg = message == null ? "" : message;
		if (this.status == status && this.statusMessage.equals(msg)) {
			return;
		}
		this.status = status;
		this.statusMessage = msg;
		if (status == VmStatus.RUNNING && firmwareShown) {
			hideFirmware();
		}
		sync();
	}

	// ---- Disks (server thread) ----

	public java.util.List<ItemStack> getDisks() {
		return disks;
	}

	public boolean hasDisks() {
		for (final ItemStack s : disks) {
			if (!s.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	public int freeDiskSlot() {
		for (int i = 0; i < disks.size(); i++) {
			if (disks.get(i).isEmpty()) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Disks inside the case change only while the VM is not alive. A suspended computer loses its saved state
	 * (the snapshot spans every attached qcow2), which the player is told about.
	 */
	public boolean disksChangeable(final ServerLevel level, final @Nullable Player player) {
		final VmManager manager = VmManager.get(level.getServer());
		final VmInstance vm = manager.get(vmId);
		if (vm != null && vm.isAlive()) {
			if (player != null) {
				player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("virtualminecraft.msg.stop_first"));
			}
			return false;
		}
		if (manager.hasSnapshot(vmId)) {
			manager.forceStop(this, null); // discards the snapshot; status → STOPPED
			if (player != null) {
				player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("virtualminecraft.msg.snapshot_discarded"));
			}
		}
		return true;
	}

	/** Puts {@code one} (a single hard drive or CD) into the first free slot. */
	public boolean insertDisk(final ItemStack one) {
		final int i = freeDiskSlot();
		if (i < 0 || !DiskItem.isDisk(one)) {
			return false;
		}
		DiskItem.ensureData(one);
		disks.set(i, one);
		sync();
		return true;
	}

	/** Takes out the disk in the highest occupied slot (empty stack if none). */
	public ItemStack ejectLastDisk() {
		for (int i = disks.size() - 1; i >= 0; i--) {
			if (!disks.get(i).isEmpty()) {
				final ItemStack out = disks.get(i);
				disks.set(i, ItemStack.EMPTY);
				sync();
				return out;
			}
		}
		return ItemStack.EMPTY;
	}

	/** Pushes a bus event if the guest subscribed to it (no-op without a live bus). */
	public void emitEvent(final String name, final JsonObject params) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		final VmInstance vm = VmManager.get(serverLevel.getServer()).get(vmId);
		final VmBus bus = vm == null ? null : vm.bus();
		if (bus != null && bus.wantsEvent(name)) {
			bus.event(name, params);
		}
	}

	// ---- "BIOS": what the monitors show when there is nothing to boot ----

	/** Draws a firmware-style screen (text mode) on every linked monitor listing the boot devices and the problem. */
	public void showFirmware(final ServerLevel level, final java.util.List<Attachment> bootOrder, final String reason) {
		firmwareShown = true;
		for (final MonitorBlockEntity m : linkedMonitors(level)) {
			if (!m.isOrigin()) {
				continue; // the origin draws for the whole rectangle
			}
			final TextGrid g = m.textGrid();
			g.resize(TextGrid.DEFAULT_COLS, TextGrid.DEFAULT_ROWS);
			g.curFg = 0xFFFFFF;
			g.curBg = 0x0000AA;
			g.clear();
			int y = 0;
			g.set(1, y++, "VirtualMinecraft BIOS");
			y++;
			g.set(1, y++, "Computer: " + config.name);
			g.set(1, y++, "Memory: " + config.memMb + " MB   CPUs: " + config.cpus);
			y++;
			g.set(1, y++, "Boot devices:");
			if (bootOrder.isEmpty()) {
				g.set(3, y++, "(none)");
			}
			int n = 1;
			for (final Attachment a : bootOrder) {
				if (y >= TextGrid.DEFAULT_ROWS - 5) {
					break;
				}
				g.set(3, y++, n++ + ". " + a.id() + "  " + a.label());
			}
			y++;
			g.curFg = 0xFFFF55;
			g.set(1, y++, reason);
			g.curFg = 0xFFFFFF;
			g.set(1, y++, "Insert a disk (hard drive, CD or a");
			g.set(1, y++, "floppy in a disk drive) and Start.");
			m.markTextChanged(true);
		}
		sync();
	}

	private void hideFirmware() {
		firmwareShown = false;
		if (level instanceof ServerLevel serverLevel) {
			for (final MonitorBlockEntity m : linkedMonitors(serverLevel)) {
				m.setTextMode(false);
			}
		}
	}

	private void sync() {
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
		}
	}

	void serverTick(final ServerLevel level) {
		if (!sourceNoted) {
			sourceNoted = true;
			ScreenSources.note(this, level);
			// So /vmc gc can say which VM directory belongs to a block that is really there (HANDOFF next-work 1c).
			dev.virtualminecraft.computer.ComputerManager.get(level.getServer())
				.note(vmId, level.dimension().identifier().getPath() + " " + worldPosition.toShortString());
			// A monitor left behind by the machine that used to stand here is adopted rather than left dead; see
			// MonitorBlockEntity.adoptOrphansAround. First tick, not setPlacedBy: /setblock never calls that one.
			final int adopted = MonitorBlockEntity.adoptOrphansAround(level, worldPosition);
			if (adopted > 0) {
				dev.virtualminecraft.VirtualMinecraft.LOGGER.info("Command Computer at {} adopted {} orphaned monitor(s)", worldPosition.toShortString(), adopted);
			}
		}
		if (!autostartChecked) {
			autostartChecked = true;
			final VmManager manager = VmManager.get(level.getServer());
			manager.attach(this);
			final VmInstance vm = manager.get(vmId);
			if (vm == null || !vm.isAlive()) {
				final boolean suspended = manager.hasSnapshot(vmId);
				if (suspended || status == VmStatus.RUNNING || status == VmStatus.STARTING) {
					// A status persisted from a previous session is stale (loadAdditional runs before level is set).
					setStatus(suspended ? VmStatus.SUSPENDED : VmStatus.STOPPED, suspended ? "Suspended" : "");
				}
				if (suspended || config.autostart) {
					manager.start(this, null); // resumes the snapshot if there is one
				}
			}
			sampleInputs(level, false);
		}
	}

	// ---- Redstone (server thread; see bus/RedstoneComponent) ----

	public int getOutput(final Direction side) {
		return outputs[side.get3DDataValue()];
	}

	/** Sets what {@code side} emits and notifies neighbours. Returns the previous level. */
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

	/** Redstone arriving at {@code side} right now (includes signal echoed back by dust we power). */
	public int getInput(final ServerLevel level, final Direction side) {
		return level.getSignal(worldPosition.relative(side), side);
	}

	public void onNeighborChanged(final ServerLevel level) {
		attachedCache = null; // a block just changed: the hot-plug diff below must not see this tick's stale list
		sampleInputs(level, true);
		sampleComponents(level);
	}

	/** Fires {@code component_added} / {@code component_removed} when the set of adjacent components changes. */
	private void sampleComponents(final ServerLevel level) {
		final VmInstance vm = VmManager.get(level.getServer()).get(vmId);
		final VmBus bus = vm == null ? null : vm.bus();
		if (bus == null || !(bus.wantsEvent("component_added") || bus.wantsEvent("component_removed"))) {
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
			return; // first sample after subscribing: nothing to compare with
		}
		for (final Map.Entry<UUID, String> e : now.entrySet()) {
			if (!before.containsKey(e.getKey())) {
				bus.event("component_added", componentEvent(e.getKey(), e.getValue()));
			}
		}
		for (final Map.Entry<UUID, String> e : before.entrySet()) {
			if (!now.containsKey(e.getKey())) {
				bus.event("component_removed", componentEvent(e.getKey(), e.getValue()));
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
		final VmManager manager = VmManager.get(level.getServer());
		final VmInstance vm = manager.get(vmId);
		final VmBus bus = vm == null ? null : vm.bus();
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
			if (fireEvents && before >= 0 && before != now && bus != null && bus.wantsEvent("redstone_changed")) {
				final JsonObject p = new JsonObject();
				p.addProperty("address", dev.virtualminecraft.bus.Component.addressOf(vmId, RedstoneComponent.TYPE, "self").toString());
				p.addProperty("side", Sides.name(d));
				p.addProperty("level", now);
				p.addProperty("previous", before);
				bus.event("redstone_changed", p);
			}
		}
		// Redstone wake / sleep (OpenComputers' wake threshold): edges only, so a level that is already high
		// when the chunk loads does not restart a computer that was shut down on purpose.
		if (fireEvents && sampledBefore && config.wakeThreshold > 0) {
			final boolean above = max >= config.wakeThreshold;
			final boolean wasAbove = maxBefore >= config.wakeThreshold;
			final boolean alive = vm != null && vm.isAlive();
			if (above && !wasAbove && !alive) {
				manager.start(this, null);
			} else if (!above && wasAbove && alive && config.redstoneSleep) {
				vm.acpiShutdown();
			}
		}
	}

	@Override
	protected void saveAdditional(final ValueOutput output) {
		super.saveAdditional(output);
		output.putString("vmId", vmId.toString());
		config.save(output.child("config"));
		output.putInt("status", status.ordinal());
		output.putString("statusMessage", statusMessage);
		output.putIntArray("outputs", outputs.clone());
		if (owner != null) {
			output.putString("owner", owner.toString());
			output.putString("ownerName", ownerName);
		}
		final ValueOutput.TypedOutputList<ItemStack> list = output.list("disks", ItemStack.OPTIONAL_CODEC);
		for (final ItemStack s : disks) {
			list.add(s);
		}
	}

	@Override
	protected void loadAdditional(final ValueInput input) {
		super.loadAdditional(input);
		input.getString("vmId").ifPresent(s -> {
			try {
				vmId = UUID.fromString(s);
			} catch (final IllegalArgumentException ignored) {
			}
		});
		final VmConfig cfg = new VmConfig();
		input.child("config").ifPresent(cfg::load);
		config = cfg;
		owner = null;
		input.getString("owner").ifPresent(o -> {
			try {
				owner = UUID.fromString(o);
			} catch (final IllegalArgumentException ignored) {
			}
		});
		ownerName = input.getStringOr("ownerName", "");
		status = VmStatus.byOrdinal(input.getIntOr("status", 0));
		statusMessage = input.getStringOr("statusMessage", "");
		Arrays.fill(outputs, 0);
		input.getIntArray("outputs").ifPresent(a -> {
			for (int i = 0; i < Math.min(6, a.length); i++) {
				outputs[i] = Nums.clamp(a[i], 0, 15);
			}
		});
		java.util.Collections.fill(disks, ItemStack.EMPTY);
		int slot = 0;
		for (final ItemStack s : input.listOrEmpty("disks", ItemStack.OPTIONAL_CODEC)) {
			if (slot < DISK_SLOTS) {
				disks.set(slot++, s);
			}
		}
		if (level != null && !level.isClientSide() && status != VmStatus.STOPPED && status != VmStatus.ERROR) {
			// A status persisted from a previous session is stale: the process is gone.
			status = VmStatus.STOPPED;
			statusMessage = "";
		}
	}

	@Override
	public void preRemoveSideEffects(final BlockPos pos, final BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof ServerLevel serverLevel) {
			VmManager.get(serverLevel.getServer()).remove(vmId, true);
			Containers.dropContents(serverLevel, pos, disks);
			java.util.Collections.fill(disks, ItemStack.EMPTY);
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
