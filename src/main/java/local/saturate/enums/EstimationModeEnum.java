package local.saturate.enums;

import dev.isxander.yacl3.api.NameableEnum;
import net.minecraft.network.chat.Component;

public enum EstimationModeEnum implements NameableEnum {
   AUTO("Auto"),
   ALWAYS("Sempre / Always"),
   NEVER("Mai / Never");

   private final String displayName;

   EstimationModeEnum(String displayName) {
      this.displayName = displayName;
   }

   @Override
   public Component getDisplayName() {
      return Component.nullToEmpty(this.displayName);
   }
}

