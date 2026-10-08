package dev.portalcraft.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.portalcraft.client.PortalcraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep up with Portal 2's frame rate even though our window is hidden. */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void portalcraft$unlimited(CallbackInfoReturnable<Integer> cir) {
		if (PortalcraftClient.get() != null && PortalcraftClient.get().linked) cir.setReturnValue(260);
	}
}
