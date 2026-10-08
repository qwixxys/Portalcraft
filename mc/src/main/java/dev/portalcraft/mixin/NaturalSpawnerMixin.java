package dev.portalcraft.mixin;

import dev.portalcraft.world.P2Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nothing spawns by itself inside Portal 2's rooms (Minecraft thinks they are dark caves when ground surrounds them);
 * caves dug behind the walls spawn mobs as usual, and spawn eggs and /summon work everywhere.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
	@Inject(method = "isValidSpawnPostitionForType", at = @At("HEAD"), cancellable = true)
	private static void portalcraft$notInRooms(ServerLevel level, MobCategory category, StructureManager structures, ChunkGenerator generator,
		MobSpawnSettings.SpawnerData data, BlockPos.MutableBlockPos pos, double distance, CallbackInfoReturnable<Boolean> cir) {
		P2Map map = P2Map.current;
		if (map != null && level.dimension() == Level.OVERWORLD && !map.p2Solid(pos.getX(), pos.getY(), pos.getZ())) cir.setReturnValue(false);
	}
}
