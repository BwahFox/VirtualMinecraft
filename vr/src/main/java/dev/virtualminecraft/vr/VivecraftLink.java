package dev.virtualminecraft.vr;

import net.minecraft.world.InteractionHand;
import org.jspecify.annotations.Nullable;
import org.vivecraft.api.client.VRClientAPI;
import org.vivecraft.api.client.data.CloseKeyboardContext;
import org.vivecraft.api.client.data.OpenKeyboardContext;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;

/**
 * The one place that touches Vivecraft, kept deliberately tiny (ROADMAP §9 U4.0). It exists so the compile-only
 * wiring is *proved* rather than assumed — if the jar in {@code vivecraft/} moves or its API shifts, this file
 * stops compiling, which is the point — and so that everything else in this module can be read without knowing
 * Vivecraft at all.
 * <p>
 * The API was confirmed with {@code javap} against {@code vivecraft-26.2-1.3.15-fabric.jar} on 2026-08-29 and is
 * identical to the 26.1.2 build. What matters for the seam:
 * <ul>
 * <li>{@code VRClientAPI.instance()} — a static {@code INSTANCE} field, so it is safe at any time; the mod is
 *     present or the class is not.</li>
 * <li>{@code isVRActive()} — false on a desktop client even with Vivecraft installed, which is the gate.</li>
 * <li>{@code getWorldRenderPose().getMainHand()} → {@code VRBodyPartData}, whose {@code getPos()} and
 *     {@code getDir()} are already {@code Vec3}. That is exactly a {@code PointerRay}, so {@link VrPointerSource}
 *     is a handful of lines on top of this.</li>
 * </ul>
 * <b>Buttons are not here on purpose.</b> Vivecraft has no "is the trigger down" to poll; the trigger arrives
 * through an {@code InteractModule} — see {@link MonitorInteractModule}, which is a better fit anyway: it makes a
 * monitor something Vivecraft knows can be pointed at rather than something fighting everything else for the
 * trigger.
 */
public final class VivecraftLink {
	private VivecraftLink() {
	}

	/** Whether a headset is actually driving this client right now. False on a desktop client. */
	public static boolean vrActive() {
		final VRClientAPI api = VRClientAPI.instance();
		return api != null && api.isVRActive();
	}

	/** The pose to build a controller ray from, or null when there is no VR. */
	public static @Nullable VRBodyPartData mainHand() {
		return hand(null);
	}

	/**
	 * One hand's pose, or the dominant one when {@code hand} is null. {@code getMainHand()} already accounts for
	 * left-handedness, so nothing here has to; asking for {@code MAIN_HAND} on a left-handed player gives the left
	 * controller, which is what "dominant hand" means and what §9 U4.2 asks for.
	 * <p>
	 * The <em>render</em> pose rather than the tick pose deliberately: it is the freshest sample Vivecraft has, and
	 * WiVRn already adds 30–50 ms that a tick-old pose would only make worse.
	 */
	/**
	 * Show or hide Vivecraft's floating keyboard (§9 U4.3). {@code FORCE} on the way in deliberately: the polite
	 * contexts obey the player's "auto-open keyboard" setting, and somebody who has that switched off asking for a
	 * keyboard by hand should still get one. Returns whether it is showing afterwards — it can refuse, and does
	 * when the player is seated or in kiosk mode.
	 */
	public static boolean keyboard(final boolean show) {
		if (!vrActive()) {
			return false;
		}
		return show
			? VRClientAPI.instance().openKeyboard(OpenKeyboardContext.FORCE)
			: VRClientAPI.instance().closeKeyboard(CloseKeyboardContext.FORCE);
	}

	/**
	 * Whether Vivecraft is drawing its keyboard right now. Internals rather than API ({@code KeyboardHandler.SHOWING})
	 * because the API only answers for requests it made — and the whole point of asking is to notice the player
	 * dismissing the keyboard through Vivecraft's <em>own</em> binding, which never tells us.
	 */
	public static boolean keyboardShowing() {
		return vrActive() && org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler.SHOWING;
	}

	/**
	 * Pin Vivecraft's floating keyboard to a spot in the <em>world</em> (§9 U4.3's second half, the Keyboard
	 * block). This is the one place the module reaches past Vivecraft's API into its internals, and it is exactly
	 * the mechanism HANDOFF named: {@code KeyboardHandler.POS_ROOM} / {@code ROTATION_ROOM} are public static
	 * fields, written once by {@code orientOverlay} when the keyboard opens (confirmed against the 26.2
	 * Multiloader source: that is its only caller) and read every frame after — so writing them after opening
	 * sticks. They are <b>room</b>-space, and the room moves with the player, which is why this is called every
	 * client tick while anchored rather than once: {@code VRPlayer.worldToRoomPos} re-derives the room position
	 * from wherever the play-space origin is now.
	 * <p>
	 * The yaw convention was derived from the same source rather than guessed: {@code orientOverlay}'s static
	 * branch builds {@code rotationY(π + atan2(look.x, look.z))} with {@code look} pointing head → keyboard, so a
	 * keyboard whose keys face world direction {@code f} wants world yaw {@code atan2(f.x, f.z)}, and room yaw is
	 * that minus {@code rotation_radians} (the room's own yaw inside the world). In Vivecraft's physical-keyboard
	 * mode the natural attitude is its own lie-flat one ({@code rotateX(π·0.8)}, from its branch of
	 * {@code orientOverlay}); the panel keyboard leans back by {@code vrKeyboardTilt} instead.
	 *
	 * @param worldPos    where the keyboard's centre should hover, in world coordinates
	 * @param worldYawRad which way the keys face, as {@code atan2(f.x, f.z)} of the facing vector
	 * @param tiltDeg     degrees the panel leans back from vertical (ignored in physical-keyboard mode)
	 */
	public static void anchorKeyboard(final net.minecraft.world.phys.Vec3 worldPos, final float worldYawRad, final double tiltDeg) {
		if (!vrActive() || !org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler.SHOWING) {
			return;
		}
		final org.vivecraft.client_vr.ClientDataHolderVR dh = org.vivecraft.client_vr.ClientDataHolderVR.getInstance();
		final org.vivecraft.client_vr.VRData data = dh.vrPlayer.vrdata_world_pre;
		if (data == null) {
			return;
		}
		final org.joml.Vector3f room = org.vivecraft.client_vr.gameplay.VRPlayer.worldToRoomPos(worldPos, data);
		final float roomYaw = worldYawRad - data.rotation_radians;
		final org.joml.Matrix4f rotation = new org.joml.Matrix4f().rotationY(roomYaw);
		if (dh.vrSettings.physicalKeyboard) {
			rotation.rotateX((float) Math.PI * 0.8F);
		} else {
			rotation.rotateX((float) Math.toRadians(-tiltDeg));
		}
		org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler.POS_ROOM = room;
		org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler.ROTATION_ROOM = rotation;
	}

	public static @Nullable VRBodyPartData hand(final @Nullable InteractionHand hand) {
		if (!vrActive()) {
			return null;
		}
		final VRPose pose = VRClientAPI.instance().getWorldRenderPose();
		if (pose == null) {
			return null;
		}
		return hand == null ? pose.getMainHand() : pose.getHand(hand);
	}
}
