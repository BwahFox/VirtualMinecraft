package dev.virtualminecraft.client.mixin;

import dev.virtualminecraft.client.input.WorldKeyboard;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one hook that lets a keyboard reach a monitor nobody has opened (ROADMAP §9 U4.3).
 * <p>
 * <b>Why a mixin, when nothing else here needs one.</b> Minecraft delivers key events to the focused
 * {@code Screen}, and a monitor in the world is not one — so the full-screen panel could always be typed at and
 * an in-world screen never could. There is no event for "a key was pressed with no GUI open" short of a key
 * binding, and a key binding is exactly the wrong shape: we want <em>every</em> key, as itself, including the
 * ones already bound to walking.
 * <p>
 * <b>Why it is safe.</b> {@link WorldKeyboard} is closed unless something deliberately opened it, and while it is
 * closed both injections return immediately and the game is bit-for-bit what it was. That keeps §9 U4.0's
 * load-bearing promise — a build without the VR jar behaves exactly as one without this feature — because the
 * only thing that opens it today lives in the VR jar.
 * <p>
 * Both targets are private in 26.2 (Vivecraft widens them for its own use; a mixin does not need to). Their
 * signatures are part of the <b>update surface</b> HANDOFF tracks: {@code keyPress(long, int, KeyEvent)} and
 * {@code charTyped(long, CharacterEvent)}.
 * <p>
 * Vivecraft's floating keyboard arrives here too, and that is the point rather than a coincidence: it types by
 * synthesising real GLFW events through {@code InputSimulator} into this very class, so a poked key and a pressed
 * one are the same event by the time we see them — which is what §9 U4.0 said text had to be.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void virtualminecraft$keyPress(final long window, final int action, final KeyEvent event, final CallbackInfo ci) {
		if (!WorldKeyboard.isOpen() || action == GLFW.GLFW_REPEAT) {
			return; // repeats are the guest's own business; it decides its own repeat rate
		}
		if (WorldKeyboard.keyEvent(event, action == GLFW.GLFW_PRESS)) {
			ci.cancel();
		}
	}

	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void virtualminecraft$charTyped(final long window, final CharacterEvent event, final CallbackInfo ci) {
		if (WorldKeyboard.isOpen() && WorldKeyboard.charEvent(event)) {
			ci.cancel();
		}
	}
}
