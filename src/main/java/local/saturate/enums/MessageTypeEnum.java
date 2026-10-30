package local.saturate.enums;

import dev.isxander.yacl3.api.NameableEnum;
import net.minecraft.network.chat.Component;

public enum MessageTypeEnum implements NameableEnum {
   ACTIONBAR("Actionbar"),
   CHAT("Chat"),
   NONE("None");

   private final String displayName;

   MessageTypeEnum(String displayName) {
      this.displayName = displayName;
   }

   @Override
   public Component getDisplayName() {
      return Component.nullToEmpty(this.displayName);
   }
}

