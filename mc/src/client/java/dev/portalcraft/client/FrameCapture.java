package dev.portalcraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import java.lang.foreign.MemorySegment;
import org.joml.Vector4f;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;

/**
 * Publishes Minecraft's frame to Portal 2 in three layers: the world's colour and depth (captured right after the
 * level pass), and everything drawn afterwards (hand, HUD, screens) as a premultiplied overlay. The colour target is
 * cleared to transparent between the two so the overlay carries its own alpha.
 */
public final class FrameCapture {
	public static final int MAGIC = 0x31464350;
	public static final float NEAR = 0.05F;

	private static final class Bufs {
		final int w, h;
		final GpuBuffer color, depth, overlay;
		volatile boolean busy;
		float ex, ey, ez, pitch, yaw, roll, fov;
		int cameraSerial;
		float far;

		Bufs(int w, int h) {
			this.w = w;
			this.h = h;
			long plane = (long) w * h * 4;
			var dev = RenderSystem.getDevice();
			color = dev.createBuffer(() -> "Portalcraft colour", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, plane);
			depth = dev.createBuffer(() -> "Portalcraft depth", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, plane);
			overlay = dev.createBuffer(() -> "Portalcraft overlay", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, plane);
		}

		void close() {
			color.close();
			depth.close();
			overlay.close();
		}
	}

	private final MemorySegment seg;
	private final HostLink host;
	private final Bufs[] ring = new Bufs[3];
	private int cur;
	private Bufs active;
	private int serial;
	private int latest = -1;
	public int published, dropped;

	public FrameCapture(HostLink host) {
		this.host = host;
		this.seg = Shm.open(Shm.FRAME, Shm.FRAME_SIZE);
		seg.set(JAVA_INT_UNALIGNED, 4, -1);
	}

	/** Right after the level pass: copy world colour + depth, then clear colour so the hand/HUD land on transparency. */
	public void captureWorld(RenderTarget target, float depthFar) {
		active = null;
		if (!PortalcraftClient.active()) return;
		int w = target.width, h = target.height;
		if (w > Shm.MAX_W || h > Shm.MAX_H || target.getDepthTexture() == null) return;
		Bufs b = ring[cur];
		if (b != null && b.busy) {
			dropped++;
			return;
		}
		if (b == null || b.w != w || b.h != h) {
			if (b != null) b.close();
			b = ring[cur] = new Bufs(w, h);
		}
		b.busy = true;
		b.ex = host.ox; b.ey = host.oy; b.ez = host.oz;
		b.pitch = host.pitch; b.yaw = host.yaw; b.roll = host.roll; b.fov = host.fov;
		b.cameraSerial = host.cameraSerial;
		b.far = depthFar;
		active = b;
		CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
		enc.copyTextureToBuffer(target.getColorTexture(), b.color, 0L, () -> {}, 0);
		enc.copyTextureToBuffer(target.getDepthTexture(), b.depth, 0L, () -> {}, 0);
		enc.clearColorTexture(target.getColorTexture(), new Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
	}

	/** End of the frame: whatever is in the colour target now is the overlay. */
	public void captureOverlay(RenderTarget target, boolean blank) {
		Bufs b = active;
		active = null;
		if (b == null) return;
		CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
		if (blank) enc.clearColorTexture(target.getColorTexture(), new Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
		enc.copyTextureToBuffer(target.getColorTexture(), b.overlay, 0L, () -> publish(b), 0);
		cur = (cur + 1) % ring.length;
	}

	/** Time spent copying finished frames into shared memory (ns), for the timing log. */
	public long copyNanos;

	private void publish(Bufs b) {
		long t0 = System.nanoTime();
		try {
			int reading = host.readingSlot();
			int slot = 0;
			for (int i = 0; i < 3; i++) {
				if (i != latest && i != reading) {
					slot = i;
					break;
				}
			}
			long plane = (long) b.w * b.h * 4;
			long base = 4096 + slot * plane * 3;
			copy(b.color, base, plane);
			copy(b.depth, base + plane, plane);
			copy(b.overlay, base + 2 * plane, plane);
			serial++;
			long sh = 64 + 64L * slot;
			seg.set(JAVA_INT_UNALIGNED, sh, serial);
			seg.set(JAVA_INT_UNALIGNED, sh + 4, b.cameraSerial);
			seg.set(JAVA_FLOAT_UNALIGNED, sh + 8, b.ex);
			seg.set(JAVA_FLOAT_UNALIGNED, sh + 12, b.ey);
			seg.set(JAVA_FLOAT_UNALIGNED, sh + 16, b.ez);
			seg.set(JAVA_FLOAT_UNALIGNED, sh + 20, b.pitch);
			seg.set(JAVA_FLOAT_UNALIGNED, sh + 24, b.yaw);
			seg.set(JAVA_FLOAT_UNALIGNED, sh + 28, b.roll);
			seg.set(JAVA_FLOAT_UNALIGNED, sh + 32, b.fov);
			seg.set(JAVA_INT_UNALIGNED, 0, MAGIC);
			seg.set(JAVA_INT_UNALIGNED, 8, b.w);
			seg.set(JAVA_INT_UNALIGNED, 12, b.h);
			seg.set(JAVA_INT_UNALIGNED, 16, serial);
			seg.set(JAVA_INT_UNALIGNED, 20, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0);
			seg.set(JAVA_FLOAT_UNALIGNED, 24, NEAR);
			seg.set(JAVA_FLOAT_UNALIGNED, 28, b.far);
			seg.set(JAVA_INT_UNALIGNED, 4, slot); // publish last
			latest = slot;
			published++;
			copyNanos += System.nanoTime() - t0;
		} finally {
			b.busy = false;
		}
	}

	private void copy(GpuBuffer buffer, long offset, long size) {
		try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
			MemorySegment src = MemorySegment.ofBuffer(view.data());
			MemorySegment.copy(src, 0, seg, offset, Math.min(size, src.byteSize()));
		}
	}
}
