package local.saturate.enums;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

public enum HeartTypeEnum {
   EMPTY("container"),
   RED_FULL("full"),
   RED_HALF("half"),
   YELLOW_FULL("absorbing_full"),
   YELLOW_HALF("absorbing_half");

   public final String icon;

   HeartTypeEnum(String heartIcon) {
      this.icon = heartIcon;
   }

   public static String addStatusIcon(LivingEntity livingEntity) {
      if (livingEntity.hasEffect(MobEffects.WITHER)) {
         return "withered_";
      } else if (livingEntity.hasEffect(MobEffects.POISON)) {
         return "poisoned_";
      } else {
         return livingEntity.isFullyFrozen() ? "frozen_" : "";
      }
   }

   public static String addHardcoreIcon(LivingEntity livingEntity) {
      return livingEntity.level().getLevelData().isHardcore() ? "hardcore_" : "";
   }
}

