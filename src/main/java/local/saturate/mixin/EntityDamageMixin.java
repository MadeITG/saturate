package local.saturate.mixin;

import local.saturate.DamageDirectionIndicatorRenderer;
import local.saturate.SaturateCommon;
import local.saturate.PlayerSaturationTracker;
import local.saturate.RenderTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class EntityDamageMixin {
   @Inject(
      method = "handleDamageEvent(Lnet/minecraft/world/damagesource/DamageSource;)V",
      at = @At("TAIL")
   )
   private void onEntityDamage(DamageSource damageSource, CallbackInfo callbackInfo) {
      LivingEntity self = (LivingEntity) (Object) this;
      RenderTracker.onDamage(damageSource, self);
      PlayerSaturationTracker.onDamage(damageSource, self);
      if (damageSource.getEntity() instanceof LivingEntity attacker && self == SaturateCommon.client.player) {
         DamageDirectionIndicatorRenderer.markDamageToPlayer(attacker);
      }
   }
}

