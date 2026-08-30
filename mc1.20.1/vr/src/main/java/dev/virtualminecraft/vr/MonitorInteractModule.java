package dev.virtualminecraft.vr;

import dev.virtualminecraft.client.pointer.PointerRay;
import dev.virtualminecraft.client.pointer.WorldPointer;
import dev.virtualminecraft.config.VmcConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.vivecraft.api.client.HeldInteractModule;

/**
 * The trigger, as Vivecraft wants it asked for (ROADMAP §9 U4.2). Vivecraft has no "is the button down" to poll;
 * it has a <em>contextual</em> interact binding that modules bid for, one module per hand per tick, and the winner
 * gets the button. So instead of fighting everything else for the trigger, a monitor becomes a thing Vivecraft
 * knows can be pointed at — {@code isActive} says "my thing is under this hand right now", and Vivecraft enables
 * the binding, gives the player a haptic tick, and calls {@link #onPress} when they pull it.
 * <p>
 * <b>The mapping</b>, which is §9 U4.2's "trigger = left click; a second button = right click" expressed in what
 * Vivecraft actually binds: both hands hit-test the <em>dominant</em> hand's ray, so you aim with one hand and
 * both triggers act on the pixel you are aiming at. Dominant trigger (or grip — the Oculus defaults bind
 * {@code vrinteract} to both, on both hands) is left click; the other hand's is right click. Aiming with the off
 * hand and clicking with the main one would land the click where the main hand points, which is a lie, so the ray
 * is the dominant hand's and only the dominant hand's.
 * <p>
 * <b>The panel is a different button, not a different distance.</b> §9 U4.1 asked whether in-world use or the
 * full-screen panel is the VR-native way, and it turned out to need no arbitration at all: the panel opens on
 * Minecraft's ordinary "use" (A on the Oculus defaults) while this module is on Vivecraft's interact binding
 * (trigger and grip). Both work at any distance and never contend. {@code vrPointerTouchRange} is a
 * disabled-by-default escape hatch from an earlier design that assumed otherwise and only ever created a dead
 * zone near the screen; the config field records what that cost.
 * <p>
 * <b>Priority</b>: a live screen under the ray outranks everything, including Vivecraft's interactive hotbar —
 * see {@link #getPriority()}, which is where that was got wrong once already.
 */
public final class MonitorInteractModule implements HeldInteractModule {
	public static final MonitorInteractModule INSTANCE = new MonitorInteractModule();

	private static final ResourceLocation ID = new ResourceLocation("virtualminecraft", "monitor_pointer");

	private MonitorInteractModule() {
	}

	@Override
	public ResourceLocation getId() {
		return ID;
	}

	@Override
	public int getPriority() {
		// Lower wins: InteractTracker sorts ascending and takes the first module whose isActive says yes.
		//
		// 900 puts a monitor behind Vivecraft's interactive hotbar (0) and bow (500) and ahead of block (1500)
		// and entity interaction and the 1000 default. It was briefly -100, to answer "the game's hotbar takes
		// over" ([name], 2026-08-29) -- and that was a misdiagnosis that cost her a worse bug: with the hand taken
		// from the hotbar, clicking a slot broke whatever the controller was aimed at. Reverted.
		//
		// The reason -100 was never needed: InteractiveHotbarModule.isActive requires the hand within **0.06 m**
		// of the hotbar line, main hand only. It cannot "take over" at large; it wins exactly when the player has
		// put their hand on their own hotbar, which is the one moment it should. Whatever [name] hit, that number
		// says it was not a monitor losing to a greedy hotbar, and the next attempt should start by measuring
		// rather than by moving this constant again.
		return 900;
	}

	@Override
	public boolean isActive(final LocalPlayer player, final InteractionHand hand, final Vec3 handPosition) {
		final PointerRay ray = VrPointerSource.rawRay(null); // always the dominant hand: see the class comment
		if (ray == null) {
			return false;
		}
		final WorldPointer.@Nullable Hit hit = WorldPointer.probe(ray);
		final boolean active = hit != null && hit.distance() >= Math.max(0.0, VmcConfig.get().vrPointerTouchRange);
		if (hit != null && !active) {
			VrDiagnostics.interactThrottled("too close to point ({} blocks < {}), leaving the hand to block interaction",
				hit.distance(), VmcConfig.get().vrPointerTouchRange);
		}
		return active;
	}

	@Override
	public boolean onPress(final LocalPlayer player, final InteractionHand hand) {
		VrPointerSource.INSTANCE.setButton(rfbBit(hand), true);
		VrDiagnostics.interact("press {} -> RFB button {} at pixel {},{} on screen {}",
			hand, rfbBit(hand), WorldPointer.x(), WorldPointer.y(), WorldPointer.screen());
		// True means "it worked", which is what earns the player Vivecraft's 750-length haptic pulse. §9 U4.5
		// asks for feedback on *press* rather than release because WiVRn adds 30-50 ms, and this is exactly that,
		// for free, from the mod whose latency it is.
		return true;
	}

	@Override
	public boolean onHoldTick(final LocalPlayer player, final InteractionHand hand) {
		// Keep the button down for as long as the trigger is, without re-testing what is under the hand: a drag
		// that wanders off the edge of a screen is still the same drag, exactly as a desktop mouse behaves.
		return true;
	}

	@Override
	public void onRelease(final LocalPlayer player, final InteractionHand hand) {
		VrPointerSource.INSTANCE.setButton(rfbBit(hand), false);
		VrDiagnostics.interact("release {}", hand);
	}

	@Override
	public void reset(final LocalPlayer player, final InteractionHand hand) {
		// Called every tick that this hand is being re-evaluated, and once more after onRelease. Clearing the bit
		// is the belt to that braces: the one bug a player cannot fix from inside the guest is a stuck button.
		VrPointerSource.INSTANCE.setButton(rfbBit(hand), false);
	}

	@Override
	public boolean swingsArm() {
		return false; // clicking a screen is not a punch
	}

	/** Dominant hand → RFB left (1); the other hand → RFB right (4). Middle stays for the desk mouse (U4.4). */
	private static int rfbBit(final InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND ? 1 : 4;
	}
}
