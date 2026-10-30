package local.saturate;

import local.saturate.compat.Compat;
import local.saturate.config.Config;
import local.saturate.config.ModConfig;
import local.saturate.util.ConfigUtils;
import local.saturate.util.Util;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyMapping.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SaturateCommon {
   public static final String MOD_ID = "saturate";
   public static Minecraft client = Minecraft.getInstance();
   public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
   public static final KeyMapping.Category SATURATE_CATEGORY = Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
   private static boolean changed = false;
   private static boolean openConfig = false;

   public static void init() {
      ModConfig.HANDLER.load();
      Config.load();
      HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), SaturateCommon::onHudRender);
      client = Minecraft.getInstance();
      LOGGER.info("Loaded Saturate");
   }

   public static void tick() {
      if (openConfig) {
         Screen configScreen = ModConfig.createScreen(Compat.currentScreen(client));
         Compat.setScreen(client, configScreen);
         openConfig = false;
      }

      if (client != null && client.level != null) {
         if (changed && client.level.getGameTime() % 200L == 0L) {
            ModConfig.HANDLER.save();
            changed = false;
         }

         RenderTracker.tick(client);
         PlayerSaturationTracker.tick(client);
         DamageDirectionIndicatorRenderer.tick();
      } else {
         if (client == null || client.getConnection() == null) {
            PlayerSaturationTracker.clear();
         }
         client = Minecraft.getInstance();
      }
   }

   public static void onHudRender(GuiGraphicsExtractor drawContext, DeltaTracker renderTickCounter) {
      if (RenderTracker.getTrackedEntity() != null) {
         HudRenderer.onHudRender(drawContext, renderTickCounter);
      }

      if (ModConfig.HANDLER.instance().enable_damage_direction_indicators && client.isLocalServer()) {
         DamageDirectionIndicatorRenderer.render(drawContext, renderTickCounter.getRealtimeDeltaTicks());
      }
   }

   public static void openConfig() {
      try {
         openConfig = client.level != null;
      } catch (NullPointerException ignored) {
         openConfig = false;
      }
   }

   public static void enableHeartsRendering() {
      Config.setHeartsRenderingEnabled(!Config.getHeartsRenderingEnabled());
      if (client != null && client.player != null) {
         ChatFormatting formatting;
         if (ModConfig.HANDLER.instance().colored_messages) {
            formatting = Config.getHeartsRenderingEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED;
         } else {
            formatting = ChatFormatting.WHITE;
         }

         ConfigUtils.sendMessage(client.player, Component.literal((Config.getHeartsRenderingEnabled() ? "Enabled" : "Disabled") + " Saturate").withStyle(formatting));
      }
   }

   public static void enableSaturationRendering() {
      Config.setSaturationRenderingEnabled(!Config.getSaturationRenderingEnabled());
      if (client != null && client.player != null) {
         ChatFormatting formatting;
         if (ModConfig.HANDLER.instance().colored_messages) {
            formatting = Config.getSaturationRenderingEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED;
         } else {
            formatting = ChatFormatting.WHITE;
         }

         ConfigUtils.sendMessage(client.player, Component.literal((Config.getSaturationRenderingEnabled() ? "Enabled" : "Disabled") + " Saturation Indicators").withStyle(formatting));
      }
   }

   public static void enableArmorRendering() {
      Config.setArmorRenderingEnabled(!Config.getArmorRenderingEnabled());
      if (client != null && client.player != null) {
         ChatFormatting formatting;
         if (ModConfig.HANDLER.instance().colored_messages) {
            formatting = Config.getArmorRenderingEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED;
         } else {
            formatting = ChatFormatting.WHITE;
         }

         ConfigUtils.sendMessage(client.player, Component.literal((Config.getArmorRenderingEnabled() ? "Enabled" : "Disabled") + " Armor Indicators").withStyle(formatting));
      }
   }

   public static void increaseOffset() {
      ModConfig.HANDLER.instance().display_offset += ModConfig.HANDLER.instance().offset_step_size;
      changed = true;
      if (client != null && client.player != null) {
         ConfigUtils.sendMessage(client.player, Component.literal("Set heart offset to " + Util.truncate(ModConfig.HANDLER.instance().display_offset, 2)));
      }
   }

   public static void decreaseOffset() {
      ModConfig.HANDLER.instance().display_offset -= ModConfig.HANDLER.instance().offset_step_size;
      changed = true;
      if (client != null && client.player != null) {
         ConfigUtils.sendMessage(client.player, Component.literal("Set heart offset to " + Util.truncate(ModConfig.HANDLER.instance().display_offset, 2)));
      }
   }

   public static void overrideFilters() {
      Config.setOverrideAllFiltersEnabled(true);
      if (client != null && client.player != null) {
         ConfigUtils.sendOverlayMessage(client.player, Component.literal(" Config Criteria " + (Config.getOverrideAllFiltersEnabled() ? "Temporarily Overridden" : "Re-implemented")));
      }
   }

   public static void disableOverrideFilters() {
      Config.setOverrideAllFiltersEnabled(false);
      Compat.clearOverlayMessage(client);
   }

   public static void openConfigScreen() {
      openConfig();
   }
}

