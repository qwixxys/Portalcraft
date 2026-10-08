package dev.portalcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Feeds the input Portal 2 forwards (Windows virtual keys, buttons, wheel, a virtual cursor) into Minecraft's own
 * handlers, the same entry points SDL events reach, so Minecraft can't tell the difference.
 */
public final class InputBridge {
	private static final int MOD_LSHIFT = 0x0001, MOD_LCTRL = 0x0040, MOD_LALT = 0x0100;
	/** Keys Portal 2 holds down for us, by SDL scancode (InputConstants.isKeyDown looks here too). */
	public static final boolean[] DOWN = new boolean[512];

	private final HostLink host;
	private int consumed = -1;
	private int mods;
	public double cursorX, cursorY;

	public InputBridge(HostLink host) {
		this.host = host;
	}

	/** Windows VK -> SDL scancode, for the keys Portal 2 forwards. */
	static int scancode(int vk) {
		if (vk >= 'A' && vk <= 'Z') return 4 + (vk - 'A');
		if (vk >= '1' && vk <= '9') return 30 + (vk - '1');
		if (vk == '0') return 39;
		if (vk >= 0x70 && vk <= 0x7B) return 58 + (vk - 0x70); // F1..F12
		if (vk >= 0x61 && vk <= 0x69) return 89 + (vk - 0x61); // keypad 1..9
		return switch (vk) {
			case 0x1B -> 41; // escape
			case 0x0D -> 40; // return
			case 0x08 -> 42; // backspace
			case 0x09 -> 43; // tab
			case 0x20 -> 44; // space
			case 0x10, 0xA0 -> 225; // shift
			case 0xA1 -> 229;
			case 0x11, 0xA2 -> 224; // control
			case 0xA3 -> 228;
			case 0x12, 0xA4 -> 226; // alt
			case 0xA5 -> 230;
			case 0x14 -> 57; // caps lock
			case 0x27 -> 79; // right
			case 0x25 -> 80; // left
			case 0x28 -> 81; // down
			case 0x26 -> 82; // up
			case 0x2D -> 73; // insert
			case 0x24 -> 74; // home
			case 0x21 -> 75; // page up
			case 0x2E -> 76; // delete
			case 0x23 -> 77; // end
			case 0x22 -> 78; // page down
			case 0x60 -> 98; // keypad 0
			case 0x6A -> 85; // keypad *
			case 0x6B -> 87; // keypad +
			case 0x6D -> 86; // keypad -
			case 0x6E -> 99; // keypad .
			case 0x6F -> 84; // keypad /
			case 0xBA -> 51; // ;
			case 0xBB -> 46; // =
			case 0xBC -> 54; // ,
			case 0xBD -> 45; // -
			case 0xBE -> 55; // .
			case 0xBF -> 56; // /
			case 0xC0 -> 53; // `
			case 0xDB -> 47; // [
			case 0xDC -> 49; // backslash
			case 0xDD -> 48; // ]
			case 0xDE -> 52; // '
			default -> -1;
		};
	}

	/** SDL keycode for the same key (layout-dependent keys are reported as US-QWERTY). */
	static int keycode(int vk, int sc) {
		if (vk >= 'A' && vk <= 'Z') return 'a' + (vk - 'A');
		if (vk >= '0' && vk <= '9') return vk;
		return switch (vk) {
			case 0x1B -> 27;
			case 0x0D -> 13;
			case 0x08 -> 8;
			case 0x09 -> 9;
			case 0x20 -> 32;
			case 0x2E -> 127; // delete
			case 0xBA -> ';';
			case 0xBB -> '=';
			case 0xBC -> ',';
			case 0xBD -> '-';
			case 0xBE -> '.';
			case 0xBF -> '/';
			case 0xC0 -> '`';
			case 0xDB -> '[';
			case 0xDC -> '\\';
			case 0xDD -> ']';
			case 0xDE -> '\'';
			default -> 0x40000000 | sc;
		};
	}

	/** Called once per frame on the render thread. */
	public void pump(boolean screenOpen) {
		Minecraft mc = Minecraft.getInstance();
		long handle = mc.getWindow().handle();
		int written = host.inputCount();
		if (consumed < 0 || written - consumed > 512) consumed = written; // start fresh / skip a backlog
		while (consumed < written) {
			int[] e = host.inputEvent(consumed++);
			switch (e[0]) {
				case 1 -> { // key
					int sc = scancode(e[1]);
					if (sc < 0) break;
					boolean down = e[2] != 0;
					DOWN[sc] = down;
					if (sc == 225 || sc == 229) mods = down ? mods | MOD_LSHIFT : mods & ~MOD_LSHIFT;
					if (sc == 224 || sc == 228) mods = down ? mods | MOD_LCTRL : mods & ~MOD_LCTRL;
					if (sc == 226 || sc == 230) mods = down ? mods | MOD_LALT : mods & ~MOD_LALT;
					mc.keyboardHandler.keyPress(handle, down ? 1 : 0, new KeyEvent(sc, keycode(e[1], sc), mods));
				}
				case 2 -> { // mouse button: 0 left, 1 right, 2 middle -> SDL 1, 3, 2
					int button = e[1] == 0 ? 1 : e[1] == 1 ? 3 : 2;
					mc.mouseHandler.onButton(handle, new MouseButtonInfo(button, mods), e[2] != 0 ? 1 : 0);
				}
				case 3 -> mc.mouseHandler.onScroll(handle, 0.0, e[1]);
				case 5 -> {
					if (screenOpen) mc.keyboardHandler.textInput(handle, new String(Character.toChars(e[1])));
				}
				case 6 -> { // cursor for screens, in Minecraft window pixels; (-1,-1) = centre
					var win = mc.getWindow();
					double x = e[1] < 0 ? win.getScreenWidth() / 2.0 : e[1] * (double) win.getScreenWidth() / Math.max(1, host.width);
					double y = e[2] < 0 ? win.getScreenHeight() / 2.0 : e[2] * (double) win.getScreenHeight() / Math.max(1, host.height);
					mc.mouseHandler.onMove(handle, x, y, x - cursorX, y - cursorY);
					cursorX = x;
					cursorY = y;
				}
				default -> {}
			}
		}
	}

	public void releaseAll() {
		java.util.Arrays.fill(DOWN, false);
		mods = 0;
	}
}
