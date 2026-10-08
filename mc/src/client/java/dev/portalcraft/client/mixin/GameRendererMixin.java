package dev.portalcraft.client.mixin;

import dev.portalcraft.client.PortalcraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frame capture points: world layer before the 3D HUD (hand), overlay layer at the end of the frame. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void portalcraft$frameStart(CallbackInfo ci) {
		PortalcraftClient pc = PortalcraftClient.get();
		if (pc != null) pc.frame();
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void portalcraft$worldDone(CallbackInfo ci) {
		PortalcraftClient pc = PortalcraftClient.get();
		if (pc == null) return;
		Minecraft mc = Minecraft.getInstance();
		float far = Math.max(mc.options.getEffectiveRenderDistance() * 16 * 4.0F, mc.options.cloudRange().get() * 16);
		pc.capture.captureWorld(((GameRenderer) (Object) this).mainRenderTarget(), far);
	}

	/** With the portal gun out, Portal 2 draws its own view model: no Minecraft hand then. */
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void portalcraft$handOnlyWhenBuilding(CallbackInfo ci) {
		PortalcraftClient pc = PortalcraftClient.get();
		// quitting after Portal 2 closed: the world is already disconnected (no game mode) for the rest of this frame
		if (Minecraft.getInstance().gameMode == null) ci.cancel();
		else if (PortalcraftClient.active() && !pc.host.has(dev.portalcraft.client.HostLink.F_BUILD)) ci.cancel();
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void portalcraft$frameEnd(CallbackInfo ci) {
		PortalcraftClient pc = PortalcraftClient.get();
		if (pc != null) pc.capture.captureOverlay(((GameRenderer) (Object) this).mainRenderTarget(), PortalcraftClient.loadingScreen());
	}
}
