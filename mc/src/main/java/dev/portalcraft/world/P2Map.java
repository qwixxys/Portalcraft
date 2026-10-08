package dev.portalcraft.world;

import dev.portalcraft.Portalcraft;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Portal 2 map the player is in, as Minecraft sees it: where its walls are (the BSP), which blocks make up its
 * barrier shell, and which of those the player has broken (holes Portal 2 should stop drawing).
 * Source cell (cx, cy, cz) = Minecraft block (cx + regionX, cz + Y_OFFSET, -(cy + 1)); 1 cell = 32 units = 1 block.
 * Shared by the client (mirror, movement) and the integrated server (explosions, block changes): same JVM.
 */
public final class P2Map {
	public static final int Y_OFFSET = 128;
	public static volatile P2Map current;

	public final String name;
	public final int regionX;
	public final Bsp bsp;
	private final LongOpenHashSet shell;
	/** Shell blocks that are no longer barriers (BlockPos.asLong). */
	public final Set<Long> broken = ConcurrentHashMap.newKeySet();
	public final AtomicInteger brokenVersion = new AtomicInteger();
	private volatile boolean brokenDirty;
	private final Path brokenFile;
	/** Called on the server thread when a shell block breaks (terrain behind it gets generated). */
	public volatile java.util.function.Consumer<BlockPos> onBreak;
	/**
	 * Sections of this map's region changed since its Minecraft side was last reset (player blocks, holes, terrain,
	 * explosions): a fresh visit of the map puts exactly these back. Our own barrier placing doesn't count.
	 */
	public final Set<Long> dirty = ConcurrentHashMap.newKeySet();
	private volatile boolean dirtyChanged;
	private final Path dirtyFile;
	/** Changes have been tracked since a reset (worlds from before only know them from what is loaded). */
	public volatile boolean tracked;
	/** Server thread: set while our own code (mirror, reset) changes blocks, which are not player changes. */
	public static boolean quiet;

