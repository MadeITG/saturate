package local.saturate;

import local.saturate.commands.ModCommands;
import local.saturate.config.Config;
import local.saturate.config.ModConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;

@Environment(EnvType.CLIENT)
public class SaturateFabric implements ClientModInitializer {
   public static final String MOD_ID = "saturate";
   public static final KeyMapping HEARTS_RENDERING_ENABLED = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.saturate.renderingEnabled", 263, SaturateCommon.SATURATE_CATEGORY));
   public static final KeyMapping SATURATION_RENDERING_ENABLED = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.saturate.saturationRenderingEnabled", 345, SaturateCommon.SATURATE_CATEGORY));
   public static final KeyMapping ARMOR_RENDERING_ENABLED = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.saturate.armorRenderingEnabled", 344, SaturateCommon.SATURATE_CATEGORY));
   public static final KeyMapping OVERRIDE_ALL_FILTERS = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.saturate.overrideAllFilters", 262, SaturateCommon.SATURATE_CATEGORY));
   public static final KeyMapping INCREASE_HEART_OFFSET = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.saturate.increaseHeartOffset", 265, SaturateCommon.SATURATE_CATEGORY));
   public static final KeyMapping DECREASE_HEART_OFFSET = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.saturate.decreaseHeartOffset", 264, SaturateCommon.SATURATE_CATEGORY));
   public static final KeyMapping OPEN_CONFIG_SCREEN = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.saturate.openModMenuConfig", 73, SaturateCommon.SATURATE_CATEGORY));

   @Override
   public void onInitializeClient() {
      SaturateCommon.init();
      ModCommands.registerDebugCommands();
      if (ModConfig.HANDLER.instance().enable_commands) {
         ModCommands.registerCommands();
      }

      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         SaturateCommon.tick();

         while (HEARTS_RENDERING_ENABLED.consumeClick()) {
            SaturateCommon.enableHeartsRendering();
         }

         while (SATURATION_RENDERING_ENABLED.consumeClick()) {
            SaturateCommon.enableSaturationRendering();
         }

         while (ARMOR_RENDERING_ENABLED.consumeClick()) {
            SaturateCommon.enableArmorRendering();
         }

         while (INCREASE_HEART_OFFSET.consumeClick()) {
            SaturateCommon.increaseOffset();
         }

         while (DECREASE_HEART_OFFSET.consumeClick()) {
            SaturateCommon.decreaseOffset();
         }

         if (OVERRIDE_ALL_FILTERS.isDown()) {
            SaturateCommon.overrideFilters();
         } else if (Config.getOverrideAllFiltersEnabled()) {
            SaturateCommon.disableOverrideFilters();
         }

         if (OPEN_CONFIG_SCREEN.consumeClick()) {
            SaturateCommon.openConfigScreen();
         }
      });
      ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
         RenderTracker.removeFromUUIDS(entity);
         PlayerSaturationTracker.removeEntity(entity);
      });
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
         PlayerSaturationTracker.clear();
         DebugLogger.close();
      });
      ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
         DebugLogger.close();
         ModConfig.HANDLER.save();
      });
   }
}

