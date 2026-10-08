package dev.portalcraft.client.mixin;

import dev.portalcraft.client.PortalcraftClient;
import dev.portalcraft.world.P2Map;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mobs, items and the player's hand inside Portal 2's rooms are drawn fully lit too. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {
	@Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
	private void portalcraft$roomsLit(T entity, float partialTicks, CallbackInfoReturnable<Integer> cir) {
		P2Map map = P2Map.current;
		if (map == null || !PortalcraftClient.active()) return;
		BlockPos pos = BlockPos.containing(entity.getLightProbePosition(partialTicks));
		if (map.litForRendering(pos.getX(), pos.getY(), pos.getZ())) cir.setReturnValue(LightCoordsUtil.withBlock(cir.getReturnValueI(), 15));
	}
}