	// shell cells by shape: wholly solid (P2 surfaces only on their faces), solid in the lower or the upper half
	private final LongOpenHashSet full, bottomHalf, topHalf;
	/** Wholly solid shell cells turned into real rock around holes and tunnels (pos -> Block.getId). */
	private final ConcurrentHashMap<Long, Integer> rock = new ConcurrentHashMap<>();
	private final java.util.Map<Long, Integer> staleRock = new java.util.HashMap<>(), fallenRock = new java.util.HashMap<>();
	private volatile boolean rockDirty;
	private final Path rockFile;
	private static final BlockState SLAB_BOTTOM = Blocks.SMOOTH_STONE_SLAB.defaultBlockState(),
		SLAB_TOP = Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);

	public P2Map(String name, int regionX, Bsp bsp, LongOpenHashSet shell, LongOpenHashSet full, LongOpenHashSet bottomHalf,
		LongOpenHashSet topHalf, Path dataDir) {
		this.name = name;
		this.regionX = regionX;
		this.bsp = bsp;
		this.shell = shell;
		this.full = full;
		this.bottomHalf = bottomHalf;
		this.topHalf = topHalf;
		this.rockFile = dataDir.resolve(name + ".rock");
		readRock();
		this.brokenFile = dataDir.resolve(name + ".broken");
		this.dirtyFile = dataDir.resolve(name + ".dirty");
		this.tracked = Files.exists(dirtyFile);
		load();
		readLongs(dirtyFile, dirty);
	}

	public boolean isShell(long pos) {
		return shell.contains(pos);
	}

	public int shellSize() {
		return shell.size();
	}

	/** Portal 2 has solid at the centre of this block's cell (a wall, a floor, or outside the map). */
	public boolean solidCenter(int x, int y, int z) {
		return bsp.solidAt((x - regionX) * 32 + 16, -(z + 1) * 32 + 16, (y - Y_OFFSET) * 32 + 16);
	}

	/**
	 * Portal 2 has ordinary, drawn wall at the centre of this block's cell: not glass, grates, clips or invisible
	 * blockers, which are solid but show what is behind them (Minecraft must keep its terrain out of those).
	 */
	public boolean opaqueCenter(int x, int y, int z) {
		return bsp.opaqueAt((x - regionX) * 32 + 16, -(z + 1) * 32 + 16, (y - Y_OFFSET) * 32 + 16);
	}

	/**
	 * Render threads: Portal 2 draws a surface between an intact wall cell and this neighbour cell: open space, or a wall
	 * cell only partly solid (debris, trim, a recess), where Portal 2 shows either its own piece or the surface right on
	 * the face. Not toward holes, whole wall cells or the ground behind the walls.
	 */
	public boolean surfaceToward(BlockPos neighbor) {
		long key = neighbor.asLong();
		if (broken.contains(key)) return false;
		if (shell.contains(key)) return !full.contains(key);
		return !opaqueCenter(neighbor.getX(), neighbor.getY(), neighbor.getZ());
	}

	/** Inside Portal 2's walls: a barrier of the shell, or solid behind it. */
	public boolean p2Solid(int x, int y, int z) {
		return shell.contains(BlockPos.asLong(x, y, z)) || solidCenter(x, y, z);
	}

	/**
	 * Minecraft draws blocks here at full light: Portal 2's rooms are lit (whatever surrounds them in Minecraft) and
	 * so is the bottom of a fresh hole in their walls. Tunnels dug deeper keep Minecraft's own (dark) light.
	 */
	public boolean litForRendering(int x, int y, int z) {
		return !p2Solid(x, y, z) || broken.contains(BlockPos.asLong(x, y, z));
	}

	/**
	 * What a shell cell holds while Portal 2's wall there is intact: rock where it was turned into rock (around holes),
	 * a smooth stone slab where Portal 2's floor or ceiling is half a block into the cell (blocks, mobs and the player
	 * then stand exactly on Portal 2's floor), an invisible barrier everywhere else.
	 */
	public BlockState expected(long pos) {
		Integer id = rock.get(pos);
		if (id != null) return Block.stateById(id);
		if (bottomHalf.contains(pos)) return SLAB_BOTTOM;
		if (topHalf.contains(pos)) return SLAB_TOP;
		return Blocks.BARRIER.defaultBlockState();
	}

	/** The shell block as the mirror puts it (no rock): what a reset goes back to. */
	public BlockState plain(long pos) {
		if (bottomHalf.contains(pos)) return SLAB_BOTTOM;
		if (topHalf.contains(pos)) return SLAB_TOP;
		return Blocks.BARRIER.defaultBlockState();
	}

	/**
	 * Render threads: the block here stands for Portal 2's own wall, still intact (barrier, half-floor slab, rock).
	 * Portal 2 draws that wall's surfaces, so Minecraft leaves out the faces lying on them: the rock's faces toward the
	 * rooms, the slabs' tops. Depth alone can't sort out two surfaces in the same place (rock bled through walls).
	 */
	public boolean intactWall(BlockPos pos) {
		long key = pos.asLong();
		return shell.contains(key) && !broken.contains(key);
	}

	/** Portal 2's wall is still there in this shell cell (anything else is a hole). */
	public boolean intact(long pos, BlockState state) {
		BlockState want = expected(pos);
		if (state.getBlock() != want.getBlock()) return false;
		return !(want.getBlock() instanceof SlabBlock) || state.getValue(SlabBlock.TYPE) == want.getValue(SlabBlock.TYPE);
	}

	/** 1: Portal 2's floor is half a block into this shell cell (solid below), 2: a ceiling likewise, 0: neither. */
	public int halfType(long pos) {
		return bottomHalf.contains(pos) ? 1 : topHalf.contains(pos) ? 2 : 0;
	}

	/**
	 * Explosions break this part of Portal 2's wall: flat, solid wall and floor (glass, grates, slanted and curved
	 * pieces stay: a 1-block grid would cut them into steps).
	 */
	public boolean breakableByExplosion(long pos) {
		return full.contains(pos) || bottomHalf.contains(pos) || topHalf.contains(pos);
	}

	/** Wholly solid shell cell: real rock can go there, its faces lie on Portal 2's surfaces (which cover them). */
	public boolean canBeRock(long pos) {
		return full.contains(pos);
	}

	/** Start of a reset: shell cells go back to their plain blocks. */
	public void clearRock() {
		rock.clear();
		rockDirty = true;
	}

	/** Server thread, right before the rock is placed. */
	public void setRock(long pos, BlockState state) {
		rock.put(pos, Block.getId(state));
		rockDirty = true;
	}

	public int halfCells() {
		return bottomHalf.size() + topHalf.size();
	}

	private void readRock() {
		if (!Files.exists(rockFile)) return;
		try (var in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(rockFile)))) {
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				long pos = in.readLong();
				int id = in.readInt();
				// rock from an older version where it no longer belongs (a slanted or curved spot): goes back
				if (!(full.contains(pos) || bottomHalf.contains(pos) || topHalf.contains(pos))) {
					staleRock.put(pos, id);
					continue;
				}
				if (Block.stateById(id).getBlock() instanceof Fallable) { // 1.0.0's gravel and sand, which fell out of ceilings
					fallenRock.put(pos, id);
					id = Block.getId(TerrainFiller.wallRock(BlockPos.getY(pos)));
				}
				rock.put(pos, id);
			}
		} catch (IOException e) {
			Portalcraft.LOG.warn("rock of {}: {}", name, e.toString());
		}
		if (!staleRock.isEmpty() || !fallenRock.isEmpty()) rockDirty = true;
	}

	/**
	 * Server thread, once the map is current: what readRock corrected is corrected in the world too. Stale rock becomes
	 * the plain wall again; gravel and sand become stone, the ceiling holes they left close, and what fell into the
	 * rooms goes.
	 */
	public void repairRock(ServerLevel level) {
		if (staleRock.isEmpty() && fallenRock.isEmpty()) return;
		int stale = staleRock.size(), turned = 0, closed = 0, cleared = 0;
		quiet = true;
		try {
			for (var e : staleRock.entrySet()) {
				BlockPos pos = BlockPos.of(e.getKey());
				if (level.getBlockState(pos) == Block.stateById(e.getValue())) level.setBlock(pos, plain(e.getKey()), Block.UPDATE_CLIENTS);
			}
			for (var e : fallenRock.entrySet()) {
				long key = e.getKey();
				BlockPos pos = BlockPos.of(key);
				BlockState now = level.getBlockState(pos);
				boolean fell = now.isAir() && broken.contains(key);
				if (!fell && now != Block.stateById(e.getValue())) continue; // dug or built over since: stays as it is
				level.setBlock(pos, expected(key), Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE); // blockChanged: no hole any more
				if (fell) closed++;
				else turned++;
			}
			if (closed > 0) cleared = clearFallen(level);
		} finally {
			quiet = false;
		}
		if (stale > 0) Portalcraft.LOG.info("{}: {} rock blocks at slanted spots back to plain wall", name, stale);
		if (!fallenRock.isEmpty())
			Portalcraft.LOG.info("{}: gravel and sand in the walls turned to stone ({} in place, {} fallen: holes closed, {} fallen blocks cleared from the rooms)",
				name, turned, closed, cleared);
		staleRock.clear();
		fallenRock.clear();
	}

	/** Gravel and sand lying in Portal 2's rooms, in the sections changed since the last reset that are loaded. */
	private int clearFallen(ServerLevel level) {
		int n = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Long key : dirty) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.x(key), SectionPos.z(key));
			int index = level.getSectionIndexFromSectionY(SectionPos.y(key));
			if (chunk == null || index < 0 || index >= level.getSectionsCount()) continue;
			LevelChunkSection section = chunk.getSection(index);
			if (section.hasOnlyAir() || !section.maybeHas(s -> s.getBlock() instanceof Fallable)) continue;
			int ox = SectionPos.sectionToBlockCoord(SectionPos.x(key)), oy = SectionPos.sectionToBlockCoord(SectionPos.y(key)),
				oz = SectionPos.sectionToBlockCoord(SectionPos.z(key));
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						if (!(section.getBlockState(x, y, z).getBlock() instanceof Fallable) || p2Solid(ox + x, oy + y, oz + z)) continue;
						level.setBlock(pos.set(ox + x, oy + y, oz + z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
						n++;
					}
		}
		return n;
	}

	private void writeRock() {
		try {
			Files.createDirectories(rockFile.getParent());
			try (var out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(rockFile)))) {
				var all = new java.util.ArrayList<>(rock.entrySet());
				out.writeInt(all.size());
				for (var e : all) {
					out.writeLong(e.getKey());
					out.writeInt(e.getValue());
				}
			}
		} catch (IOException e) {
			Portalcraft.LOG.warn("saving rock of {}: {}", name, e.toString());
		}
	}

	/** Server thread, from every block change in the overworld. */
	public void blockChanged(BlockPos pos, BlockState state) {
		long key = pos.asLong();
		if (!shell.contains(key)) return;
		boolean ok = intact(key, state);
		boolean changed = ok ? broken.remove(key) : broken.add(key);
		if (!changed) return;
		brokenVersion.incrementAndGet();
		brokenDirty = true;
		if (!ok) Portalcraft.LOG.info("wall of {} broken at {} (Source cell {} {} {})", name, pos.toShortString(), pos.getX() - regionX, -(pos.getZ() + 1), pos.getY() - Y_OFFSET);
		var cb = onBreak;
		if (cb != null && !ok) cb.accept(pos.immutable());
	}

	/** This map's stretch of the world along x (regions are 2048 blocks apart). */
	public boolean inRegion(int x) {
		return Math.abs(x - regionX) < 1024;
	}

	/** Server thread, from every block change in the overworld. */
	public void markDirty(BlockPos pos) {
		if (quiet || !inRegion(pos.getX())) return;
		if (dirty.add(net.minecraft.core.SectionPos.asLong(pos))) dirtyChanged = true;
	}

	/** After a reset: nothing differs from the plain mirror any more. */
	public void resetDone() {
		tracked = true;
		dirty.clear();
		rock.clear();
		rockDirty = true;
		dirtyChanged = true;
		brokenDirty = true;
		saveIfDirty();
	}

	private void load() {
		readLongs(brokenFile, broken);
		brokenVersion.incrementAndGet();
	}

	public void saveIfDirty() {
		if (brokenDirty) {
			brokenDirty = false;
			writeLongs(brokenFile, broken);
		}
		if (rockDirty) {
			rockDirty = false;
			writeRock();
		}
		if (dirtyChanged && tracked) { // untracked (older) worlds start tracking at their first reset
			dirtyChanged = false;
			writeLongs(dirtyFile, dirty);
		}
	}

	private void readLongs(Path file, Set<Long> into) {
		if (!Files.exists(file)) return;
		try (var in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
			int n = in.readInt();
			for (int i = 0; i < n; i++) into.add(in.readLong());
		} catch (IOException e) {
			Portalcraft.LOG.warn("{} of {}: {}", file.getFileName(), name, e.toString());
		}
	}

	private void writeLongs(Path file, Set<Long> from) {
		try {
			Files.createDirectories(file.getParent());
			try (var out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
				Long[] all = from.toArray(new Long[0]);
				out.writeInt(all.length);
				for (Long l : all) out.writeLong(l);
			}
		} catch (IOException e) {
			Portalcraft.LOG.warn("saving {} of {}: {}", file.getFileName(), name, e.toString());
		}
	}
}
