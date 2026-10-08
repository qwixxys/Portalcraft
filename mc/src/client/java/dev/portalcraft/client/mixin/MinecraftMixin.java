package dev.portalcraft.client.mixin;

import dev.portalcraft.client.PortalcraftClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
	private void portalcraft$active(CallbackInfoReturnable<Boolean> cir) {
		if (PortalcraftClient.get() != null && PortalcraftClient.get().linked) cir.setReturnValue(true);
	}
}
