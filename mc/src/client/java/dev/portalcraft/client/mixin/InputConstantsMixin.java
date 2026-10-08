package dev.portalcraft.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.portalcraft.client.InputBridge;
import dev.portalcraft.client.PortalcraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The hidden Minecraft window must never take the real mouse; keys held in Portal 2 count as held here. */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private static void portalcraft$noGrab(Window window, double x, double y, CallbackInfo ci) {
		if (PortalcraftClient.get() != null && PortalcraftClient.get().linked) ci.cancel();
	}

	@Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
	private static void portalcraft$noRelease(Window window, double x, double y, CallbackInfo ci) {
		if (PortalcraftClient.get() != null && PortalcraftClient.get().linked) ci.cancel();
	}

	@Inject(method = "isKeyDown", at = @At("RETURN"), cancellable = true)
	private static void portalcraft$forwardedKeys(int key, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && key >= 0 && key < InputBridge.DOWN.length && InputBridge.DOWN[key]) cir.setReturnValue(true);
	}
}
