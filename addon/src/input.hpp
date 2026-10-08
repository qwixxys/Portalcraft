#pragma once
#include <windows.h>

namespace input
{
	extern bool build;
	extern bool third;
	extern bool drive; // Minecraft moves the player (holes in the walls, elytra)
	void attach(HWND hwnd);
	void detach();
	void poll();         // once per presented frame
	bool screen_open();
	const char *debug(); // focus, link and flags, for the status log
}
