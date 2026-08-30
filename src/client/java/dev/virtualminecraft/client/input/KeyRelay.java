package dev.virtualminecraft.client.input;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import dev.virtualminecraft.client.render.ScreenTextures;

/**
 * One physical keyboard, translated for one machine. This is the whole of "what does a key press mean to the thing
 * on the other side", and it is deliberately in one place because there are now two ways to reach a machine's
 * keyboard — the full-screen {@code VmScreen} and, since §9 U4.3, Vivecraft's floating keyboard typing at a screen
 * the player is only pointing at.
 * <p>
 * Those two must be <em>indistinguishable</em> to the guest, which is the seam's rule for the pointer restated for
 * text: a machine that reads scancodes must not be able to tell a poked key from a pressed one. Sharing the code
 * is how that is guaranteed rather than hoped for.
 * <p>
 * Three shapes of consumer, decided per screen by the flags the source advertises:
 * <ul>
 * <li><b>chars</b> ({@code FLAG_CHARS}, the Computer) — codepoints, plus keysyms for the keys that have no
 *     character. Ctrl+V is a clipboard event rather than a key, because that is what the Computer wants.</li>
 * <li><b>scancodes</b> (a VM with its own keyboard layout) — every physical key down and up as itself, and the
 *     character callback adds nothing.</li>
 * <li><b>keysyms</b> (VNC) — where the awkward one lives: QEMU maps a keysym to a keycode and expects the client
 *     to hold Shift itself, so {@code ':'} typed without a real Shift arrives as {@code ';'} unless we synthesise
 *     one. A player holds the real key; a floating keyboard, a puppet, an IME or a paste does not.</li>
 * </ul>
 * Not static: each consumer owns its own instance, because the held-key maps are what let it let go cleanly, and
 * two consumers sharing one set of held keys would release each other's.
 */
public final class KeyRelay {
	private final Map<Integer, Integer> downKeys = new HashMap<>();
	/** GLFW key → QKeyCode we pressed, when the source takes scancodes. */
	private final Map<Integer, Integer> downCodes = new HashMap<>();
	private boolean rightAltDown;

	/** Whether Right Alt is held; {@code VmScreen} uses it to tell "leave" from "send Esc to the guest". */
	public boolean rightAltDown() {
		return rightAltDown;
	}

	/**
	 * A key going down. Returns false only for the one case the caller must handle itself — Escape without Right
	 * Alt, which means "get me out" rather than anything the machine should see.
	 */
	public boolean keyDown(final UUID vm, final KeyEvent event) {
		final int key = event.key();
		final int mods = event.modifiers();
		if (key == GLFW.GLFW_KEY_RIGHT_ALT) {
			rightAltDown = true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE && !rightAltDown) {
			return false;
		}
		if (ScreenTextures.chars(vm) && key == GLFW.GLFW_KEY_V && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
			// paste: the Computer takes clipboard text as one event (ROADMAP §7h §6)
			InputSender.paste(Minecraft.getInstance().keyboardHandler.getClipboard());
			return true;
		}
		if (ScreenTextures.scancodes(vm)) {
			final int qcode = QCodes.fromGlfw(key);
			if (qcode != 0 && !downCodes.containsKey(key)) {
				downCodes.put(key, qcode);
				InputSender.scancode(qcode, true);
			}
			return true;
		}
		final int keysym = keysymFor(key, mods);
		if (keysym != 0) {
			downKeys.put(key, keysym);
			InputSender.key(keysym, true);
		}
		return true;
	}

	public void keyUp(final KeyEvent event) {
		final int key = event.key();
		if (key == GLFW.GLFW_KEY_RIGHT_ALT) {
			rightAltDown = false;
		}
		final Integer qcode = downCodes.remove(key);
		if (qcode != null) {
			InputSender.scancode(qcode, false);
		}
		final Integer keysym = downKeys.remove(key);
		if (keysym != null) {
			InputSender.key(keysym, false);
		}
	}

	public void charTyped(final UUID vm, final CharacterEvent event) {
		if (ScreenTextures.chars(vm)) {
			InputSender.chr(event.codepoint());
			return;
		}
		if (ScreenTextures.scancodes(vm)) {
			return; // keyDown already sent the physical key
		}
		final int cp = event.codepoint();
		final int keysym = Keysyms.fromCodepoint(cp);
		final boolean needShift = Keysyms.needsShiftUs(cp) && !downKeys.containsValue(0xffe1) && !downKeys.containsValue(0xffe2);
		if (needShift) {
			InputSender.key(0xffe1, true);
		}
		InputSender.key(keysym, true);
		InputSender.key(keysym, false);
		if (needShift) {
			InputSender.key(0xffe1, false);
		}
	}

	/** Let go of everything still held. A key left down in a guest is a bug the player cannot fix from inside. */
	public void releaseAll() {
		for (final int keysym : downKeys.values()) {
			InputSender.key(keysym, false);
		}
		downKeys.clear();
		for (final int qcode : downCodes.values()) {
			InputSender.scancode(qcode, false);
		}
		downCodes.clear();
		rightAltDown = false;
	}

	/** Which keysym (if any) to send on key-down; printable keys without Ctrl/Alt go through {@link #charTyped}. */
	private static int keysymFor(final int key, final int mods) {
		final int special = Keysyms.fromGlfwKey(key);
		if (special != 0) {
			return special;
		}
		if (!Keysyms.isPrintable(key)) {
			return 0;
		}
		final boolean ctrlOrAlt = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) != 0;
		if (!ctrlOrAlt) {
			return 0; // the char callback delivers it
		}
		return Keysyms.fromPrintableKey(key, (mods & GLFW.GLFW_MOD_SHIFT) != 0);
	}
}
