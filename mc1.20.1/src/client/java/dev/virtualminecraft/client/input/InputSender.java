package dev.virtualminecraft.client.input;

import dev.virtualminecraft.net.VmInputPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import dev.virtualminecraft.client.ClientNet;
import org.jspecify.annotations.Nullable;

/** Batches keyboard / pointer events for the VM the player is interacting with and ships them to the server. */
public final class InputSender {
	private static @Nullable UUID target;
	private static final List<VmInputPayload.Event> PENDING = new ArrayList<>();
	private static int buttonMask;
	private static int pointerX;
	private static int pointerY;
	private static boolean pointerDirty;

	private InputSender() {
	}

	public static void beginSession(final UUID vm) {
		if (!vm.equals(target)) {
			endSession();
		}
		target = vm;
	}

	public static void endSession() {
		if (target != null) {
			// Release every button so the guest does not see a stuck mouse.
			if (buttonMask != 0) {
				buttonMask = 0;
				PENDING.add(VmInputPayload.Event.pointer(0, pointerX, pointerY));
			}
			flush();
		}
		target = null;
		PENDING.clear();
		pointerDirty = false;
		buttonMask = 0;
	}

	public static @Nullable UUID target() {
		return target;
	}

	public static void key(final int keysym, final boolean down) {
		if (target == null || keysym == 0) {
			return;
		}
		PENDING.add(VmInputPayload.Event.key(keysym, down));
		flush();
	}

	/** A typed character for a source that asked for them ({@code FLAG_CHARS}, the Computer). */
	public static void chr(final int codepoint) {
		if (target == null || codepoint < 0x20) {
			return;
		}
		PENDING.add(VmInputPayload.Event.chr(codepoint));
		flush();
	}

	/** Clipboard text for the source (Ctrl+V in the view), its own payload since it is not an event. */
	public static void paste(final String text) {
		if (target == null || text == null || text.isEmpty() || !ClientNet.canSend(dev.virtualminecraft.net.ScreenPastePayload.ID)) {
			return;
		}
		flush();
		ClientNet.send(new dev.virtualminecraft.net.ScreenPastePayload(target, text));
	}

	public static void scancode(final int qcode, final boolean down) {
		if (target == null || qcode <= 0) {
			return;
		}
		PENDING.add(VmInputPayload.Event.scancode(qcode, down));
		flush();
	}

	public static void pointerMove(final int x, final int y) {
		if (target == null) {
			return;
		}
		pointerX = x;
		pointerY = y;
		pointerDirty = true;
	}

	public static void pointerButton(final int rfbButtonBit, final boolean down, final int x, final int y) {
		if (target == null) {
			return;
		}
		pointerX = x;
		pointerY = y;
		buttonMask = down ? (buttonMask | rfbButtonBit) : (buttonMask & ~rfbButtonBit);
		PENDING.add(VmInputPayload.Event.pointer(buttonMask, x, y));
		pointerDirty = false;
		flush();
	}

	/** Wheel: RFB expresses scrolling as a press+release of button 4 (up) / 5 (down) / 6 (left) / 7 (right). */
	public static void wheel(final int rfbButtonBit, final int x, final int y) {
		if (target == null) {
			return;
		}
		PENDING.add(VmInputPayload.Event.pointer(buttonMask | rfbButtonBit, x, y));
		PENDING.add(VmInputPayload.Event.pointer(buttonMask, x, y));
		flush();
	}

	public static void tick() {
		if (target != null && pointerDirty) {
			pointerDirty = false;
			PENDING.add(VmInputPayload.Event.pointer(buttonMask, pointerX, pointerY));
		}
		flush();
	}

	// ---- in-world pointer (milestone 5 A4; the ray behind it became a seam in §9 U4.0) ----

	/**
	 * Pointer state for a screen the player is pointing at in the world rather than sitting inside. Same payload
	 * and same server-side distance check as a full-screen session; it simply is not bound to one, because the
	 * thing holding the ray may be a controller and the player may never open the view at all.
	 * <p>
	 * {@link dev.virtualminecraft.client.pointer.WorldPointer} decides *when* — the rate limit, the pixel, which
	 * monitor — and this only sends. Until §9 U4.0 the two were one method here ({@code hoverTick}), which was
	 * fine while the camera was the only thing that could point.
	 */
	public static void worldPointer(final UUID screen, final int buttonMask, final int x, final int y) {
		if (screen == null || !ClientNet.canSend(VmInputPayload.ID)) {
			return;
		}
		ClientNet.send(new VmInputPayload(screen, List.of(VmInputPayload.Event.pointer(buttonMask, x, y))));
	}

	/** Wheel for a world pointer: RFB press+release of button 4/5, {@code notches} times. */
	public static void worldWheel(final UUID screen, final int rfbButtonBit, final int heldMask, final int x, final int y, final int notches) {
		if (screen == null || notches <= 0 || !ClientNet.canSend(VmInputPayload.ID)) {
			return;
		}
		final List<VmInputPayload.Event> events = new ArrayList<>(notches * 2);
		for (int i = 0; i < notches; i++) {
			events.add(VmInputPayload.Event.pointer(heldMask | rfbButtonBit, x, y));
			events.add(VmInputPayload.Event.pointer(heldMask, x, y));
		}
		ClientNet.send(new VmInputPayload(screen, events));
	}

	/** Characters from a {@link dev.virtualminecraft.client.pointer.TextSource}, replayed into a world screen. */
	public static void worldChars(final UUID screen, final String text) {
		if (screen == null || text == null || text.isEmpty() || !ClientNet.canSend(VmInputPayload.ID)) {
			return;
		}
		final List<VmInputPayload.Event> events = new ArrayList<>();
		text.codePoints().filter(cp -> cp >= 0x20).forEach(cp -> events.add(VmInputPayload.Event.chr(cp)));
		if (!events.isEmpty()) {
			ClientNet.send(new VmInputPayload(screen, events));
		}
	}

	private static void flush() {
		if (target == null || PENDING.isEmpty()) {
			PENDING.clear();
			return;
		}
		if (!ClientNet.canSend(VmInputPayload.ID)) {
			PENDING.clear();
			return;
		}
		ClientNet.send(new VmInputPayload(target, new ArrayList<>(PENDING)));
		PENDING.clear();
	}
}
