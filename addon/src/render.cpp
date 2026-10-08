// Uploads Minecraft's latest frame (colour, depth, hand/HUD overlay) into textures the Portalcraft.fx effect reads.
#include <reshade.hpp>
#include "bridge.hpp"
#include "render.hpp"
#include "game.hpp"
#include <cstring>
#include <cstdio>
#include <string>
#include <vector>
#include <cmath>
#include <windows.h>

using namespace reshade::api;

namespace render
{
	struct Tex { resource res = {}; resource_view srv = {}; };
	static Tex s_color, s_depth, s_overlay, s_holes;
	// system-memory copies of the three layers, filled from shared memory and sent to the GPU textures each frame
	static resource s_stage[3] = {};
	static bool s_staged = true; // false: fall back to update_texture_region
	static format s_rgba = format::b8g8r8a8_unorm; // D3D9 keeps it B,G,R,A: the effect swaps red and blue of staged frames
	static uint32_t s_w = 0, s_h = 0, s_uploaded_serial = 0;
	static bool s_have_frame = false;
	State state = {};

	static void destroy(device *dev, Tex &t)
	{
		if (t.srv.handle) dev->destroy_resource_view(t.srv);
		if (t.res.handle) dev->destroy_resource(t.res);
		t = {};
	}

	static bool create(device *dev, Tex &t, uint32_t w, uint32_t h, format fmt)
	{
		resource_desc desc(w, h, 1, 1, fmt, 1, memory_heap::default_, resource_usage::shader_resource | resource_usage::copy_dest);
		if (!dev->create_resource(desc, nullptr, resource_usage::shader_resource, &t.res)) return false;
		return dev->create_resource_view(t.res, resource_usage::shader_resource, resource_view_desc(fmt), &t.srv);
	}

	static bool create_stage(device *dev, resource &r, uint32_t w, uint32_t h, format fmt)
	{
		resource_desc desc(w, h, 1, 1, fmt, 1, memory_heap::upload, resource_usage::copy_source);
		return dev->create_resource(desc, nullptr, resource_usage::copy_source, &r);
	}

	// shared memory -> system-memory texture -> GPU texture (one copy on the CPU, no allocation per frame)
	static bool upload(device *dev, effect_runtime *rt, resource stage, resource dest, const uint8_t *src, uint32_t w, uint32_t h)
	{
		subresource_data m = {};
		if (!dev->map_texture_region(stage, 0, nullptr, map_access::write_only, &m)) return false;
		if (m.row_pitch == w * 4)
			memcpy(m.data, src, static_cast<size_t>(w) * h * 4);
		else
			for (uint32_t y = 0; y < h; ++y)
				memcpy(static_cast<uint8_t *>(m.data) + static_cast<size_t>(y) * m.row_pitch, src + static_cast<size_t>(y) * w * 4, w * 4);
		dev->unmap_texture_region(stage, 0);
		rt->get_command_queue()->get_immediate_command_list()->copy_resource(stage, dest);
		return true;
	}

	void shutdown(device *dev)
	{
		destroy(dev, s_color); destroy(dev, s_depth); destroy(dev, s_overlay); destroy(dev, s_holes);
		for (resource &r : s_stage)
			if (r.handle) { dev->destroy_resource(r); r = {}; }
		s_w = s_h = 0;
		s_have_frame = false;
	}

	static void set_float(effect_runtime *rt, const char *name, const float *v, size_t n)
	{
		effect_uniform_variable var = rt->find_uniform_variable("Portalcraft.fx", name);
		if (var.handle) rt->set_uniform_value_float(var, v, n);
		state.uniforms_found = var.handle != 0;
	}

