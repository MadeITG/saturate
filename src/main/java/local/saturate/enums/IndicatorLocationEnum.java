package local.saturate.enums;

import dev.isxander.yacl3.api.NameableEnum;
import net.minecraft.network.chat.Component;

public enum IndicatorLocationEnum implements NameableEnum {
   WORLD("World"),
   GUI("GUI"),
   BOTH("Both");

   private final String displayName;

   IndicatorLocationEnum(String displayName) {
      this.displayName = displayName;
   }

   @Override
   public Component getDisplayName() {
      return Component.nullToEmpty(this.displayName);
   }
}

