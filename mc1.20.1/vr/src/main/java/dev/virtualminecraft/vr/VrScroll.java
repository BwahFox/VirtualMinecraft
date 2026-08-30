package dev.virtualminecraft.vr;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Scroll as two key mappings ([name]'s idea, 2026-08-29: <i>"why not just make scrolling happen by pressing up and
 * down on the right stick?"</i>). No thumbstick API is involved and none was ever needed: Vivecraft's
 * {@code MCVR.populateInputActions()} iterates {@code mc.options.keyMappings}, so <b>any KeyMapping a mod registers
 * becomes a bindable VR action</b> in its controller settings — she binds these two to the stick directions once,
 * by hand, and nothing scrolls until she does.
 * <p>
 * The mappings ship <b>unbound</b> on purpose, and that is load-bearing rather than laziness: Vivecraft presses a
 * binding by synthesising a real key press of whatever key it is bound to, but for a mapping with no key it falls
 * back to pressing the {@code KeyMapping} object directly ({@code VRInputAction.setKeyBindState} — confirmed
 * against the 26.2 Multiloader source). The direct path is the good one here, twice over: no synthesised key means
 * nothing for the world keyboard's mixin to swallow while typing, and nothing for the stick-click fix to mistake
 * for a movement key.
 * <p>
 * Notches go to {@link VrPointerSource}, whose {@code takeWheel()} the main mod already polls — the scroll lands
 * on whatever screen the controller points at, like every other pointer event. Holding the stick repeats after a
 * moment, because a long page on a held stick should not need thirty clicks.
 */
final class VrScroll {
	/** Ticks a direction must be held before it starts repeating, then how many ticks between repeats. */
	private static final int REPEAT_DELAY = 8;
	private static final int REPEAT_EVERY = 4;

	private static KeyMapping up;
	private static KeyMapping down;
	private static int heldUp;
	private static int heldDown;

	private VrScroll() {
	}

	static void register() {
		// 1.20.1: a category is its translation key (the same one 26.2's Category registers under)
		final String category = "key.category.virtualminecraft.vr";
		up = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.virtualminecraft.vr_scroll_up", GLFW.GLFW_KEY_UNKNOWN, category));
		down = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.virtualminecraft.vr_scroll_down", GLFW.GLFW_KEY_UNKNOWN, category));
	}

	/** Turn presses into wheel notches. Called once a client tick by {@link VrModule}. */
	static void tick() {
		int notches = 0;
		while (up.consumeClick()) {
			notches++;
		}
		while (down.consumeClick()) {
			notches--;
		}
		heldUp = up.isDown() ? heldUp + 1 : 0;
		heldDown = down.isDown() ? heldDown + 1 : 0;
		if (heldUp > REPEAT_DELAY && heldUp % REPEAT_EVERY == 0) {
			notches++;
		}
		if (heldDown > REPEAT_DELAY && heldDown % REPEAT_EVERY == 0) {
			notches--;
		}
		if (notches != 0) {
			VrPointerSource.INSTANCE.addWheel(notches);
		}
	}
}