	// Holes in Portal 2's walls (grid from Minecraft) and the camera, so the effect can find the wall under a pixel
	static void update_holes(effect_runtime *rt, device *dev)
	{
		static std::vector<uint8_t> buf;
		static int32_t origin[3] = {};
		static uint32_t have_serial = 0;
		static bool bound = false;
		if (!s_holes.res.handle)
		{
			if (!create(dev, s_holes, bridge::HOLES_W, bridge::HOLES_H, format::b8g8r8a8_unorm)) return;
			bound = false;
		}
		if (!bound)
		{
			rt->update_texture_bindings("PC_HOLES", s_holes.srv, s_holes.srv);
			bound = true;
			have_serial = 0;
		}
		uint32_t serial = bridge::holes_serial();
		if (serial && serial != have_serial)
		{
			buf.resize(bridge::HOLES_BYTES);
			uint32_t got = 0;
			if (bridge::holes_copy(buf.data(), origin, got))
			{
				subresource_data sd = {};
				sd.data = buf.data();
				sd.row_pitch = bridge::HOLES_W * 4;
				sd.slice_pitch = bridge::HOLES_BYTES;
				dev->update_texture_region(sd, s_holes.res, 0, nullptr);
				have_serial = got;
			}
		}
		game::Camera c = game::camera();
		const float rad = 3.14159265f / 180.0f;
		float sp = sinf(c.angles.x * rad), cp = cosf(c.angles.x * rad), sy = sinf(c.angles.y * rad), cy = cosf(c.angles.y * rad);
		// Source's fov is horizontal for 4:3; the projection is vertical-fov based
		float tan_y = tanf(c.fov * 0.5f * rad) * 0.75f;
		float aspect = c.height > 0 ? float(c.width) / float(c.height) : 16.0f / 9.0f;
		bool outside = (bridge::guest_flags() & bridge::G_CAM_OUTSIDE) != 0;
		// 2: deep inside the walls (a tunnel), 1: the eye inside Portal 2's wall (in a hole of its floor), 0: in the open
		bool in_wall = (bridge::guest_flags() & bridge::G_CAM_IN_WALL) != 0;
		float eye[4] = { c.origin.x, c.origin.y, c.origin.z, outside ? 2.0f : in_wall ? 1.0f : 0.0f };
		float fwd[4] = { cp * cy, cp * sy, -sp, tan_y };
		float right[4] = { sy, -cy, 0.0f, tan_y * aspect };
		float up[4] = { sp * cy, sp * sy, cp, have_serial ? 1.0f : 0.0f };
		float grid[4] = { float(origin[0]), float(origin[1]), float(origin[2]), 0.0f };
		set_float(rt, "PC_Eye", eye, 4);
		set_float(rt, "PC_Fwd", fwd, 4);
		set_float(rt, "PC_Right", right, 4);
		set_float(rt, "PC_Up", up, 4);
		set_float(rt, "PC_Grid", grid, 4);
		// the camera of the Minecraft frame shown: the effect turns that frame to this camera when they differ
		float fp = state.frame_angles[0] * rad, fy = state.frame_angles[1] * rad;
		float fsp = sinf(fp), fcp = cosf(fp), fsy = sinf(fy), fcy = cosf(fy);
		float ftan_y = tanf(state.frame_fov * 0.5f * rad) * 0.75f;
		bool reproject = state.frame_fov > 1.0f;
		float sfwd[4] = { fcp * fcy, fcp * fsy, -fsp, ftan_y };
		float sright[4] = { fsy, -fcy, 0.0f, ftan_y * state.frame_aspect };
		float sup[4] = { fsp * fcy, fsp * fsy, fcp, reproject ? 1.0f : 0.0f };
		set_float(rt, "PC_SFwd", sfwd, 4);
		set_float(rt, "PC_SRight", sright, 4);
		set_float(rt, "PC_SUp", sup, 4);
		float seye[4] = { state.frame_eye[0], state.frame_eye[1], state.frame_eye[2], 0.0f };
		set_float(rt, "PC_SEye", seye, 4);
	}

