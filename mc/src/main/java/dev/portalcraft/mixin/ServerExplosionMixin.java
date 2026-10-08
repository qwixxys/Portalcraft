package dev.portalcraft.mixin;

import dev.portalcraft.world.TerrainFiller;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The ground behind Portal 2's walls exists before the blast, so TNT leaves a real crater in it. */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow @Final private ServerLevel level;
	@Shadow @Final private Vec3 center;
	@Shadow @Final private float radius;

	@Inject(method = "explode", at = @At("HEAD"))
	private void portalcraft$terrainFirst(CallbackInfoReturnable<Integer> cir) {
		TerrainFiller f = TerrainFiller.current();
		if (f != null && level.dimension() == Level.OVERWORLD) f.ensure(BlockPos.containing(center), (int) Math.ceil(radius * 1.5F) + 1);
	}
}
