package dev.portalcraft.client;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.phys.Vec3;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;

/**
 * Portal 2's side of the link (see PROTOCOL.md), plus the one mapping between Source and Minecraft space:
 * 32 Source units = 1 block, Source (x east, y north, z up) -> Minecraft (x, z + Y_OFFSET, -y).
 */
public final class HostLink {
	public static final int MAGIC = 0x31484350;
	public static final double UNITS_PER_BLOCK = 32.0;
	public static final double Y_OFFSET = 128.0;
	/** Every Portal 2 map gets its own stretch of the Minecraft world, this many blocks along x. */
	public static volatile int regionX;

	public static final int F_IN_GAME = 1, F_PAUSED = 2, F_BUILD = 4, F_CONSOLE = 8, F_THIRD = 16, F_JUMP = 32;

	private final MemorySegment seg;
	private int lastHeartbeat = -1;
	private long lastBeatNanos;

	// snapshot (Source space)
	public int flags, heartbeat, width, height, health, cameraSerial, playerFlags;
	public float ox, oy, oz, pitch, yaw, roll, fov;
	public float fx, fy, fz, vx, vy, vz;
	public double time;
	public String map = "";
	public String gameDir = "";
	/** Level loads in Portal 2: this run of Portal 2, the load count, and 1 = fresh map, 2 = a save game. */
	public int session, loadSerial, loadKind;
	public static final int LOAD_FRESH = 1, LOAD_SAVE = 2;

	public HostLink() {
		this.seg = Shm.open(Shm.HOST, Shm.HOST_SIZE);
	}

	public MemorySegment segment() {
		return seg;
	}

	/** Reads a consistent snapshot. Returns false if Portal 2 isn't there (no magic or no heartbeat for 2 s). */
	public boolean poll() {
		for (int attempt = 0; attempt < 8; attempt++) {
			int s1 = seg.get(JAVA_INT_UNALIGNED, 4);
			if ((s1 & 1) != 0) { Thread.onSpinWait(); continue; }
			if (seg.get(JAVA_INT_UNALIGNED, 0) != MAGIC) return false;
			flags = seg.get(JAVA_INT_UNALIGNED, 8);
			heartbeat = seg.get(JAVA_INT_UNALIGNED, 12);
			ox = f(16); oy = f(20); oz = f(24);
			pitch = f(28); yaw = f(32); roll = f(36);
			fov = f(40);
			width = seg.get(JAVA_INT_UNALIGNED, 44);
			height = seg.get(JAVA_INT_UNALIGNED, 48);
			fx = f(52); fy = f(56); fz = f(60);
			vx = f(64); vy = f(68); vz = f(72);
			health = seg.get(JAVA_INT_UNALIGNED, 76);
			time = seg.get(JAVA_DOUBLE_UNALIGNED, 80);
			byte[] name = new byte[64];
			MemorySegment.copy(seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 88, name, 0, 64);
			int n = 0;
			while (n < 64 && name[n] != 0) n++;
			map = new String(name, 0, n, StandardCharsets.US_ASCII);
			cameraSerial = seg.get(JAVA_INT_UNALIGNED, 156);
			playerFlags = seg.get(JAVA_INT_UNALIGNED, 160);
			byte[] dir = new byte[260];
			MemorySegment.copy(seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 9000, dir, 0, 260);
			int d = 0;
			while (d < 260 && dir[d] != 0) d++;
			gameDir = new String(dir, 0, d, java.nio.charset.Charset.defaultCharset());
			session = seg.get(JAVA_INT_UNALIGNED, 9300);
			loadSerial = seg.get(JAVA_INT_UNALIGNED, 9304);
			loadKind = seg.get(JAVA_INT_UNALIGNED, 9308);
			if (seg.get(JAVA_INT_UNALIGNED, 4) == s1) break;
		}
		long now = System.nanoTime();
		if (heartbeat != lastHeartbeat) {
			lastHeartbeat = heartbeat;
			lastBeatNanos = now;
		}
		return now - lastBeatNanos < 2_000_000_000L;
	}

	private float f(long off) {
		return seg.get(JAVA_FLOAT_UNALIGNED, off);
	}

	public boolean has(int flag) {
		return (flags & flag) != 0;
	}

	// ------------------------------------------------------------------ space mapping
	public static Vec3 toMc(double sx, double sy, double sz) {
		return new Vec3(sx / UNITS_PER_BLOCK + regionX, sz / UNITS_PER_BLOCK + Y_OFFSET, -sy / UNITS_PER_BLOCK);
	}

	public static double[] toSource(double mx, double my, double mz) {
		return new double[] { (mx - regionX) * UNITS_PER_BLOCK, -mz * UNITS_PER_BLOCK, (my - Y_OFFSET) * UNITS_PER_BLOCK };
	}

	/** Source yaw (0 = +x, counter-clockwise) to Minecraft yRot (0 = +z, clockwise). */
	public static float toMcYaw(float sourceYaw) {
		return -sourceYaw - 90.0F;
	}

	public Vec3 eyeMc() {
		return toMc(ox, oy, oz);
	}

	public Vec3 feetMc() {
		return toMc(fx, fy, fz);
	}

	/** Source's fov is horizontal for a 4:3 screen whatever the real aspect; Minecraft wants the vertical fov. */
	public float verticalFov() {
		double h43 = Math.toRadians(fov);
		return (float) Math.toDegrees(2.0 * Math.atan(Math.tan(h43 / 2.0) * 0.75));
	}

	/** Input events written since {@code since}; returns the new count. */
	public int inputCount() {
		return seg.get(JAVA_INT_UNALIGNED, 256);
	}

	public int[] inputEvent(int index) {
		long off = 264 + (long) (index % 512) * 16;
		return new int[] { seg.get(JAVA_INT_UNALIGNED, off), seg.get(JAVA_INT_UNALIGNED, off + 4),
			seg.get(JAVA_INT_UNALIGNED, off + 8), seg.get(JAVA_INT_UNALIGNED, off + 12) };
	}

	/** Portal 2's camera count right now (cheap, no snapshot). */
	public int cameraSerialNow() {
		return seg.get(JAVA_INT_UNALIGNED, 156);
	}

	public int readingSlot() {
		return seg.get(JAVA_INT_UNALIGNED, 152);
	}
}
