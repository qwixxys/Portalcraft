// Keeps invisible solid boxes in Portal 2 in step with the Minecraft blocks near the player.
#include <reshade.hpp>
#include "solids.hpp"
#include "bridge.hpp"
#include "game.hpp"
#include <array>
#include <set>
#include <string>
#include <vector>
#include <windows.h>

namespace solids
{
	using Box = std::array<int32_t, 6>; // min corner cell x, y, z and size in cells
	static std::set<Box> s_have, s_want; // boxes Portal 2 has / should have
	static int s_serial = -1;
	static DWORD s_last = 0;
	static const int k_max_boxes = 2000;
	static int32_t s_buf[k_max_boxes * 6];
	// Portal 2's command buffer and its server link take only so much at once (too much drops the game):
	// at most this many script lines per update, the rest follows in the next ones
	static const int k_batches_per_update = 6, k_boxes_per_batch = 8;

	static std::string box_list(std::vector<Box> &boxes, size_t &i)
	{
		std::string s;
		for (int k = 0; k < k_boxes_per_batch && i < boxes.size(); ++k, ++i)
		{
			for (int j = 0; j < 6; ++j)
				s += std::to_string(boxes[i][j]) + (j < 5 ? "," : ";");
		}
		return s;
	}

	void update()
	{
		DWORD now = GetTickCount();
		if (now - s_last < 150) return;
		s_last = now;
		if (!game::in_game() || !bridge::guest_alive()) return;

		int serial = 0;
		int n = bridge::solid_boxes(s_buf, k_max_boxes, serial);
		if (serial != s_serial)
		{
			s_serial = serial;
			s_want.clear();
			for (int i = 0; i < n; ++i)
				s_want.insert({ s_buf[i * 6], s_buf[i * 6 + 1], s_buf[i * 6 + 2], s_buf[i * 6 + 3], s_buf[i * 6 + 4], s_buf[i * 6 + 5] });
		}

		std::vector<Box> add, rem;
		for (auto &b : s_want) if (!s_have.count(b)) add.push_back(b);
		for (auto &b : s_have) if (!s_want.count(b)) rem.push_back(b);
		if (add.empty() && rem.empty()) return;
		if (add.size() + rem.size() > 100)
			reshade::log::message(reshade::log::level::info, ("Portalcraft: solids " + std::to_string(s_want.size()) + " boxes, +" +
				std::to_string(add.size()) + " -" + std::to_string(rem.size()) + " (sent over several updates)").c_str());

		size_t ai = 0, ri = 0;
		for (int b = 0; b < k_batches_per_update && (ai < add.size() || ri < rem.size()); ++b)
		{
			size_t a0 = ai, r0 = ri;
			std::string a = box_list(add, ai), r = box_list(rem, ri);
			game::client_cmd(("script PC_Apply(\"" + a + "\", \"" + r + "\")").c_str());
			for (size_t i = a0; i < ai; ++i) s_have.insert(add[i]);
			for (size_t i = r0; i < ri; ++i) s_have.erase(rem[i]);
		}
	}

	void on_load()
	{
		// every level load: a fresh map has a fresh script VM, a save game restores the VM (and boxes) of its time,
		// which may predate the helpers or not match Minecraft now: load the helpers and rebuild every box
		s_have.clear();
		s_want.clear();
		s_serial = -1;
		game::client_cmd("sv_cheats 1");
		game::client_cmd("script_execute portalcraft/solids");
		game::client_cmd("script_execute portalcraft/view");
		game::client_cmd("script PC_Clear()");
	}
}
