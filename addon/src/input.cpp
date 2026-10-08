// Routes the player's input: Portal 2 keeps movement and the view, Minecraft gets building controls,
// T and / open Minecraft's chat in any mode, and while a Minecraft screen (inventory, chat) is open it gets
// everything plus a virtual cursor. While Minecraft drives the player (holes, elytra) it gets the movement keys too.
#include "input.hpp"
#include "bridge.hpp"
#include "render.hpp"
#include "game.hpp"
#include <windows.h>
#include <windowsx.h>
#include <cstdio>

namespace input
{
	bool build = false;
	bool third = false;
	bool drive = false;
	static HWND s_hwnd = nullptr;
	static WNDPROC s_orig = nullptr;
	static bool s_screen = false;          // Minecraft has a screen open
	static float s_cx = 0.5f, s_cy = 0.5f; // virtual cursor
	static bool s_down[256] = {};          // the add-on's own keys (B, F5, V)
	static bool s_sent[256] = {};          // keys Minecraft was told are down
	static unsigned s_chars = 0, s_keydowns = 0; // diagnostics

	// keys Minecraft gets while building (movement stays with Portal 2)
	static const int k_build_keys[] = { 'E', 'Q', 'F', '1', '2', '3', '4', '5', '6', '7', '8', '9', VK_F3 };
	// chat and commands, in every mode
	static const int k_chat_keys[] = { 'T', VK_OEM_2 };
	// Minecraft's movement while it drives the player
	// (Ctrl is Portal 2's crouch: it sneaks too, so a hole nearby doesn't turn it into sprint; double-tap W sprints)
	static const int k_drive_keys[] = { 'W', 'A', 'S', 'D', VK_SPACE, VK_LSHIFT };

	enum : uint32_t { IN_ATTACK = 1u << 0, IN_JUMP = 1u << 1, IN_DUCK = 1u << 2, IN_FORWARD = 1u << 3, IN_BACK = 1u << 4,
		IN_USE = 1u << 5, IN_MOVELEFT = 1u << 9, IN_MOVERIGHT = 1u << 10, IN_ATTACK2 = 1u << 11, IN_RELOAD = 1u << 13,
		IN_ZOOM = 1u << 19 };

	static bool game_has_focus()
	{
		return s_hwnd && GetForegroundWindow() == s_hwnd && game::in_game() && !game::console_visible() && !game::paused();
	}

	static LRESULT CALLBACK wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
	{
		// F5 is Minecraft's view toggle here, not Portal 2's screenshot key
		if ((msg == WM_KEYDOWN || msg == WM_KEYUP) && wp == VK_F5 && bridge::guest_alive())
			return 0;
		// T and / open Minecraft's chat (Portal 2 configs often bind them to its console, which stays on ~)
		if ((msg == WM_KEYDOWN || msg == WM_KEYUP) && (wp == 'T' || wp == VK_OEM_2) && bridge::guest_alive() && game_has_focus())
			return 0;
		// while a Minecraft screen is open the keyboard belongs to Minecraft only (Esc would open Portal 2's pause
		// menu, E would grab a cube, ~ would open its console); typed characters go to Minecraft's text fields
		if (s_screen && game_has_focus())
		{
			if (msg == WM_CHAR) { s_chars++; bridge::push_event(bridge::EV_CHAR, static_cast<int32_t>(wp)); return 0; }
			if (msg == WM_KEYDOWN) s_keydowns++;
			if (msg == WM_KEYDOWN || msg == WM_KEYUP || msg == WM_SYSKEYDOWN || msg == WM_SYSKEYUP || msg == WM_SYSCHAR || msg == WM_DEADCHAR)
				return 0;
		}
		if ((build || drive || s_screen) && game_has_focus())
		{
			switch (msg)
			{
			case WM_MOUSEWHEEL:
				bridge::push_event(bridge::EV_WHEEL, GET_WHEEL_DELTA_WPARAM(wp) / WHEEL_DELTA);
				return 0; // Portal 2's wheel zoom stays out of the way
			case WM_INPUT:
				if (s_screen)
				{
					RAWINPUT ri; UINT size = sizeof(ri);
					if (GetRawInputData(reinterpret_cast<HRAWINPUT>(lp), RID_INPUT, &ri, &size, sizeof(RAWINPUTHEADER)) != (UINT)-1 &&
						ri.header.dwType == RIM_TYPEMOUSE && !(ri.data.mouse.usFlags & MOUSE_MOVE_ABSOLUTE))
					{
						RECT rc; GetClientRect(hwnd, &rc);
						s_cx += ri.data.mouse.lLastX / float(rc.right > 0 ? rc.right : 1);
						s_cy += ri.data.mouse.lLastY / float(rc.bottom > 0 ? rc.bottom : 1);
						s_cx = s_cx < 0 ? 0 : (s_cx > 1 ? 1 : s_cx);
						s_cy = s_cy < 0 ? 0 : (s_cy > 1 ? 1 : s_cy);
						bridge::push_event(bridge::EV_CURSOR, int(s_cx * rc.right), int(s_cy * rc.bottom));
					}
				}
				break;
			}
		}
		return CallWindowProcW(s_orig, hwnd, msg, wp, lp);
	}

