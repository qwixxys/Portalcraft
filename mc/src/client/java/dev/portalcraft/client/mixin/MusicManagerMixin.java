package dev.portalcraft.client.mixin;

import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Minecraft music over Portal 2 (block, mob and other sounds stay). Jukeboxes still play: they aren't music. */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
	@Inject(method = "startPlaying", at = @At("HEAD"), cancellable = true)
	private void portalcraft$noMusic(CallbackInfo ci) {
		ci.cancel();
	}
}
