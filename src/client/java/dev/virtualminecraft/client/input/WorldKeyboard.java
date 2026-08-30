package dev.virtualminecraft.client.input;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.client.pointer.WorldPointer;

/**
 * Typing at a screen you are only <em>pointing</em> at (ROADMAP §9 U4.3). The full-screen panel has always been
 * able to take a keyboard, because it is a {@code Screen} and Minecraft hands focused screens their key events.
 * A monitor across the room is not a screen in that sense, so nothing reached it — which is why, before this,
 * Vivecraft's floating keyboard typed into thin air.
 * <p>
 * <b>Off unless something switches it on, and that is the whole safety story.</b> While this is closed, the
 * keyboard mixin does not exist as far as the game is concerned: keys go where they always went. It is opened
 * deliberately — today by the VR module on sneak + interact ([name]'s design, 2026-08-29: <i>"in vr, since we
 * don't need a direct input the same way flatscreen needs it, sneaking and interacting should open the
 * keyboard"</i>) — and closed the same way. Without that, a desktop player pressing W near a monitor would both
 * walk and type, and the seam's promise that a build without the VR jar behaves identically would be a lie.
 * <p>
 * It borrows {@code InputSender}'s ordinary session, so everything downstream — the batching, the payload, the
 * server's own distance check — is the path the panel uses and nothing new had to be trusted. {@link WorldPointer}
 * knows to keep pointing while that session belongs to this class rather than to an open view, so you can aim and
 * type at once, which is the entire point of a keyboard that floats next to the screen.
 */
public final class WorldKeyboard {
	private static @Nullable UUID screen;
	private static final KeyRelay KEYS = new KeyRelay();
	/** A jump/sneak-bound key press waiting for the character that proves it was poked, not stick-clicked. */
	private static @Nullable KeyEvent pendingBound;
	private static long pendingBoundAt;
	/** Whether the pending press's release already went by — the confirm then delivers a tap, never a stuck key. */
	private static boolean pendingBoundReleased;
	/** Confirmed poke presses the machine has seen go down and must see go up. */
	private static final java.util.Set<Integer> deliveredBound = new java.util.HashSet<>();
	/** How long a bound key press may wait for its confirming character. A poke's pair arrives in the same frame;
	 * anything slower is a stick click followed by unrelated typing and must stay dropped. */
	private static final long BOUND_CONFIRM_MS = 80;

	private WorldKeyboard() {
	}

	/** The screen being typed at, or null when nothing is. */
	public static @Nullable UUID screen() {
		return screen;
	}

	public static boolean isOpen() {
		return screen != null;
	}

	/**
	 * Start typing at {@code target}. Idempotent for the same screen; switching screens lets go of the old one
	 * first, so a key held while you look away does not stay held for ever on a machine you left.
	 */
	public static void open(final UUID target) {
		if (target == null || target.equals(screen)) {
			return;
		}
		close();
		screen = target;
		InputSender.beginSession(target);
		VirtualMinecraft.LOGGER.info("world keyboard opened on screen {}", target);
	}

	public static void close() {
		if (screen == null) {
			return;
		}
		KEYS.releaseAll();
		pendingBound = null;
		deliveredBound.clear();
		InputSender.endSession();
		// Minecraft's own key state has to be cleared on the way out, and this is not belt-and-braces: while the
		// keyboard was open the mixin was *cancelling* key events, so any key whose press the game saw but whose
		// release it did not is still held as far as KeyMapping is concerned. [name] found it immediately (2026-08-29:
		// "after closing the keyboard, i need to hit the pause button again before it will let me jump and stuff") --
		// and the pause menu fixing it is the clue, because opening a screen is the other thing that calls this.
		net.minecraft.client.KeyMapping.releaseAll();
		VirtualMinecraft.LOGGER.info("world keyboard closed on screen {}", screen);
		screen = null;
	}

	/**
	 * A key event on its way to the game. Returns true when it was consumed, which is the mixin's signal to stop
	 * it — otherwise typing would also walk, jump and open the inventory.
	 * <p>
	 * Escape is deliberately <em>not</em> consumed: it closes the keyboard instead. A player who cannot get their
	 * movement keys back is stuck, and stuck is worse than any feature.
	 */
	public static boolean keyEvent(final KeyEvent event, final boolean down) {
		if (screen == null || Minecraft.getInstance().gui.screen() != null) {
			return false;
		}
		// [name]'s stick-click decision (2026-08-29, option A, refined the same day when Drift 3D could not shoot):
		// keys bound to jump and sneak are never *acted on* while the keyboard is open -- a VR controller binding
		// types by synthesising a real key press of whatever the binding uses (jump = left-stick click = a Space
		// press), so clicking the stick was typing stray spaces, and passing it through instead would make every
		// Space poked on the floating keyboard jump. But swallowing them whole also swallowed the space bar as a
		// *game* key, and machine-side games in VR only exist behind this keyboard. The tell that separates the
		// two: a poked key sends a key press AND a character; a binding sends the press alone. So a bound key's
		// press is held here and delivered only when its character confirms it (a few ms later, same frame in
		// practice) -- poke-to-shoot and poke-and-hold both reach the machine, a stick click never does.
		final net.minecraft.client.Options options = Minecraft.getInstance().options;
		if (options.keyJump.matches(event) || options.keyShift.matches(event)) {
			if (down) {
				pendingBound = event;
				pendingBoundAt = net.minecraft.util.Util.getMillis();
				pendingBoundReleased = false;
			} else if (deliveredBound.remove(event.key())) {
				KEYS.keyUp(event); // we delivered its press, so the machine must see it let go
			} else if (pendingBound != null && pendingBound.key() == event.key()) {
				pendingBoundReleased = true; // released before the char arrived: the confirm becomes a tap
			}
			return true;
		}
		if (down) {
			if (!KEYS.keyDown(screen, event)) {
				close(); // Escape without Right Alt
			}
		} else {
			KEYS.keyUp(event);
		}
		return true;
	}

	/** A typed character on its way to the game; true when it was consumed. */
	public static boolean charEvent(final CharacterEvent event) {
		if (screen == null || Minecraft.getInstance().gui.screen() != null) {
			return false;
		}
		if (pendingBound != null) {
			// the character a poked bound key sends right behind its press -- deliver the held press first, so a
			// game watching for Space sees the key, then let the character type as usual
			if (net.minecraft.util.Util.getMillis() - pendingBoundAt <= BOUND_CONFIRM_MS) {
				KEYS.keyDown(screen, pendingBound);
				if (pendingBoundReleased) {
					KEYS.keyUp(pendingBound); // its release already went by, so complete the tap here
				} else {
					deliveredBound.add(pendingBound.key());
				}
			}
			pendingBound = null;
		}
		KEYS.charTyped(screen, event);
		return true;
	}
}
