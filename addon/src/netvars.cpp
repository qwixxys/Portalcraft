// Network variables by name (walks ClientClass -> RecvTable), never hard-coded offsets.
#include "game.hpp"
#include <cstring>
#include <map>

namespace game
{
	struct RecvTable;
	struct RecvProp // 60 bytes on 32-bit
	{
		const char *name; int type; int flags; int string_size; bool inside_array; const void *extra;
		RecvProp *array_prop; void *array_length_proxy; void *proxy; void *dt_proxy; RecvTable *table;
		int offset; int stride; int elements; const char *parent_array_name;
	};
	struct RecvTable { RecvProp *props; int count; void *decoder; const char *name; };
	struct ClientClass { void *create; void *create_event; const char *name; RecvTable *table; ClientClass *next; int id; };

	static int find_in_table(RecvTable *t, const char *prop)
	{
		for (int i = 0; i < t->count; ++i)
		{
			RecvProp &p = t->props[i];
			if (p.name && strcmp(p.name, prop) == 0)
				return p.offset;
			if (p.type == 6 /* DPT_DataTable */ && p.table)
			{
				int o = find_in_table(p.table, prop);
				if (o >= 0) return p.offset + o;
			}
		}
		return -1;
	}

	static int netvar(const char *cls, const char *prop)
	{
		static std::map<std::string, int> cache;
		std::string key = std::string(cls) + "." + prop;
		auto it = cache.find(key);
		if (it != cache.end()) return it->second;
		HMODULE m = GetModuleHandleA("client.dll");
		using CreateInterfaceFn = void *(__cdecl *)(const char *, int *);
		auto ci = reinterpret_cast<CreateInterfaceFn>(GetProcAddress(m, "CreateInterface"));
		void *client = ci ? ci("VClient016", nullptr) : nullptr;
		int result = -1;
		if (client)
		{
			auto get_all = (*reinterpret_cast<ClientClass *(__thiscall ***)(void *)>(client))[8];
			for (ClientClass *c = get_all(client); c; c = c->next)
				if (strcmp(c->name, cls) == 0) { result = find_in_table(c->table, prop); break; }
		}
		cache[key] = result;
		return result;
	}

	Player local_player()
	{
		Player p = {};
		static void *engine = nullptr, *entlist = nullptr;
		if (!engine)
		{
			using CreateInterfaceFn = void *(__cdecl *)(const char *, int *);
			auto e = reinterpret_cast<CreateInterfaceFn>(GetProcAddress(GetModuleHandleA("engine.dll"), "CreateInterface"));
			auto c = reinterpret_cast<CreateInterfaceFn>(GetProcAddress(GetModuleHandleA("client.dll"), "CreateInterface"));
			engine = e ? e("VEngineClient015", nullptr) : nullptr;
			entlist = c ? c("VClientEntityList003", nullptr) : nullptr;
		}
		if (!engine || !entlist) return p;
		int index = (*reinterpret_cast<int(__thiscall ***)(void *)>(engine))[12](engine); // GetLocalPlayer
		char *ent = (*reinterpret_cast<char *(__thiscall ***)(void *, int)>(entlist))[3](entlist, index); // GetClientEntity
		if (!ent) return p;
		int o_origin = netvar("CPortal_Player", "m_vecOrigin");
		int o_view = netvar("CPortal_Player", "m_vecViewOffset[0]");
		int o_vel = netvar("CPortal_Player", "m_vecVelocity[0]");
		int o_health = netvar("CPortal_Player", "m_iHealth");
		int o_flags = netvar("CPortal_Player", "m_fFlags");
		if (o_origin < 0 || o_view < 0) return p;
		memcpy(&p.origin, ent + o_origin, 12);
		memcpy(&p.view_offset, ent + o_view, 12);
		if (o_vel >= 0) memcpy(&p.velocity, ent + o_vel, 12);
		if (o_health >= 0) memcpy(&p.health, ent + o_health, 4);
		if (o_flags >= 0) memcpy(&p.flags, ent + o_flags, 4);
		p.valid = true;
		return p;
	}
}
