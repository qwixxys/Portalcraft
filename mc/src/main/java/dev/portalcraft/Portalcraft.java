package dev.portalcraft;

import java.util.List;
import java.util.Set;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Portalcraft implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("portalcraft");
	/** Set by the client while Portal 2 drives the player (single player: same JVM). */
	public static volatile boolean hostLinked;

	@Override
	public void onInitialize() {
		LOG.info("Portalcraft common init");
		// a player in the Nether or the End (a save from before portals were blocked, /execute in ...) goes back to the
		// overworld, where Portal 2's map is; Portal 2 then puts them where Chell is
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!hostLinked) return;
			ServerLevel home = server.overworld();
			for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
				if (p.level() == home) continue;
				LOG.info("{} was in {}: back to the overworld", p.getName().getString(), p.level().dimension().identifier());
				p.teleportTo(home, p.getX(), p.getY(), p.getZ(), Set.of(), p.getYRot(), p.getXRot(), true);
			}
		});
	}
}
