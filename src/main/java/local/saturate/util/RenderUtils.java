package local.saturate.util;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Locale;
import local.saturate.PlayerSaturationTracker;
import local.saturate.config.ModConfig;
import local.saturate.enums.ArmorTypeEnum;
import local.saturate.enums.HeartTypeEnum;
import local.saturate.enums.SaturationTypeEnum;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;

public class RenderUtils {
   public static void drawHeart(Matrix4f model, VertexConsumer vertexConsumer, float x, HeartTypeEnum type, LivingEntity livingEntity) {
      drawHeart(model, vertexConsumer, x, type, livingEntity, 1.0F, 0.0);
   }

   public static void drawHeart(Matrix4f model, VertexConsumer vertexConsumer, float x, HeartTypeEnum type, LivingEntity livingEntity, float opacity) {
      drawHeart(model, vertexConsumer, x, type, livingEntity, opacity, 0.0);
   }

   public static void drawHeart(Matrix4f model, VertexConsumer vertexConsumer, float x, HeartTypeEnum type, LivingEntity livingEntity, float opacity, double squaredDistance) {
      drawHeart(model, vertexConsumer, x, type, livingEntity, opacity, squaredDistance, false);
   }

   public static void drawHeart(Matrix4f model, VertexConsumer vertexConsumer, float x, HeartTypeEnum type, LivingEntity livingEntity, float opacity, double squaredDistance, boolean isObstructed) {
      float minU = 0.0F;
      float maxU = 1.0F;
      float minV = 0.0F;
      float maxV = 1.0F;
      float heartSize = 9.0F;
      float z = 0.0F;
      if (!isObstructed) {
         float baseOffset = 0.01F;
         float distanceScale = 1.0F + (float) Math.sqrt(squaredDistance);
         float scaledOffset = baseOffset * distanceScale;
         z = type == HeartTypeEnum.EMPTY ? -scaledOffset : scaledOffset;
      }

      vertexConsumer.addVertex(model, x, -heartSize, z).setUv(minU, maxV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
      vertexConsumer.addVertex(model, x - heartSize, -heartSize, z).setUv(maxU, maxV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
      vertexConsumer.addVertex(model, x - heartSize, 0.0F, z).setUv(maxU, minV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
      vertexConsumer.addVertex(model, x, 0.0F, z).setUv(minU, minV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
   }

   public static void drawSaturation(Matrix4f model, VertexConsumer vertexConsumer, float x, SaturationTypeEnum type, float opacity, double squaredDistance, boolean isObstructed) {
      float minU = 0.0F;
      float maxU = 1.0F;
      float minV = 0.0F;
      float maxV = 1.0F;
      float iconSize = 9.0F;
      float z = 0.0F;
      if (!isObstructed) {
         float baseOffset = 0.01F;
         float distanceScale = 1.0F + (float) Math.sqrt(squaredDistance);
         float scaledOffset = baseOffset * distanceScale;
         z = type == SaturationTypeEnum.EMPTY ? -scaledOffset : scaledOffset;
      }

      vertexConsumer.addVertex(model, x, -iconSize, z).setUv(minU, maxV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
      vertexConsumer.addVertex(model, x - iconSize, -iconSize, z).setUv(maxU, maxV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
      vertexConsumer.addVertex(model, x - iconSize, 0.0F, z).setUv(maxU, minV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
      vertexConsumer.addVertex(model, x, 0.0F, z).setUv(minU, minV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
   }

   public static void drawArmor(Matrix4f model, VertexConsumer vertexConsumer, float x, ArmorTypeEnum type) {
      drawArmor(model, vertexConsumer, x, type, 1.0F, 0.0);
   }

   public static void drawArmor(Matrix4f model, VertexConsumer vertexConsumer, float x, ArmorTypeEnum type, float opacity) {
      drawArmor(model, vertexConsumer, x, type, opacity, 0.0);
   }

   public static void drawArmor(Matrix4f model, VertexConsumer vertexConsumer, float x, ArmorTypeEnum type, float opacity, double squaredDistance) {
      drawArmor(model, vertexConsumer, x, type, opacity, squaredDistance, false);
   }

   public static void drawArmor(Matrix4f model, VertexConsumer vertexConsumer, float x, ArmorTypeEnum type, float opacity, double squaredDistance, boolean isObstructed) {
      float minU = 0.0F;
      float maxU = 1.0F;
      float minV = 0.0F;
      float maxV = 1.0F;
      float armorSize = 9.0F;
      float z = 0.0F;
      if (!isObstructed) {
         float baseOffset = 0.01F;
         float distanceScale = 1.0F + (float) (Math.sqrt(squaredDistance) * 0.001);
         float scaledOffset = baseOffset * distanceScale;
         z = type == ArmorTypeEnum.EMPTY ? -scaledOffset : scaledOffset;
      }

      vertexConsumer.addVertex(model, x, -armorSize, z).setUv(minU, maxV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
      vertexConsumer.addVertex(model, x - armorSize, -armorSize, z).setUv(maxU, maxV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
      vertexConsumer.addVertex(model, x - armorSize, 0.0F, z).setUv(maxU, minV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
      vertexConsumer.addVertex(model, x, 0.0F, z).setUv(minU, minV).setLight(15728880).setColor(1.0F, 1.0F, 1.0F, opacity);
   }

   public static String getHealthText(LivingEntity livingEntity) {
      ModConfig config = ModConfig.HANDLER.instance();
      boolean estimated = PlayerSaturationTracker.isHealthEstimated(livingEntity);
      int decimalPlaces = estimated ? Math.min(config.decimal_places, 1) : config.decimal_places;
      String uncertaintyPrefix = (config.show_uncertainty_indicator && PlayerSaturationTracker.hasWideUncertainty(livingEntity)) ? "~" : "";
      float health = PlayerSaturationTracker.getEffectiveHealth(livingEntity);
      float maxHealth = livingEntity.getMaxHealth();
      float absorption = PlayerSaturationTracker.getEffectiveAbsorption(livingEntity);
      String healthStr = formatToDecimalPlaces(health + absorption, decimalPlaces);
      String maxHealthStr = formatToDecimalPlaces(maxHealth, decimalPlaces);
      if (config.percentage_based_health) {
         float percentage = (health + absorption) / maxHealth * 100.0F;
         return uncertaintyPrefix + formatToDecimalPlaces(percentage, decimalPlaces) + " %";
      } else {
         return uncertaintyPrefix + healthStr + " / " + maxHealthStr;
      }
   }

   public static String getSaturationText(Player player) {
      ModConfig config = ModConfig.HANDLER.instance();
      if (!PlayerSaturationTracker.isSaturationKnown(player)) {
         return "?";
      }
      boolean estimated = PlayerSaturationTracker.isHealthEstimated(player) || Float.isNaN(player.getFoodData().getSaturationLevel());
      int decimalPlaces = estimated ? Math.min(config.decimal_places, 1) : config.decimal_places;
      float saturation = PlayerSaturationTracker.getSaturation(player);
      if (config.percentage_based_health) {
         float percentage = saturation / 20.0F * 100.0F;
         return formatToDecimalPlaces(percentage, decimalPlaces) + " %";
      } else {
         return formatToDecimalPlaces(saturation, decimalPlaces) + " / " + formatToDecimalPlaces(20.0F, decimalPlaces);
      }
   }

   private static String formatToDecimalPlaces(float value, int decimalPlaces) {
      if (decimalPlaces == 0) {
         return String.format(Locale.ROOT, "%.0f", value);
      } else {
         return String.format(Locale.ROOT, "%." + decimalPlaces + "f", value);
      }
   }
}
