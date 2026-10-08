package dev.portalcraft.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * Reads a Portal 2 map (VBSP v21) and turns the world's solid brushes into 32-unit cells (1 cell = 1 block).
 * Only brushes reachable from model 0's BSP tree count, so doors and other brush entities stay out.
 */
public final class Bsp {
	private static final int LUMP_PLANES = 1, LUMP_NODES = 5, LUMP_LEAFS = 10, LUMP_MODELS = 14,
		LUMP_LEAFBRUSHES = 17, LUMP_BRUSHES = 18, LUMP_BRUSHSIDES = 19;
	// solid, window, grate, player clip
	private static final int SOLID_MASK = 0x1 | 0x2 | 0x8 | 0x10000;
	// window, grate, player clip, monster clip
	private static final int SEE_THROUGH = 0x2 | 0x8 | 0x10000 | 0x20000;
	private static final int LUMP_TEXINFO = 6, SURF_TRANS = 0x10, SURF_SKY = 0x4, SURF_NODRAW = 0x80;
	private static final int LUMP_VERTEXES = 3, LUMP_FACES = 7, LUMP_EDGES = 12, LUMP_SURFEDGES = 13;
	public static final int CELL = 32;

	private final ByteBuffer b;
	private final int[] lumpOfs = new int[64], lumpLen = new int[64];

