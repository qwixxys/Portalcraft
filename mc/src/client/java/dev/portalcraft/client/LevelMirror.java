package dev.portalcraft.client;

import dev.portalcraft.Portalcraft;
import dev.portalcraft.world.Bsp;
import dev.portalcraft.world.P2Map;
import dev.portalcraft.world.TerrainFiller;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Mirrors the current Portal 2 map into Minecraft as invisible barrier blocks (only the surface shell), so
 * Minecraft blocks can be placed against Portal 2's floors and walls and mobs walk on them. Every session it also
 * rebuilds the map's P2Map (shell, BSP) for holes, terrain and movement.
 * Each map gets its own region along x (portalcraft_regions.txt in the world folder); a marker file remembers
 * which maps already have their barriers.
 */
public final class LevelMirror {
	/** 2: re-places barriers knocked out before holes existed; 3: slabs for half-block floors; 4: not under slanted ones. */
	private static final int VERSION = 4;
	private String current = "";
	private volatile boolean building;
	private final ConcurrentLinkedQueue<long[]> pending = new ConcurrentLinkedQueue<>();
	public volatile String status = "";

	/** The old fixed scheme (hash of the name); kept for maps mirrored with it. */
	private static int legacySlot(String map) {
		return Math.floorMod(map.hashCode(), 200);
	}

	private static int regionOf(int slot) {
		return (slot - 100) * 2048;
	}

	/** Region slot of a map in this world: its legacy slot unless another map already has it. */
	private static synchronized int slotFor(Path root, String map) {
		Path file = root.resolve("portalcraft_regions.txt");
		Map<String, Integer> slots = new HashMap<>();
		try {
			if (Files.exists(file))
				for (String line : Files.readAllLines(file)) {
					String[] p = line.trim().split(" ");
					if (p.length == 2) slots.put(p[0], Integer.parseInt(p[1]));
				}
			// maps mirrored before the registry existed keep their old place
			Path marker = root.resolve("portalcraft_maps.txt");
			if (Files.exists(marker))
				for (String line : Files.readAllLines(marker)) {
					String name = line.trim().split(" ")[0];
					if (!name.isEmpty()) slots.putIfAbsent(name, legacySlot(name));
				}
			Integer have = slots.get(map);
			if (have == null) {
				Set<Integer> used = new HashSet<>(slots.values());
				int s = legacySlot(map);
				while (used.contains(s)) s++;
				slots.put(map, have = s);
			}
			StringBuilder sb = new StringBuilder();
			slots.forEach((k, v) -> sb.append(k).append(' ').append(v).append(System.lineSeparator()));
			Files.writeString(file, sb.toString());
			return have;
		} catch (Exception e) {
			Portalcraft.LOG.warn("region registry: {}", e.toString());
			return legacySlot(map);
		}
	}

	private MinecraftServer rootServer;
	private Path root;

	/** The world folder, looked up on the server thread (its path cache is not thread-safe). */
	Path worldRoot(MinecraftServer server) {
		if (server != rootServer) {
			root = server.submit(() -> server.getWorldPath(LevelResource.ROOT)).join();
			rootServer = server;
		}
		return root;
	}

