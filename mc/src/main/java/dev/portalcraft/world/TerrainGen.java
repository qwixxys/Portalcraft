package dev.portalcraft.world;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Real overworld underground for the space behind Portal 2's walls: the vanilla noise router (caves, aquifers,
 * lava) sampled at a shifted height, then stone types, ores and a grassy surface on top. The world itself stays a
 * void superflat; blocks from here are only placed where Portal 2 is solid (see TerrainFiller).
 * Not thread-safe: one generator thread.
 */
public final class TerrainGen {
	/** Minecraft y 128 (Portal 2's z = 0) is generated as overworld y -8: chambers sit in deep stone. */
	public static final int VY_SHIFT = 136;

	private final NoiseGeneratorSettings settings;
	private final RandomState random;
	private final Aquifer.FluidPicker fluids;
	private final SimplexNoise stones, stoneKind, dirt, coal, iron, copper, gold, redstone, lapis, diamond, dither;

	public TerrainGen(MinecraftServer server, long seed) {
		var reg = server.registryAccess();
		settings = reg.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
		random = RandomState.create(reg.lookupOrThrow(Registries.NOISE), seed, settings);
		Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
		Aquifer.FluidStatus sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
		fluids = (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : sea;
		RandomSource r = RandomSource.create(seed ^ 0x5043L);
		stones = new SimplexNoise(r);
		stoneKind = new SimplexNoise(r);
		dirt = new SimplexNoise(r);
		coal = new SimplexNoise(r);
		iron = new SimplexNoise(r);
		copper = new SimplexNoise(r);
		gold = new SimplexNoise(r);
		redstone = new SimplexNoise(r);
		lapis = new SimplexNoise(r);
		diamond = new SimplexNoise(r);
		dither = new SimplexNoise(r);
	}

	/** Blocks of the 16^3 section with this Minecraft origin; index (y * 16 + z) * 16 + x. */
	public BlockState[] section(int x0, int y0, int z0) {
		final int extra = 4; // rows above, to know how deep under the surface a block is
		int vy0 = y0 - VY_SHIFT;
		DensityVolume volume = new DensityVolume(16, 16 + extra, 16, x0, vy0, z0);
		BlockState[] raw = new BlockState[16 * 16 * (16 + extra)];
		BlockState stone = settings.defaultBlock();
		try (NoiseChunk noise = new NoiseChunk(random, null, settings, fluids, Blender.empty(), volume)) {
			Aquifer aquifer = noise.aquifer();
			var density = noise.cachingSamplers().get(settings.noiseRouter().finalDensity());
			try (var buf = density.sampleVolume(volume)) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						for (int y = 16 + extra - 1; y >= 0; y--) {
							float d = buf.get(volume.indexUnchecked(x, y, z));
							BlockState s = aquifer.computeSubstance(x0 + x, vy0 + y, z0 + z, d);
							raw[(y * 16 + z) * 16 + x] = s == null ? stone : s;
						}
					}
				}
			}
		}
		BlockState[] out = new BlockState[4096];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int below = 0; // solid blocks between this one and the open air above (up to the extra rows)
				boolean open = false, underWater = false;
				for (int y = 16 + extra - 1; y >= 0; y--) {
					BlockState s = raw[(y * 16 + z) * 16 + x];
					if (s.isAir() || !s.getFluidState().isEmpty()) {
						open = true;
						underWater = !s.getFluidState().isEmpty();
						below = 0;
					} else {
						below++;
					}
					if (y >= 16) continue;
					if (s == stone) s = rock(x0 + x, vy0 + y, z0 + z, open ? below : 99, underWater);
					out[(y * 16 + z) * 16 + x] = s;
				}
			}
		}
		return out;
	}

	private static boolean blob(SimplexNoise n, int x, int y, int z, double scale, double threshold) {
		return n.get(x * scale, y * scale, z * scale) > threshold;
	}

	/** Stone, by depth: grass and dirt near the surface, ores, granite/diorite/andesite/tuff, deepslate below y 0. */
	private BlockState rock(int x, int vy, int z, int depth, boolean underWater) {
		if (vy > 56 && depth <= 4) {
			if (underWater) return (depth == 1 ? Blocks.GRAVEL : Blocks.DIRT).defaultBlockState();
			return (depth == 1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState();
		}
		boolean deep = vy < 0 || (vy < 8 && dither.get(x * 0.7, vy * 0.7, z * 0.7) * 4 > vy);
		Block ore = null;
		if (vy > 0 && blob(coal, x, vy, z, 0.16, 0.78)) ore = deep ? Blocks.DEEPSLATE_COAL_ORE : Blocks.COAL_ORE;
		else if (vy > -16 && vy < 112 && blob(copper, x, vy, z, 0.18, 0.80)) ore = deep ? Blocks.DEEPSLATE_COPPER_ORE : Blocks.COPPER_ORE;
		else if (vy > -24 && vy < 80 && blob(iron, x, vy, z, 0.2, 0.79)) ore = deep ? Blocks.DEEPSLATE_IRON_ORE : Blocks.IRON_ORE;
		else if (vy < 32 && blob(gold, x, vy, z, 0.22, 0.86)) ore = deep ? Blocks.DEEPSLATE_GOLD_ORE : Blocks.GOLD_ORE;
		else if (vy < 16 && blob(redstone, x, vy, z, 0.22, 0.84)) ore = deep ? Blocks.DEEPSLATE_REDSTONE_ORE : Blocks.REDSTONE_ORE;
		else if (vy > -48 && vy < 32 && blob(lapis, x, vy, z, 0.24, 0.87)) ore = deep ? Blocks.DEEPSLATE_LAPIS_ORE : Blocks.LAPIS_ORE;
		else if (vy < 16 && blob(diamond, x, vy, z, 0.26, vy < -32 ? 0.86 : 0.9)) ore = deep ? Blocks.DEEPSLATE_DIAMOND_ORE : Blocks.DIAMOND_ORE;
		if (ore != null) return ore.defaultBlockState();
		if (blob(dirt, x, vy, z, 0.07, 0.62)) return (deep ? Blocks.TUFF : vy > 20 ? Blocks.DIRT : Blocks.GRAVEL).defaultBlockState();
		if (blob(stones, x, vy, z, 0.05, 0.45)) {
			if (deep) return Blocks.TUFF.defaultBlockState();
			double k = stoneKind.get(x * 0.01, vy * 0.01, z * 0.01);
			return (k < -0.25 ? Blocks.GRANITE : k < 0.25 ? Blocks.DIORITE : Blocks.ANDESITE).defaultBlockState();
		}
		return (deep ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState();
	}
}
