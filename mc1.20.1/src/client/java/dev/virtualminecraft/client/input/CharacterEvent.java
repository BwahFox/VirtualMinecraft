package dev.virtualminecraft.client.input;

/** 1.20.1 only: the shape of 26.2's {@code net.minecraft.client.input.CharacterEvent} (see {@link KeyEvent}). */
public record CharacterEvent(int codepoint, int modifiers) {
	public CharacterEvent(final int codepoint) {
		this(codepoint, 0);
	}
}
