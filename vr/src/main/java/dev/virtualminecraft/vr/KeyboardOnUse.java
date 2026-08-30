package dev.virtualminecraft.vr;

import dev.virtualminecraft.client.input.WorldKeyboard;
import dev.virtualminecraft.client.pointer.MonitorUse;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * The keyboard gesture (ROADMAP §9 U4.3): <b>use a monitor, get a keyboard</b>. [name]'s design, 2026-08-29, after
 * the first attempt (sneak + interact) was tried in the headset and rejected — <i>"the gesture fights with other
 * things... hitting Interact on the monitor when in vr will open it, and when the keyboard is open, the hotbar
 * shouldn't do anything anyway, so left and right click can remain on those triggers"</i>.
 * <p>
 * <b>Why it is the right button, which is not obvious until you are wearing the headset.</b> On a desktop, using a
 * monitor sends a click at that pixel, because there the mouse and the keyboard are the same device and the click
 * is the only thing "use" could sensibly mean. In VR the controller ray is <em>already</em> the mouse and the
 * triggers are already its buttons ({@link MonitorInteractModule}), so "use" has nothing left to do — which makes
 * it the one free button on the controller and the obvious home for the keyboard. Sneak is not involved at all:
 * sneaking in VR is a joystick click and doing it while holding a ray steady is exactly the fight [name] hit.
 * <p>
 * Sneak + use still opens the full-screen panel, untouched — the main mod passes on the event when the player is
 * sneaking, so the flatscreen gesture keeps working for anyone who wants the panel in VR.
 * <p>
 * It toggles, because the gesture that opens a thing has to be the gesture that closes it: a player with a
 * keyboard in the way and no way to put it down is a player who has to quit the game.
 */
final class KeyboardOnUse implements MonitorUse {
	static final KeyboardOnUse INSTANCE = new KeyboardOnUse();

	/** Whether Vivecraft actually drew the keyboard when we opened it — the seated player gets none, on purpose. */
	private static boolean sawVivecraftKeyboard;

	private KeyboardOnUse() {
	}

	/**
	 * The other way a keyboard closes, found by [name] minutes after "everything's working": Vivecraft's own
	 * keyboard binding (Y on her layout) dismisses the visual keyboard and tells nobody — so the world keyboard
	 * kept capturing, jump stayed swallowed, and it took a second press (a GUI open, whose close path we do hear)
	 * to get movement back. So: while both halves are supposed to be up, watch Vivecraft's {@code SHOWING} once a
	 * tick and fold ours the moment its keyboard is gone, whoever closed it. Guarded by {@link #sawVivecraftKeyboard}
	 * so a seated player — for whom Vivecraft never draws one — keeps the capture the gesture gave them.
	 */
	static void tick() {
		if (sawVivecraftKeyboard && WorldKeyboard.isOpen() && !VivecraftLink.keyboardShowing()) {
			sawVivecraftKeyboard = false;
			WorldKeyboard.close();
			VrDiagnostics.interact("keyboard closed (dismissed on Vivecraft's side)");
		}
	}

	@Override
	public boolean onUse(final UUID screen, final BlockPos pos) {
		if (!VivecraftLink.vrActive()) {
			return false; // desktop client with the VR jar installed: using a monitor still clicks it
		}
		if (WorldKeyboard.isOpen()) {
			WorldKeyboard.close();
			VivecraftLink.keyboard(false);
			sawVivecraftKeyboard = false;
			VrDiagnostics.interact("keyboard closed");
			return true;
		}
		WorldKeyboard.open(screen);
		final boolean showing = VivecraftLink.keyboard(true);
		sawVivecraftKeyboard = showing;
		KeyboardAnchor.locate(); // after opening: the anchor re-pins a keyboard that exists, and now it does
		VrDiagnostics.interact("keyboard opened on screen {} at {} (Vivecraft showing it: {})", screen, pos, showing);
		// Kept open even when Vivecraft refuses to draw one -- it does that for a seated player -- because their
		// real keyboard now reaches the machine, which beats the gesture appearing to do nothing at all.
		return true;
	}
}