	void attach(HWND hwnd)
	{
		if (s_hwnd || !hwnd) return;
		s_hwnd = hwnd;
		s_orig = reinterpret_cast<WNDPROC>(SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(&wndproc)));
	}

	void detach()
	{
		if (s_hwnd && s_orig) SetWindowLongPtrW(s_hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(s_orig));
		s_hwnd = nullptr;
	}

	static bool edge(int vk, bool now)
	{
		bool was = s_down[vk & 255];
		s_down[vk & 255] = now;
		return now != was;
	}

	static bool is_button(int vk) { return vk == VK_LBUTTON || vk == VK_RBUTTON || vk == VK_MBUTTON; }

	static void send(int vk, bool down)
	{
		s_sent[vk] = down;
		if (is_button(vk))
			bridge::push_event(bridge::EV_BUTTON, vk == VK_LBUTTON ? 0 : vk == VK_RBUTTON ? 1 : 2, down ? 1 : 0);
		else
			bridge::push_event(bridge::EV_KEY, vk, down ? 1 : 0);
	}

	static void set_build(bool on)
	{
		if (on == build) return;
		build = on;
		// building: Minecraft's hand and crosshair; portal gun: Portal 2's
		game::console_cmd(build ? "r_drawviewmodel 0; crosshair 0" : "r_drawviewmodel 1; crosshair 1");
	}

	void poll()
	{
		bool focus = game_has_focus();
		bool alive = bridge::guest_alive();
		uint32_t gf = alive ? bridge::guest_flags() : 0;
		drive = (gf & bridge::G_DRIVE) != 0;

		// B toggles building (only when Minecraft is connected); while Minecraft drives, you are Steve
		bool b = focus && (GetAsyncKeyState('B') & 0x8000) != 0;
		if (edge('B', b) && b && !s_screen && alive && !drive)
			set_build(!build);
		if (!alive) set_build(false);
		else if (drive) set_build(true);

		// F5 like Minecraft: Portal 2's camera goes 4 blocks behind, Chell hides and Minecraft draws Steve
		bool f5 = focus && (GetAsyncKeyState(VK_F5) & 0x8000) != 0;
		if (edge(VK_F5, f5) && f5 && alive)
		{
			third = !third;
			game::console_cmd(third ? "cam_idealdist 128; cam_idealdistright 0; cam_idealdistup 0; cam_collision 1; c_thirdpersonshoulder 0; thirdperson"
			                        : "firstperson");
			game::client_cmd(third ? "script PC_Third(true)" : "script PC_Third(false)");
		}

		bool screen = (gf & bridge::G_SCREEN) != 0;
		if (screen != s_screen)
		{
			s_screen = screen;
			s_cx = s_cy = 0.5f;
			// freeze Portal 2's view while a Minecraft screen is open
			game::console_cmd(screen ? "m_yaw 0; m_pitch 0" : "m_yaw 0.022; m_pitch 0.022");
			if (screen) bridge::push_event(bridge::EV_CURSOR, -1, -1); // centre
		}
		render::state.cursor_visible = s_screen;
		render::state.cursor_x = s_cx;
		render::state.cursor_y = s_cy;

		uint32_t strip = 0;
		if (build) strip |= IN_ATTACK | IN_ATTACK2 | IN_USE | IN_ZOOM | IN_RELOAD;
		if (s_screen || drive) strip = 0xFFFFFFFFu;
		game::strip_buttons = strip;
		// V picks up / drops Portal 2 objects (cubes, turrets) whatever mode you're in
		bool v = focus && !s_screen && !drive && (GetAsyncKeyState('V') & 0x8000) != 0;
		edge('V', v);
		game::force_buttons = v ? IN_USE : 0;
		game::freeze_movement = s_screen || drive;

		// which keys Minecraft gets now
		bool want[256] = {};
		if (focus && alive)
		{
			for (int vk : k_chat_keys) want[vk] = true;
			if (build || drive || s_screen)
			{
				want[VK_LBUTTON] = want[VK_RBUTTON] = want[VK_MBUTTON] = true;
				for (int vk : k_build_keys) want[vk] = true;
			}
			if (drive)
				for (int vk : k_drive_keys) want[vk] = true;
			if (s_screen) // typing: everything (letters arrive as characters too)
				for (int vk = 0x08; vk < 0xFF; ++vk)
					if (vk != VK_F5 && vk != VK_SHIFT && vk != VK_CONTROL && vk != VK_MENU) want[vk] = true;
		}
		for (int vk = 1; vk < 256; ++vk)
		{
			if (!want[vk])
			{
				if (s_sent[vk]) send(vk, false); // release anything Minecraft still thinks is held
				continue;
			}
			bool now = (GetAsyncKeyState(vk) & 0x8000) != 0;
			if (drive && !s_screen && vk == VK_LSHIFT) now = now || (GetAsyncKeyState(VK_LCONTROL) & 0x8000) != 0;
			if (now != s_sent[vk]) send(vk, now);
		}
	}

	bool screen_open() { return s_screen; }
}

namespace input
{
	const char *debug()
	{
		static char s[160];
		HWND fg = GetForegroundWindow();
		snprintf(s, sizeof(s), "fg=%d ingame=%d console=%d paused=%d alive=%d guest=%x screen=%d drive=%d chars=%u keydowns=%u",
			fg == s_hwnd ? 1 : 0, game::in_game() ? 1 : 0, game::console_visible() ? 1 : 0, game::paused() ? 1 : 0,
			bridge::guest_alive() ? 1 : 0, bridge::guest_flags(), s_screen ? 1 : 0, drive ? 1 : 0, s_chars, s_keydowns);
		return s;
	}
}
