package dev.portalcraft.client;

import dev.portalcraft.Portalcraft;
import dev.portalcraft.world.MapReset;
import dev.portalcraft.world.P2Map;
import dev.portalcraft.world.TerrainFiller;
import java.util.List;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLVideo;

public class PortalcraftClient implements ClientModInitializer {
	public static final String WORLD = "Portalcraft";
	private static PortalcraftClient INSTANCE;

	public HostLink host;
	public GuestLink guest;
	public FrameCapture capture;
	public InputBridge input;
	public LevelMirror mirror = new LevelMirror();
	private final Visits visits = new Visits();
	/** Portal 2 is running and talking to us this frame. */
	public boolean linked;
	private boolean wasLinked, hidden, worldRequested, optionsApplied, rulesApplied;
	private int wantW, wantH;
	private boolean everLinked, quitting;
	private long goneSince;
	private int solidSerial, solidTick;
	private int[] lastBoxes = new int[0];

	/**
	 * Minecraft moves the player (its own physics) instead of Portal 2: next to holes in Portal 2's walls, inside
	 * them, and while flying (elytra, creative flight). Portal 2's camera then follows Minecraft's.
	 */
	public volatile boolean driving;
	private boolean returning, lastJump, valid, camOutside, camInWall, lockstep;
	private int lastFrameCamera = Integer.MIN_VALUE;
	private long lastRenderStart, renderNanos, waitNanos, timingSince;
	private int timedFrames;
	private int calmTicks;
	private long returnSince;
	private String driveReason = "";

	// hole grid
	private final byte[] grid = new byte[GuestLink.GRID_BYTES];
	private int gridVersion = -1, gox = Integer.MIN_VALUE, goy, goz;
	private P2Map gridMap, litMap;

	public static PortalcraftClient get() {
		return INSTANCE;
	}

