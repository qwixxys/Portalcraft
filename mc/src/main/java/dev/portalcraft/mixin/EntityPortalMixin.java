package dev.portalcraft.mixin;

import dev.portalcraft.Portalcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nether and End portals don't take the player anywhere while Portal 2 is linked: only the overworld lines up with
 * Portal 2's map (elsewhere its terrain would become solid boxes in Portal 2's rooms).
 */
@Mixin(Entity.class)
public abstract class EntityPortalMixin {
	@Inject(method = "setAsInsidePortal", at = @At("HEAD"), cancellable = true)
	private void portalcraft$stayInOverworld(Portal portal, BlockPos pos, CallbackInfo ci) {
		if (Portalcraft.hostLinked && (Object) this instanceof Player) ci.cancel();
	}
}
