package dev.portalcraft.client.mixin;

import dev.portalcraft.client.PortalcraftClient;
import dev.portalcraft.world.P2Map;
import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla block meshing (without Fabric's Indigo renderer; with it, IndigoBlockRendererMixin does the same).
 * Blocks standing for Portal 2's intact walls (rock, half-floor slabs) don't draw the faces that lie on Portal 2's
 * surfaces: toward its rooms (and its glass and invisible blockers), and a slab's top in the middle of the cell.
 * Portal 2 draws those surfaces itself; two surfaces in one place made the composite pick Minecraft's at a distance.
 * Faces toward holes and tunnels stay.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
	@Unique private boolean portalcraft$wall; // the block being drawn (one renderer per meshing thread)

	@Inject(method = "tesselateBlock", at = @At("HEAD"))
	private void portalcraft$whichBlock(BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level, BlockPos pos,
		BlockState state, BlockStateModel model, long seed, CallbackInfo ci) {
		P2Map map = P2Map.current;
		portalcraft$wall = map != null && PortalcraftClient.active() && map.intactWall(pos);
	}

	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
	private void portalcraft$notOnPortal2Surface(BlockAndTintGetter level, BlockState state, Direction direction, BlockPos neighborPos,
		CallbackInfoReturnable<Boolean> cir) {
		if (!portalcraft$wall) return;
		P2Map map = P2Map.current;
		if (map != null && map.surfaceToward(neighborPos)) cir.setReturnValue(false);
	}

	/** Faces without a neighbour to cull against (a slab's top or bottom inside its cell). */
	@Redirect(method = { "tesselateAmbientOcclusion", "tesselateFlat" }, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModelPart;getQuads(Lnet/minecraft/core/Direction;)Ljava/util/List;"))
	private List<BakedQuad> portalcraft$noInnerFaces(BlockStateModelPart part, Direction direction) {
		return portalcraft$wall && direction == null ? List.of() : part.getQuads(direction);
	}
}