	public Bsp(Path file) throws IOException {
		b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != 0x50534256) throw new IOException("not a VBSP file: " + file);
		for (int i = 0; i < 64; i++) {
			lumpOfs[i] = b.getInt(8 + i * 16);
			lumpLen[i] = b.getInt(8 + i * 16 + 4);
		}
	}

	/** Brush indices used by the world (model 0). */
	private BitSet worldBrushes() {
		BitSet out = new BitSet();
		int headnode = b.getInt(lumpOfs[LUMP_MODELS] + 36);
		int nodes = lumpOfs[LUMP_NODES], leafs = lumpOfs[LUMP_LEAFS], lb = lumpOfs[LUMP_LEAFBRUSHES];
		int leafSize = 32;
		java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
		stack.push(headnode);
		while (!stack.isEmpty()) {
			int n = stack.pop();
			if (n < 0) {
				int leaf = leafs + (-1 - n) * leafSize;
				int first = Short.toUnsignedInt(b.getShort(leaf + 24));
				int count = Short.toUnsignedInt(b.getShort(leaf + 26));
				for (int i = 0; i < count; i++) out.set(Short.toUnsignedInt(b.getShort(lb + (first + i) * 2)));
			} else {
				int node = nodes + n * 32;
				stack.push(b.getInt(node + 4));
				stack.push(b.getInt(node + 8));
			}
		}
		return out;
	}

	public interface CellSink {
		void accept(int x, int y, int z);
	}

	/** Every 32-unit cell that is solid in the world; returns the number of cells. */
	public int solidCells(CellSink sink, int maxCells) {
		BitSet world = worldBrushes();
		int planes = lumpOfs[LUMP_PLANES], brushes = lumpOfs[LUMP_BRUSHES], sides = lumpOfs[LUMP_BRUSHSIDES];
		int brushCount = lumpLen[LUMP_BRUSHES] / 12;
		java.util.HashSet<Long> seen = new java.util.HashSet<>();
		int count = 0;
		for (int bi = world.nextSetBit(0); bi >= 0 && bi < brushCount; bi = world.nextSetBit(bi + 1)) {
			int br = brushes + bi * 12;
			int firstSide = b.getInt(br), numSides = b.getInt(br + 4), contents = b.getInt(br + 8);
			if ((contents & SOLID_MASK) == 0 || numSides <= 0) continue;
			float[][] pl = new float[numSides][4];
			float minX = -1e9f, minY = -1e9f, minZ = -1e9f, maxX = 1e9f, maxY = 1e9f, maxZ = 1e9f;
			for (int s = 0; s < numSides; s++) {
				int side = sides + (firstSide + s) * 8;
				int planeNum = Short.toUnsignedInt(b.getShort(side));
				int p = planes + planeNum * 20;
				float nx = b.getFloat(p), ny = b.getFloat(p + 4), nz = b.getFloat(p + 8), d = b.getFloat(p + 12);
				pl[s][0] = nx; pl[s][1] = ny; pl[s][2] = nz; pl[s][3] = d;
				// axial planes bound the brush
				if (nx == 1) maxX = Math.min(maxX, d);
				if (nx == -1) minX = Math.max(minX, -d);
				if (ny == 1) maxY = Math.min(maxY, d);
				if (ny == -1) minY = Math.max(minY, -d);
				if (nz == 1) maxZ = Math.min(maxZ, d);
				if (nz == -1) minZ = Math.max(minZ, -d);
			}
			if (maxX - minX > 30000 || maxY - minY > 30000 || maxZ - minZ > 30000) continue; // unbounded
			int cx0 = Math.floorDiv((int) Math.floor(minX), CELL), cx1 = Math.floorDiv((int) Math.ceil(maxX) - 1, CELL);
			int cy0 = Math.floorDiv((int) Math.floor(minY), CELL), cy1 = Math.floorDiv((int) Math.ceil(maxY) - 1, CELL);
			int cz0 = Math.floorDiv((int) Math.floor(minZ), CELL), cz1 = Math.floorDiv((int) Math.ceil(maxZ) - 1, CELL);
			for (int cx = cx0; cx <= cx1; cx++)
				for (int cy = cy0; cy <= cy1; cy++)
					for (int cz = cz0; cz <= cz1; cz++) {
						if (!cellTouches(pl, cx, cy, cz)) continue;
						long key = ((long) (cx & 0x1FFFFF) << 42) | ((long) (cy & 0x1FFFFF) << 21) | (cz & 0x1FFFFF);
						if (!seen.add(key)) continue;
						sink.accept(cx, cy, cz);
						if (++count >= maxCells) return count;
					}
		}
		return count;
	}

	/**
	 * Solid at a point: inside a solid leaf (structural walls, and everything outside the sealed map) or inside one
	 * of the solid brushes listed in its leaf (detail brushes, clips). Thread-safe (reads only).
	 */
	public boolean solidAt(float x, float y, float z) {
		return query(x, y, z, false);
	}

	/**
	 * Solid and drawn as an ordinary wall at a point: no glass, grates, player clips or translucent brushes. Minecraft
	 * may put real blocks right behind such a surface (Portal 2 still covers them); never behind see-through ones.
	 */
	public boolean opaqueAt(float x, float y, float z) {
		return query(x, y, z, true);
	}

	private boolean query(float x, float y, float z, boolean opaqueOnly) {
		int nodes = lumpOfs[LUMP_NODES], planes = lumpOfs[LUMP_PLANES];
		int n = b.getInt(lumpOfs[LUMP_MODELS] + 36);
		while (n >= 0) {
			int node = nodes + n * 32;
			int p = planes + b.getInt(node) * 20;
			float d = b.getFloat(p) * x + b.getFloat(p + 4) * y + b.getFloat(p + 8) * z - b.getFloat(p + 12);
			n = b.getInt(node + (d >= 0 ? 4 : 8));
		}
		int leaf = lumpOfs[LUMP_LEAFS] + (-1 - n) * 32;
		if ((b.getInt(leaf) & 1) != 0) return true;
		int first = Short.toUnsignedInt(b.getShort(leaf + 24)), count = Short.toUnsignedInt(b.getShort(leaf + 26));
		int lb = lumpOfs[LUMP_LEAFBRUSHES], brushes = lumpOfs[LUMP_BRUSHES], sides = lumpOfs[LUMP_BRUSHSIDES];
		for (int i = 0; i < count; i++) {
			int br = brushes + Short.toUnsignedInt(b.getShort(lb + (first + i) * 2)) * 12;
			int contents = b.getInt(br + 8);
			if ((contents & SOLID_MASK) == 0) continue;
			if (opaqueOnly && ((contents & 1) == 0 || (contents & SEE_THROUGH) != 0)) continue;
			int firstSide = b.getInt(br), numSides = b.getInt(br + 4);
			boolean inside = numSides > 0;
			for (int s = 0; s < numSides && inside; s++) {
				int side = sides + (firstSide + s) * 8;
				int p = planes + Short.toUnsignedInt(b.getShort(side)) * 20;
				if (b.getFloat(p) * x + b.getFloat(p + 4) * y + b.getFloat(p + 8) * z - b.getFloat(p + 12) > 0.01f) inside = false;
				if (opaqueOnly && inside && (texFlags(b.getShort(side + 2)) & SURF_TRANS) != 0) inside = false;
			}
			if (inside) return true;
		}
		return false;
	}

	private int texFlags(short texinfo) {
		if (texinfo < 0 || texinfo >= lumpLen[LUMP_TEXINFO] / 72) return 0;
		return b.getInt(lumpOfs[LUMP_TEXINFO] + texinfo * 72 + 64);
	}

	/**
	 * Cells near a visible slanted or curved surface of the world (or a displacement): every world face whose plane
	 * isn't axial reports the cells its bounds touch. Faces between brushes don't exist, so a round room built of
	 * wedge brushes with a flat floor only marks its curved rims and walls.
	 */
	public void slantedFaceCells(CellSink sink) {
		int model = lumpOfs[LUMP_MODELS];
		int firstFace = b.getInt(model + 40), numFaces = b.getInt(model + 44);
		int faces = lumpOfs[LUMP_FACES], planes = lumpOfs[LUMP_PLANES], surfedges = lumpOfs[LUMP_SURFEDGES], edges = lumpOfs[LUMP_EDGES],
			verts = lumpOfs[LUMP_VERTEXES];
		for (int f = firstFace; f < firstFace + numFaces; f++) {
			int face = faces + f * 56;
			int p = planes + Short.toUnsignedInt(b.getShort(face)) * 20;
			float nx = b.getFloat(p), ny = b.getFloat(p + 4), nz = b.getFloat(p + 8);
			boolean displacement = b.getShort(face + 12) >= 0;
			if (!displacement && Math.max(Math.abs(nx), Math.max(Math.abs(ny), Math.abs(nz))) >= 0.999f) continue;
			if ((texFlags(b.getShort(face + 10)) & (SURF_NODRAW | SURF_SKY)) != 0) continue;
			int first = b.getInt(face + 4), num = Short.toUnsignedInt(b.getShort(face + 8));
			float minX = 1e9f, minY = 1e9f, minZ = 1e9f, maxX = -1e9f, maxY = -1e9f, maxZ = -1e9f;
			for (int e = 0; e < num; e++) {
				int se = b.getInt(surfedges + (first + e) * 4);
				int v = Short.toUnsignedInt(b.getShort(edges + Math.abs(se) * 4 + (se >= 0 ? 0 : 2)));
				float x = b.getFloat(verts + v * 12), y = b.getFloat(verts + v * 12 + 4), z = b.getFloat(verts + v * 12 + 8);
				minX = Math.min(minX, x); maxX = Math.max(maxX, x);
				minY = Math.min(minY, y); maxY = Math.max(maxY, y);
				minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
			}
			if (num == 0 || maxX - minX > 30000 || maxY - minY > 30000 || maxZ - minZ > 30000) continue;
			for (int cx = Math.floorDiv((int) Math.floor(minX - 1), CELL); cx <= Math.floorDiv((int) Math.floor(maxX + 1), CELL); cx++)
				for (int cy = Math.floorDiv((int) Math.floor(minY - 1), CELL); cy <= Math.floorDiv((int) Math.floor(maxY + 1), CELL); cy++)
					for (int cz = Math.floorDiv((int) Math.floor(minZ - 1), CELL); cz <= Math.floorDiv((int) Math.floor(maxZ + 1), CELL); cz++)
						sink.accept(cx, cy, cz);
		}
	}

	/** True if a 3x3x3 sample of the cell (inset 2 units) has a point inside the convex brush. */
	private static boolean cellTouches(float[][] planes, int cx, int cy, int cz) {
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++)
				for (int k = 0; k < 3; k++) {
					float x = cx * CELL + 2 + i * 14, y = cy * CELL + 2 + j * 14, z = cz * CELL + 2 + k * 14;
					boolean inside = true;
					for (float[] p : planes) {
						if (p[0] * x + p[1] * y + p[2] * z - p[3] > 0.01f) {
							inside = false;
							break;
						}
					}
					if (inside) return true;
				}
		return false;
	}
}
