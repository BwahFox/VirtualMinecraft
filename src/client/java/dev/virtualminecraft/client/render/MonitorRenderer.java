package dev.virtualminecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.virtualminecraft.block.MonitorBlock;
import dev.virtualminecraft.block.MonitorBlockEntity;
import dev.virtualminecraft.client.audio.VmAudio;
import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.bus.TextGrid;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Draws the linked screen on the monitor's front face — for a multi-block rectangle, from its origin across all of it. */
public class MonitorRenderer implements BlockEntityRenderer<MonitorBlockEntity, MonitorRenderState> {
	/** Screen area inset from the block edge (the bezel), in blocks. */
	private static final float BEZEL = 1f / 16f;
	private static final float SURFACE_OFFSET = 0.002f;
	private static final Identifier WHITE = VirtualMinecraft.id("textures/block/white.png");
	private final Font font;

	public MonitorRenderer(final BlockEntityRendererProvider.Context context) {
		this.font = context.font();
	}

	@Override
	public MonitorRenderState createRenderState() {
		return new MonitorRenderState();
	}

	@Override
	public void extractRenderState(final MonitorBlockEntity blockEntity, final MonitorRenderState state, final float partialTicks, final Vec3 cameraPosition,
		final ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
		BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
		state.facing = blockEntity.getBlockState().getValue(MonitorBlock.FACING);
		state.origin = blockEntity.isOrigin();
		state.mbW = blockEntity.mbW();
		state.mbH = blockEntity.mbH();
		state.textMode = blockEntity.isTextMode();
		state.grid = blockEntity.textGridOrNull();
		state.vm = null;
		if (!state.origin) {
			return; // the origin draws the whole rectangle
		}
		final UUID vm = blockEntity.getScreenId();
		state.vm = vm;
		// A monitor with no source at all keeps its old look (just the block); a monitor whose machine is switched
		// off goes dark. screenActive() answers on the client too -- the Lua tier from the synced "picture" prefix,
		// the VM tier from its synced status.
		final dev.virtualminecraft.screen.ScreenSource src = blockEntity.getSource();
		state.on = src == null || src.screenActive();
		if (vm != null) {
			// Distance to the rectangle's centre, per block of its width, chooses the level of detail (A3).
			final Direction right = state.facing.getCounterClockWise();
			final Vec3 centre = Vec3.atCenterOf(blockEntity.getBlockPos()).add(right.getStepX() * (state.mbW - 1) / 2.0, (state.mbH - 1) / 2.0, right.getStepZ() * (state.mbW - 1) / 2.0);
			ScreenTextures.touch(vm, cameraPosition.distanceTo(centre), state.mbW);
			if (blockEntity.getSourcePos() != null) {
				VmAudio.setPosition(vm, Vec3.atCenterOf(blockEntity.getSourcePos()));
			}
		}
	}

	/** The origin draws outside its own block, so it must not be culled by its block's bounds. */
	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	/**
	 * Moves the pose to the rectangle's frame: origin at the bottom-left front corner as seen by a viewer, x running
	 * to screen-left (+x on the north face), y up, z into the screen. Wall-space coordinates (x to screen-right)
	 * therefore map to local x as {@code 1 - X}.
	 */
	private static void toRectangleFrame(final PoseStack poseStack, final MonitorRenderState state) {
		poseStack.translate(0.5, 0.5, 0.5);
		poseStack.mulPose(Axis.YP.rotationDegrees(180f - state.facing.toYRot()));
		poseStack.translate(-0.5, -0.5, -0.5);
	}

