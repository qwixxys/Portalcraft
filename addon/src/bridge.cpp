#include "bridge.hpp"
#include <windows.h>
#include <cstring>
#include <string>

namespace bridge
{
	static uint8_t *s_host = nullptr, *s_guest = nullptr, *s_frame = nullptr;
	static const uint64_t HOST_SIZE = 65536, GUEST_SIZE = 1ull << 21;
	static const size_t DRIVE = 49152, HOLES = 65536, GRID = HOLES + 4096;
	static const uint64_t FRAME_SIZE = 4096ull + 3ull * MAX_W * MAX_H * 12;
	static uint32_t s_last_guest_beat = 0;
	static DWORD s_last_guest_change = 0;

	static uint8_t *map(const wchar_t *name, uint64_t size)
	{
		HANDLE h = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE,
			static_cast<DWORD>(size >> 32), static_cast<DWORD>(size), name);
		if (!h) return nullptr;
		return static_cast<uint8_t *>(MapViewOfFile(h, FILE_MAP_ALL_ACCESS, 0, 0, static_cast<SIZE_T>(size)));
	}

	template <typename T> static T &at(uint8_t *base, size_t off) { return *reinterpret_cast<T *>(base + off); }

	bool open()
	{
		if (s_host) return true;
		s_host = map(L"Local\\PortalcraftHost", HOST_SIZE);
		s_guest = map(L"Local\\PortalcraftGuest", GUEST_SIZE);
		s_frame = map(L"Local\\PortalcraftFrame", FRAME_SIZE);
		if (!s_host || !s_guest || !s_frame) return false;
		at<uint32_t>(s_host, 0) = 0x31484350;
		at<uint32_t>(s_host, 152) = 0xFFFFFFFF;
		// Portal 2's game folder (...\Portal 2\portal2), so Minecraft can read the maps
		char exe[MAX_PATH];
		GetModuleFileNameA(nullptr, exe, MAX_PATH);
		std::string dir(exe);
		dir = dir.substr(0, dir.find_last_of("\\/")) + "\\portal2";
		strncpy_s(reinterpret_cast<char *>(s_host + 9000), 260, dir.c_str(), _TRUNCATE);
		// this run of Portal 2, so Minecraft can tell a load it already handled (after its own restart) from a new one
		at<uint32_t>(s_host, 9300) = GetTickCount() ^ (GetCurrentProcessId() << 16);
		return true;
	}

	void publish_camera(const game::Camera &cam, const game::Player &pl, uint32_t flags, const char *map_name)
	{
		if (!s_host) return;
		volatile uint32_t &seq = at<uint32_t>(s_host, 4);
		seq = seq + 1; // odd: writing
		MemoryBarrier();
		at<uint32_t>(s_host, 8) = flags;
		memcpy(s_host + 16, &cam.origin, 12);
		memcpy(s_host + 28, &cam.angles, 12);
		at<float>(s_host, 40) = cam.fov;
		at<int32_t>(s_host, 44) = cam.width;
		at<int32_t>(s_host, 48) = cam.height;
		if (pl.valid)
		{
			memcpy(s_host + 52, &pl.origin, 12);
			memcpy(s_host + 64, &pl.velocity, 12);
			at<int32_t>(s_host, 76) = pl.health;
			at<int32_t>(s_host, 160) = pl.flags;
		}
		LARGE_INTEGER f, c;
		QueryPerformanceFrequency(&f);
		QueryPerformanceCounter(&c);
		at<double>(s_host, 80) = static_cast<double>(c.QuadPart) / static_cast<double>(f.QuadPart);
		strncpy_s(reinterpret_cast<char *>(s_host + 88), 64, map_name, _TRUNCATE);
		at<uint32_t>(s_host, 156) = cam.serial;
		MemoryBarrier();
		seq = seq + 1; // even: done
	}

	void publish_load(uint32_t serial, uint32_t kind)
	{
		if (!s_host) return;
		at<uint32_t>(s_host, 9308) = kind;
		MemoryBarrier();
		at<uint32_t>(s_host, 9304) = serial;
	}

	void heartbeat()
	{
		if (s_host) at<uint32_t>(s_host, 12)++;
	}

	void push_event(int32_t type, int32_t a, int32_t b, int32_t c)
	{
		if (!s_host) return;
		uint32_t n = at<uint32_t>(s_host, 256);
		int32_t *e = reinterpret_cast<int32_t *>(s_host + 264 + (n % 512) * 16);
		e[0] = type; e[1] = a; e[2] = b; e[3] = c;
		MemoryBarrier();
		at<uint32_t>(s_host, 256) = n + 1;
	}

	void set_reading_slot(uint32_t slot)
	{
		if (s_host) at<uint32_t>(s_host, 152) = slot;
	}

	const FrameHeader *frame_header()
	{
		return s_frame ? reinterpret_cast<const FrameHeader *>(s_frame) : nullptr;
	}

	const SlotHeader *slot_header(uint32_t i)
	{
		return reinterpret_cast<const SlotHeader *>(s_frame + 64 + 64 * i);
	}

	const uint8_t *slot_data(uint32_t i)
	{
		const FrameHeader *h = frame_header();
		return s_frame + 4096 + static_cast<size_t>(i) * h->width * h->height * 12;
	}

	uint32_t guest_flags()
	{
		return s_guest && at<uint32_t>(s_guest, 0) == 0x31474350 ? at<uint32_t>(s_guest, 8) : 0;
	}

	bool guest_alive()
	{
		if (!s_guest || at<uint32_t>(s_guest, 0) != 0x31474350) return false;
		uint32_t beat = at<uint32_t>(s_guest, 12);
		DWORD now = GetTickCount();
		if (beat != s_last_guest_beat) { s_last_guest_beat = beat; s_last_guest_change = now; }
		return now - s_last_guest_change < 2000;
	}

	bool drive_state(DriveState &out)
	{
		if (!s_guest) return false;
		for (int attempt = 0; attempt < 4; ++attempt)
		{
			uint32_t s1 = at<volatile uint32_t>(s_guest, DRIVE);
			if (s1 == 0 || (s1 & 1)) continue;
			memcpy(out.eye, s_guest + DRIVE + 4, 12);
			memcpy(out.feet, s_guest + DRIVE + 16, 12);
			memcpy(out.vel, s_guest + DRIVE + 28, 12);
			MemoryBarrier();
			if (at<volatile uint32_t>(s_guest, DRIVE) == s1) return true;
		}
		return false;
	}

	uint32_t holes_serial()
	{
		return s_guest ? at<volatile uint32_t>(s_guest, HOLES + 4) : 0;
	}

	bool holes_copy(uint8_t *dst, int32_t origin[3], uint32_t &serial)
	{
		if (!s_guest) return false;
		uint32_t s1 = at<volatile uint32_t>(s_guest, HOLES);
		if (s1 == 0 || (s1 & 1)) return false;
		memcpy(origin, s_guest + HOLES + 8, 12);
		memcpy(dst, s_guest + GRID, HOLES_BYTES);
		MemoryBarrier();
		if (at<volatile uint32_t>(s_guest, HOLES) != s1) return false;
		serial = s1;
		return true;
	}

	int solid_boxes(int32_t *boxes, int max, int &serial)
	{
		if (!s_guest) return 0;
		for (int attempt = 0; attempt < 4; ++attempt)
		{
			uint32_t s1 = at<uint32_t>(s_guest, 4);
			if (s1 & 1) continue;
			int n = at<int32_t>(s_guest, 16);
			if (n > max) n = max;
			if (n < 0) n = 0;
			serial = at<int32_t>(s_guest, 20);
			memcpy(boxes, s_guest + 32, static_cast<size_t>(n) * 24);
			if (at<uint32_t>(s_guest, 4) == s1) return n;
		}
		return 0;
	}
}
