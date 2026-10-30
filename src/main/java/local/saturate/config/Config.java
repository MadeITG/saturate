package local.saturate.config;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public class Config {
   private static final Gson GSON = new Gson();
   public static final String CONFIG_FILE = "saturate_state.json";
   private static Config INSTANCE = new Config();
   private boolean heartsRenderingEnabled = true;
   private boolean saturationRenderingEnabled = true;
   private boolean armorRenderingEnabled = false;
   private boolean overrideAllFiltersEnabled = false;

   public static boolean getRenderingEnabled() {
      return INSTANCE.heartsRenderingEnabled || INSTANCE.saturationRenderingEnabled || INSTANCE.armorRenderingEnabled;
   }

   public static boolean getHeartsRenderingEnabled() {
      return INSTANCE.heartsRenderingEnabled;
   }

   public static void setHeartsRenderingEnabled(boolean renderingEnabled) {
      INSTANCE.heartsRenderingEnabled = renderingEnabled;
      save();
   }

   public static boolean getSaturationRenderingEnabled() {
      return INSTANCE.saturationRenderingEnabled;
   }

   public static void setSaturationRenderingEnabled(boolean saturationRenderingEnabled) {
      INSTANCE.saturationRenderingEnabled = saturationRenderingEnabled;
      save();
   }

   public static boolean getArmorRenderingEnabled() {
      return INSTANCE.armorRenderingEnabled;
   }

   public static void setArmorRenderingEnabled(boolean armorRenderingEnabled) {
      INSTANCE.armorRenderingEnabled = armorRenderingEnabled;
      save();
   }

   public static boolean getOverrideAllFiltersEnabled() {
      return INSTANCE.overrideAllFiltersEnabled;
   }

   public static void setOverrideAllFiltersEnabled(boolean overrideAllFiltersEnabled) {
      INSTANCE.overrideAllFiltersEnabled = overrideAllFiltersEnabled;
      save();
   }

   public static void load() {
      Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
      if (!Files.exists(configPath)) {
         return;
      }
      try (BufferedReader reader = Files.newBufferedReader(configPath)) {
         Config config = GSON.fromJson(reader, Config.class);
         if (config != null) {
            INSTANCE = config;
         }
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   public static void save() {
      Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
      try (BufferedWriter writer = Files.newBufferedWriter(configPath)) {
         GSON.toJson(INSTANCE, writer);
      } catch (Exception e) {
         e.printStackTrace();
      }
   }
}

