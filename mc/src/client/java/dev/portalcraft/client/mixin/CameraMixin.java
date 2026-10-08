package dev.portalcraft.client.mixin;

import dev.portalcraft.client.HostLink;
import dev.portalcraft.client.PortalcraftClient;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft's camera is Portal 2's camera: same eye, angles and field of view. While Minecraft drives the player,
 * the camera Minecraft would have used is handed to Portal 2 first (which then renders from there).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow protected abstract void setRotation(float yRot, float xRot);

	@Shadow protected abstract void setPosition(Vec3 position);

	@Shadow public abstract Vec3 position();

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void portalcraft$followPortal2(float partialTicks, CallbackInfo ci) {
		if (!PortalcraftClient.active()) return;
		PortalcraftClient pc = PortalcraftClient.get();
		pc.cameraComputed(position(), partialTicks);
		HostLink h = pc.host;
		setRotation(HostLink.toMcYaw(h.yaw), h.pitch);
		setPosition(h.eyeMc());
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void portalcraft$fov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (PortalcraftClient.active()) cir.setReturnValue(PortalcraftClient.get().host.verticalFov());
	}
}
