package local.saturate;

import java.awt.Color;
import local.saturate.config.ModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class DamageDirectionIndicatorRenderer {
   private static Player player = SaturateCommon.client.player;
   private static int timeSinceLastDamage = Integer.MAX_VALUE;
   private static LivingEntity attacker;

   public static void markDamageToPlayer(LivingEntity livingEntity) {
      timeSinceLastDamage = 0;
      attacker = livingEntity;
   }

   public static void tick() {
      player = SaturateCommon.client.player;
      if (timeSinceLastDamage != Integer.MAX_VALUE) {
         ++timeSinceLastDamage;
      }

      if (attacker == null || attacker.isDeadOrDying() || attacker.isRemoved()) {
         timeSinceLastDamage = Integer.MAX_VALUE;
         attacker = null;
      }

      if (timeSinceLastDamage == Integer.MAX_VALUE) {
         attacker = null;
      }
   }

   public static void render(GuiGraphicsExtractor drawContext, float tickDelta) {
      if (player != null) {
         ModConfig config = ModConfig.HANDLER.instance();
         if (timeSinceLastDamage <= config.damage_direction_indicators_visibility_time * 20 && attacker != null) {
            Vec3 playerPos = player.position();
            Vec3 attackerPos = attacker.position();
            double deltaX = attackerPos.x - playerPos.x;
            double deltaZ = attackerPos.z - playerPos.z;
            float yawToAttacker = (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0F;
            yawToAttacker = Mth.wrapDegrees(yawToAttacker);
            float deltaYaw = Mth.wrapDegrees(yawToAttacker - player.getYRot());
            int centerX = drawContext.guiWidth() / 2;
            int centerY = drawContext.guiHeight() / 2;
            float angle = (float) Math.toRadians(deltaYaw);
            int alpha = 255;
            if (config.damage_direction_indicators_fade_out) {
               int fadeDelay = (config.damage_direction_indicators_visibility_time - config.damage_direction_indicators_fade_out_time) * 20;
               if (timeSinceLastDamage >= fadeDelay) {
                  float progress = Math.min((float) (timeSinceLastDamage - fadeDelay) / (float) (config.damage_direction_indicators_fade_out_time * 20), 1.0F);
                  alpha = 255 - (int) (255.0F * progress);
               }
            }

            float scale = (float) config.damage_direction_indicators_scale;
            Color color = config.damage_direction_indicators_color;
            drawArcIndicator(drawContext, centerX, centerY, angle, scale, color, alpha);
         }
      }
   }

   private static void drawArcIndicator(GuiGraphicsExtractor context, int centerX, int centerY, float directionAngle, float scale, Color color, int alpha) {
      float radius = 28.0F * scale;
      float arcSpan = 50.0F;
      float thickness = 1.6F * scale;
      int segments = 20;
      int mainColor = alpha << 24 | color.getRed() << 16 | color.getGreen() << 8 | color.getBlue();
      int darkerAlpha = (int) ((float) alpha * 0.25F);
      int darkerColor = darkerAlpha << 24 | color.getRed() * 2 / 3 << 16 | color.getGreen() * 2 / 3 << 8 | color.getBlue() * 2 / 3;

      for (int i = 0; i < segments; ++i) {
         float t1 = (float) i / (float) segments;
         float t2 = (float) (i + 1) / (float) segments;
         float angle1 = directionAngle - (float) Math.toRadians(arcSpan / 2.0F) + (float) Math.toRadians(arcSpan * t1);
         float angle2 = directionAngle - (float) Math.toRadians(arcSpan / 2.0F) + (float) Math.toRadians(arcSpan * t2);
         float x1Inner = (float) centerX + radius * Mth.sin(angle1);
         float y1Inner = (float) centerY - radius * Mth.cos(angle1);
         float x2Inner = (float) centerX + radius * Mth.sin(angle2);
         float y2Inner = (float) centerY - radius * Mth.cos(angle2);
         float x1Outer = (float) centerX + (radius + thickness) * Mth.sin(angle1);
         float y1Outer = (float) centerY - (radius + thickness) * Mth.cos(angle1);
         float x2Outer = (float) centerX + (radius + thickness) * Mth.sin(angle2);
         float y2Outer = (float) centerY - (radius + thickness) * Mth.cos(angle2);
         drawQuad(context, x1Inner, y1Inner, x2Inner, y2Inner, x2Outer, y2Outer, x1Outer, y1Outer, mainColor);
      }

      for (int i = 0; i < segments; ++i) {
         float t1 = (float) i / (float) segments;
         float t2 = (float) (i + 1) / (float) segments;
         float angle1 = directionAngle - (float) Math.toRadians(arcSpan / 2.0F) + (float) Math.toRadians(arcSpan * t1);
         float angle2 = directionAngle - (float) Math.toRadians(arcSpan / 2.0F) + (float) Math.toRadians(arcSpan * t2);
         float x1 = (float) centerX + (radius + thickness) * Mth.sin(angle1);
         float y1 = (float) centerY - (radius + thickness) * Mth.cos(angle1);
         float x2 = (float) centerX + (radius + thickness) * Mth.sin(angle2);
         float y2 = (float) centerY - (radius + thickness) * Mth.cos(angle2);
         float x1Outer = (float) centerX + (radius + thickness + 1.0F * scale) * Mth.sin(angle1);
         float y1Outer = (float) centerY - (radius + thickness + 1.0F * scale) * Mth.cos(angle1);
         float x2Outer = (float) centerX + (radius + thickness + 1.0F * scale) * Mth.sin(angle2);
         float y2Outer = (float) centerY - (radius + thickness + 1.0F * scale) * Mth.cos(angle2);
         drawQuad(context, x1, y1, x2, y2, x2Outer, y2Outer, x1Outer, y1Outer, darkerColor);
      }

      drawArrowPointer(context, centerX, centerY, directionAngle, radius, scale, color, alpha);
   }

   private static void drawArrowPointer(GuiGraphicsExtractor context, int centerX, int centerY, float directionAngle, float radius, float scale, Color color, int alpha) {
      float arrowLength = 6.0F * scale;
      float arrowWidth = 4.5F * scale;
      float arrowBaseX = (float) centerX + radius * Mth.sin(directionAngle);
      float arrowBaseY = (float) centerY - radius * Mth.cos(directionAngle);
      float arrowTipX = (float) centerX + (radius + arrowLength) * Mth.sin(directionAngle);
      float arrowTipY = (float) centerY - (radius + arrowLength) * Mth.cos(directionAngle);
      float perpAngle = directionAngle + ((float) Math.PI / 2.0F);
      float leftWingX = arrowBaseX + arrowWidth * Mth.sin(perpAngle);
      float leftWingY = arrowBaseY - arrowWidth * Mth.cos(perpAngle);
      float rightWingX = arrowBaseX - arrowWidth * Mth.sin(perpAngle);
      float rightWingY = arrowBaseY + arrowWidth * Mth.cos(perpAngle);
      int arrowColor = alpha << 24 | color.getRed() << 16 | color.getGreen() << 8 | color.getBlue();
      float[] xs = new float[]{arrowTipX, leftWingX, rightWingX};
      float[] ys = new float[]{arrowTipY, leftWingY, rightWingY};
      sortVerticesByY(xs, ys);
      drawTriangle(context, xs, ys, arrowColor);
   }

   private static void drawQuad(GuiGraphicsExtractor context, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int color) {
      float[] tri1X = new float[]{x1, x2, x3};
      float[] tri1Y = new float[]{y1, y2, y3};
      float[] tri2X = new float[]{x1, x3, x4};
      float[] tri2Y = new float[]{y1, y3, y4};
      sortVerticesByY(tri1X, tri1Y);
      drawTriangle(context, tri1X, tri1Y, color);
      sortVerticesByY(tri2X, tri2Y);
      drawTriangle(context, tri2X, tri2Y, color);
   }

   private static void sortVerticesByY(float[] xs, float[] ys) {
      if (ys[0] > ys[1]) {
         swap(xs, ys, 0, 1);
      }

      if (ys[0] > ys[2]) {
         swap(xs, ys, 0, 2);
      }

      if (ys[1] > ys[2]) {
         swap(xs, ys, 1, 2);
      }
   }

   private static void swap(float[] xs, float[] ys, int i, int j) {
      float tx = xs[i];
      xs[i] = xs[j];
      xs[j] = tx;
      float ty = ys[i];
      ys[i] = ys[j];
      ys[j] = ty;
   }

   private static void drawTriangle(GuiGraphicsExtractor context, float[] xs, float[] ys, int color) {
      float minY = ys[0];
      float midY = ys[1];
      float maxY = ys[2];
      if (minY != maxY) {
         for (int y = (int) Math.floor(minY); y <= (int) Math.ceil(maxY); ++y) {
            if (!((float) y < minY) && !((float) y > maxY)) {
               boolean inSecondHalf = (float) y > midY;
               float xa;
               float xb;
               if (!inSecondHalf) {
                  xa = interpolate(xs[0], ys[0], xs[1], ys[1], (float) y);
                  xb = interpolate(xs[0], ys[0], xs[2], ys[2], (float) y);
               } else {
                  xa = interpolate(xs[1], ys[1], xs[2], ys[2], (float) y);
                  xb = interpolate(xs[0], ys[0], xs[2], ys[2], (float) y);
               }

               if (xa > xb) {
                  float tmp = xa;
                  xa = xb;
                  xb = tmp;
               }

               context.fill((int) xa, y, (int) xb + 1, y + 1, color);
            }
         }
      }
   }

   private static float interpolate(float x1, float y1, float x2, float y2, float y) {
      return y1 == y2 ? x1 : x1 + (x2 - x1) * (y - y1) / (y2 - y1);
   }
}

