package dev.portalcraft.mixin;

import dev.portalcraft.Portalcraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Portal 2 moves the player (through walls via portals, across the map in one frame): while the link is up, the
 * server accepts the client's position instead of colliding it with barriers or calling it "moved too quickly".
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow public ServerPlayer player;
	@Unique private boolean portalcraft$oldNoPhysics;

	@Inject(method = "handleMovePlayer", at = @At("HEAD"))
	private void portalcraft$before(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		portalcraft$oldNoPhysics = player.noPhysics;
		if (Portalcraft.hostLinked) player.noPhysics = true;
	}

	@Inject(method = "handleMovePlayer", at = @At("RETURN"))
	private void portalcraft$after(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		player.noPhysics = portalcraft$oldNoPhysics;
	}

	@Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
	private void portalcraft$noSpeedCheck(boolean isFallFlying, CallbackInfoReturnable<Boolean> cir) {
		if (Portalcraft.hostLinked) cir.setReturnValue(false);
	}
}
