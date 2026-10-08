package dev.portalcraft.mixin;

import dev.portalcraft.Portalcraft;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inside Portal 2 there is no sun to see (Portal 2 draws its own rooms over Minecraft's open sky): zombies and
 * skeletons don't burn in the daylight that keeps Minecraft's blocks lit.
 */
@Mixin(Mob.class)
public abstract class MobMixin {
	@Inject(method = "burnUndead", at = @At("HEAD"), cancellable = true)
	private void portalcraft$noSun(CallbackInfo ci) {
		if (Portalcraft.hostLinked) ci.cancel();
	}
}
