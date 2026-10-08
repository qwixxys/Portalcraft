package dev.portalcraft.mixin;

import dev.portalcraft.Portalcraft;
import dev.portalcraft.world.P2Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In survival Portal 2's walls can be mined like stone (barriers are normally unbreakable). */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
	@Inject(method = "getDestroySpeed", at = @At("HEAD"), cancellable = true)
	private void portalcraft$mineableWalls(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		P2Map map = P2Map.current;
		if (Portalcraft.hostLinked && map != null && ((BlockBehaviour.BlockStateBase) (Object) this).is(Blocks.BARRIER) && map.isShell(pos.asLong()))
			cir.setReturnValue(1.5F);
	}
}
