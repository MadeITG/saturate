package local.saturate;

import java.awt.Color;
import local.saturate.config.ModConfig;
import local.saturate.util.RenderUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class HudRenderer {
   private static final RandomSource random = RandomSource.create();

   public static void onHudRender(GuiGraphicsExtractor drawContext, DeltaTracker renderTickCounter) {
      ModConfig config = ModConfig.HANDLER.instance();
      drawNumberHealthGUIIndicator(RenderTracker.getTrackedEntity(), config.number_color, 20, 20, config.render_number_display_shadow, drawContext);
   }

   private static void drawHeart(GuiGraphicsExtractor context, HudHeartType type, int x, int y, boolean hardcore, boolean blinking, boolean half) {
   }

   public static void drawNumberHealthGUIIndicator(LivingEntity livingEntity, Color textColor, int x, int y, boolean shadow, GuiGraphicsExtractor drawContext) {
      String name = String.valueOf(livingEntity.getCustomName() != null ? livingEntity.getCustomName().tryCollapseToString() : livingEntity.getDisplayName().getString());
      drawContext.text(Minecraft.getInstance().font, name, x, y, textColor.getRGB(), shadow);
      drawContext.text(Minecraft.getInstance().font, RenderUtils.getHealthText(livingEntity), x, y + 10, textColor.getRGB(), shadow);
      if (livingEntity instanceof Player player) {
         drawContext.text(Minecraft.getInstance().font, "Sat: " + RenderUtils.getSaturationText(player), x, y + 20, 0xFFFFAA00, shadow);
      }
   }

   enum HudHeartType {
      CONTAINER(
         Identifier.withDefaultNamespace("hud/heart/container"),
         Identifier.withDefaultNamespace("hud/heart/container_blinking"),
         Identifier.withDefaultNamespace("hud/heart/container"),
         Identifier.withDefaultNamespace("hud/heart/container_blinking"),
         Identifier.withDefaultNamespace("hud/heart/container_hardcore"),
         Identifier.withDefaultNamespace("hud/heart/container_hardcore_blinking"),
         Identifier.withDefaultNamespace("hud/heart/container_hardcore"),
         Identifier.withDefaultNamespace("hud/heart/container_hardcore_blinking")
      ),
      NORMAL(
         Identifier.withDefaultNamespace("hud/heart/full"),
         Identifier.withDefaultNamespace("hud/heart/full_blinking"),
         Identifier.withDefaultNamespace("hud/heart/half"),
         Identifier.withDefaultNamespace("hud/heart/half_blinking"),
         Identifier.withDefaultNamespace("hud/heart/hardcore_full"),
         Identifier.withDefaultNamespace("hud/heart/hardcore_full_blinking"),
         Identifier.withDefaultNamespace("hud/heart/hardcore_half"),
         Identifier.withDefaultNamespace("hud/heart/hardcore_half_blinking")
      ),
      POISONED(
         Identifier.withDefaultNamespace("hud/heart/poisoned_full"),
         Identifier.withDefaultNamespace("hud/heart/poisoned_full_blinking"),
         Identifier.withDefaultNamespace("hud/heart/poisoned_half"),
         Identifier.withDefaultNamespace("hud/heart/poisoned_half_blinking"),
         Identifier.withDefaultNamespace("hud/heart/poisoned_hardcore_full"),
         Identifier.withDefaultNamespace("hud/heart/poisoned_hardcore_full_blinking"),
         Identifier.withDefaultNamespace("hud/heart/poisoned_hardcore_half"),
         Identifier.withDefaultNamespace("hud/heart/poisoned_hardcore_half_blinking")
      ),
      WITHERED(
         Identifier.withDefaultNamespace("hud/heart/withered_full"),
         Identifier.withDefaultNamespace("hud/heart/withered_full_blinking"),
         Identifier.withDefaultNamespace("hud/heart/withered_half"),
         Identifier.withDefaultNamespace("hud/heart/withered_half_blinking"),
         Identifier.withDefaultNamespace("hud/heart/withered_hardcore_full"),
         Identifier.withDefaultNamespace("hud/heart/withered_hardcore_full_blinking"),
         Identifier.withDefaultNamespace("hud/heart/withered_hardcore_half"),
         Identifier.withDefaultNamespace("hud/heart/withered_hardcore_half_blinking")
      ),
      ABSORBING(
         Identifier.withDefaultNamespace("hud/heart/absorbing_full"),
         Identifier.withDefaultNamespace("hud/heart/absorbing_full_blinking"),
         Identifier.withDefaultNamespace("hud/heart/absorbing_half"),
         Identifier.withDefaultNamespace("hud/heart/absorbing_half_blinking"),
         Identifier.withDefaultNamespace("hud/heart/absorbing_hardcore_full"),
         Identifier.withDefaultNamespace("hud/heart/absorbing_hardcore_full_blinking"),
         Identifier.withDefaultNamespace("hud/heart/absorbing_hardcore_half"),
         Identifier.withDefaultNamespace("hud/heart/absorbing_hardcore_half_blinking")
      ),
      FROZEN(
         Identifier.withDefaultNamespace("hud/heart/frozen_full"),
         Identifier.withDefaultNamespace("hud/heart/frozen_full_blinking"),
         Identifier.withDefaultNamespace("hud/heart/frozen_half"),
         Identifier.withDefaultNamespace("hud/heart/frozen_half_blinking"),
         Identifier.withDefaultNamespace("hud/heart/frozen_hardcore_full"),
         Identifier.withDefaultNamespace("hud/heart/frozen_hardcore_full_blinking"),
         Identifier.withDefaultNamespace("hud/heart/frozen_hardcore_half"),
         Identifier.withDefaultNamespace("hud/heart/frozen_hardcore_half_blinking")
      );

      private final Identifier fullTexture;
      private final Identifier fullBlinkingTexture;
      private final Identifier halfTexture;
      private final Identifier halfBlinkingTexture;
      private final Identifier hardcoreFullTexture;
      private final Identifier hardcoreFullBlinkingTexture;
      private final Identifier hardcoreHalfTexture;
      private final Identifier hardcoreHalfBlinkingTexture;

      HudHeartType(
         Identifier fullTexture,
         Identifier fullBlinkingTexture,
         Identifier halfTexture,
         Identifier halfBlinkingTexture,
         Identifier hardcoreFullTexture,
         Identifier hardcoreFullBlinkingTexture,
         Identifier hardcoreHalfTexture,
         Identifier hardcoreHalfBlinkingTexture
      ) {
         this.fullTexture = fullTexture;
         this.fullBlinkingTexture = fullBlinkingTexture;
         this.halfTexture = halfTexture;
         this.halfBlinkingTexture = halfBlinkingTexture;
         this.hardcoreFullTexture = hardcoreFullTexture;
         this.hardcoreFullBlinkingTexture = hardcoreFullBlinkingTexture;
         this.hardcoreHalfTexture = hardcoreHalfTexture;
         this.hardcoreHalfBlinkingTexture = hardcoreHalfBlinkingTexture;
      }

      public Identifier getTexture(boolean hardcore, boolean half, boolean blinking) {
         if (!hardcore) {
            if (half) {
               return blinking ? this.halfBlinkingTexture : this.halfTexture;
            } else {
               return blinking ? this.fullBlinkingTexture : this.fullTexture;
            }
         } else if (half) {
            return blinking ? this.hardcoreHalfBlinkingTexture : this.hardcoreHalfTexture;
         } else {
            return blinking ? this.hardcoreFullBlinkingTexture : this.hardcoreFullTexture;
         }
      }

      static HudHeartType fromEntityState(LivingEntity livingEntity) {
         if (livingEntity.hasEffect(MobEffects.POISON)) {
            return POISONED;
         } else if (livingEntity.hasEffect(MobEffects.WITHER)) {
            return WITHERED;
         } else if (livingEntity.isFullyFrozen()) {
            return FROZEN;
         } else {
            return NORMAL;
         }
      }
   }
}

