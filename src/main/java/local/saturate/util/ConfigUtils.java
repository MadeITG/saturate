package local.saturate.util;

import local.saturate.config.ModConfig;
import local.saturate.enums.MessageTypeEnum;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class ConfigUtils {
   public static void sendMessage(LocalPlayer player, Component text) {
      if (isSendMessage()) {
         boolean overlay = ModConfig.HANDLER.instance().message_type == MessageTypeEnum.ACTIONBAR;
         if (overlay) {
            player.sendOverlayMessage(text);
         } else {
            player.sendSystemMessage(text);
         }
      }
   }

   public static void sendMessage(ServerPlayer player, Component text) {
      if (isSendMessage()) {
         boolean overlay = ModConfig.HANDLER.instance().message_type == MessageTypeEnum.ACTIONBAR;
         if (overlay) {
            player.sendOverlayMessage(text);
         } else {
            player.sendSystemMessage(text);
         }
      }
   }

   public static void sendOverlayMessage(LocalPlayer player, Component text) {
      if (isSendMessage()) {
         player.sendOverlayMessage(text);
      }
   }

   public static boolean isSendMessage() {
      return ModConfig.HANDLER.instance().message_type != MessageTypeEnum.NONE;
   }
}

