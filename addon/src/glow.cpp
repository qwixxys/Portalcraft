// Portal 2's glowing effects (the laser beam, sprites, particles, the crosshair) blend additively and write no depth:
// the composite saw the depth of what is behind them, so Minecraft's blocks in between replaced them. Each such draw
// into the scene is drawn again into a layer of its own (against Portal 2's depth, as before) and once more into a
// depth buffer of its own (the nearest glow per pixel); the effect adds the layer back on top of Minecraft's pixels
// wherever the glow is nearer than Minecraft's block.
#include <reshade.hpp>
#include "glow.hpp"
#include <string>

using namespace reshade::api;

namespace glow
{
	static resource s_tex = {}, s_depth = {};
	static resource_view s_rtv = {}, s_srv = {}, s_dsv_own = {}, s_depth_srv = {};
	static uint32_t s_w = 0, s_h = 0;
	unsigned draws = 0;

	// what Portal 2 has bound, as far as it matters here
	static resource_view s_rtvs[4] = {};
	static uint32_t s_rt_count = 0;
	static resource_view s_dsv = {};
	static bool s_scene = false; // render target 0 is the size of the screen (the scene, not a texture or a shadow map)
	static bool s_blend = false, s_zwrite = true, s_zenable = true;
	static uint32_t s_dst = 0, s_zfunc = static_cast<uint32_t>(compare_op::less_equal), s_mask = 0xF;
	static viewport s_vp = {};
	static bool s_busy = false; // while we draw (our own binds are not Portal 2's state)

	static void on_bind_render_targets(command_list *cmd, uint32_t count, const resource_view *rtvs, resource_view dsv)
	{
		if (s_busy) return;
		s_rt_count = count < 4 ? count : 4;
		for (uint32_t i = 0; i < s_rt_count; ++i) s_rtvs[i] = rtvs[i];
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
			case dynamic_state::depth_enable: s_zenable = values[i] != 0; break;
			case dynamic_state::depth_func: s_zfunc = values[i]; break;
			case dynamic_state::render_target_write_mask: s_mask = values[i]; break;
			case dynamic_state::dest_color_blend_factor: s_dst = values[i]; break;
			default: break;
			}
		}
	}

	static void on_bind_viewports(command_list *, uint32_t first, uint32_t count, const viewport *vps)
	{
		if (!s_busy && first == 0 && count) s_vp = vps[0];
	}

	// added on top of what is there, depth tested but not written: a glow
	static bool glowing()
	{
		return s_rtv.handle && s_scene && s_dsv.handle && s_blend && !s_zwrite && s_dst == static_cast<uint32_t>(blend_factor::one);
	}

	template <typename F>
	static void redraw(command_list *cmd, F issue)
	{
		s_busy = true;
		// the glow itself, hidden where Portal 2 has something in front of it
		cmd->bind_render_targets_and_depth_stencil(1, &s_rtv, s_dsv);
		cmd->bind_viewports(0, 1, &s_vp); // binding a render target resets the viewport in D3D9
		issue();
		// where it is: its nearest depth into our own buffer, no colour
		if (s_dsv_own.handle)
		{
			cmd->bind_render_targets_and_depth_stencil(1, &s_rtv, s_dsv_own);
			cmd->bind_viewports(0, 1, &s_vp);
			const dynamic_state st[4] = { dynamic_state::depth_enable, dynamic_state::depth_write_mask, dynamic_state::depth_func,
				dynamic_state::render_target_write_mask };
			const uint32_t on[4] = { 1, 1, static_cast<uint32_t>(compare_op::less_equal), 0 };
			cmd->bind_pipeline_states(4, st, on);
			issue();
			const uint32_t back[4] = { s_zenable ? 1u : 0u, 0, s_zfunc, s_mask };
			cmd->bind_pipeline_states(4, st, back);
		}
		cmd->bind_render_targets_and_depth_stencil(s_rt_count, s_rtvs, s_dsv);
		cmd->bind_viewports(0, 1, &s_vp);
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
		reshade::register_event<reshade::addon_event::bind_viewports>(on_bind_viewports);
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