	/** Client tick. */
	public void tick(Minecraft mc, HostLink host) {
		if (host.map.isEmpty() || host.gameDir.isEmpty()) return;
		if (host.map.equals(current)) return;
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) return;
		current = host.map;
		P2Map.current = null;
		TerrainFiller.stop();
		Path root = worldRoot(server);
		int region = regionOf(slotFor(root, current));
		HostLink.regionX = region;
		Path marker = root.resolve("portalcraft_maps.txt");
		String key = current + " v" + VERSION;
		boolean mirrored = false;
		try {
			mirrored = Files.exists(marker) && Files.readAllLines(marker).contains(key);
		} catch (java.io.IOException ignored) {
		}
		Path bsp = Path.of(host.gameDir, "maps", current + ".bsp");
		if (!Files.exists(bsp)) {
			for (String dlc : new String[] { "portal2_dlc1", "portal2_dlc2" }) {
				Path alt = Path.of(host.gameDir).resolveSibling(dlc).resolve("maps").resolve(current + ".bsp");
				if (Files.exists(alt)) bsp = alt;
			}
		}
		Path file = bsp;
		String name = current;
		boolean place = !mirrored;
		building = true;
		status = current + ": reading map";
		Thread t = new Thread(() -> build(server, file, name, region, root, marker, key, place), "Portalcraft map mirror");
		t.setDaemon(true);
		t.start();
	}

	/** World closed: nothing is current any more. */
	public void reset() {
		current = "";
		P2Map map = P2Map.current;
		if (map != null) map.saveIfDirty();
		P2Map.current = null;
		TerrainFiller.stop();
	}

	private void build(MinecraftServer server, Path file, String name, int region, Path root, Path marker, String key, boolean place) {
		try {
			Bsp bsp = new Bsp(file);
			Set<Long> cells = new HashSet<>();
			List<int[]> list = new ArrayList<>();
			bsp.solidCells((x, y, z) -> {
				cells.add(pack(x, y, z));
				list.add(new int[] { x, y, z });
			}, 4_000_000);
			Set<Long> angled = new HashSet<>(); // cells near visible slanted or curved surfaces
			bsp.slantedFaceCells((x, y, z) -> angled.add(pack(x, y, z)));
			// keep only cells with an open face: the inside of thick walls never matters
			LongOpenHashSet shell = new LongOpenHashSet(), full = new LongOpenHashSet(), bottomHalf = new LongOpenHashSet(),
				topHalf = new LongOpenHashSet();
			List<long[]> blocks = new ArrayList<>();
			for (int[] c : list) {
				int x = c[0], y = c[1], z = c[2];
				if (cells.contains(pack(x + 1, y, z)) && cells.contains(pack(x - 1, y, z)) && cells.contains(pack(x, y + 1, z))
					&& cells.contains(pack(x, y - 1, z)) && cells.contains(pack(x, y, z + 1)) && cells.contains(pack(x, y, z - 1))) continue;
				// Source cell -> Minecraft block: x, z + Y_OFFSET, -(y + 1)
				long bx = x + region, by = z + (long) P2Map.Y_OFFSET, bz = -(y + 1);
				long cell = BlockPos.asLong((int) bx, (int) by, (int) bz);
				shell.add(cell);
				blocks.add(new long[] { bx, by, bz });
				// the cell's shape in Portal 2: 4 heights x 9 spots (edges included) of ordinary (opaque) wall; real blocks
				// only behind straight walls (a slanted or curved surface would have the block's corners poking through)
				int lower = 0, upper = 0;
				if (!angled.contains(pack(x, y, z)))
					for (int k = 0; k < 4; k++)
						for (int s = 0; s < 9; s++)
							if (bsp.opaqueAt(x * 32 + 1 + (s % 3) * 15, y * 32 + 1 + (s / 3) * 15, z * 32 + 1 + k * 10)) {
								if (k < 2) lower++;
								else upper++;
							}
				if (lower == 18 && upper == 18) full.add(cell);
				else if (lower == 18 && upper == 0) bottomHalf.add(cell); // a floor half a block into the cell
				else if (upper == 18 && lower == 0) topHalf.add(cell);   // a ceiling likewise
			}
			if (!name.equals(current)) return; // the player moved on while we read
			Path dataDir = root.resolve("portalcraft");
			P2Map map = new P2Map(name, region, bsp, shell, full, bottomHalf, topHalf, dataDir);
			status = file.getFileName() + ": " + shell.size() + " wall cells (" + list.size() + " solid cells, " + map.halfCells() + " half-block floors and ceilings), "
				+ map.broken.size() + " broken";
			Portalcraft.LOG.info("map {}: region x {}, {}", name, region, status);
			server.execute(() -> {
				if (!name.equals(current)) return;
				P2Map.current = map;
				map.repairRock(server.overworld());
				TerrainFiller.start(server, map, dataDir);
				if (place) {
					pending.clear();
					pending.addAll(blocks);
					place(server, map, marker, key);
				} else {
					building = false;
				}
			});
		} catch (Exception e) {
			status = "map mirror failed: " + e;
			Portalcraft.LOG.error("map mirror failed", e);
			building = false;
		}
	}

	/** Places a batch per server tick so the game keeps running. */
	private void place(MinecraftServer server, P2Map map, Path marker, String key) {
		if (P2Map.current != map) return;
		ServerLevel level = server.overworld();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int n = 0;
		long[] c;
		P2Map.quiet = true; // the mirror itself, not a player's change
		try {
			while (n < 6000 && (c = pending.poll()) != null) {
				if (c[1] < level.getMinY() || c[1] > level.getMaxY()) continue;
				pos.set(c[0], c[1], c[2]);
				BlockState s = level.getBlockState(pos);
				long at = pos.asLong();
				// air, or our own barrier or slab that is no longer the right one for this cell
				if (s.isAir() || ((s.is(Blocks.BARRIER) || s.is(Blocks.SMOOTH_STONE_SLAB)) && !map.intact(at, s)))
					level.setBlock(pos, map.expected(at), Block.UPDATE_CLIENTS);
				else map.blockChanged(pos, s); // a player's block where the wall was: counts as a hole
				n++;
			}
		} finally {
			P2Map.quiet = false;
		}
		if (!pending.isEmpty()) {
			server.execute(() -> place(server, map, marker, key));
			return;
		}
		building = false;
		status = status + ", placed";
		try {
			Files.writeString(marker, key + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (java.io.IOException e) {
			Portalcraft.LOG.warn("marker: {}", e.toString());
		}
		Portalcraft.LOG.info("map mirror done: {}", status);
	}

	/** The current Portal 2 map has its region (where the player goes in Minecraft). */
	public boolean regionFor(String map) {
		return !map.isEmpty() && map.equals(current);
	}

	public boolean building() {
		return building;
	}

	private static long pack(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}
}
