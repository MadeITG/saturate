package local.saturate.mixin;

import local.saturate.PlayerSaturationTracker;
import local.saturate.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
   @Inject(
      method = "handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V",
      at = @At("HEAD")
   )
   private void onLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
      // Reset all AHI flags and per-entity states on server join or Bungee/Velocity sub-server switch
      PlayerSaturationTracker.clear();
   }

   @Inject(
      method = "handleChangeDifficulty(Lnet/minecraft/network/protocol/game/ClientboundChangeDifficultyPacket;)V",
      at = @At("TAIL")
   )
   private void onChangeDifficulty(ClientboundChangeDifficultyPacket packet, CallbackInfo ci) {
      PlayerSaturationTracker.onDifficultyChange(packet.difficulty());
   }

   @Inject(
      method = "handleEntityEvent(Lnet/minecraft/network/protocol/game/ClientboundEntityEventPacket;)V",
      at = @At("TAIL")
   )
   private void onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      byte eventId = packet.getEventId();
      if (eventId == 35 || eventId == 29) {
         Entity entity = packet.getEntity(client.level);
         if (entity instanceof LivingEntity livingEntity) {
            if (eventId == 35) {
               PlayerSaturationTracker.onTotemPop(livingEntity);
            } else {
               PlayerSaturationTracker.onShieldBlock(livingEntity);
            }
         }
      } else if (eventId == 3) {
         Entity entity = packet.getEntity(client.level);
         if (entity instanceof LivingEntity livingEntity) {
            PlayerSaturationTracker.onEntityDeath(livingEntity);
         }
      }
   }

   @Inject(
      method = "handleRespawn(Lnet/minecraft/network/protocol/game/ClientboundRespawnPacket;)V",
      at = @At("TAIL")
   )
   private void onRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
      Minecraft client = Minecraft.getInstance();
      if (client.player != null) {
         PlayerSaturationTracker.onPlayerRespawn(client.player);
      }
   }

   @Inject(
      method = "handleSetEntityData(Lnet/minecraft/network/protocol/game/ClientboundSetEntityDataPacket;)V",
      at = @At("TAIL")
   )
   private void onSetEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      Entity entity = client.level.getEntity(packet.id());
      if (!(entity instanceof LivingEntity livingEntity)) {
         return;
      }

      int healthDataId = LivingEntityAccessor.getDataHealthId().id();
      for (SynchedEntityData.DataValue<?> item : packet.packedItems()) {
         if (item.id() == healthDataId && item.value() instanceof Float hpVal) {
            PlayerSaturationTracker.onHealthMetadataPacket(livingEntity, hpVal);
         }
      }
   }

   @Inject(
      method = "handleAnimate(Lnet/minecraft/network/protocol/game/ClientboundAnimatePacket;)V",
      at = @At("TAIL")
   )
   private void onAnimate(ClientboundAnimatePacket packet, CallbackInfo ci) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      Entity entity = client.level.getEntity(packet.getId());
      if (entity == null) {
         return;
      }
      int action = packet.getAction();
      if (Compat.isSwingAnimateAction(action)
         && entity instanceof Player player) {
         PlayerSaturationTracker.onPlayerSwing(player);
      } else if ((action == ClientboundAnimatePacket.CRITICAL_HIT || action == ClientboundAnimatePacket.MAGIC_CRITICAL_HIT)
         && entity instanceof LivingEntity livingEntity) {
         PlayerSaturationTracker.onCombatAnimation(livingEntity, action);
      }
   }

   @Inject(
      method = "handleMoveEntity(Lnet/minecraft/network/protocol/game/ClientboundMoveEntityPacket;)V",
      at = @At("TAIL")
   )
   private void onMoveEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      Entity entity = packet.getEntity(client.level);
      if (entity instanceof Player player && player != client.player) {
         Vec3 base = player.getPositionCodec().getBase();
         PlayerSaturationTracker.onServerMovePacket(player, base.x, base.y, base.z, packet.isOnGround(), false, packet.hasPosition());
      }
   }

   @Inject(
      method = "handleEntityPositionSync(Lnet/minecraft/network/protocol/game/ClientboundEntityPositionSyncPacket;)V",
      at = @At("TAIL")
   )
   private void onEntityPositionSync(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      Entity entity = client.level.getEntity(packet.id());
      if (entity instanceof Player player && player != client.player) {
         Vec3 pos = Compat.positionSyncPosition(packet);
         PlayerSaturationTracker.onServerMovePacket(player, pos.x, pos.y, pos.z, packet.onGround(), false, true);
      }
   }

   @Inject(
      method = "handleTeleportEntity(Lnet/minecraft/network/protocol/game/ClientboundTeleportEntityPacket;)V",
      at = @At("TAIL")
   )
   private void onTeleportEntity(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      Entity entity = client.level.getEntity(packet.id());
      if (entity instanceof Player player && player != client.player) {
         Vec3 base = player.getPositionCodec().getBase();
         PlayerSaturationTracker.onServerMovePacket(player, base.x, base.y, base.z, packet.onGround(), true, true);
      }
   }

   @Inject(
      method = "handleSoundEvent(Lnet/minecraft/network/protocol/game/ClientboundSoundPacket;)V",
      at = @At("TAIL")
   )
   private void onSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
      dispatchTrackedSound(packet.getSound().value(), packet.getX(), packet.getY(), packet.getZ());
   }

   @Inject(
      method = "handleSoundEntityEvent(Lnet/minecraft/network/protocol/game/ClientboundSoundEntityPacket;)V",
      at = @At("TAIL")
   )
   private void onSoundEntityEvent(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      Entity entity = client.level.getEntity(packet.getId());
      if (entity != null) {
         Vec3 base = entity.getPositionCodec().getBase();
         dispatchTrackedSound(packet.getSound().value(), base.x, base.y, base.z);
      }
   }

   @Inject(
      method = "handleBlockDestruction(Lnet/minecraft/network/protocol/game/ClientboundBlockDestructionPacket;)V",
      at = @At("TAIL")
   )
   private void onBlockDestruction(ClientboundBlockDestructionPacket packet, CallbackInfo ci) {
      PlayerSaturationTracker.onBlockDestructionProgress(packet.getId(), packet.getPos(), packet.getProgress());
   }

   @Inject(
      method = "handleLevelEvent(Lnet/minecraft/network/protocol/game/ClientboundLevelEventPacket;)V",
      at = @At("TAIL")
   )
   private void onLevelEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
      if (packet.getType() == 2001) {
         PlayerSaturationTracker.onBlockBroken(packet.getPos());
      }
   }

   @Unique
   private static void dispatchTrackedSound(SoundEvent sound, double x, double y, double z) {
      if (sound == SoundEvents.PLAYER_BURP) {
         PlayerSaturationTracker.onBurpSound(x, y, z);
      } else if (sound == SoundEvents.SHIELD_BLOCK.value()) {
         PlayerSaturationTracker.onShieldBlockSound(x, y, z);
      } else if (sound == SoundEvents.PLAYER_ATTACK_CRIT
         || sound == SoundEvents.PLAYER_ATTACK_WEAK
         || sound == SoundEvents.PLAYER_ATTACK_STRONG
         || sound == SoundEvents.PLAYER_ATTACK_KNOCKBACK
         || sound == SoundEvents.PLAYER_ATTACK_SWEEP) {
         PlayerSaturationTracker.onAttackSound(sound, x, y, z);
      }
   }
}
