package dev.virtualminecraft.block;

import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.bus.TextGrid;
import dev.virtualminecraft.net.ScreenTextPayload;
import dev.virtualminecraft.screen.ScreenSource;
import dev.virtualminecraft.screen.ScreenViewers;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import dev.virtualminecraft.util.Nbt;
import org.jspecify.annotations.Nullable;

/**
 * Shows the screen of the {@link ScreenSource} it is linked to. Knows nothing about what the source is: it asks
 * for a screen UUID, renders whatever frames arrive under it, and hands touches back to the source.
 */
public class MonitorBlockEntity extends BlockEntity {
	public static final int LINK_RADIUS = 8;

	private @Nullable BlockPos sourcePos;
	/**
	 * Multi-block position: this block's index within its rectangle of monitors (x to screen-right, y up, origin at
	 * the bottom-left) and the rectangle's size. Singles are {@code 0,0,1,1}. Assigned by {@link MonitorMultiblock};
	 * the origin owns the picture, the text grid and the rendering for the whole rectangle.
	 */
	private int mbX;
	private int mbY;
	private int mbW = 1;
	private int mbH = 1;
	/** Text-mode grid (bus {@code screen} component); created on first use, kept on both sides. */
	private @Nullable TextGrid grid;
	private boolean textMode;
	private boolean textDirty;
	private boolean registered;
	/** Viewers who hold the current grid; anyone watching who is not in here gets every row, not just the dirty ones. */
	private final Set<UUID> textSynced = new HashSet<>();

	public MonitorBlockEntity(final BlockPos pos, final BlockState state) {
		super(ModContent.MONITOR_BLOCK_ENTITY, pos, state);
	}

	public @Nullable BlockPos getSourcePos() {
		return sourcePos;
	}

	/** The linked source, if it is loaded (works on both sides). */
	public @Nullable ScreenSource getSource() {
		if (sourcePos == null || level == null) {
			return null;
		}
		return level.getBlockEntity(sourcePos) instanceof ScreenSource s ? s : null;
	}

	/** The UUID this monitor's frames are streamed under, or null while the source is unloaded or missing. */
	public @Nullable UUID getScreenId() {
		final ScreenSource s = getSource();
		return s == null ? null : s.screenId();
	}

	// ---- multi-block ----

	public int mbX() {
		return mbX;
	}

	public int mbY() {
		return mbY;
	}

	public int mbW() {
		return mbW;
	}

	public int mbH() {
		return mbH;
	}

	public boolean isOrigin() {
		return mbX == 0 && mbY == 0;
	}

	/** The bottom-left monitor of this block's rectangle (itself for a single); null if that block is not loaded. */
	public @Nullable MonitorBlockEntity origin() {
		if (isOrigin()) {
			return this;
		}
		if (level == null) {
			return null;
		}
		final Direction right = getBlockState().getValue(MonitorBlock.FACING).getCounterClockWise();
		final BlockPos o = worldPosition.relative(right, -mbX).below(mbY);
		return level.getBlockEntity(o) instanceof MonitorBlockEntity m ? m : null;
	}

