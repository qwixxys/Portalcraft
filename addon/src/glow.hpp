#pragma once
#include <reshade.hpp>

// Portal 2's glowing effects (laser beams, sprites, particles, the crosshair) as a layer of their own
namespace glow
{
	void attach();
	void begin_effects(reshade::api::effect_runtime *rt);
	void finish_effects(reshade::api::command_list *cmd);
	void shutdown(reshade::api::device *dev);
	extern unsigned draws; // draws copied into the layer since the start (status log)
}
