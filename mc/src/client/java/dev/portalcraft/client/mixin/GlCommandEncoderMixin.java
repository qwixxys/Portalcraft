package dev.portalcraft.client.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla bug we hit by reading depth back: copyTextureToBuffer sets the shared read FBO's read buffer to GL_NONE for
 * depth textures and never restores it, so every later colour read-back fails with "No color buffer".
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin {
	@Shadow @Final private int readFbo;

	@Inject(method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V", at = @At("TAIL"))
	private void portalcraft$restoreReadBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel,
		int x, int y, int width, int height, CallbackInfo ci) {
		if (source.getFormat().hasDepthAspect()) {
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
			GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
		}
	}
}