	@Override
	public void submit(final MonitorRenderState state, final PoseStack poseStack, final SubmitNodeCollector collector, final CameraRenderState camera) {
		if (!state.origin) {
			return;
		}
		if (!state.on) {
			// Off: dark glass and nothing else. Without this the last frame the machine drew stays on the monitor
			// for good, so a computer that has been shut down is indistinguishable from one still running.
			poseStack.pushPose();
			toRectangleFrame(poseStack, state);
			submitGlass(state, poseStack, collector);
			poseStack.popPose();
			return;
		}
		if (state.textMode && state.grid != null) {
			submitText(state, poseStack, collector);
			return;
		}
		if (state.vm == null) {
			return;
		}
		final ScreenTexture tex = ScreenTextures.get(state.vm);
		final int light = LightCoordsUtil.FULL_BRIGHT;
		poseStack.pushPose();
		toRectangleFrame(poseStack, state);
		if (state.mbW > 1 || state.mbH > 1) {
			submitGlass(state, poseStack, collector);
		}
		if (tex != null) {
			// Aspect-fit the picture inside the rectangle's bezel-inset area (same maths as MonitorBlock.pictureBox).
			final int[] full = ScreenTextures.fullSize(state.vm);
			final float[] box = MonitorBlock.pictureBox(state.mbW, state.mbH, full[0], full[1]);
			final float x0 = 1f - box[0]; // screen-left edge (wall x0) in local x
			final float x1 = 1f - box[2]; // screen-right edge
			final float y0 = box[3];      // top
			final float y1 = box[1];      // bottom
			final float z = -SURFACE_OFFSET;
			collector.submitCustomGeometry(poseStack, RenderTypes.text(tex.id), (pose, buffer) -> {
				buffer.addVertex(pose, x0, y0, z).setColor(-1).setUv(0f, 0f).setLight(light);
				buffer.addVertex(pose, x0, y1, z).setColor(-1).setUv(0f, 1f).setLight(light);
				buffer.addVertex(pose, x1, y1, z).setColor(-1).setUv(1f, 1f).setLight(light);
				buffer.addVertex(pose, x1, y0, z).setColor(-1).setUv(1f, 0f).setLight(light);
			});
			// The hardware cursor (U1.3): one quad in front of the picture, mapped through the same box; clipped to the screen.
			final ScreenTextures.Cursor cur = ScreenTextures.cursor(state.vm);
			if (cur != null && full[0] > 0 && full[1] > 0) {
				final int px0 = cur.x() - cur.hotX();
				final int py0 = cur.y() - cur.hotY();
				final int cx0 = Math.max(0, px0);
				final int cy0 = Math.max(0, py0);
				final int cx1 = Math.min(full[0], px0 + cur.w());
				final int cy1 = Math.min(full[1], py0 + cur.h());
				if (cx1 > cx0 && cy1 > cy0) {
					final float lx0 = (1f - box[0]) - (box[2] - box[0]) * cx0 / full[0];
					final float lx1 = (1f - box[0]) - (box[2] - box[0]) * cx1 / full[0];
					final float ly0 = box[3] + (box[1] - box[3]) * cy0 / full[1];
					final float ly1 = box[3] + (box[1] - box[3]) * cy1 / full[1];
					final float u0 = (cx0 - px0) / (float) cur.w();
					final float u1 = (cx1 - px0) / (float) cur.w();
					final float v0 = (cy0 - py0) / (float) cur.h();
					final float v1 = (cy1 - py0) / (float) cur.h();
					final float zc = -SURFACE_OFFSET * 2f;
					collector.submitCustomGeometry(poseStack, RenderTypes.text(cur.tex().id), (pose, buffer) -> {
						buffer.addVertex(pose, lx0, ly0, zc).setColor(-1).setUv(u0, v0).setLight(light);
						buffer.addVertex(pose, lx0, ly1, zc).setColor(-1).setUv(u0, v1).setLight(light);
						buffer.addVertex(pose, lx1, ly1, zc).setColor(-1).setUv(u1, v1).setLight(light);
						buffer.addVertex(pose, lx1, ly0, zc).setColor(-1).setUv(u1, v0).setLight(light);
					});
				}
			}
		}
		poseStack.popPose();
	}

	/**
	 * A multi-block rectangle is made of ordinary monitor blocks, each with its own bezel texture; one black quad
	 * over the whole bezel-inset area hides the bezels of the interior blocks so the wall reads as one screen.
	 */
	private static void submitGlass(final MonitorRenderState state, final PoseStack poseStack, final SubmitNodeCollector collector) {
		final int light = LightCoordsUtil.FULL_BRIGHT;
		final float x0 = 1f - BEZEL;
		final float x1 = 1f - (state.mbW - BEZEL);
		final float y0 = state.mbH - BEZEL;
		final float y1 = BEZEL;
		final float z = -SURFACE_OFFSET / 2f;
		final int black = 0xFF000000;
		collector.submitCustomGeometry(poseStack, RenderTypes.text(WHITE), (pose, buffer) -> {
			buffer.addVertex(pose, x0, y0, z).setColor(black).setUv(0f, 0f).setLight(light);
			buffer.addVertex(pose, x0, y1, z).setColor(black).setUv(0f, 1f).setLight(light);
			buffer.addVertex(pose, x1, y1, z).setColor(black).setUv(1f, 1f).setLight(light);
			buffer.addVertex(pose, x1, y0, z).setColor(black).setUv(1f, 0f).setLight(light);
		});
	}

