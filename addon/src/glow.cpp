// Portal 2's glowing effects (the laser beam, sprites, particles, the crosshair) blend additively and write no depth:
// the composite saw the depth of what is behind them, so Minecraft's blocks in between replaced them. Each such draw
// into the scene is drawn again into a layer of its own (against Portal 2's depth, as before) and once more into a
// depth buffer of its own (the nearest glow per pixel); the effect adds the layer back on top of Minecraft's pixels
// wherever the glow is nearer than Minecraft's block.
#include <reshade.hpp>
#include "glow.hpp"
#include "render.hpp"
#include <string>
#include <d3d9.h>

using namespace reshade::api;

namespace glow
{
	static resource s_tex = {}, s_depth = {};
	static resource_view s_rtv = {}, s_srv = {}, s_dsv_own = {}, s_depth_srv = {};
	static uint32_t s_w = 0, s_h = 0;
	unsigned draws = 0;

	// what Portal 2 has bound, as far as it matters here
	static resource_view s_dsv = {};
	static bool s_scene = false; // render target 0 is the size of the screen (the scene, not a texture or a shadow map)
	static bool s_blend = false, s_zwrite = true;
	static uint32_t s_dst = 0, s_zfunc = static_cast<uint32_t>(compare_op::less_equal);
	static bool s_busy = false; // while we draw (our own binds are not Portal 2's state)
	static bool s_states_readable = false; // the device answers GetRenderState (not a pure device)

	static void on_bind_render_targets(command_list *cmd, uint32_t count, const resource_view *rtvs, resource_view dsv)
	{
		if (s_busy) return;
		s_dsv = dsv;
		s_scene = false;
		if (count && rtvs[0].handle && s_w)
		{
			device *dev = cmd->get_device();
			resource r = dev->get_resource_from_view(rtvs[0]);
			if (r.handle && r != s_tex)
			{
				resource_desc d = dev->get_resource_desc(r);
				s_scene = d.texture.width == s_w && d.texture.height == s_h;
			}
		}
	}

	static void on_bind_states(command_list *, uint32_t count, const dynamic_state *states, const uint32_t *values)
	{
		if (s_busy) return;
		for (uint32_t i = 0; i < count; ++i)
		{
			switch (states[i])
			{
			case dynamic_state::blend_enable: s_blend = values[i] != 0; break;
			case dynamic_state::depth_write_mask: s_zwrite = values[i] != 0; break;
			case dynamic_state::depth_func: s_zfunc = values[i]; break;
			case dynamic_state::dest_color_blend_factor: s_dst = values[i]; break;
			default: break;
			}
		}
	}

	// added on top of what is there, depth tested but not written: a glow
	static bool glowing()
	{
		// (depth tested "equal": a light added onto surfaces already drawn, like the projected sunlight; not a glow, it
		// would light up holes in those surfaces; debug.txt 9 turns the layer off)
		return render::state.debug != 9 && s_rtv.handle && s_scene && s_dsv.handle && s_blend && !s_zwrite && s_dst == static_cast<uint32_t>(blend_factor::one)
			&& s_zfunc != static_cast<uint32_t>(compare_op::equal);
	}

	// Our draws go straight to the D3D9 device and put back exactly what they change (render targets, depth buffer,
	// viewport, scissor, a few render states): Portal 2's renderer caches its device state, so anything left changed,
	// or restored from a guess, stayed wrong for its next draws (the projected sunlight went missing).
	template <typename F>
	static void redraw(command_list *cmd, F issue)
	{
		IDirect3DDevice9 *d = reinterpret_cast<IDirect3DDevice9 *>(cmd->get_device()->get_native());
		D3DVIEWPORT9 vp;
		RECT sc;
		if (FAILED(d->GetViewport(&vp)) || FAILED(d->GetScissorRect(&sc))) return; // can't put them back: leave it alone
		IDirect3DSurface9 *rts[4] = {}, *ds = nullptr;
		for (DWORD i = 0; i < 4; ++i) d->GetRenderTarget(i, &rts[i]);
		d->GetDepthStencilSurface(&ds);
		s_busy = true;
		// the glow itself, hidden where Portal 2 has something in front of it
		d->SetRenderTarget(0, reinterpret_cast<IDirect3DSurface9 *>(s_rtv.handle));
		for (DWORD i = 1; i < 4; ++i)
			if (rts[i]) d->SetRenderTarget(i, nullptr);
		d->SetViewport(&vp); // setting a render target resets them
		d->SetScissorRect(&sc);
		issue();
		// where it is: its nearest depth into our own buffer, no colour, no stencil test (ours is empty)
		if (s_dsv_own.handle && s_states_readable)
		{
			const D3DRENDERSTATETYPE st[5] = { D3DRS_ZENABLE, D3DRS_ZWRITEENABLE, D3DRS_ZFUNC, D3DRS_COLORWRITEENABLE, D3DRS_STENCILENABLE };
			const DWORD on[5] = { D3DZB_TRUE, TRUE, D3DCMP_LESSEQUAL, 0, FALSE };
			DWORD old[5] = {};
			bool ok = true;
			for (int i = 0; i < 5; ++i) ok = ok && SUCCEEDED(d->GetRenderState(st[i], &old[i]));
			if (ok)
			{
				d->SetDepthStencilSurface(reinterpret_cast<IDirect3DSurface9 *>(s_dsv_own.handle));
				for (int i = 0; i < 5; ++i) d->SetRenderState(st[i], on[i]);
				issue();
				for (int i = 0; i < 5; ++i) d->SetRenderState(st[i], old[i]);
			}
		}
		for (DWORD i = 0; i < 4; ++i)
			if (i == 0 || rts[i]) d->SetRenderTarget(i, rts[i]);
		d->SetDepthStencilSurface(ds);
		d->SetViewport(&vp);
		d->SetScissorRect(&sc);
		for (IDirect3DSurface9 *s : rts)
			if (s) s->Release();
		if (ds) ds->Release();
		s_busy = false;
		draws++;
	}