	/** Server: {@link MonitorMultiblock} assigns the rectangle; a change is synced to clients. */
	void setMultiblock(final int x, final int y, final int w, final int h) {
		if (mbX == x && mbY == y && mbW == w && mbH == h) {
			return;
		}
		mbX = x;
		mbY = y;
		mbW = w;
		mbH = h;
		textSynced.clear();
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
		}
	}

	// ---- text mode (server writes, both sides read) ----

	public TextGrid textGrid() {
		if (grid == null) {
			grid = new TextGrid(TextGrid.DEFAULT_COLS, TextGrid.DEFAULT_ROWS);
		}
		return grid;
	}

	public @Nullable TextGrid textGridOrNull() {
		return grid;
	}

	public boolean isTextMode() {
		return textMode;
	}

	public void setTextMode(final boolean on) {
		if (on) {
			textGrid();
		}
		if (textMode != on) {
			textMode = on;
			textDirty = true;
			setChanged();
		}
	}

	/** Server: a drawing call happened; switch to text mode if asked and flush on the next tick. */
	public void markTextChanged(final boolean enableTextMode) {
		if (enableTextMode && !textMode) {
			setTextMode(true);
		}
		textDirty = true;
		setChanged();
	}

	/**
	 * Server tick: register with the source once, then push text rows to the players <em>watching this screen</em>
	 * — the same viewer set the framebuffer uses, so a text screen in a busy chunk costs nothing to the players
	 * who cannot see it. A viewer seen for the first time gets the whole grid; after that only dirty rows.
	 */
	void serverTick(final ServerLevel level) {
		final ScreenSource source = getSource();
		// Re-registered on a beat rather than once for the life of the block entity: a source whose chunk reloaded
		// while this one stayed loaded comes back with an empty monitor set, and a one-shot flag on this side would
		// leave it empty for good — an invisible screen, and a case GUI that says "no monitor" about a monitor that
		// is right there. Adding to a set the source already holds costs nothing.
		if (source != null && (!registered || level.getGameTime() % 20 == 0)) {
			registered = true;
			source.registerMonitor(worldPosition);
		}
		if (grid == null || source == null) {
			return;
		}
		final var viewers = ScreenViewers.get(level.getServer()).of(source.screenId());
		if (viewers.isEmpty()) {
			// Nobody watching: keep the dirty state, forget who was synced, and send the lot when someone comes back.
			textSynced.clear();
			return;
		}
		final boolean changed = textDirty || grid.hasDirty();
		textDirty = false;
		BitSet rows = changed ? grid.takeDirtyRows() : new BitSet();
		if (changed && grid.takeDirtySize()) {
			rows = new BitSet();
			rows.set(0, grid.rows);
		}
		final ScreenTextPayload dirtyPayload = changed ? ScreenTextPayload.of(worldPosition, textMode, grid, rows) : null;
		ScreenTextPayload fullPayload = null;
		final Set<UUID> watching = new HashSet<>();
		for (final ScreenViewers.Viewer v : viewers) {
			final UUID id = v.player.getUUID();
			watching.add(id);
			if (textSynced.add(id)) {
				if (fullPayload == null) {
					final BitSet all = new BitSet();
					all.set(0, grid.rows);
					fullPayload = ScreenTextPayload.of(worldPosition, textMode, grid, all);
				}
				dev.virtualminecraft.net.ModNetworking.send(v.player, fullPayload);
			} else if (dirtyPayload != null) {
				dev.virtualminecraft.net.ModNetworking.send(v.player, dirtyPayload);
			}
		}
		textSynced.retainAll(watching);
	}

	/** Client: apply a text payload. */
	public void applyScreenText(final ScreenTextPayload payload) {
		if (grid == null || grid.cols != payload.cols() || grid.rows != payload.rows()) {
			grid = new TextGrid(payload.cols(), payload.rows());
		}
		textMode = payload.textMode();
		payload.applyTo(grid);
	}

	public void setSourcePos(final @Nullable BlockPos pos) {
		this.sourcePos = pos == null ? null : pos.immutable();
		registered = false;
		textSynced.clear();
		setChanged();
		if (level instanceof ServerLevel serverLevel) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
			// A monitor groups only with monitors showing the same source, so a relink can split or join rectangles.
			MonitorMultiblock.rebuildAround(serverLevel, worldPosition, getBlockState());
		}
	}

	/** Server side: link to the nearest {@link ScreenSource} within {@link #LINK_RADIUS}. */
	public boolean linkToNearestSource() {
		if (level == null) {
			return false;
		}
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (final BlockPos p : BlockPos.betweenClosed(worldPosition.offset(-LINK_RADIUS, -LINK_RADIUS, -LINK_RADIUS), worldPosition.offset(LINK_RADIUS, LINK_RADIUS, LINK_RADIUS))) {
			if (!level.getBlockState(p).hasBlockEntity() || !(level.getBlockEntity(p) instanceof ScreenSource)) {
				continue;
			}
			final double d = p.distSqr(worldPosition);
			if (d < bestDist) {
				bestDist = d;
				best = p.immutable();
			}
		}
		setSourcePos(best);
		return best != null;
	}

	/**
	 * A source block was placed: take over any monitor touching it that is still bound to a machine which no longer
	 * exists, and the rest of that monitor's rectangle with it. Rebuilding a computer under an orphaned screen is a
	 * normal thing to do, and until this existed the result was a perfectly healthy machine reporting
	 * {@code screen 0x0} next to a monitor that looked like dead hardware (HANDOFF (p)) — the only cure was
	 * {@code /vmc link}. Only a source whose chunk is <em>loaded</em> counts as gone: not loaded is not the same as
	 * orphaned, the same caveat {@code /vmc gc} prints. Seeding from the six touching faces and then following the
	 * dead source outwards is deliberate — it re-homes exactly the screen that belonged to the machine that stood
	 * here, and cannot quietly steal a neighbouring dead computer's wall.
	 *
	 * @return how many monitors changed hands
	 */
	public static int adoptOrphansAround(final ServerLevel level, final BlockPos sourcePos) {
		final Set<BlockPos> dead = new HashSet<>();
		for (final Direction d : Direction.values()) {
			final BlockPos p = sourcePos.relative(d);
			if (!(level.getBlockEntity(p) instanceof MonitorBlockEntity m)) {
				continue;
			}
			final BlockPos owner = m.getSourcePos();
			if (owner != null && !owner.equals(sourcePos) && level.hasChunkAt(owner) && !(level.getBlockEntity(owner) instanceof ScreenSource)) {
				dead.add(owner);
			}
		}
		if (dead.isEmpty()) {
			return 0;
		}
		int adopted = 0;
		for (final BlockPos p : BlockPos.betweenClosed(sourcePos.offset(-LINK_RADIUS, -LINK_RADIUS, -LINK_RADIUS), sourcePos.offset(LINK_RADIUS, LINK_RADIUS, LINK_RADIUS))) {
			if (!level.getBlockState(p).hasBlockEntity() || !(level.getBlockEntity(p) instanceof MonitorBlockEntity m) || !dead.contains(m.getSourcePos())) {
				continue;
			}
			m.setSourcePos(sourcePos);
			adopted++;
		}
		return adopted;
	}

	@Override
	protected void saveAdditional(final CompoundTag output) {
		super.saveAdditional(output);
		if (sourcePos != null) {
			// Key kept from before sources were an interface, so existing worlds keep their links.
			output.putIntArray("computer", new int[] { sourcePos.getX(), sourcePos.getY(), sourcePos.getZ() });
		}
		output.putBoolean("textMode", textMode);
		output.putIntArray("mb", new int[] { mbX, mbY, mbW, mbH });
		if (grid != null) {
			grid.save(Nbt.newChild(output, "text"));
		}
	}

	@Override
	public void load(final CompoundTag input) {
		super.load(input);
		sourcePos = null;
		Nbt.getIntArray(input, "computer").ifPresent(a -> {
			if (a.length == 3) {
				sourcePos = new BlockPos(a[0], a[1], a[2]);
			}
		});
		textMode = Nbt.getBooleanOr(input, "textMode", false);
		mbX = 0;
		mbY = 0;
		mbW = 1;
		mbH = 1;
		Nbt.getIntArray(input, "mb").ifPresent(a -> {
			if (a.length == 4 && a[2] >= 1 && a[3] >= 1) {
				mbX = a[0];
				mbY = a[1];
				mbW = a[2];
				mbH = a[3];
			}
		});
		grid = Nbt.child(input, "text").map(TextGrid::load).orElse(null);
		if (grid != null) {
			grid.version++;
		}
	}

	@Override
	public CompoundTag getUpdateTag() {
		return saveWithoutMetadata();
	}

	@Override
	public @Nullable ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
