package dev.virtualminecraft.client.input;

import org.lwjgl.glfw.GLFW;

/**
 * GLFW key → QEMU <em>key number</em>, the value the D-Bus {@code Keyboard.Press} takes: an XT/AT set-1 scancode,
 * with the {@code 0xe0}-prefixed extended keys folded into bit 7 ({@code 0xe048} → {@code 0xc8}) and Pause as
 * {@code 0xc6}. GLFW keys are named after the US layout but identify <em>physical</em> keys, which is exactly
 * what a scancode is: the guest's own keyboard layout then decides what the key means, so a German guest gets a
 * Z from the key labelled Y, and a game gets WASD wherever they are. Generated from QEMU's keycodemapdb
 * ({@code data/keymaps.csv}, AT set1 column).
 */
public final class QCodes {
	private QCodes() {
	}

	/** 0 for keys with no QEMU equivalent. */
	public static int fromGlfw(final int key) {
		return switch (key) {
			case GLFW.GLFW_KEY_ESCAPE -> 0x01;
			case GLFW.GLFW_KEY_1 -> 0x02;
			case GLFW.GLFW_KEY_2 -> 0x03;
			case GLFW.GLFW_KEY_3 -> 0x04;
			case GLFW.GLFW_KEY_4 -> 0x05;
			case GLFW.GLFW_KEY_5 -> 0x06;
			case GLFW.GLFW_KEY_6 -> 0x07;
			case GLFW.GLFW_KEY_7 -> 0x08;
			case GLFW.GLFW_KEY_8 -> 0x09;
			case GLFW.GLFW_KEY_9 -> 0x0a;
			case GLFW.GLFW_KEY_0 -> 0x0b;
			case GLFW.GLFW_KEY_MINUS -> 0x0c;
			case GLFW.GLFW_KEY_EQUAL -> 0x0d;
			case GLFW.GLFW_KEY_BACKSPACE -> 0x0e;
			case GLFW.GLFW_KEY_TAB -> 0x0f;
			case GLFW.GLFW_KEY_Q -> 0x10;
			case GLFW.GLFW_KEY_W -> 0x11;
			case GLFW.GLFW_KEY_E -> 0x12;
			case GLFW.GLFW_KEY_R -> 0x13;
			case GLFW.GLFW_KEY_T -> 0x14;
			case GLFW.GLFW_KEY_Y -> 0x15;
			case GLFW.GLFW_KEY_U -> 0x16;
			case GLFW.GLFW_KEY_I -> 0x17;
			case GLFW.GLFW_KEY_O -> 0x18;
			case GLFW.GLFW_KEY_P -> 0x19;
			case GLFW.GLFW_KEY_LEFT_BRACKET -> 0x1a;
			case GLFW.GLFW_KEY_RIGHT_BRACKET -> 0x1b;
			case GLFW.GLFW_KEY_ENTER -> 0x1c;
			case GLFW.GLFW_KEY_LEFT_CONTROL -> 0x1d;
			case GLFW.GLFW_KEY_A -> 0x1e;
			case GLFW.GLFW_KEY_S -> 0x1f;
			case GLFW.GLFW_KEY_D -> 0x20;
			case GLFW.GLFW_KEY_F -> 0x21;
			case GLFW.GLFW_KEY_G -> 0x22;
			case GLFW.GLFW_KEY_H -> 0x23;
			case GLFW.GLFW_KEY_J -> 0x24;
			case GLFW.GLFW_KEY_K -> 0x25;
			case GLFW.GLFW_KEY_L -> 0x26;
			case GLFW.GLFW_KEY_SEMICOLON -> 0x27;
			case GLFW.GLFW_KEY_APOSTROPHE -> 0x28;
			case GLFW.GLFW_KEY_GRAVE_ACCENT -> 0x29;
			case GLFW.GLFW_KEY_LEFT_SHIFT -> 0x2a;
			case GLFW.GLFW_KEY_BACKSLASH -> 0x2b;
			case GLFW.GLFW_KEY_Z -> 0x2c;
			case GLFW.GLFW_KEY_X -> 0x2d;
			case GLFW.GLFW_KEY_C -> 0x2e;
			case GLFW.GLFW_KEY_V -> 0x2f;
			case GLFW.GLFW_KEY_B -> 0x30;
			case GLFW.GLFW_KEY_N -> 0x31;
			case GLFW.GLFW_KEY_M -> 0x32;
			case GLFW.GLFW_KEY_COMMA -> 0x33;
			case GLFW.GLFW_KEY_PERIOD -> 0x34;
			case GLFW.GLFW_KEY_SLASH -> 0x35;
			case GLFW.GLFW_KEY_RIGHT_SHIFT -> 0x36;
			case GLFW.GLFW_KEY_KP_MULTIPLY -> 0x37;
			case GLFW.GLFW_KEY_LEFT_ALT -> 0x38;
			case GLFW.GLFW_KEY_SPACE -> 0x39;
			case GLFW.GLFW_KEY_CAPS_LOCK -> 0x3a;
			case GLFW.GLFW_KEY_F1 -> 0x3b;
			case GLFW.GLFW_KEY_F2 -> 0x3c;
			case GLFW.GLFW_KEY_F3 -> 0x3d;
			case GLFW.GLFW_KEY_F4 -> 0x3e;
			case GLFW.GLFW_KEY_F5 -> 0x3f;
			case GLFW.GLFW_KEY_F6 -> 0x40;
			case GLFW.GLFW_KEY_F7 -> 0x41;
			case GLFW.GLFW_KEY_F8 -> 0x42;
			case GLFW.GLFW_KEY_F9 -> 0x43;
			case GLFW.GLFW_KEY_F10 -> 0x44;
			case GLFW.GLFW_KEY_NUM_LOCK -> 0x45;
			case GLFW.GLFW_KEY_SCROLL_LOCK -> 0x46;
			case GLFW.GLFW_KEY_KP_7 -> 0x47;
			case GLFW.GLFW_KEY_KP_8 -> 0x48;
			case GLFW.GLFW_KEY_KP_9 -> 0x49;
			case GLFW.GLFW_KEY_KP_SUBTRACT -> 0x4a;
			case GLFW.GLFW_KEY_KP_4 -> 0x4b;
			case GLFW.GLFW_KEY_KP_5 -> 0x4c;
			case GLFW.GLFW_KEY_KP_6 -> 0x4d;
			case GLFW.GLFW_KEY_KP_ADD -> 0x4e;
			case GLFW.GLFW_KEY_KP_1 -> 0x4f;
			case GLFW.GLFW_KEY_KP_2 -> 0x50;
			case GLFW.GLFW_KEY_KP_3 -> 0x51;
			case GLFW.GLFW_KEY_KP_0 -> 0x52;
			case GLFW.GLFW_KEY_KP_DECIMAL -> 0x53;
			case GLFW.GLFW_KEY_PRINT_SCREEN -> 0x54;
			case GLFW.GLFW_KEY_WORLD_1 -> 0x56;
			case GLFW.GLFW_KEY_F11 -> 0x57;
			case GLFW.GLFW_KEY_F12 -> 0x58;
			case GLFW.GLFW_KEY_KP_ENTER -> 0x9c;
			case GLFW.GLFW_KEY_RIGHT_CONTROL -> 0x9d;
			case GLFW.GLFW_KEY_MENU -> 0x9e;
			case GLFW.GLFW_KEY_KP_DIVIDE -> 0xb5;
			case GLFW.GLFW_KEY_RIGHT_ALT -> 0xb8;
			case GLFW.GLFW_KEY_PAUSE -> 0xc6;
			case GLFW.GLFW_KEY_HOME -> 0xc7;
			case GLFW.GLFW_KEY_UP -> 0xc8;
			case GLFW.GLFW_KEY_PAGE_UP -> 0xc9;
			case GLFW.GLFW_KEY_LEFT -> 0xcb;
			case GLFW.GLFW_KEY_RIGHT -> 0xcd;
			case GLFW.GLFW_KEY_END -> 0xcf;
			case GLFW.GLFW_KEY_DOWN -> 0xd0;
			case GLFW.GLFW_KEY_PAGE_DOWN -> 0xd1;
			case GLFW.GLFW_KEY_INSERT -> 0xd2;
			case GLFW.GLFW_KEY_DELETE -> 0xd3;
			case GLFW.GLFW_KEY_LEFT_SUPER -> 0xdb;
			case GLFW.GLFW_KEY_RIGHT_SUPER -> 0xdc;
			default -> 0;
		};
	}
}
