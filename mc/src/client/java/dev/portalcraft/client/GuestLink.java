package dev.portalcraft.client;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;

/**
 * Minecraft's side of the link (PROTOCOL.md): heartbeat, flags, the solid cells Portal 2 should collide with,
 * where Minecraft has moved the player while it drives, and the grid of holes in Portal 2's walls.
 */
public final class GuestLink {
	public static final int MAGIC = 0x31474350;
	public static final int F_CONNECTED = 1, F_SCREEN = 2, F_HIDE_VIEWMODEL = 4, F_DRIVE = 8, F_VALID = 16, F_RETURN = 32,
		F_CAM_OUTSIDE = 64, F_BUSY = 128, F_LOCKSTEP = 256, F_CAM_IN_WALL = 512;
	/** Room in the shared memory for solid boxes (24 bytes each, up to DRIVE). */
	public static final int MAX_BOXES = 2000;
	public static final long DRIVE = 49152, HOLES = 65536, GRID = HOLES + 4096;
	/** Hole grid: 128 x 128 x 64 cells around the camera, packed into a 512 x 512 texture (4 z cells per texel, bytes R,G,B,A). */
	public static final int GX = 128, GY = 128, GZ = 64, GRID_BYTES = 512 * 512 * 4;

	private final MemorySegment seg;
	private int beat, driveSerial, gridSerial;

	public GuestLink() {
		this.seg = Shm.open(Shm.GUEST, Shm.GUEST_SIZE);
		seg.set(JAVA_INT_UNALIGNED, 0, MAGIC);
	}

	public void publish(int flags) {
		seg.set(JAVA_INT_UNALIGNED, 8, flags);
		seg.set(JAVA_INT_UNALIGNED, 12, ++beat);
	}

	/** boxes: 6 ints each in the Source grid (32-unit cells): min corner x, y, z and size x, y, z. */
	public void solids(int[] boxes, int count, int serial) {
		count = Math.min(count, MAX_BOXES);
		int seq = seg.get(JAVA_INT_UNALIGNED, 4);
		seg.set(JAVA_INT_UNALIGNED, 4, seq | 1);
		seg.set(JAVA_INT_UNALIGNED, 16, count);
		seg.set(JAVA_INT_UNALIGNED, 20, serial);
		MemorySegment.copy(boxes, 0, seg, JAVA_INT_UNALIGNED, 32, count * 6);
		seg.set(JAVA_INT_UNALIGNED, 4, (seq | 1) + 1);
	}

	/** While Minecraft drives: camera eye, feet and velocity in Source units (per second). */
	public void drive(double[] eye, double[] feet, double[] vel) {
		int s = (driveSerial | 1);
		seg.set(JAVA_INT_UNALIGNED, DRIVE, s);
		for (int i = 0; i < 3; i++) {
			seg.set(JAVA_FLOAT_UNALIGNED, DRIVE + 4 + i * 4, (float) eye[i]);
			seg.set(JAVA_FLOAT_UNALIGNED, DRIVE + 16 + i * 4, (float) feet[i]);
			seg.set(JAVA_FLOAT_UNALIGNED, DRIVE + 28 + i * 4, (float) vel[i]);
		}
		driveSerial = s + 1;
		seg.set(JAVA_INT_UNALIGNED, DRIVE, driveSerial);
	}

	/** The hole grid (bytes laid out as described above) with its origin in Source cells. */
	public void holes(byte[] grid, int ox, int oy, int oz) {
		int s = gridSerial | 1;
		seg.set(JAVA_INT_UNALIGNED, HOLES, s);
		seg.set(JAVA_INT_UNALIGNED, HOLES + 8, ox);
		seg.set(JAVA_INT_UNALIGNED, HOLES + 12, oy);
		seg.set(JAVA_INT_UNALIGNED, HOLES + 16, oz);
		MemorySegment.copy(MemorySegment.ofArray(grid), 0, seg, GRID, GRID_BYTES);
		gridSerial = s + 1;
		seg.set(JAVA_INT_UNALIGNED, HOLES + 4, gridSerial);
		seg.set(JAVA_INT_UNALIGNED, HOLES, gridSerial);
	}

	/** Byte offset of a cell (relative to the grid origin) in the grid. */
	public static int gridIndex(int cx, int cy, int cz) {
		int tx = cx + ((cz >> 2) & 3) * GX, ty = cy + (cz >> 4) * GY;
		return (ty * 512 + tx) * 4 + (cz & 3);
	}
}
