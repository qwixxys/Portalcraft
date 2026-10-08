#include "console.hpp"
#include <windows.h>
#include <atomic>
#include <cstring>

namespace console
{
	static std::atomic<bool> s_save_load{ false };

	// tier0's ILoggingListener: one virtual method, called for every console line (any thread)
	class ILoggingListener
	{
	public:
		virtual void Log(const void *context, const char *message) = 0;
	};

	class Listener : public ILoggingListener
	{
	public:
		void Log(const void *, const char *message) override
		{
			// "Loading game from SAVE\<id>\quick.sav..." (level transitions only restore .HL1 files)
			if (message && strstr(message, "Loading game from") && strstr(message, ".sav"))
				s_save_load = true;
		}
	};
	static Listener s_listener;
	static bool s_attached = false;

	bool attach()
	{
		if (s_attached) return true;
		HMODULE tier0 = GetModuleHandleA("tier0.dll");
		auto reg = tier0 ? reinterpret_cast<void(__cdecl *)(ILoggingListener *)>(GetProcAddress(tier0, "LoggingSystem_RegisterLoggingListener")) : nullptr;
		if (!reg) return false;
		reg(&s_listener);
		s_attached = true;
		return true;
	}

	bool take_save_load() { return s_save_load.exchange(false); }
}
