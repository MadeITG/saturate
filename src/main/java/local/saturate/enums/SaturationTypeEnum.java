package local.saturate.enums;

import net.minecraft.resources.Identifier;

public enum SaturationTypeEnum {
   FULL("saturation_full"),
   HALF("saturation_half"),
   EMPTY("saturation_empty");

   public final Identifier icon;
   public final Identifier vanillaContainerIcon;

   SaturationTypeEnum(String saturationIcon) {
      this.icon = Identifier.fromNamespaceAndPath("saturate", "textures/gui/saturation/" + saturationIcon + ".png");
      this.vanillaContainerIcon = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/sprites/hud/food_empty.png");
   }
}

