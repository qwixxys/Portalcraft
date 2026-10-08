package dev.portalcraft.mixin;

import dev.portalcraft.world.P2Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every change to Portal 2's barrier shell (hand, TNT, commands): broken ones become holes in Portal 2. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Shadow @Final private Level level;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void portalcraft$shellChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		P2Map map = P2Map.current;
		if (map == null || cir.getReturnValue() == null || !(level instanceof ServerLevel) || level.dimension() != Level.OVERWORLD) return;
		map.blockChanged(pos, state);
		map.markDirty(pos);
	}
}
