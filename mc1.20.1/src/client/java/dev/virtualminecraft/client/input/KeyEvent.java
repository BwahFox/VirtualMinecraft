package dev.virtualminecraft.client.input;

/**
 * 1.20.1 only: the shape of 26.2's {@code net.minecraft.client.input.KeyEvent}, so {@link KeyRelay} and
 * {@link WorldKeyboard} read exactly as they do there. 1.20.1 hands a key press over as three ints; the screens and
 * the keyboard mixin fold them into one of these at the edge.
 */
public record KeyEvent(int key, int scancode, int modifiers) {
}
