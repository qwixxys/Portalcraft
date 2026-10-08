package dev.portalcraft.client;

import dev.portalcraft.Portalcraft;
import dev.portalcraft.world.MapReset;
import dev.portalcraft.world.P2Map;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Level loads in Portal 2 decide what happens to the map's Minecraft side: a fresh visit (new game, chapter select,
 * the next map, restart) starts it clean (MapReset); a save game (death, quickload, Continue) keeps it.
 * The last load handled is remembered in the world, so restarting Minecraft mid-visit doesn't wipe it.
 */
final class Visits {
	private int doneSession, doneSerial = -1;
	private MinecraftServer loadedFor;
	/** A fresh load waits for its map (or its reset): Portal 2 doesn't show Minecraft until then. */
	volatile boolean pending;

	void tick(Minecraft mc, HostLink host, LevelMirror mirror) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || host.loadSerial == 0 || host.loadKind == 0) return;
		Path file = mirror.worldRoot(server).resolve("portalcraft_visit.txt");
		if (loadedFor != server) {
			loadedFor = server;
			doneSession = 0;
			doneSerial = -1;
			try {
				if (Files.exists(file)) {
					String[] p = Files.readString(file).trim().split(" ");
					doneSession = Integer.parseInt(p[0]);
					doneSerial = Integer.parseInt(p[1]);
				}
			} catch (Exception e) {
				Portalcraft.LOG.warn("{}: {}", file.getFileName(), e.toString());
			}
		}
		if (host.session == doneSession && host.loadSerial == doneSerial) {
			pending = false;
			return;
		}
		pending = host.loadKind == HostLink.LOAD_FRESH;
		P2Map map = P2Map.current;
		if (map == null || !map.name.equals(host.map) || mirror.building()) return; // the map is still being read
		doneSession = host.session;
		doneSerial = host.loadSerial;
		try {
			Files.writeString(file, doneSession + " " + doneSerial + System.lineSeparator());
		} catch (java.io.IOException e) {
			Portalcraft.LOG.warn("{}: {}", file.getFileName(), e.toString());
		}
		if (host.loadKind == HostLink.LOAD_FRESH) {
			Path dataDir = mirror.worldRoot(server).resolve("portalcraft");
			MapReset.schedule(server, map, dataDir);
		} else {
			Portalcraft.LOG.info("save game of {} loaded: its Minecraft side stays as it is", map.name);
		}
		pending = false;
	}
}