	/**
	 * Text mode: the grid's pixel box (cols×CELL_W by rows×CELL_H) is scaled to fit the rectangle's bezel-inset area
	 * and centred (same maths as {@code MonitorBlock.hitToCell}). Backgrounds are runs of same-coloured cells as
	 * quads on a white texture; characters are submitted one per cell so the layout is monospace regardless of
	 * glyph width.
	 */
	private void submitText(final MonitorRenderState state, final PoseStack poseStack, final SubmitNodeCollector collector) {
		final TextGrid g = state.grid;
		final float[] box = MonitorBlock.pictureBox(state.mbW, state.mbH, g.cols * TextGrid.CELL_W, g.rows * TextGrid.CELL_H);
		final float w = box[2] - box[0];
		final float scale = w / (g.cols * TextGrid.CELL_W);
		final int light = LightCoordsUtil.FULL_BRIGHT;

		poseStack.pushPose();
		toRectangleFrame(poseStack, state);
		if (state.mbW > 1 || state.mbH > 1) {
			submitGlass(state, poseStack, collector);
		}
		// Move to the top-left of the text box, flip Y (font draws downwards), and mirror X so +x in "pixel space"
		// runs towards screen-right (which is -x in this frame).
		poseStack.translate(1f - box[0], box[3], -SURFACE_OFFSET);
		poseStack.scale(-scale, -scale, scale);

		// Backgrounds: one quad per run of equal colour per row.
		collector.submitCustomGeometry(poseStack, RenderTypes.text(WHITE), (pose, buffer) -> {
			for (int y = 0; y < g.rows; y++) {
				int x = 0;
				while (x < g.cols) {
					final int color = g.bg[y * g.cols + x];
					int x1 = x + 1;
					while (x1 < g.cols && g.bg[y * g.cols + x1] == color) {
						x1++;
					}
					final int argb = 0xFF000000 | (color & 0xFFFFFF);
					final float px0 = x * TextGrid.CELL_W;
					final float px1 = x1 * TextGrid.CELL_W;
					final float py0 = y * TextGrid.CELL_H;
					final float py1 = (y + 1) * TextGrid.CELL_H;
					buffer.addVertex(pose, px0, py0, 0f).setColor(argb).setUv(0f, 0f).setLight(light);
					buffer.addVertex(pose, px0, py1, 0f).setColor(argb).setUv(0f, 1f).setLight(light);
					buffer.addVertex(pose, px1, py1, 0f).setColor(argb).setUv(1f, 1f).setLight(light);
					buffer.addVertex(pose, px1, py0, 0f).setColor(argb).setUv(1f, 0f).setLight(light);
					x = x1;
				}
			}
		});

		// Characters, slightly in front of the backgrounds.
		poseStack.translate(0f, 0f, -0.01f);
		for (int y = 0; y < g.rows; y++) {
			for (int x = 0; x < g.cols; x++) {
				final int cp = g.chars[y * g.cols + x];
				if (cp == ' ' || cp < 32) {
					continue;
				}
				final FormattedCharSequence seq = FormattedCharSequence.codepoint(cp, Style.EMPTY);
				final int cw = font.width(seq);
				final float px = x * TextGrid.CELL_W + (TextGrid.CELL_W - cw) / 2f;
				final float py = y * TextGrid.CELL_H + 1f;
				collector.submitText(poseStack, px, py, seq, false, Font.DisplayMode.NORMAL, light, 0xFF000000 | (g.fg[y * g.cols + x] & 0xFFFFFF), 0, 0);
			}
		}
		poseStack.popPose();
	}

	@Override
	public int getViewDistance() {
		return 64;
	}
}
