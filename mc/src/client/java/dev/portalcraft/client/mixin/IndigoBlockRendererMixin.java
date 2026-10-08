package dev.portalcraft.client.mixin;

import dev.portalcraft.client.PortalcraftClient;
import dev.portalcraft.world.P2Map;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric API's Indigo renderer meshes the world's blocks in place of vanilla's ModelBlockRenderer: the same rule as
 * ModelBlockRendererMixin. Blocks standing for Portal 2's intact walls drop the quads lying on Portal 2's surfaces
 * (toward its rooms, glass and invisible blockers, and a slab's top or bottom inside its cell); quads toward holes
 * and tunnels stay.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl", remap = false)
public abstract class IndigoBlockRendererMixin {
	@Shadow(remap = false) private BlockPos pos; // the block being meshed

	@Inject(method = "transform", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void portalcraft$notOnPortal2Surface(MutableQuadView quad, CallbackInfoReturnable<Boolean> cir) {
		P2Map map = P2Map.current;
		if (map == null || pos == null || !PortalcraftClient.active() || !map.intactWall(pos)) return;
		Direction face = quad.cullFace();
		if (face == null || map.surfaceToward(pos.relative(face))) cir.setReturnValue(false);
	}
}
