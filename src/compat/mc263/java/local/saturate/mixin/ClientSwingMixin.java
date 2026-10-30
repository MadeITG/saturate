package local.saturate.mixin;

import local.saturate.PlayerSaturationTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.3+: arm swings arrive in ClientboundSwingAnimationPacket instead of ClientboundAnimatePacket. */
@Mixin(ClientPacketListener.class)
public class ClientSwingMixin {
   @Inject(
      method = "handleSwingAnimation(Lnet/minecraft/network/protocol/game/ClientboundSwingAnimationPacket;)V",
      at = @At("TAIL")
   )
   private void onSwingAnimation(ClientboundSwingAnimationPacket packet, CallbackInfo ci) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      Entity entity = client.level.getEntity(packet.entityId());
      if (entity instanceof Player player) {
         PlayerSaturationTracker.onPlayerSwing(player);
      }
   }
}
