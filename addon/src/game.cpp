#include "game.hpp"
#include <mutex>
#include <vector>
#include <cstring>
#include <cstdio>

namespace game
{
	void (*on_view)(const Camera &) = nullptr;
	bool (*override_origin)(float *origin) = nullptr;
	volatile uint32_t strip_buttons = 0;
	volatile bool freeze_movement = false;
	volatile uint32_t force_buttons = 0;
	volatile uint32_t last_buttons = 0;

	static void *s_engine = nullptr;              // VEngineClient015
	static void *s_orig_override_view = nullptr;
	static void *s_orig_create_move = nullptr;
	static std::mutex s_mutex;
	static std::vector<std::string> s_cmd_queue;
	static Camera s_camera = {};
	static unsigned char s_view_raw[0x200];

	// VEngineClient015 vtable indices for build 10090 (from the Portalcraft field note)
	enum { VE_ClientCmd = 7, VE_ConIsVisible = 11, VE_GetLocalPlayer = 12, VE_IsInGame = 25,
	       VE_GetLevelNameShort = 53, VE_IsPaused = 86 };
	// ClientModeShared
	enum { CM_OverrideView = 18, CM_CreateMove = 24 };

	template <typename T> static T vfunc(void *obj, int index)
	{
		return reinterpret_cast<T>((*reinterpret_cast<void ***>(obj))[index]);
	}

	static void *get_interface(const char *module, const char *name)
	{
		HMODULE m = GetModuleHandleA(module);
		if (!m) return nullptr;
		using CreateInterfaceFn = void *(__cdecl *)(const char *, int *);
		auto fn = reinterpret_cast<CreateInterfaceFn>(GetProcAddress(m, "CreateInterface"));
		return fn ? fn(name, nullptr) : nullptr;
	}

	static bool section(HMODULE m, const char *name, uintptr_t &start, size_t &size)
	{
		auto dos = reinterpret_cast<IMAGE_DOS_HEADER *>(m);
		auto nt = reinterpret_cast<IMAGE_NT_HEADERS *>(reinterpret_cast<uintptr_t>(m) + dos->e_lfanew);
		auto sec = IMAGE_FIRST_SECTION(nt);
		for (int i = 0; i < nt->FileHeader.NumberOfSections; ++i, ++sec)
			if (strncmp(reinterpret_cast<const char *>(sec->Name), name, 8) == 0)
			{
				start = reinterpret_cast<uintptr_t>(m) + sec->VirtualAddress;
				size = sec->Misc.VirtualSize;
				return true;
			}
		return false;
	}

	// MSVC RTTI: type name -> TypeDescriptor -> CompleteObjectLocator (offset 0) -> primary vtable
	static void **find_vtable(const char *module, const char *mangled)
	{
		HMODULE m = GetModuleHandleA(module);
		uintptr_t data, rdata; size_t data_size, rdata_size;
		if (!m || !section(m, ".data", data, data_size) || !section(m, ".rdata", rdata, rdata_size))
			return nullptr;
		const size_t len = strlen(mangled);
		uintptr_t td = 0;
		for (uintptr_t p = data; p + len < data + data_size; ++p)
			if (memcmp(reinterpret_cast<void *>(p), mangled, len + 1) == 0) { td = p - 8; break; }
		if (!td) return nullptr;
		uintptr_t col = 0;
		for (uintptr_t p = rdata; p + 4 <= rdata + rdata_size; p += 4)
			if (*reinterpret_cast<uintptr_t *>(p) == td)
			{
				auto c = reinterpret_cast<uint32_t *>(p - 12);
				if (c[0] == 0 && c[1] == 0) { col = p - 12; break; }
			}
		if (!col) return nullptr;
		for (uintptr_t p = rdata; p + 4 <= rdata + rdata_size; p += 4)
			if (*reinterpret_cast<uintptr_t *>(p) == col)
				return reinterpret_cast<void **>(p + 4);
		return nullptr;
	}

	static void patch(void **slot, void *fn, void **orig)
	{
		DWORD old;
		VirtualProtect(slot, sizeof(void *), PAGE_READWRITE, &old);
		if (orig && !*orig) *orig = *slot;
		*slot = fn;
		VirtualProtect(slot, sizeof(void *), old, &old);
	}

	static void run_queued()
	{
		std::vector<std::string> q;
		{
			std::lock_guard<std::mutex> lock(s_mutex);
			q.swap(s_cmd_queue);
		}
		for (auto &c : q)
			vfunc<void(__thiscall *)(void *, const char *)>(s_engine, VE_ClientCmd)(s_engine, c.c_str());
	}

	static void parse_view(const unsigned char *v, Camera &c);

	static void __fastcall hk_override_view(void *self, void *, void *setup)
	{
		reinterpret_cast<void(__thiscall *)(void *, void *)>(s_orig_override_view)(self, setup);
		if (override_origin) override_origin(reinterpret_cast<float *>(static_cast<char *>(setup) + 0x70));
		{
			std::lock_guard<std::mutex> lock(s_mutex);
			memcpy(s_view_raw, setup, sizeof(s_view_raw));
			parse_view(s_view_raw, s_camera);
			s_camera.serial++;
		}
		run_queued();
		if (on_view) on_view(s_camera);
	}

