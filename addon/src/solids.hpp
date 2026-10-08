#pragma once

namespace solids
{
	void update();  // main thread, every frame (throttled)
	void on_load(); // a level finished loading (fresh or from a save)
}
