package dev.virtualminecraft.vr;

import dev.virtualminecraft.client.pointer.MonitorUse;
import dev.virtualminecraft.client.pointer.Pointers;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vivecraft.api.client.VRClientAPI;

/**
 * The VR module's entrypoint (ROADMAP §9 U4). Two registrations and nothing else, which is what the seam was built
 * to make possible: a ray goes in one side, and the main mod's ray → monitor → pixel → {@code InputSender} path —
 * unchanged, and already proved without a headset by {@code FakePointerSource} — comes out the other.
 * <ul>
 * <li>{@link VrPointerSource} (U4.2) — the dominant controller's ray, registered above the camera so it takes over
 *     the moment a headset is driving the client.</li>
 * <li>{@link MonitorInteractModule} (U4.2) — the trigger, bid for through Vivecraft's contextual interact binding
 *     rather than polled.</li>
 * <li>{@link KeyboardOnUse} (U4.3) — the keyboard, on the "use" button, which VR leaves free because the
 *     controller is already the mouse.</li>
 * <li>{@link KeyboardAnchor} (U4.3, the Keyboard block) — pins the floating keyboard over the nearest Keyboard
 *     block, re-pinned once a tick because the pin is room-space and the room moves.</li>
 * <li>{@link VrScroll} ([name]'s stick-scroll) — two bindable key mappings feeding the pointer's wheel.</li>
 * </ul>
 * Parked, on the record: the desk mouse and controllers-as-gamepad (U4.4) — for software that wants relative
 * mouse motion, which none does yet.
 * <p>
 * <b>Nothing registered means nothing changed.</b> Registering the pointer source unconditionally is not a
 * violation of that: {@link VrPointerSource#ray()} returns null whenever {@code isVRActive()} is false, so on a
 * desktop client with this jar installed the camera is still the only source pointing and the game behaves exactly
 * as it does without it. TESTING's monitor recipes are the regression for that and must pass unchanged.
 * <p>
 * <b>The rules this module exists to enforce</b>, both worth keeping as it fills up:
 * <ul>
 * <li><b>Vivecraft is {@code compileOnly} and always will be.</b> We never redistribute anyone else's code
 *     (HANDOFF's standing rule); if Vivecraft itself ever needs changing, that is a separate patch plus an apply
 *     tool, or preferably upstream.</li>
 * <li><b>Check before you touch.</b> Vivecraft is not a stable API. Everything reached for here was confirmed
 *     against the actual jar with {@code javap} — and for the interact modules, against the Multiloader source
 *     beside it, because {@code InteractTracker}'s call order is the contract and no signature says what it is.</li>
 * </ul>
 */
public final class VrModule implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("virtualminecraft-vr");

	@Override
	public void onInitializeClient() {
		Pointers.register(VrPointerSource.INSTANCE);
		MonitorUse.Registry.set(KeyboardOnUse.INSTANCE);
		VrScroll.register();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			VrScroll.tick();
			KeyboardOnUse.tick(); // fold the world keyboard when Vivecraft's was dismissed by its own binding
			KeyboardAnchor.tick();
		});
		try {
			// Has to happen before the game loop starts, which client init comfortably is: Vivecraft closes
			// registration on the first resource reload and throws afterwards.
			VRClientAPI.instance().addClientRegistrationHandler(event -> event.registerInteractModules(MonitorInteractModule.INSTANCE));
			LOGGER.info("VR pointer registered; the trigger comes from Vivecraft's interact binding");
		} catch (final RuntimeException e) {
			// Pointing survives without it -- the ray still moves the guest's mouse, there is simply no click.
			// Worth a loud line rather than a crash: a player whose hover works and whose trigger does not needs
			// to be told which half failed.
			LOGGER.error("Could not register the monitor interact module; pointing will work, clicking will not", e);
		}
	}
}