	/** Linked and in a world: the camera, capture and input follow Portal 2. */
	public static boolean active() {
		PortalcraftClient pc = INSTANCE;
		return pc != null && pc.linked && Minecraft.getInstance().level != null && Minecraft.getInstance().player != null;
	}

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		host = new HostLink();
		guest = new GuestLink();
		capture = new FrameCapture(host);
		input = new InputBridge(host);
		ClientTickEvents.START_CLIENT_TICK.register(this::beforeTick);
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		Portalcraft.LOG.info("Portalcraft client ready, waiting for Portal 2");
	}

	/** Start of every frame (render thread). */
	public void frame() {
		Minecraft mc = Minecraft.getInstance();
		linked = host.poll();
		// one game: draw exactly one frame per Portal 2 frame, from its newest camera. Until Portal 2 has the next
		// camera, finish publishing the previous frame (its GPU copy completes in the meantime): Portal 2 waits
		// for that very frame before it presents, so Minecraft never lags behind or runs ahead of its walls
		long frameStart = System.nanoTime();
		if (lastRenderStart != 0) renderNanos += frameStart - lastRenderStart;
		lockstep = linked && active() && host.has(HostLink.F_IN_GAME) && !host.has(HostLink.F_PAUSED) && !loadingScreen();
		if (lockstep) {
			long t0 = frameStart;
			while (host.cameraSerialNow() == lastFrameCamera) {
				com.mojang.blaze3d.systems.RenderSystem.executePendingTasks();
				long waited = System.nanoTime() - t0;
				if (waited > 40_000_000L) break; // Portal 2 stopped drawing (alt-tab, loading): keep Minecraft going
				if (waited < 1_000_000L) Thread.onSpinWait();
				else Thread.yield();
			}
			linked = host.poll();
		}
		lastFrameCamera = host.cameraSerial;
		lastRenderStart = System.nanoTime();
		waitNanos += lastRenderStart - frameStart;
		timedFrames++;
		if (lastRenderStart - timingSince > 5_000_000_000L && timedFrames > 0) {
			Portalcraft.LOG.info("frames: {} in 5 s, waiting for Portal 2 {} ms, drawing {} ms, copying out {} ms (per frame)", timedFrames,
				waitNanos / timedFrames / 100_000 / 10.0, renderNanos / timedFrames / 100_000 / 10.0, capture.copyNanos / timedFrames / 100_000 / 10.0);
			timingSince = lastRenderStart;
			timedFrames = 0;
			waitNanos = renderNanos = capture.copyNanos = 0;
		}
		Portalcraft.hostLinked = linked;
		if (linked != wasLinked) {
			wasLinked = linked;
			Portalcraft.LOG.info(linked ? "Portal 2 connected ({})" : "Portal 2 gone", host.map);
			if (!linked) input.releaseAll();
			if (linked) everLinked = true;
			goneSince = System.nanoTime();
		}
		// Portal 2 closed: our window is hidden, so save the world and quit rather than linger in the background
		if (everLinked && !linked && System.nanoTime() - goneSince > 5_000_000_000L && !quitting) {
			quitting = true;
			Portalcraft.LOG.info("Portal 2 closed 5 s ago: saving and quitting Minecraft");
			mc.execute(() -> {
				mirror.reset();
				if (mc.level != null) {
					mc.level.disconnect(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
					mc.disconnectWithSavingScreen(); // same as "Save and Quit to Title"
				}
				mc.stop();
			});
		}
		boolean screen = mc.level != null && takesInput(mc.gui.screen());
		int flags = GuestLink.F_CONNECTED | (screen ? GuestLink.F_SCREEN : 0);
		if (driving) flags |= GuestLink.F_DRIVE | (valid ? GuestLink.F_VALID : 0) | (returning ? GuestLink.F_RETURN : 0);
		if (camOutside) flags |= GuestLink.F_CAM_OUTSIDE;
		if (camInWall) flags |= GuestLink.F_CAM_IN_WALL;
		if (lockstep) flags |= GuestLink.F_LOCKSTEP;
		if (busy()) flags |= GuestLink.F_BUSY;
		guest.publish(flags);
		if (!linked) return;

		// render at Portal 2's resolution, out of sight
		var win = mc.getWindow();
		// same aspect ratio as Portal 2's screen, at most the shared frame size (3440x1440 -> 2560x1072);
		// the composite works in normalised coordinates, so a smaller frame still lines up exactly
		if (host.width >= 320 && host.height >= 240) {
			double scale = Math.min(1.0, Math.min((double) Shm.MAX_W / host.width, (double) Shm.MAX_H / host.height));
			// the height from the width, rounded: 2560x1070 was 0.15% wider than Portal 2, enough to misjudge walls far off
			int tw = (int) (host.width * scale) & ~1, th = Math.min(Shm.MAX_H, (int) Math.round((double) tw * host.height / host.width));
			if (tw != wantW || th != wantH) {
				wantW = tw;
				wantH = th;
				Portalcraft.LOG.info("Portal 2 renders {}x{}: Minecraft renders {}x{}", host.width, host.height, tw, th);
			}
			if (win.getWidth() != tw || win.getHeight() != th) win.setWindowed(tw, th);
		}
		if (!hidden && mc.level != null && !Boolean.getBoolean("portalcraft.showWindow")) {
			SDLVideo.SDL_HideWindow(win.handle());
			hidden = true;
		}
		if (active()) input.pump(screen);
	}

	/**
	 * Render thread, from the camera: where Minecraft's own camera would be this frame (first or third person).
	 * While driving, Portal 2 renders from there.
	 */
	public void cameraComputed(Vec3 eye, float partialTicks) {
		if (!driving || !active()) return;
		LocalPlayer p = Minecraft.getInstance().player;
		Vec3 feet = p.getPosition(partialTicks);
		Vec3 v = p.getDeltaMovement();
		guest.drive(HostLink.toSource(eye.x, eye.y, eye.z), HostLink.toSource(feet.x, feet.y, feet.z),
			new double[] { v.x * 640.0, -v.z * 640.0, v.y * 640.0 });
	}

	/** A fresh visit of the map is being reset: Portal 2 shows only itself meanwhile. */
	private boolean busy() {
		return MapReset.running() || visits.pending;
	}

	/** Minecraft's full-screen loading screens would hide Portal 2: they are left out of the overlay. */
	public static boolean loadingScreen() {
		var sc = Minecraft.getInstance().gui.screen();
		if (sc == null) return false;
		String n = sc.getClass().getSimpleName();
		return n.contains("Loading") || n.contains("Receiving") || n.contains("Progress") || n.contains("GenericMessage");
	}

	/** Loading and message screens don't need the mouse: only real screens freeze Portal 2's view. */
	private static boolean takesInput(net.minecraft.client.gui.screens.Screen s) {
		if (s == null) return false;
		String n = s.getClass().getSimpleName();
		return !(n.contains("Loading") || n.contains("Receiving") || n.contains("GenericMessage") || n.contains("Progress"));
	}

	private void tick(Minecraft mc) {
		if (!linked) return;
		if (!optionsApplied) applyOptions(mc);
		if (mc.level == null) {
			if (driving) stopDriving(null, "no world");
			mirror.reset();
			rulesApplied = false;
			if (!worldRequested && mc.gui.screen() != null && mc.gui.overlay() == null) {
				worldRequested = true;
				openWorld(mc);
			}
			return;
		}
		LocalPlayer p = mc.player;
		if (p == null) return;
		if (!rulesApplied) applyRules(mc);
		mirror.tick(mc, host);
		visits.tick(mc, host, mirror);
		syncPlayer(p);
		CameraType want = host.has(HostLink.F_THIRD) ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON;
		if (mc.options.getCameraType() != want) mc.options.setCameraType(want);
		if (++solidTick % 5 == 0) publishSolids(mc, p);
		if (solidTick % 10 == 0) runCommandFile(mc, p);
		publishHoles();
		// the rooms' light in new (or closed) holes: the blocks around them are meshed again with it
		P2Map lit = P2Map.current;
		if (lit != null) {
			int r = P2Map.HOLE_LIGHT_REACH;
			for (Long k; (k = lit.lightChanged.poll()) != null; ) {
				int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
				mc.levelExtractor.setBlocksDirty(x - r, y - r, z - r, x + r, y + r, z + r);
			}
		}
		// blocks meshed before the map was read have Minecraft's own (dark) light inside Portal 2's rooms
		if (P2Map.current != litMap) {
			litMap = P2Map.current;
			if (litMap != null) mc.levelExtractor.allChanged();
		}
		if (solidTick % 100 == 0) {
			P2Map map = P2Map.current;
			if (map != null) map.saveIfDirty();
			TerrainFiller f = TerrainFiller.current();
			if (f != null) f.saveIfDirty();
		}
	}

	/** Test hook: lines of portalcraft_cmd.txt in the game folder run as chat commands, then the file is deleted. */
	private void runCommandFile(Minecraft mc, LocalPlayer p) {
		java.nio.file.Path f = mc.gameDirectory.toPath().resolve("portalcraft_cmd.txt");
		if (!java.nio.file.Files.exists(f)) return;
		try {
			for (String line : java.nio.file.Files.readAllLines(f)) {
				line = line.strip();
				if (line.isEmpty() || line.startsWith("#")) continue;
				if (line.startsWith("/")) line = line.substring(1);
				Portalcraft.LOG.info("test command: {}", line);
				p.connection.sendCommand(line);
			}
			java.nio.file.Files.delete(f);
		} catch (java.io.IOException e) {
			Portalcraft.LOG.warn("command file: {}", e.toString());
		}
	}

	/**
	 * Before Minecraft's tick. Following Portal 2: give the player exactly the velocity that takes it to Portal 2's
	 * position during this tick, so Minecraft moves it itself (legs swing, the model interpolates between ticks).
	 * Big jumps (portals, teleports, a new map) snap instead. Driving: Minecraft's physics, keys and all.
	 */
	private void beforeTick(Minecraft mc) {
		// until the map has its region the player would land in the wrong place (blocks there would become solid in Portal 2)
		if (!active() || !mirror.regionFor(host.map)) return;
		LocalPlayer p = mc.player;
		P2Map map = P2Map.current;
		boolean jump = host.has(HostLink.F_JUMP);
		boolean jumpPressed = jump && !lastJump;
		lastJump = jump;
		if (driving) {
			drive(mc, p, map);
			return;
		}
		Vec3 feet = host.feetMc();
		Vec3 d = feet.subtract(p.position());
		p.noPhysics = true;
		p.getAbilities().flying = false;
		if (d.lengthSqr() > 4.0 * 4.0) {
			p.setPos(feet.x, feet.y, feet.z);
			p.xo = p.xOld = feet.x;
			p.yo = p.yOld = feet.y;
			p.zo = p.zOld = feet.z;
			p.setDeltaMovement(Vec3.ZERO);
		} else {
			p.setDeltaMovement(d);
		}
		// crouching in Portal 2 = sneaking in Minecraft (the client reads sneaking from the key mapping)
		mc.options.keyShift.setDown((host.playerFlags & 2) != 0);

		// elytra: jump again in mid-air with a glider on, like in Minecraft
		boolean airborne = (host.playerFlags & 1) == 0;
		if (jumpPressed && airborne && LivingEntity.canGlideUsing(p.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST)) {
			p.setOnGround(false);
			if (p.tryToStartFallFlying()) {
				p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
				startDriving(mc, p, "elytra");
				return;
			}
		}
		if (map != null && !busy() && nearHole(map, p, 0.45)) startDriving(mc, p, "hole");
	}

	private void startDriving(Minecraft mc, LocalPlayer p, String why) {
		driving = true;
		returning = false;
		calmTicks = 0;
		driveReason = why;
		Vec3 feet = host.feetMc();
		p.setPos(feet.x, feet.y, feet.z);
		p.xo = p.xOld = feet.x;
		p.yo = p.yOld = feet.y;
		p.zo = p.zOld = feet.z;
		// Portal 2's velocity (units/s) as blocks per tick
		p.setDeltaMovement(host.vx / 640.0, host.vz / 640.0, -host.vy / 640.0);
		p.noPhysics = false;
		mc.options.keyShift.setDown(false);
		Portalcraft.LOG.info("Minecraft drives the player ({})", why);
	}

	private void stopDriving(LocalPlayer p, String why) {
		driving = false;
		returning = false;
		if (p != null) {
			p.getAbilities().flying = false;
			p.noPhysics = true;
		}
		Portalcraft.LOG.info("Portal 2 drives the player again ({}, was {})", why, driveReason);
	}

	/** Minecraft moves the player; hand it back to Portal 2 once there is no hole around and nothing to fly. */
	private void drive(Minecraft mc, LocalPlayer p, P2Map map) {
		p.noPhysics = false;
		if (map == null || busy()) {
			stopDriving(p, map == null ? "no map" : "map reset");
			return;
		}
		boolean need = p.isFallFlying() || p.getAbilities().flying || nearHole(map, p, 0.9) || insideWalls(map, p);
		calmTicks = need ? 0 : calmTicks + 1;
		valid = !overlapsWalls(map, p.getBoundingBox());
		TerrainFiller f = TerrainFiller.current();
		if (f != null && (insideWalls(map, p) || nearHole(map, p, 2.0))) f.request(p.blockPosition(), 1);
		long now = System.nanoTime();
		if (!returning && calmTicks >= 5 && valid) {
			returning = true;
			returnSince = now;
		}
		if (returning && (need || !valid)) returning = false;
		if (returning) {
			// Portal 2 puts the player where we are; once it has, it takes over
			if (host.feetMc().distanceTo(p.position()) < 0.3 || now - returnSince > 1_000_000_000L) stopDriving(p, "no hole around");
		}
	}

	/**
	 * An open hole in Portal 2's wall within {@code margin} blocks of the player: Portal 2 still has its wall there but
	 * Minecraft has nothing to stand on (a block placed back into a hole is solid in both, so it doesn't count).
	 */
	private static boolean nearHole(P2Map map, LocalPlayer p, double margin) {
		if (map.broken.isEmpty()) return false;
		AABB bb = p.getBoundingBox().inflate(margin);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = (int) Math.floor(bb.minX); x <= (int) Math.floor(bb.maxX); x++)
			for (int y = (int) Math.floor(bb.minY); y <= (int) Math.floor(bb.maxY); y++)
				for (int z = (int) Math.floor(bb.minZ); z <= (int) Math.floor(bb.maxZ); z++) {
					if (!map.broken.contains(BlockPos.asLong(x, y, z))) continue;
					pos.set(x, y, z);
					if (p.level().getBlockState(pos).getCollisionShape(p.level(), pos).isEmpty()) return true;
				}
		return false;
	}

	/** The middle of the player is inside Portal 2's walls (a tunnel dug behind them). */
	private static boolean insideWalls(P2Map map, LocalPlayer p) {
		BlockPos c = BlockPos.containing(p.getBoundingBox().getCenter());
		return map.solidCenter(c.getX(), c.getY(), c.getZ()) && !map.isShell(c.asLong());
	}

	/** Any of Portal 2's solid cells inside the box: Portal 2's player couldn't stand there. */
	private static boolean overlapsWalls(P2Map map, AABB bb) {
		bb = bb.deflate(0.01);
		for (int x = (int) Math.floor(bb.minX); x <= (int) Math.floor(bb.maxX); x++)
			for (int y = (int) Math.floor(bb.minY); y <= (int) Math.floor(bb.maxY); y++)
				for (int z = (int) Math.floor(bb.minZ); z <= (int) Math.floor(bb.maxZ); z++)
					if (map.p2Solid(x, y, z)) return true;
		return false;
	}

	/** After the tick: where it looks (head and body follow smoothly between ticks) and whether it stands. */
	private void syncPlayer(LocalPlayer p) {
		p.setYRot(HostLink.toMcYaw(host.yaw));
		p.setXRot(host.pitch);
		p.setYHeadRot(p.getYRot());
		P2Map map = P2Map.current;
		if (map != null) {
			Vec3 eye = host.eyeMc();
			BlockPos c = BlockPos.containing(eye);
			camOutside = map.solidCenter(c.getX(), c.getY(), c.getZ()) && !map.isShell(c.asLong());
			// the eye itself inside Portal 2's drawn walls (falling through a hole in the floor): Portal 2's depth there is
			// only good for what it sees through the hole
			camInWall = map.bsp.opaqueAt((float) host.ox, (float) host.oy, (float) host.oz);
		} else {
			camOutside = camInWall = false;
		}
		if (driving) return;
		p.setOnGround((host.playerFlags & 1) != 0);
		p.noPhysics = true;
		p.getAbilities().flying = false;
	}

	private void applyOptions(Minecraft mc) {
		optionsApplied = true;
		var o = mc.options;
		o.cloudStatus().set(CloudStatus.OFF);
		o.bobView().set(false);
		o.vignette().set(false);
		o.damageTiltStrength().set(0.0);
		o.screenEffectScale().set(0.0);
		o.fovEffectScale().set(0.0);
		o.enableVsync().set(false);
		o.framerateLimit().set(260);
		// Portal 2 waits for each Minecraft frame: a test chamber fits in 10 chunks, farther ones only cost time
		if (o.renderDistance().get() > 10) o.renderDistance().set(10);
		if (o.simulationDistance().get() > 8) o.simulationDistance().set(8);
		o.pauseOnLostFocus = false;
		o.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
		mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
		mc.gui.toastManager().clear();
		o.save();
	}

	/**
	 * Once per world: mobs from spawn eggs and /summon should stay (not peaceful), and Portal 2's chambers are
	 * drawn lit, and noon's sky light keeps holes lit too (undead mobs don't burn, see MobMixin; no phantoms).
	 */
	private void applyRules(Minecraft mc) {
		rulesApplied = true;
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		server.execute(() -> {
			java.nio.file.Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT); // server thread
			java.nio.file.Path marker = root.resolve("portalcraft_rules_v3.txt");
			if (java.nio.file.Files.exists(marker)) return;
			java.util.List<String> cmds = new java.util.ArrayList<>();
			if (!java.nio.file.Files.exists(root.resolve("portalcraft_rules.txt"))) cmds.add("difficulty normal"); // once per world
			// always noon: the void world's sky light keeps holes and craters lit (undead don't burn: MobMixin); no phantoms
			cmds.addAll(java.util.List.of("time set 6000", "time pause", "gamerule spawn_phantoms false"));
			var src = server.createCommandSourceStack().withSuppressedOutput();
			for (String cmd : cmds)
				server.getCommands().performPrefixedCommand(src, cmd);
			server.saveEverything(true, false, false); // the marker below must not outlive these settings
			try {
				java.nio.file.Files.writeString(marker, String.join(", ", cmds) + System.lineSeparator());
			} catch (java.io.IOException ignored) {
			}
			Portalcraft.LOG.info("world rules set: {}", cmds);
		});
	}

	/** A void superflat world in creative: Portal 2 supplies the ground (as barriers, later). */
	private void openWorld(Minecraft mc) {
		var flows = mc.createWorldOpenFlows();
		if (mc.getLevelSource().levelExists(WORLD)) {
			flows.openWorld(WORLD, () -> {});
			return;
		}
		LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE,
			new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true, WorldDataConfiguration.DEFAULT);
		flows.createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false), registries -> {
			var biomes = registries.lookupOrThrow(Registries.BIOME);
			FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.empty(), biomes.getOrThrow(Biomes.THE_VOID), List.of());
			flat = flat.withBiomeAndLayers(List.of(new FlatLayerInfo(1, Blocks.AIR)), Optional.empty(), biomes.getOrThrow(Biomes.THE_VOID));
			return WorldPresets.createNormalWorldDimensions(registries).replaceOverworldGenerator(registries, new FlatLevelSource(flat));
		}, mc.gui.screen());
	}

	/** Portal 2 has room for about 2000 entities, most of them the map's own: the boxes stay well below that. */
	private static final int MAX_SOLID_BOXES = 300;

	/**
	 * Minecraft blocks near the player, in Portal 2's 32-unit grid, so Portal 2 can make them solid. Blocks inside
	 * Portal 2's own walls (the ground behind them, blocks in holes) are left out: Portal 2 is solid there already.
	 * Neighbouring blocks share one box, the nearest boxes go first.
	 */
	private void publishSolids(Minecraft mc, LocalPlayer p) {
		BlockPos c = p.blockPosition();
		P2Map map = P2Map.current;
		// not before the map is read: without it the ground behind Portal 2's walls would count too
		if (map == null || !map.name.equals(host.map) || busy()) return;
		// a whole test chamber around the player, so blocks left on a floor button keep it pressed
		int r = 18, ry = 10, w = 2 * r + 1, h = 2 * ry + 1;
		boolean[] solid = new boolean[w * w * h]; // ((y * w) + z) * w + x, from c - (r, ry, r)
		int blocks = 0;
		// in another dimension (the Nether, the End) nothing lines up with Portal 2's map: no boxes
		if (mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int y = 0; y < h; y++)
				for (int z = 0; z < w; z++)
					for (int x = 0; x < w; x++) {
						pos.set(c.getX() - r + x, c.getY() - ry + y, c.getZ() - r + z);
						BlockState s = mc.level.getBlockState(pos);
						if (s.isAir() || s.is(Blocks.BARRIER) || s.getCollisionShape(mc.level, pos).isEmpty()) continue;
						if (map.p2Solid(pos.getX(), pos.getY(), pos.getZ())) continue;
						solid[(y * w + z) * w + x] = true;
						blocks++;
					}
		}
		// greedy boxes: a run along x, grown into rows along z, then into layers along y
		List<int[]> found = new java.util.ArrayList<>(); // x, y, z, size x, size y, size z (relative, Minecraft axes), distance
		for (int y = 0; y < h; y++)
			for (int z = 0; z < w; z++)
				for (int x = 0; x < w; x++) {
					if (!solid[(y * w + z) * w + x]) continue;
					int x1 = x, z1 = z, y1 = y;
					while (x1 + 1 < w && solid[(y * w + z) * w + x1 + 1]) x1++;
					rows:
					while (z1 + 1 < w) {
						for (int k = x; k <= x1; k++) if (!solid[(y * w + z1 + 1) * w + k]) break rows;
						z1++;
					}
					layers:
					while (y1 + 1 < h) {
						for (int j = z; j <= z1; j++)
							for (int k = x; k <= x1; k++) if (!solid[((y1 + 1) * w + j) * w + k]) break layers;
						y1++;
					}
					for (int i = y; i <= y1; i++)
						for (int j = z; j <= z1; j++)
							for (int k = x; k <= x1; k++) solid[(i * w + j) * w + k] = false;
					int dx = Math.max(0, Math.max(x - r, r - x1)), dy = Math.max(0, Math.max(y - ry, ry - y1)), dz = Math.max(0, Math.max(z - r, r - z1));
					found.add(new int[] { x, y, z, x1 - x + 1, y1 - y + 1, z1 - z + 1, dx * dx + dy * dy + dz * dz });
				}
		found.sort(java.util.Comparator.comparingInt(b -> b[6]));
		int n = Math.min(found.size(), MAX_SOLID_BOXES);
		int[] boxes = new int[n * 6];
		for (int i = 0; i < n; i++) {
			int[] b = found.get(i);
			int bx = c.getX() - r + b[0], by = c.getY() - ry + b[1], bz = c.getZ() - r + b[2];
			// Minecraft block (bx, by, bz) is Source cell (bx - region, -(bz + 1), by - 128)
			boxes[i * 6] = bx - HostLink.regionX;
			boxes[i * 6 + 1] = -(bz + b[5]);
			boxes[i * 6 + 2] = by - (int) HostLink.Y_OFFSET;
			boxes[i * 6 + 3] = b[3];
			boxes[i * 6 + 4] = b[5];
			boxes[i * 6 + 5] = b[4];
		}
		if (!java.util.Arrays.equals(boxes, lastBoxes)) {
			lastBoxes = boxes;
			solidSerial++;
			if (found.size() > MAX_SOLID_BOXES)
				Portalcraft.LOG.info("{} Minecraft blocks near the player need {} boxes in Portal 2: only the nearest {} are solid there", blocks, found.size(), n);
		}
		guest.solids(boxes, n, solidSerial);
	}

	/**
	 * Holes in Portal 2's walls around the camera, as a 128 x 128 x 64 cell grid in Source cells, so Portal 2's
	 * composite can show Minecraft where its own wall is gone.
	 */
	private void publishHoles() {
		P2Map map = P2Map.current;
		// camera cell in Source's grid
		int cx = (int) Math.floor(host.ox / 32.0), cy = (int) Math.floor(host.oy / 32.0), cz = (int) Math.floor(host.oz / 32.0);
		boolean recenter = gox == Integer.MIN_VALUE || Math.abs(cx - (gox + GuestLink.GX / 2)) > 24 || Math.abs(cy - (goy + GuestLink.GY / 2)) > 24
			|| Math.abs(cz - (goz + GuestLink.GZ / 2)) > 12;
		int version = map == null ? -1 : map.brokenVersion.get();
		if (!recenter && map == gridMap && version == gridVersion) return;
		if (recenter) {
			gox = cx - GuestLink.GX / 2;
			goy = cy - GuestLink.GY / 2;
			goz = cz - GuestLink.GZ / 2;
		}
		gridMap = map;
		gridVersion = version;
		java.util.Arrays.fill(grid, (byte) 0);
		if (map != null) {
			for (Long l : map.broken) {
				long k = l;
				int sx = BlockPos.getX(k) - map.regionX - gox, sy = -(BlockPos.getZ(k) + 1) - goy, sz = BlockPos.getY(k) - P2Map.Y_OFFSET - goz;
				if (sx < 0 || sy < 0 || sz < 0 || sx >= GuestLink.GX || sy >= GuestLink.GY || sz >= GuestLink.GZ) continue;
				grid[GuestLink.gridIndex(sx, sy, sz)] = (byte) 255;
			}
		}
		guest.holes(grid, gox, goy, goz);
		if (map != null && !map.broken.isEmpty()) Portalcraft.LOG.info("hole grid: {} holes, origin {} {} {} (camera cell {} {} {})", map.broken.size(), gox, goy, goz, cx, cy, cz);
	}
}
