// Portal 2's console output (tier0's logging system), read to tell a loaded save game from a freshly started map.
#pragma once

namespace console
{
	bool attach();          // registers a logging listener; false if tier0 doesn't offer it
	bool take_save_load();  // a save game was loaded since the last call ("Loading game from ...sav")
}
