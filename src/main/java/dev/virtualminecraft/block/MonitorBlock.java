package dev.virtualminecraft.block;

import com.mojang.serialization.MapCodec;
import dev.virtualminecraft.VirtualMinecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.bus.TextGrid;
import dev.virtualminecraft.net.VmInputPayload;
import dev.virtualminecraft.screen.ScreenSource;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * A monitor shows the screen of the source it is linked to. Right-click on the picture <em>clicks</em> it (a left
 * click at that pixel, for the guest); sneak + right-click opens the full-screen view with keyboard capture. A
 * monitor with nothing live to click on opens the view on a plain right-click. Linking happens on placement and
 * with {@code /vmc link}.
 */
public class MonitorBlock extends BaseEntityBlock {
	public static final MapCodec<MonitorBlock> CODEC = simpleCodec(MonitorBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	public MonitorBlock(final Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected RenderShape getRenderShape(final BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		return new MonitorBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(final Level level, final BlockState state, final BlockEntityType<T> type) {
		if (level.isClientSide() || type != ModContent.MONITOR_BLOCK_ENTITY) {
			return null;
		}
		return (lvl, pos, st, be) -> ((MonitorBlockEntity) be).serverTick((ServerLevel) lvl);
	}

	/** Screen area inset from the rectangle's outer edge (the bezel), in blocks — must match the renderer. */
	public static final float BEZEL = 1f / 16f;

	/**
	 * Where a hit lands in the rectangle's wall space: x to screen-right and y up, in blocks, measured from the
	 * bottom-left corner of the whole rectangle. Null if the hit is not on the front face.
	 */
	private static double @Nullable [] wallHit(final MonitorBlockEntity monitor, final BlockState state, final BlockPos pos, final BlockHitResult hit) {
		final Direction facing = state.getValue(FACING);
		if (hit.getDirection() != facing) {
			return null;
		}
		final Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
		final Direction right = facing.getCounterClockWise();
		final double toRight = 0.5 + (local.x - 0.5) * right.getStepX() + (local.z - 0.5) * right.getStepZ(); // 0..1 across this block
		return new double[] { monitor.mbX() + toRight, monitor.mbY() + local.y };
	}

	/**
	 * The picture's box in wall space for a rectangle of {@code w × h} blocks showing a {@code fullW × fullH}
	 * picture: aspect-fitted inside the bezel-inset area and centred. Returns {@code {x0, y0, x1, y1}} (y up).
	 */
	public static float[] pictureBox(final int w, final int h, final int fullW, final int fullH) {
		final float aw = w - 2f * BEZEL;
		final float ah = h - 2f * BEZEL;
		float pw = aw;
		float ph = fullW <= 0 || fullH <= 0 ? ah : aw * fullH / fullW;
		if (ph > ah) {
			ph = ah;
			pw = ah * fullW / fullH;
		}
		return new float[] { w / 2f - pw / 2f, h / 2f - ph / 2f, w / 2f + pw / 2f, h / 2f + ph / 2f };
	}

	/**
	 * Maps a hit on the front face to a text cell (1-based) of the rectangle's grid, using the same fit as the
	 * renderer: the grid's pixel box ({@code cols*CELL_W × rows*CELL_H}) scaled into the bezel-inset area, centred.
	 * Null if outside.
	 */
	public static int @Nullable [] hitToCell(final MonitorBlockEntity monitor, final BlockState state, final BlockPos pos, final BlockHitResult hit, final TextGrid grid) {
		final double[] wh = wallHit(monitor, state, pos, hit);
		if (wh == null) {
			return null;
		}
		final float[] box = pictureBox(monitor.mbW(), monitor.mbH(), grid.cols * TextGrid.CELL_W, grid.rows * TextGrid.CELL_H);
		final double u = (wh[0] - box[0]) / (box[2] - box[0]);
		final double v = (box[3] - wh[1]) / (box[3] - box[1]);
		if (u < 0 || u >= 1 || v < 0 || v >= 1) {
			return null;
		}
		return new int[] { (int) (u * grid.cols) + 1, (int) (v * grid.rows) + 1 };
	}

	/**
	 * Maps a hit on the front face to a framebuffer pixel using the same fit as {@code MonitorRenderer}. Null if
	 * the hit is off the picture.
	 */
	public static int @Nullable [] hitToPixel(final MonitorBlockEntity monitor, final BlockState state, final BlockPos pos, final BlockHitResult hit, final int fullW, final int fullH) {
		if (fullW <= 0 || fullH <= 0) {
			return null;
		}
		final double[] wh = wallHit(monitor, state, pos, hit);
		if (wh == null) {
			return null;
		}
		final float[] box = pictureBox(monitor.mbW(), monitor.mbH(), fullW, fullH);
		final double u = (wh[0] - box[0]) / (box[2] - box[0]);
		final double v = (box[3] - wh[1]) / (box[3] - box[1]);
		if (u < 0 || u >= 1 || v < 0 || v >= 1) {
			return null;
		}
		return new int[] { Math.min(fullW - 1, (int) (u * fullW)), Math.min(fullH - 1, (int) (v * fullH)) };
	}

	@Override
	public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, final @Nullable LivingEntity placer, final ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof MonitorBlockEntity monitor) {
			monitor.linkToNearestSource(); // setSourcePos regroups the rectangle
		}
	}

	@Override
	protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
			MonitorMultiblock.rebuildAround(serverLevel, pos, state);
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		MonitorMultiblock.rebuildAround(level, pos, state); // the block is gone; the neighbours regroup without it
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof MonitorBlockEntity hitMonitor)) {
			return InteractionResult.PASS;
		}
		// The rectangle's origin owns the picture and the grid; the hit block only contributes its position in it.
		final MonitorBlockEntity monitor = hitMonitor.origin() != null ? hitMonitor.origin() : hitMonitor;
		final ScreenSource source = monitor.getSource();
		if (player.isShiftKeyDown()) {
			openView(level, monitor, player);
			return InteractionResult.SUCCESS;
		}
		if (monitor.isTextMode()) {
			// Text mode: a click is a touch for the source.
			if (level instanceof ServerLevel serverLevel && monitor.textGridOrNull() != null && player instanceof ServerPlayer serverPlayer) {
				final int[] cell = hitToCell(hitMonitor, state, pos, hitResult, monitor.textGridOrNull());
				if (source != null && cell != null) {
					source.monitorTouched(serverLevel, monitor, cell[0], cell[1], serverPlayer);
				}
			}
			return InteractionResult.SUCCESS;
		}
		if (source == null || !source.screenActive()) {
			openView(level, monitor, player);
			return InteractionResult.SUCCESS;
		}
		// A live picture: the click goes to the guest as a left click at that pixel (milestone 5 A4).
		if (level instanceof ServerLevel && player instanceof ServerPlayer serverPlayer) {
			final int[] size = source.screenSize();
			final int[] px = hitToPixel(hitMonitor, state, pos, hitResult, size[0], size[1]);
			if (px != null) {
				source.screenInput(serverPlayer, List.of(VmInputPayload.Event.pointer(1, px[0], px[1]), VmInputPayload.Event.pointer(0, px[0], px[1])));
			}
		}
		return InteractionResult.SUCCESS;
	}

	private static void openView(final Level level, final MonitorBlockEntity monitor, final Player player) {
		if (!level.isClientSide()) {
			return;
		}
		final BlockPos sourcePos = monitor.getSourcePos();
		if (sourcePos == null || monitor.getSource() == null) {
			player.sendOverlayMessage(Component.translatable("virtualminecraft.msg.not_linked"));
		} else {
			VirtualMinecraft.clientHooks.openMonitorScreen(sourcePos);
		}
	}
}