	void begin_effects(effect_runtime *rt)
	{
		device *dev = rt->get_device();
		if (dev->get_api() != device_api::d3d9)
			return; // other runtimes (Steam overlay / video D3D11 devices) are not Portal 2's frame

		// make sure our technique runs, whatever the preset says
		static DWORD last_check = 0;
		DWORD now = GetTickCount();
		if (now - last_check > 3000)
		{
			last_check = now;
			if (!rt->get_effects_state()) rt->set_effects_state(true);
			std::string report = "Portalcraft: techniques:";
			rt->enumerate_techniques(nullptr, [&report](effect_runtime *r, effect_technique t) {
				char name[128] = "", fx[128] = "";
				size_t n = sizeof(name), m = sizeof(fx);
				r->get_technique_name(t, name, &n);
				r->get_technique_effect_name(t, fx, &m);
				bool on = r->get_technique_state(t);
				report += std::string(" ") + fx + "/" + name + (on ? "=on" : "=off");
				if (!on && strcmp(name, "Portalcraft") == 0) r->set_technique_state(t, true);
			});
			report += " uniforms:";
			rt->enumerate_uniform_variables(nullptr, [&report](effect_runtime *r, effect_uniform_variable v) {
				char name[128] = "", fx[128] = "";
				size_t n = sizeof(name), m = sizeof(fx);
				r->get_uniform_variable_name(v, name, &n);
				r->get_uniform_variable_effect_name(v, fx, &m);
				report += std::string(" ") + fx + "/" + name;
			});
			reshade::log::message(reshade::log::level::info, report.c_str());
		}
		const bridge::FrameHeader *fh = bridge::frame_header();
		bool alive = bridge::guest_alive();
		if (fh && alive && fh->magic == 0x31464350 && fh->latest < 3 && fh->width && fh->height &&
			fh->width <= bridge::MAX_W && fh->height <= bridge::MAX_H)
		{
			if (fh->width != s_w || fh->height != s_h)
			{
				shutdown(dev);
				// Minecraft's bytes are R,G,B,A: textures in that order take them as they are (else the slower,
				// converting update_texture_region path into B,G,R,A textures)
				if (!create(dev, s_color, fh->width, fh->height, s_rgba)) { s_rgba = format::b8g8r8a8_unorm; s_staged = false; }
				if ((s_color.res.handle || create(dev, s_color, fh->width, fh->height, s_rgba)) &&
					create(dev, s_depth, fh->width, fh->height, format::r32_float) &&
					create(dev, s_overlay, fh->width, fh->height, s_rgba))
				{
					s_w = fh->width; s_h = fh->height;
					state.textures_ok = true;
					rt->update_texture_bindings("PC_COLOR", s_color.srv, s_color.srv);
					rt->update_texture_bindings("PC_MCDEPTH", s_depth.srv, s_depth.srv);
					rt->update_texture_bindings("PC_OVERLAY", s_overlay.srv, s_overlay.srv);
				}
			}
			// one game: wait (briefly) for Minecraft's frame drawn from this very camera (Minecraft renders one frame
			// per Portal 2 camera, see G_LOCKSTEP), so its blocks never trail Portal 2's walls when you turn or move
			if ((bridge::guest_flags() & bridge::G_LOCKSTEP) && game::in_game() && !game::paused() && !game::console_visible())
			{
				// always wait for it: showing an older frame (even turned to this camera) lags behind when you move
				const int budget_ms = 25;
				const uint32_t target = game::camera().serial;
				const volatile uint32_t *latest = &fh->latest;
				LARGE_INTEGER freq, t0, t;
				QueryPerformanceFrequency(&freq);
				QueryPerformanceCounter(&t0);
				for (;;)
				{
					uint32_t l = *latest;
					if (l < 3 && static_cast<int32_t>(*reinterpret_cast<const volatile uint32_t *>(&bridge::slot_header(l)->camera_serial) - target) >= 0)
					{
						state.synced++;
						break;
					}
					QueryPerformanceCounter(&t);
					if ((t.QuadPart - t0.QuadPart) * 1000 >= freq.QuadPart * budget_ms) // Minecraft is stuck: its last frame, turned to this camera
					{
						state.late++;
						break;
					}
					YieldProcessor();
					SwitchToThread();
				}
				QueryPerformanceCounter(&t);
				state.wait_us += static_cast<unsigned>((t.QuadPart - t0.QuadPart) * 1000000 / freq.QuadPart);
			}
			uint32_t slot = fh->latest;
			const bridge::SlotHeader *sh = bridge::slot_header(slot);
			if (s_w && sh->serial != s_uploaded_serial)
			{
				LARGE_INTEGER u0, u1, uf;
				QueryPerformanceCounter(&u0);
				bridge::set_reading_slot(slot);
				const uint8_t *data = bridge::slot_data(slot);
				const size_t plane = static_cast<size_t>(s_w) * s_h * 4;
				const resource dest[3] = { s_color.res, s_depth.res, s_overlay.res };
				const format fmts[3] = { s_rgba, format::r32_float, s_rgba };
				for (int i = 0; i < 3; ++i)
				{
					if (s_staged && !s_stage[i].handle && !create_stage(dev, s_stage[i], s_w, s_h, fmts[i])) s_staged = false;
					if (s_staged && upload(dev, rt, s_stage[i], dest[i], data + i * plane, s_w, s_h)) continue;
					s_staged = false; // this driver path didn't work: the slow but sure one
					subresource_data sd = {};
					sd.row_pitch = s_w * 4;
					sd.slice_pitch = static_cast<uint32_t>(plane);
					sd.data = const_cast<uint8_t *>(data + i * plane);
					dev->update_texture_region(sd, dest[i], 0, nullptr);
				}
				bridge::set_reading_slot(0xFFFFFFFF);
				state.staged = s_staged;
				s_uploaded_serial = sh->serial;
				s_have_frame = true;
				state.uploads++;
				QueryPerformanceCounter(&u1);
				QueryPerformanceFrequency(&uf);
				state.upload_us += static_cast<unsigned>((u1.QuadPart - u0.QuadPart) * 1000000 / uf.QuadPart);
				state.frame_eye[0] = sh->eye[0]; state.frame_eye[1] = sh->eye[1]; state.frame_eye[2] = sh->eye[2];
				state.frame_angles[0] = sh->angles[0]; state.frame_angles[1] = sh->angles[1]; state.frame_angles[2] = sh->angles[2];
				state.frame_fov = sh->fov;
				state.frame_aspect = float(fh->width) / float(fh->height);
			}
			// Portal 2's pause menu, console and loading screens stay clean
			bool show = s_have_frame && game::in_game() && !game::paused() && !game::console_visible() && !(bridge::guest_flags() & bridge::G_BUSY);
			float mc[4] = { fh->near_plane, fh->far_plane, static_cast<float>(fh->depth_mode), show ? 1.0f : 0.0f };
			set_float(rt, "PC_MC", mc, 4);
		}
		else
		{
			float off[4] = { 0.05f, 1000.0f, 0.0f, 0.0f };
			set_float(rt, "PC_MC", off, 4);
		}
		// debug view: write a number into portalcraft_runtime\debug.txt (1 = Portal 2 depth, 2 = no depth test, 3 = Minecraft depth)
		static float debug_mode = 0.0f;
		static DWORD last_debug = 0;
		if (now - last_debug > 1000)
		{
			last_debug = now;
			debug_mode = 0.0f;
			state.debug = 0;
			FILE *f = nullptr;
			static std::string path;
			if (path.empty())
			{
				HMODULE self = nullptr;
				GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
					reinterpret_cast<LPCSTR>(&begin_effects), &self);
				char buf[MAX_PATH];
				GetModuleFileNameA(self, buf, MAX_PATH);
				path = buf;
				path = path.substr(0, path.find_last_of("\\/") + 1) + "debug.txt";
			}
			if (fopen_s(&f, path.c_str(), "r") == 0 && f)
			{
				fscanf_s(f, "%f", &debug_mode);
				state.debug = static_cast<int>(debug_mode);
				fclose(f);
			}
			// an updated Portalcraft.fx (reinstall while the game runs) is picked up without a restart
			static FILETIME s_fx_time = {};
			std::string fx = path.substr(0, path.find_last_of("\\/") + 1) + "shaders\\Portalcraft.fx";
			WIN32_FILE_ATTRIBUTE_DATA fa;
			if (GetFileAttributesExA(fx.c_str(), GetFileExInfoStandard, &fa))
			{
				if ((s_fx_time.dwLowDateTime || s_fx_time.dwHighDateTime) && CompareFileTime(&fa.ftLastWriteTime, &s_fx_time) != 0)
				{
					rt->reload_effect_next_frame("Portalcraft.fx");
					reshade::log::message(reshade::log::level::info, "Portalcraft: Portalcraft.fx changed, reloading");
				}
				s_fx_time = fa.ftLastWriteTime;
			}
		}
		update_holes(rt, dev);
		float p2[4] = { state.znear, state.zfar, state.cursor_visible ? 1.0f : 0.0f, debug_mode < 8.5f ? debug_mode : 0.0f }; // 9: add-on only
		set_float(rt, "PC_P2", p2, 4);
		float cur[4] = { state.cursor_x, state.cursor_y, s_staged ? 1.0f : 0.0f, 0.0f }; // z: Minecraft's R,G,B,A bytes arrived as they are
		set_float(rt, "PC_Cursor", cur, 4);
	}
}
