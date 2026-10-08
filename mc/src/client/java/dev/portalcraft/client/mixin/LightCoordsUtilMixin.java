package dev.portalcraft.client.mixin;

import dev.portalcraft.client.PortalcraftClient;
import dev.portalcraft.world.P2Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks, particles and fluids inside Portal 2's rooms are drawn fully lit (see P2Map.litForRendering), and the rooms'
 * light reaches a few blocks into holes and craters in their walls (P2Map.holeLight).
 */
@Mixin(LightCoordsUtil.class)
public abstract class LightCoordsUtilMixin {
	@Inject(method = "getLightCoords(Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
		at = @At("RETURN"), cancellable = true)
	private static void portalcraft$roomsLit(LightCoordsUtil.BrightnessGetter getter, BlockAndLightGetter level, BlockState state, BlockPos pos,
		CallbackInfoReturnable<Integer> cir) {
		P2Map map = P2Map.current;
		if (map == null || !PortalcraftClient.active()) return;
		int coords = cir.getReturnValueI();
		int light = map.litForRendering(pos.getX(), pos.getY(), pos.getZ()) ? 15 : map.holeLight(pos.getX(), pos.getY(), pos.getZ());
		if (light > LightCoordsUtil.block(coords)) cir.setReturnValue(LightCoordsUtil.withBlock(coords, light));
	}
}
