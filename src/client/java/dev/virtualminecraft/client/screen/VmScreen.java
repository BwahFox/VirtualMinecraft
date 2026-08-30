package dev.virtualminecraft.client.screen;

import dev.virtualminecraft.client.audio.VmAudio;
import dev.virtualminecraft.client.input.InputSender;
import dev.virtualminecraft.client.input.KeyRelay;
import dev.virtualminecraft.client.render.ScreenTexture;
import dev.virtualminecraft.client.render.ScreenTextures;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Full-screen view of a VM with keyboard and mouse capture. Esc leaves; Right Alt + Esc sends Esc to the guest. */
public class VmScreen extends Screen {
	private static final int BG = 0xFF000000;
	private static final int HINT_COLOR = 0xFF9A9A9A;

	private final UUID vm;
	private final String vmName;
	private final BlockPos computerPos;
	/** The key translation, shared with the in-world keyboard (§9 U4.3) so a guest cannot tell them apart. */
	private final KeyRelay keys = new KeyRelay();

	private int fitX;
	private int fitY;
	private int fitW;
	private int fitH;

	public VmScreen(final UUID vm, final String vmName, final BlockPos computerPos) {
		super(Component.literal(vmName));
		this.vm = vm;
		this.vmName = vmName;
		this.computerPos = computerPos;
	}

	@Override
	protected void init() {
		InputSender.beginSession(vm);
		ScreenTextures.touch(vm);
		VmAudio.setRelative(vm, true);
	}

	@Override
	public void removed() {
		keys.releaseAll();
		InputSender.endSession();
		VmAudio.setRelative(vm, false);
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void tick() {
		ScreenTextures.touch(vm);
	}

	// ---------------------------------------------------------------------------------------------
	// Rendering
	// ---------------------------------------------------------------------------------------------

	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		graphics.fill(0, 0, this.width, this.height, BG);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		final ScreenTexture tex = ScreenTextures.get(vm);
		final int hintH = 12;
		if (tex != null) {
			final int availW = this.width;
			final int availH = this.height - hintH;
			final double scale = Math.min((double) availW / tex.width, (double) availH / tex.height);
			fitW = Math.max(1, (int) Math.round(tex.width * scale));
			fitH = Math.max(1, (int) Math.round(tex.height * scale));
			fitX = (availW - fitW) / 2;
			fitY = (availH - fitH) / 2;
			graphics.blit(tex.id, fitX, fitY, fitX + fitW, fitY + fitH, 0f, 1f, 0f, 1f);
			// The hardware cursor (U1.3), mapped from full-resolution pixels into the fitted picture and clipped to it.
			final ScreenTextures.Cursor cur = ScreenTextures.cursor(vm);
			final int[] full = ScreenTextures.fullSize(vm);
			if (cur != null && full[0] > 0 && full[1] > 0) {
				final int px0 = cur.x() - cur.hotX();
				final int py0 = cur.y() - cur.hotY();
				final int cx0 = Math.max(0, px0);
				final int cy0 = Math.max(0, py0);
				final int cx1 = Math.min(full[0], px0 + cur.w());
				final int cy1 = Math.min(full[1], py0 + cur.h());
				if (cx1 > cx0 && cy1 > cy0) {
					final int gx0 = fitX + Math.round(cx0 * (float) fitW / full[0]);
					final int gx1 = fitX + Math.round(cx1 * (float) fitW / full[0]);
					final int gy0 = fitY + Math.round(cy0 * (float) fitH / full[1]);
					final int gy1 = fitY + Math.round(cy1 * (float) fitH / full[1]);
					graphics.blit(cur.tex().id, gx0, gy0, Math.max(gx0 + 1, gx1), Math.max(gy0 + 1, gy1),
						(cx0 - px0) / (float) cur.w(), (cx1 - px0) / (float) cur.w(), (cy0 - py0) / (float) cur.h(), (cy1 - py0) / (float) cur.h());
				}
			}
		} else {
			fitW = 0;
			fitH = 0;
			final String msg = ScreenTextures.isRunning(vm) ? "Waiting for display…" : "This computer is not running.";
			graphics.text(this.font, msg, (this.width - this.font.width(msg)) / 2, this.height / 2 - 4, 0xFFFFFFFF);
		}
		final int[] full = ScreenTextures.fullSize(vm);
		final String hint = vmName + "  ·  Esc: leave  ·  RAlt+Esc: send Esc  ·  " + (tex == null ? "" : full[0] + "×" + full[1] + (ScreenTextures.lod(vm) > 0 ? " (lod " + ScreenTextures.lod(vm) + ")" : "") + (ScreenTextures.scancodes(vm) ? "  ·  scancodes" : "  ·  keysyms"));
		graphics.text(this.font, hint, (this.width - this.font.width(hint)) / 2, this.height - hintH + 2, HINT_COLOR);
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	// ---------------------------------------------------------------------------------------------
	// Keyboard
	// ---------------------------------------------------------------------------------------------

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (!keys.keyDown(vm, event)) {
			this.onClose(); // Escape without Right Alt: leave, rather than send Esc to the guest
		}
		return true;
	}

	@Override
	public boolean keyReleased(final KeyEvent event) {
		keys.keyUp(event);
		return true;
	}

	@Override
	public boolean charTyped(final CharacterEvent event) {
		keys.charTyped(vm, event);
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// Mouse
	// ---------------------------------------------------------------------------------------------

	// Pointer coordinates are always in the guest's full-resolution space, whatever level the texture is at.
	private int fbX(final double mouseX) {
		final int full = ScreenTextures.fullSize(vm)[0];
		if (full <= 0 || fitW <= 0) {
			return 0;
		}
		return (int) Math.clamp(Math.round((mouseX - fitX) / fitW * full), 0, full - 1);
	}

	private int fbY(final double mouseY) {
		final int full = ScreenTextures.fullSize(vm)[1];
		if (full <= 0 || fitH <= 0) {
			return 0;
		}
		return (int) Math.clamp(Math.round((mouseY - fitY) / fitH * full), 0, full - 1);
	}

	private static int rfbButton(final int glfwButton) {
		return switch (glfwButton) {
			case GLFW.GLFW_MOUSE_BUTTON_LEFT -> 1;
			case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 2;
			case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 4;
			default -> 0;
		};
	}

	@Override
	public void mouseMoved(final double x, final double y) {
		InputSender.pointerMove(fbX(x), fbY(y));
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		final int bit = rfbButton(event.button());
		if (bit != 0) {
			InputSender.pointerButton(bit, true, fbX(event.x()), fbY(event.y()));
		}
		return true;
	}

	@Override
	public boolean mouseReleased(final MouseButtonEvent event) {
		final int bit = rfbButton(event.button());
		if (bit != 0) {
			InputSender.pointerButton(bit, false, fbX(event.x()), fbY(event.y()));
		}
		return true;
	}

	@Override
	public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
		InputSender.pointerMove(fbX(event.x()), fbY(event.y()));
		return true;
	}

	@Override
	public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
		final int fx = fbX(x);
		final int fy = fbY(y);
		if (scrollY > 0) {
			InputSender.wheel(8, fx, fy);
		} else if (scrollY < 0) {
			InputSender.wheel(16, fx, fy);
		}
		if (scrollX > 0) {
			InputSender.wheel(64, fx, fy);
		} else if (scrollX < 0) {
			InputSender.wheel(32, fx, fy);
		}
		return true;
	}
}
