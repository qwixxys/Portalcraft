package dev.portalcraft.world;

import dev.portalcraft.Portalcraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * A fresh visit of a Portal 2 map (new game, chapter select, the next map) starts its Minecraft side clean, like
 * Portal 2 itself: every section changed since the last reset goes back to the plain mirror (barriers where Portal 2
 * has walls, air everywhere else), mobs and dropped items in the region go, and the ground behind the walls is
 * forgotten (it is generated again when something opens it). Loading a save game keeps everything.
 * Runs on the server thread, a batch of sections per tick.
 */
public final class MapReset {
	private static final int SECTIONS_PER_TICK = 24;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
		| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
	private static volatile boolean running;

	private MapReset() {}

	/** A reset is under way (Portal 2 doesn't show Minecraft meanwhile). */
	public static boolean running() {
		return running;
	}

	/** Any thread: the reset starts on the server thread; running() is true from now on. */
	public static void schedule(MinecraftServer server, P2Map map, Path dataDir) {
		running = true;
		server.execute(() -> start(server, map, dataDir));
	}

	private static void start(MinecraftServer server, P2Map map, Path dataDir) {
		if (P2Map.current != map) {
			running = false;
			return;
		}
		ServerLevel level = server.overworld();
		TerrainFiller.stop();
		try {
			Files.deleteIfExists(dataDir.resolve(map.name + ".terrain"));
		} catch (java.io.IOException e) {
			Portalcraft.LOG.warn("terrain sections of {}: {}", map.name, e.toString());
		}

		Set<Long> sections = new HashSet<>(map.dirty);
		boolean legacy = !map.tracked;
		if (legacy) {
			// a world from before changes were tracked: everything loaded in the region (the chamber you are in)
			int cx0 = (map.regionX - 1024) >> 4, cx1 = (map.regionX + 1023) >> 4;
			for (int cx = cx0; cx <= cx1; cx++)
				for (int cz = -64; cz <= 64; cz++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) continue;
					for (int i = 0; i < chunk.getSectionsCount(); i++)
						if (!chunk.getSection(i).hasOnlyAir()) sections.add(SectionPos.asLong(cx, level.getSectionYFromSectionIndex(i), cz));
				}
		}
		Portalcraft.LOG.info("fresh visit of {}: resetting {} sections{} and {} holes", map.name, sections.size(), legacy ? " (first reset: all loaded)" : "",
			map.broken.size());
		purgeEntities(level, map);
		map.clearRock(); // from now on the walls are judged against the plain mirror

		// holes in Portal 2's walls close again
		P2Map.quiet = true;
		try {
			for (Long b : map.broken.toArray(new Long[0])) level.setBlock(BlockPos.of(b), map.plain(b), FLAGS);
		} finally {
			P2Map.quiet = false;
		}
		ArrayDeque<Long> queue = new ArrayDeque<>(sections);
		server.execute(() -> step(server, level, map, dataDir, queue));
	}

	private static void step(MinecraftServer server, ServerLevel level, P2Map map, Path dataDir, ArrayDeque<Long> queue) {
		if (P2Map.current != map) { // the player went on to another map meanwhile
			running = false;
			return;
		}
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		P2Map.quiet = true;
		try {
			for (int n = 0; n < SECTIONS_PER_TICK && !queue.isEmpty(); n++) {
				long key = queue.poll();
				int sy = SectionPos.y(key);
				int index = level.getSectionIndexFromSectionY(sy);
				if (index < 0 || index >= level.getSectionsCount()) continue;
				LevelChunk chunk = level.getChunk(SectionPos.x(key), SectionPos.z(key));
				LevelChunkSection section = chunk.getSection(index);
				if (section.hasOnlyAir()) continue;
				int ox = SectionPos.sectionToBlockCoord(SectionPos.x(key)), oy = SectionPos.sectionToBlockCoord(sy), oz = SectionPos.sectionToBlockCoord(SectionPos.z(key));
				for (int y = 0; y < 16; y++)
					for (int z = 0; z < 16; z++)
						for (int x = 0; x < 16; x++) {
							BlockState s = section.getBlockState(x, y, z);
							if (s.isAir()) continue;
							pos.set(ox + x, oy + y, oz + z);
							long p = pos.asLong();
							boolean wall = map.isShell(p);
							BlockState want = wall ? map.plain(p) : air;
							if (s == want) continue;
							level.setBlock(pos, want, FLAGS);
						}
			}
		} finally {
			P2Map.quiet = false;
		}
		if (!queue.isEmpty()) {
			server.execute(() -> step(server, level, map, dataDir, queue));
			return;
		}
		purgeEntities(level, map); // anything that dropped meanwhile
		map.resetDone();
		TerrainFiller.start(server, map, dataDir);
		running = false;
		Portalcraft.LOG.info("fresh visit of {}: Minecraft side is clean", map.name);
	}

	/** Mobs, items, arrows, TNT... in the map's region; players stay. */
	private static void purgeEntities(ServerLevel level, P2Map map) {
		AABB box = new AABB(map.regionX - 1024, level.getMinY(), -1024, map.regionX + 1024, level.getMaxY() + 1, 1024);
		int n = 0;
		for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof Player))) {
			e.discard();
			n++;
		}
		if (n > 0) Portalcraft.LOG.info("removed {} entities from {}", n, map.name);
	}
}
