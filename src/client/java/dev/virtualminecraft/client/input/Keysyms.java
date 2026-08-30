package dev.virtualminecraft.client.input;

import org.lwjgl.glfw.GLFW;

/** GLFW key code / Unicode code point to X11 keysym conversion, as expected by the RFB KeyEvent message. */
public final class Keysyms {
	private Keysyms() {
	}

	public static final int XK_BackSpace = 0xff08;
	public static final int XK_Tab = 0xff09;
	public static final int XK_Return = 0xff0d;
	public static final int XK_Escape = 0xff1b;
	public static final int XK_Delete = 0xffff;

	/** Keysym for keys that do not produce a character (navigation, function and modifier keys), or 0. */
	public static int fromGlfwKey(final int key) {
		return switch (key) {
			case GLFW.GLFW_KEY_ESCAPE -> XK_Escape;
			case GLFW.GLFW_KEY_ENTER -> XK_Return;
			case GLFW.GLFW_KEY_TAB -> XK_Tab;
			case GLFW.GLFW_KEY_BACKSPACE -> XK_BackSpace;
			case GLFW.GLFW_KEY_INSERT -> 0xff63;
			case GLFW.GLFW_KEY_DELETE -> XK_Delete;
			case GLFW.GLFW_KEY_RIGHT -> 0xff53;
			case GLFW.GLFW_KEY_LEFT -> 0xff51;
			case GLFW.GLFW_KEY_DOWN -> 0xff54;
			case GLFW.GLFW_KEY_UP -> 0xff52;
			case GLFW.GLFW_KEY_PAGE_UP -> 0xff55;
			case GLFW.GLFW_KEY_PAGE_DOWN -> 0xff56;
			case GLFW.GLFW_KEY_HOME -> 0xff50;
			case GLFW.GLFW_KEY_END -> 0xff57;
			case GLFW.GLFW_KEY_CAPS_LOCK -> 0xffe5;
			case GLFW.GLFW_KEY_SCROLL_LOCK -> 0xff14;
			case GLFW.GLFW_KEY_NUM_LOCK -> 0xff7f;
			case GLFW.GLFW_KEY_PRINT_SCREEN -> 0xff61;
			case GLFW.GLFW_KEY_PAUSE -> 0xff13;
			case GLFW.GLFW_KEY_F1 -> 0xffbe;
			case GLFW.GLFW_KEY_F2 -> 0xffbf;
			case GLFW.GLFW_KEY_F3 -> 0xffc0;
			case GLFW.GLFW_KEY_F4 -> 0xffc1;
			case GLFW.GLFW_KEY_F5 -> 0xffc2;
			case GLFW.GLFW_KEY_F6 -> 0xffc3;
			case GLFW.GLFW_KEY_F7 -> 0xffc4;
			case GLFW.GLFW_KEY_F8 -> 0xffc5;
			case GLFW.GLFW_KEY_F9 -> 0xffc6;
			case GLFW.GLFW_KEY_F10 -> 0xffc7;
			case GLFW.GLFW_KEY_F11 -> 0xffc8;
			case GLFW.GLFW_KEY_F12 -> 0xffc9;
			case GLFW.GLFW_KEY_KP_0 -> 0xffb0;
			case GLFW.GLFW_KEY_KP_1 -> 0xffb1;
			case GLFW.GLFW_KEY_KP_2 -> 0xffb2;
			case GLFW.GLFW_KEY_KP_3 -> 0xffb3;
			case GLFW.GLFW_KEY_KP_4 -> 0xffb4;
			case GLFW.GLFW_KEY_KP_5 -> 0xffb5;
			case GLFW.GLFW_KEY_KP_6 -> 0xffb6;
			case GLFW.GLFW_KEY_KP_7 -> 0xffb7;
			case GLFW.GLFW_KEY_KP_8 -> 0xffb8;
			case GLFW.GLFW_KEY_KP_9 -> 0xffb9;
			case GLFW.GLFW_KEY_KP_DECIMAL -> 0xffae;
			case GLFW.GLFW_KEY_KP_DIVIDE -> 0xffaf;
			case GLFW.GLFW_KEY_KP_MULTIPLY -> 0xffaa;
			case GLFW.GLFW_KEY_KP_SUBTRACT -> 0xffad;
			case GLFW.GLFW_KEY_KP_ADD -> 0xffab;
			case GLFW.GLFW_KEY_KP_ENTER -> 0xff8d;
			case GLFW.GLFW_KEY_LEFT_SHIFT -> 0xffe1;
			case GLFW.GLFW_KEY_LEFT_CONTROL -> 0xffe3;
			case GLFW.GLFW_KEY_LEFT_ALT -> 0xffe9;
			case GLFW.GLFW_KEY_LEFT_SUPER -> 0xffeb;
			case GLFW.GLFW_KEY_RIGHT_SHIFT -> 0xffe2;
			case GLFW.GLFW_KEY_RIGHT_CONTROL -> 0xffe4;
			case GLFW.GLFW_KEY_RIGHT_ALT -> 0xffea;
			case GLFW.GLFW_KEY_RIGHT_SUPER -> 0xffec;
			case GLFW.GLFW_KEY_MENU -> 0xff67;
			default -> 0;
		};
	}

	/** True for keys whose character arrives through the char callback (letters, digits, punctuation, space). */
	public static boolean isPrintable(final int key) {
		return key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_GRAVE_ACCENT || key == GLFW.GLFW_KEY_WORLD_1 || key == GLFW.GLFW_KEY_WORLD_2;
	}

	/** Keysym to use for a printable GLFW key when a modifier such as Ctrl suppresses the char callback. */
	public static int fromPrintableKey(final int key, final boolean shift) {
		if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
			return shift ? key : Character.toLowerCase(key);
		}
		if (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_GRAVE_ACCENT) {
			return key; // GLFW printable key codes are their ASCII values (unshifted)
		}
		return 0;
	}

	/** Keysym for a Unicode code point. */
	public static int fromCodepoint(final int cp) {
		if (cp >= 0x20 && cp <= 0x7e || cp >= 0xa0 && cp <= 0xff) {
			return cp;
		}
		return 0x01000000 | cp;
	}

	/** True for characters that need Shift on a US keyboard (upper-case letters and the shifted symbol row). */
	public static boolean needsShiftUs(final int cp) {
		return (cp >= 'A' && cp <= 'Z') || (cp < 128 && "~!@#$%^&*()_+{}|:\"<>?".indexOf(cp) >= 0);
	}
}
