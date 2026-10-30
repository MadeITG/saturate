package local.saturate.enums;

import net.minecraft.resources.Identifier;

public enum FoodTypeEnum {
   FULL("food_full"),
   HALF("food_half"),
   EMPTY("food_empty");

   public final Identifier icon;

   FoodTypeEnum(String foodIcon) {
      this.icon = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/sprites/hud/" + foodIcon + ".png");
   }

   public SaturationTypeEnum geometryType() {
      return this == EMPTY ? SaturationTypeEnum.EMPTY : (this == HALF ? SaturationTypeEnum.HALF : SaturationTypeEnum.FULL);
   }
}
