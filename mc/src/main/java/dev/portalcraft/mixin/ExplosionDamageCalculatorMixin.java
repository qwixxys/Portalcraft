package dev.portalcraft.mixin;

import dev.portalcraft.Portalcraft;
import dev.portalcraft.world.P2Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Portal 2's flat walls and floors (the barrier shell) take explosions like weak stone, so TNT blows holes in them. */
@Mixin(ExplosionDamageCalculator.class)
public abstract class ExplosionDamageCalculatorMixin {
	@Inject(method = "getBlockExplosionResistance", at = @At("HEAD"), cancellable = true)
	private void portalcraft$wallsBreak(Explosion explosion, BlockGetter level, BlockPos pos, BlockState block, FluidState fluid,
		CallbackInfoReturnable<Optional<Float>> cir) {
		P2Map map = P2Map.current;
		if (Portalcraft.hostLinked && map != null && block.is(Blocks.BARRIER) && map.breakableByExplosion(pos.asLong())) cir.setReturnValue(Optional.of(3.0F));
	}
}
