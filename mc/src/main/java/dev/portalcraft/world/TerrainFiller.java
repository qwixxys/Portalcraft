package dev.portalcraft.world;

import dev.portalcraft.Portalcraft;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Generates Minecraft terrain inside Portal 2's walls, floors and ceilings, one 16^3 section at a time, the first
 * time something can see it: around a broken wall, around an explosion (before it happens, so it leaves a crater),
 * and around the player while Minecraft moves them through holes. Blocks only go where Portal 2 has opaque wall, not
 * next to its open space (the shell of barriers stays the visible wall). Sections done are remembered per map.
 */
public final class TerrainFiller {
	private static volatile TerrainFiller current;
	private static final long ROCK = 1L << 40; // marks a wall cell turned into rock in compute()'s output
	private static final int FORMAT = -2; // first int of the sections file (1.0.0's started with the count)

	private final MinecraftServer server;
	private final P2Map map;
	private final TerrainGen gen;
	private final Set<Long> done = ConcurrentHashMap.newKeySet();
	private final Set<Long> queued = ConcurrentHashMap.newKeySet();
	private final LinkedBlockingDeque<Long> queue = new LinkedBlockingDeque<>();
	private final Path file;
	private volatile boolean dirty, stopped;
	public volatile int generatedSections, placedBlocks;

	private TerrainFiller(MinecraftServer server, P2Map map, Path dataDir) {
		this.server = server;
		this.map = map;
		this.gen = new TerrainGen(server, server.overworld().getSeed());
		this.file = dataDir.resolve(map.name + ".terrain");
		boolean old = load();
		Thread t = new Thread(() -> {
			if (old) cleanSeeThrough();
			run();
		}, "Portalcraft terrain");
		t.setDaemon(true);
		t.start();
	}

	public static TerrainFiller current() {
		return current;
	}

	/** New map: a fresh filler (the old one stops). */
	public static void start(MinecraftServer server, P2Map map, Path dataDir) {
		TerrainFiller old = current;
		if (old != null) {
			old.saveIfDirty();
			old.stopped = true;
		}
		TerrainFiller f = new TerrainFiller(server, map, dataDir);
		current = f;
		map.onBreak = pos -> server.execute(() -> {
			f.ensure(pos, 3);  // what you see through the hole right away
			f.request(pos, 1); // and the rest of the neighbourhood soon after
		});
	}

	public static void stop() {
		TerrainFiller old = current;
		current = null;
		if (old != null) {
			old.saveIfDirty();
			old.stopped = true;
		}
	}