	static bool on_draw(command_list *cmd, uint32_t vertices, uint32_t instances, uint32_t first_vertex, uint32_t first_instance)
	{
		if (!s_busy && glowing()) redraw(cmd, [&] { cmd->draw(vertices, instances, first_vertex, first_instance); });
		return false;
	}

	static bool on_draw_indexed(command_list *cmd, uint32_t indices, uint32_t instances, uint32_t first_index, int32_t vertex_offset, uint32_t first_instance)
	{
		if (!s_busy && glowing()) redraw(cmd, [&] { cmd->draw_indexed(indices, instances, first_index, vertex_offset, first_instance); });
		return false;
	}

	void attach()
	{
		reshade::register_event<reshade::addon_event::bind_render_targets_and_depth_stencil>(on_bind_render_targets);
		reshade::register_event<reshade::addon_event::bind_pipeline_states>(on_bind_states);
		reshade::register_event<reshade::addon_event::draw>(on_draw);
		reshade::register_event<reshade::addon_event::draw_indexed>(on_draw_indexed);
	}

	static void drop_depth(device *dev)
	{
		if (s_depth_srv.handle) dev->destroy_resource_view(s_depth_srv);
		if (s_dsv_own.handle) dev->destroy_resource_view(s_dsv_own);
		if (s_depth.handle) dev->destroy_resource(s_depth);
		s_depth_srv = s_dsv_own = {};
		s_depth = {};
	}

	void shutdown(device *dev)
	{
		drop_depth(dev);
		if (s_srv.handle) dev->destroy_resource_view(s_srv);
		if (s_rtv.handle) dev->destroy_resource_view(s_rtv);
		if (s_tex.handle) dev->destroy_resource(s_tex);
		s_srv = s_rtv = {};
		s_tex = {};
		s_w = s_h = 0;
	}

	// once per frame, before the effect: the layer at the screen's size, bound to the effect
	void begin_effects(effect_runtime *rt)
	{
		uint32_t w = 0, h = 0;
		rt->get_screenshot_width_and_height(&w, &h);
		if (!w || !h) return;
		if (s_tex.handle && w == s_w && h == s_h) return;
		device *dev = rt->get_device();
		shutdown(dev);
		const format fmt = format::b8g8r8a8_unorm;
		resource_desc desc(w, h, 1, 1, fmt, 1, memory_heap::default_, resource_usage::render_target | resource_usage::shader_resource);
		if (!dev->create_resource(desc, nullptr, resource_usage::shader_resource, &s_tex) ||
			!dev->create_resource_view(s_tex, resource_usage::render_target, resource_view_desc(fmt), &s_rtv) ||
			!dev->create_resource_view(s_tex, resource_usage::shader_resource, resource_view_desc(fmt), &s_srv))
		{
			reshade::log::message(reshade::log::level::warning, "Portalcraft: no layer for Portal 2's glowing effects");
			shutdown(dev);
			return;
		}
		// readable depth (INTZ); without it glows simply always go on top of Minecraft
		resource_desc ddesc(w, h, 1, 1, format::intz, 1, memory_heap::default_, resource_usage::depth_stencil | resource_usage::shader_resource);
		if (!dev->create_resource(ddesc, nullptr, resource_usage::depth_stencil_write, &s_depth) ||
			!dev->create_resource_view(s_depth, resource_usage::depth_stencil, resource_view_desc(format::intz), &s_dsv_own) ||
			!dev->create_resource_view(s_depth, resource_usage::shader_resource, resource_view_desc(format::intz), &s_depth_srv))
		{
			reshade::log::message(reshade::log::level::warning, "Portalcraft: no depth for Portal 2's glowing effects");
			drop_depth(dev);
		}
		// the depth pass changes render states and must read them back first. Portal 2 asks for a pure device, which
		// can't answer, but the device it gets (through ReShade) may: ask it
		DWORD probe = 0;
		s_states_readable = SUCCEEDED(reinterpret_cast<IDirect3DDevice9 *>(dev->get_native())->GetRenderState(D3DRS_ZFUNC, &probe));
		reshade::log::message(reshade::log::level::info, s_states_readable ? "Portalcraft: glows get their own depth"
			: "Portalcraft: render states can't be read back: Portal 2's glows go on top of Minecraft's blocks");
		s_w = w;
		s_h = h;
		rt->update_texture_bindings("PC_GLOW", s_srv, s_srv);
		rt->update_texture_bindings("PC_GLOWDEPTH", s_depth_srv, s_depth_srv);
		finish_effects(rt->get_command_queue()->get_immediate_command_list());
	}

	// after the effect: empty again for the next frame
	void finish_effects(command_list *cmd)
	{
		if (!s_rtv.handle) return;
		const float black[4] = { 0.0f, 0.0f, 0.0f, 0.0f };
		cmd->clear_render_target_view(s_rtv, black, 0, nullptr);
		if (s_dsv_own.handle)
		{
			const float far_depth = 1.0f;
			const uint8_t stencil = 0;
			cmd->clear_depth_stencil_view(s_dsv_own, &far_depth, &stencil, 0, nullptr);
		}
	}
}