	static bool __fastcall hk_create_move(void *self, void *, float sample_time, void *cmd)
	{
		bool r = reinterpret_cast<bool(__thiscall *)(void *, float, void *)>(s_orig_create_move)(self, sample_time, cmd);
		if (cmd)
		{
			auto buttons = reinterpret_cast<uint32_t *>(reinterpret_cast<char *>(cmd) + 36);
			last_buttons = *buttons;
			*buttons &= ~strip_buttons;
			*buttons |= force_buttons;
			if (freeze_movement) // forwardmove, sidemove, upmove
				memset(reinterpret_cast<char *>(cmd) + 24, 0, 12);
		}
		return r;
	}

	// The player's size lives in CViewVectors tables (2 in server.dll, 2 in client.dll for prediction):
	// view (0,0,64), hull (-16,-16,0)-(16,16,72), duck hull (-16,-16,0)-(16,16,36), duck view (0,0,28).
	// Portalcraft makes Chell exactly as big as a Minecraft player so blocks never trap her:
	// 0.6 x 1.8 blocks, eye at 1.62, sneaking 1.5 tall with the eye at 1.27 (1 block = 32 units).
	int patch_player_size(const char *module)
	{
		HMODULE m = GetModuleHandleA(module);
		if (!m) return -1;
		static const float from[15] = { 0, 0, 64, -16, -16, 0, 16, 16, 72, -16, -16, 0, 16, 16, 36 };
		static const float to[18] = { 0, 0, 51.84f, -9.6f, -9.6f, 0, 9.6f, 9.6f, 57.6f, -9.6f, -9.6f, 0, 9.6f, 9.6f, 48.0f, 0, 0, 40.64f };
		int patched = 0;
		for (const char *sec : { ".data", ".rdata" })
		{
			uintptr_t start; size_t size;
			if (!section(m, sec, start, size)) continue;
			for (uintptr_t p = start; p + sizeof(to) <= start + size; p += 4)
			{
				if (memcmp(reinterpret_cast<void *>(p), from, sizeof(from)) != 0) continue;
				const float *duck_view = reinterpret_cast<const float *>(p + sizeof(from));
				if (duck_view[0] != 0 || duck_view[1] != 0 || duck_view[2] != 28) continue;
				DWORD old;
				VirtualProtect(reinterpret_cast<void *>(p), sizeof(to), PAGE_READWRITE, &old);
				memcpy(reinterpret_cast<void *>(p), to, sizeof(to));
				VirtualProtect(reinterpret_cast<void *>(p), sizeof(to), old, &old);
				patched++;
			}
		}
		return patched;
	}

	bool init()
	{
		s_engine = get_interface("engine.dll", "VEngineClient015");
		if (!s_engine) return false;
		bool hooked = false;
		for (const char *cls : { ".?AVClientModePortalNormal@@", ".?AVClientModePortalNormalFullscreen@@" })
		{
			void **vt = find_vtable("client.dll", cls);
			if (!vt) continue;
			// both classes may share the same functions; keep the first originals
			void *ov = vt[CM_OverrideView], *cm = vt[CM_CreateMove];
			if (!s_orig_override_view) s_orig_override_view = ov;
			if (!s_orig_create_move) s_orig_create_move = cm;
			if (ov == s_orig_override_view) patch(&vt[CM_OverrideView], reinterpret_cast<void *>(&hk_override_view), nullptr);
			if (cm == s_orig_create_move) patch(&vt[CM_CreateMove], reinterpret_cast<void *>(&hk_create_move), nullptr);
			hooked = true;
		}
		return hooked;
	}

	void client_cmd(const char *cmd)
	{
		std::lock_guard<std::mutex> lock(s_mutex);
		s_cmd_queue.emplace_back(cmd);
	}

	void console_cmd(const char *cmd)
	{
		std::string c = "script SendToConsole(\"";
		for (const char *p = cmd; *p; ++p)
			if (*p != '"') c += *p;
		c += "\")";
		client_cmd(c.c_str());
	}

	bool in_game() { return s_engine && vfunc<bool(__thiscall *)(void *)>(s_engine, VE_IsInGame)(s_engine); }
	bool paused() { return s_engine && vfunc<bool(__thiscall *)(void *)>(s_engine, VE_IsPaused)(s_engine); }
	bool console_visible() { return s_engine && vfunc<bool(__thiscall *)(void *)>(s_engine, VE_ConIsVisible)(s_engine); }

	std::string level_name()
	{
		if (!s_engine) return {};
		const char *n = vfunc<const char *(__thiscall *)(void *)>(s_engine, VE_GetLevelNameShort)(s_engine);
		return n ? n : "";
	}

	Camera camera()
	{
		std::lock_guard<std::mutex> lock(s_mutex);
		return s_camera;
	}

	// CViewSetup layout in build 10090 (found by dumping it against setpos/setang)
	static void parse_view(const unsigned char *v, Camera &c)
	{
		memcpy(&c.width, v + 0x10, 4);
		memcpy(&c.height, v + 0x18, 4);
		memcpy(&c.fov, v + 0x68, 4);
		memcpy(&c.origin, v + 0x70, 12);
		memcpy(&c.angles, v + 0x7c, 12);
		memcpy(&c.znear, v + 0x88, 4);
		memcpy(&c.zfar, v + 0x8c, 4);
	}

	void dump_view_setup(std::string &out)
	{
		unsigned char raw[sizeof(s_view_raw)];
		{
			std::lock_guard<std::mutex> lock(s_mutex);
			memcpy(raw, s_view_raw, sizeof(raw));
		}
		char line[128];
		for (int off = 0; off < (int)sizeof(raw); off += 4)
		{
			int i; float f;
			memcpy(&i, raw + off, 4); memcpy(&f, raw + off, 4);
			snprintf(line, sizeof(line), "%03x: %11d %14.4f\n", off, i, f);
			out += line;
		}
	}
}