	/** Any thread: sections within {@code radius} sections of pos, nearest first, in the background. */
	public void request(BlockPos pos, int radius) {
		int sx = SectionPos.blockToSectionCoord(pos.getX()), sy = SectionPos.blockToSectionCoord(pos.getY()), sz = SectionPos.blockToSectionCoord(pos.getZ());
		List<Long> keys = new ArrayList<>();
		for (int d = 0; d <= radius; d++)
			for (int x = -d; x <= d; x++)
				for (int y = -d; y <= d; y++)
					for (int z = -d; z <= d; z++) {
						if (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) != d) continue;
						long key = SectionPos.asLong(sx + x, sy + y, sz + z);
						if (!done.contains(key) && queued.add(key)) keys.add(key);
					}
		for (int i = keys.size() - 1; i >= 0; i--) queue.offerFirst(keys.get(i)); // newest requests first
	}

	/** Server thread: every section touching the sphere is generated now (explosions, a fresh hole). */
	public void ensure(BlockPos pos, int radius) {
		int x0 = SectionPos.blockToSectionCoord(pos.getX() - radius), x1 = SectionPos.blockToSectionCoord(pos.getX() + radius);
		int y0 = SectionPos.blockToSectionCoord(pos.getY() - radius), y1 = SectionPos.blockToSectionCoord(pos.getY() + radius);
		int z0 = SectionPos.blockToSectionCoord(pos.getZ() - radius), z1 = SectionPos.blockToSectionCoord(pos.getZ() + radius);
		for (int x = x0; x <= x1; x++)
			for (int y = y0; y <= y1; y++)
				for (int z = z0; z <= z1; z++) {
					long key = SectionPos.asLong(x, y, z);
					if (done.contains(key)) continue;
					place(key, compute(key));
				}
	}

	private void run() {
		while (!stopped) {
			try {
				Long key = queue.pollFirst(500, TimeUnit.MILLISECONDS);
				if (key == null || done.contains(key)) continue;
				long[] blocks = compute(key);
				server.execute(() -> {
					if (!stopped) place(key, blocks);
				});
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				Portalcraft.LOG.error("terrain generation failed", t);
			}
		}
	}

	/** Which blocks the section gets: pairs (BlockPos.asLong, Block.getId(state)). */
	private long[] compute(long key) {
		int ox = SectionPos.sectionToBlockCoord(SectionPos.x(key)), oy = SectionPos.sectionToBlockCoord(SectionPos.y(key)),
			oz = SectionPos.sectionToBlockCoord(SectionPos.z(key));
		// Portal 2 solid / shell for the section plus a one-block margin; see-through solids (glass, clips, invisible
		// blockers) count as open: terrain inside them would stand in the room
		byte[] kind = new byte[18 * 18 * 18]; // 0 open, 1 solid, 2 shell
		boolean any = false;
		for (int y = 0; y < 18; y++)
			for (int z = 0; z < 18; z++)
				for (int x = 0; x < 18; x++) {
					int bx = ox + x - 1, by = oy + y - 1, bz = oz + z - 1;
					byte k = map.isShell(BlockPos.asLong(bx, by, bz)) ? (byte) 2 : map.opaqueCenter(bx, by, bz) ? (byte) 1 : 0;
					kind[(y * 18 + z) * 18 + x] = k;
					if (k == 1) any = true;
				}
		List<Integer> fill = new ArrayList<>(), rockCells = new ArrayList<>();
		// wholly solid wall cells become real rock, so holes and tunnels have walls and ceilings (Minecraft leaves out the
		// rock's faces that lie on Portal 2's surfaces: P2Map.intactWall)
		for (int y = 1; y < 17; y++)
			for (int z = 1; z < 17; z++)
				for (int x = 1; x < 17; x++)
					if (kind[(y * 18 + z) * 18 + x] == 2) {
						long p = BlockPos.asLong(ox + x - 1, oy + y - 1, oz + z - 1);
						if (map.canBeRock(p) || map.halfType(p) != 0) rockCells.add(((y - 1) * 16 + z - 1) * 16 + x - 1);
					}
		if (!any && rockCells.isEmpty()) return new long[0];
		for (int y = 1; y < 17; y++)
			for (int z = 1; z < 17; z++)
				for (int x = 1; x < 17; x++) {
					if (kind[(y * 18 + z) * 18 + x] != 1) continue;
					boolean nearOpen = false;
					for (int dy = -1; dy <= 1 && !nearOpen; dy++)
						for (int dz = -1; dz <= 1 && !nearOpen; dz++)
							for (int dx = -1; dx <= 1 && !nearOpen; dx++)
								if (kind[((y + dy) * 18 + z + dz) * 18 + x + dx] == 0) nearOpen = true;
					if (!nearOpen) fill.add(((y - 1) * 16 + z - 1) * 16 + x - 1);
				}
		if (fill.isEmpty() && rockCells.isEmpty()) return new long[0];
		BlockState[] states;
		synchronized (gen) {
			states = gen.section(ox, oy, oz);
		}
		long[] out = new long[(fill.size() + rockCells.size()) * 2];
		int n = 0;
		for (int i : fill) {
			BlockState s = states[i];
			if (s == null || s.isAir()) continue;
			out[n++] = BlockPos.asLong(ox + (i & 15), oy + (i >> 8), oz + ((i >> 4) & 15));
			out[n++] = Block.getId(s);
		}
		for (int i : rockCells) {
			BlockState s = states[i];
			int by = oy + (i >> 8);
			boolean deep = by - TerrainGen.VY_SHIFT < 0;
			// a wall needs a solid block that stays put, even where the noise has a cave, water, gravel or sand (those
			// fell out of ceilings, and every hole they left made more terrain, with more gravel...)
			if (s == null || !s.isSolidRender() || s.getBlock() instanceof Fallable) s = wallRock(by);
			long p = BlockPos.asLong(ox + (i & 15), by, oz + ((i >> 4) & 15));
			int half = map.halfType(p);
			if (half != 0) // a half-block floor or ceiling: a slab of the rock around it
				s = (deep ? Blocks.COBBLED_DEEPSLATE_SLAB : Blocks.STONE_SLAB).defaultBlockState()
					.setValue(net.minecraft.world.level.block.SlabBlock.TYPE, half == 1 ? net.minecraft.world.level.block.state.properties.SlabType.BOTTOM
						: net.minecraft.world.level.block.state.properties.SlabType.TOP);
			out[n++] = p;
			out[n++] = Block.getId(s) | ROCK;
		}
		return java.util.Arrays.copyOf(out, n);
	}

	/** Plain rock for a wall cell at this Minecraft height (stone, deepslate deeper down). */
	static BlockState wallRock(int y) {
		return (y - TerrainGen.VY_SHIFT < 0 ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState();
	}

	/** Server thread. Only into air: what players built or dug since stays as it is. */
	private void place(long key, long[] blocks) {
		if (!done.add(key)) return;
		queued.remove(key);
		dirty = true;
		generatedSections++;
		ServerLevel level = server.overworld();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < blocks.length; i += 2) {
			pos.set(blocks[i]);
			if (level.isOutsideBuildHeight(pos.getY())) continue;
			BlockState now = level.getBlockState(pos);
			boolean rock = (blocks[i + 1] & ROCK) != 0;
			BlockState s = Block.stateById((int) (blocks[i + 1] & ~ROCK));
			if (rock) {
				// only an untouched wall (a hole stays a hole)
				if (!map.intact(blocks[i], now)) continue;
				map.setRock(blocks[i], s);
			} else if (!now.isAir()) {
				continue;
			}
			// like world generation: gravel and sand stay put, water and lava stay still, until something next to them changes
			level.setBlock(pos, s, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE);
			placedBlocks++;
		}
	}

	public boolean isDone(BlockPos pos) {
		return done.contains(SectionPos.asLong(pos));
	}

	/** The sections done; true for a file from 1.0.0 (see cleanSeeThrough). */
	private boolean load() {
		if (!Files.exists(file)) return false;
		boolean old = false;
		try (var in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
			int n = in.readInt();
			if (n == FORMAT) n = in.readInt();
			else old = true;
			for (int i = 0; i < n; i++) done.add(in.readLong());
		} catch (IOException e) {
			Portalcraft.LOG.warn("terrain sections of {}: {}", map.name, e.toString());
		}
		return old;
	}

	/**
	 * Terrain thread, once for sections generated by 1.0.0: it filled Portal 2's see-through solids too (invisible
	 * blockers, clips), so blocks stood in its rooms. Those go.
	 */
	private void cleanSeeThrough() {
		List<Long> out = new ArrayList<>();
		for (long key : done.toArray(new Long[0])) {
			int ox = SectionPos.sectionToBlockCoord(SectionPos.x(key)), oy = SectionPos.sectionToBlockCoord(SectionPos.y(key)),
				oz = SectionPos.sectionToBlockCoord(SectionPos.z(key));
			for (int y = oy; y < oy + 16; y++)
				for (int z = oz; z < oz + 16; z++)
					for (int x = ox; x < ox + 16; x++)
						if (!map.isShell(BlockPos.asLong(x, y, z)) && map.solidCenter(x, y, z) && !map.opaqueCenter(x, y, z)) out.add(BlockPos.asLong(x, y, z));
		}
		dirty = true; // saved in the current format from now on
		if (out.isEmpty()) return;
		server.execute(() -> {
			if (stopped) return;
			ServerLevel level = server.overworld();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			int n = 0;
			for (long p : out) {
				if (level.getBlockState(pos.set(p)).isAir()) continue;
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				n++;
			}
			Portalcraft.LOG.info("{}: {} terrain blocks taken out of Portal 2's see-through walls", map.name, n);
		});
	}

	public void saveIfDirty() {
		if (!dirty) return;
		dirty = false;
		try {
			Files.createDirectories(file.getParent());
			try (var out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
				Long[] all = done.toArray(new Long[0]);
				out.writeInt(FORMAT);
				out.writeInt(all.length);
				for (Long l : all) out.writeLong(l);
			}
		} catch (IOException e) {
			Portalcraft.LOG.warn("saving terrain sections of {}: {}", map.name, e.toString());
		}
	}
}
