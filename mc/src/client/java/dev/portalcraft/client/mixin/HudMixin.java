package dev.portalcraft.client.mixin;

import dev.portalcraft.client.HostLink;
import dev.portalcraft.client.PortalcraftClient;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** With the portal gun out, Portal 2's own crosshair is the one that counts. */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
	private void portalcraft$crosshair(CallbackInfo ci) {
		if (PortalcraftClient.active() && !PortalcraftClient.get().host.has(HostLink.F_BUILD)) ci.cancel();
	}
}
