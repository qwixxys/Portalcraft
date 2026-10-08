#pragma once
#include <reshade.hpp>

namespace render
{
	struct State
	{
		float znear = 7.0f, zfar = 28377.9f;      // Portal 2 view planes (from CViewSetup)
		bool cursor_visible = false;
		float cursor_x = 0.5f, cursor_y = 0.5f; // 0..1, for Minecraft screens
		float frame_eye[3] = {};
		float frame_angles[3] = {}, frame_fov = 0.0f, frame_aspect = 1.0f; // the camera the shown Minecraft frame was drawn from
		bool uniforms_found = false, textures_ok = false;
		unsigned uploads = 0;
		unsigned synced = 0, late = 0, wait_us = 0, upload_us = 0;
		bool staged = true; // uploads through system-memory textures (else update_texture_region) // frames shown in step with Minecraft, given up on, time waited
	};
	extern State state;

	void begin_effects(reshade::api::effect_runtime *rt);
	void shutdown(reshade::api::device *dev);
}
