package dev.portalcraft.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.portalcraft.client.PortalcraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While Portal 2 drives us, the hidden window counts as focused (no pause menu, no AFK frame limit). */
@Mixin(Window.class)
public abstract class WindowMixin {
	@Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
	private void portalcraft$focused(CallbackInfoReturnable<Boolean> cir) {
		if (PortalcraftClient.get() != null && PortalcraftClient.get().linked) cir.setReturnValue(true);
	}
}
