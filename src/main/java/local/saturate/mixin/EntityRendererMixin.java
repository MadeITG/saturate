package local.saturate.mixin;

import local.saturate.enums.EstimationModeEnum;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.WeakHashMap;
import local.saturate.PlayerSaturationTracker;
import local.saturate.RenderTracker;
import local.saturate.compat.Compat;
import local.saturate.config.Config;
import local.saturate.config.ModConfig;
import local.saturate.enums.ArmorTypeEnum;
import local.saturate.enums.FoodTypeEnum;
import local.saturate.enums.HealthDisplayTypeEnum;
import local.saturate.enums.HeartTypeEnum;
import local.saturate.enums.SaturationTypeEnum;
import local.saturate.util.HeartJumpData;
import local.saturate.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.DisplaySlot;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class EntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> extends EntityRenderer<T, S> implements RenderLayerParent<S, M> {
   @Unique
   private final Minecraft client = Minecraft.getInstance();
   @Unique
   private static final WeakHashMap<LivingEntityRenderState, LivingEntity> ENTITY_MAP = new WeakHashMap<>();

   protected EntityRendererMixin(EntityRendererProvider.Context ctx) {
      super(ctx);
   }

   @Inject(
      method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
      at = @At("TAIL")
   )
   public void updateRenderState(T livingEntity, S livingEntityRenderState, float f, CallbackInfo ci) {
      ENTITY_MAP.put(livingEntityRenderState, livingEntity);
   }

   @Inject(
      method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
      at = @At("TAIL")
   )
   public void render(S livingEntityRenderState, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState, CallbackInfo ci) {
      LivingEntity livingEntity = ENTITY_MAP.get(livingEntityRenderState);
      if (livingEntity != null && (RenderTracker.isInUUIDS(livingEntity) || (Config.getOverrideAllFiltersEnabled() && !RenderTracker.isInvalid(livingEntity)))) {
         ModConfig config = ModConfig.HANDLER.instance();
         double verticalOffset = 0.0;
         boolean isNumberMode = config.indicator_type == HealthDisplayTypeEnum.NUMBER
            || (config.indicator_type == HealthDisplayTypeEnum.DYNAMIC && livingEntity.getMaxHealth() > (float) config.dynamic_health_threshold);

         if (Config.getHeartsRenderingEnabled() || Config.getOverrideAllFiltersEnabled()) {
            if (isNumberMode) {
               verticalOffset += this.renderNumber(livingEntity, livingEntityRenderState.bodyRot, 0.0F, poseStack, submitNodeCollector, verticalOffset);
            } else {
               verticalOffset += this.renderHearts(livingEntity, livingEntityRenderState.bodyRot, 0.0F, poseStack, submitNodeCollector);
            }
         }

         if (livingEntity instanceof Player player && ((Config.getSaturationRenderingEnabled() && config.show_saturation_for_players) || Config.getOverrideAllFiltersEnabled())) {
            verticalOffset += this.renderSaturationPoints(player, livingEntityRenderState.bodyRot, 0.0F, poseStack, submitNodeCollector, verticalOffset);
         }

         if (livingEntity instanceof Player foodPlayer && config.show_food_for_players && (Config.getSaturationRenderingEnabled() || Config.getOverrideAllFiltersEnabled())) {
            verticalOffset += this.renderFoodPoints(foodPlayer, livingEntityRenderState.bodyRot, 0.0F, poseStack, submitNodeCollector, verticalOffset);
         }

         if (Config.getArmorRenderingEnabled() || Config.getOverrideAllFiltersEnabled()) {
            this.renderArmorPoints(livingEntity, livingEntityRenderState.bodyRot, 0.0F, poseStack, submitNodeCollector, verticalOffset);
         }
      }
   }

   @Unique
   @SuppressWarnings("unchecked")
   private double renderHearts(LivingEntity livingEntity, float yaw, float tickDelta, PoseStack matrixStack, SubmitNodeCollector orderedRenderCommandQueue) {
      ModConfig config = ModConfig.HANDLER.instance();
      double d = this.entityRenderDispatcher.distanceToSqr(livingEntity);
      T entAsT = (T) livingEntity;
      float effectiveHp = PlayerSaturationTracker.getEffectiveHealth(livingEntity);
      float effectiveAbsorption = PlayerSaturationTracker.getEffectiveAbsorption(livingEntity);
      boolean showUncertainty = config.show_uncertainty_indicator && PlayerSaturationTracker.hasWideUncertainty(livingEntity);
      int rawMaxHealth = Math.max(1, Mth.ceil(livingEntity.getMaxHealth()));
      int healthRed = Mth.ceil(effectiveHp);
      int minHealthCertain = Mth.floor(PlayerSaturationTracker.getHealthUncertaintyMin(livingEntity));
      int maxHealth = rawMaxHealth;
      int healthYellow = Mth.ceil(effectiveAbsorption);
      if (config.percentage_based_health) {
         healthRed = Mth.ceil((float) healthRed / (float) rawMaxHealth * (float) config.max_health);
         minHealthCertain = Mth.floor((float) minHealthCertain / (float) rawMaxHealth * (float) config.max_health);
         maxHealth = Mth.ceil((float) config.max_health);
         healthYellow = Mth.ceil(effectiveAbsorption);
      }

      int heartsRed = Mth.ceil((float) healthRed / 2.0F);
      int certainRedHearts = showUncertainty ? Math.max(0, Math.min(heartsRed - 1, minHealthCertain / 2)) : heartsRed;
      boolean lastRedHalf = (healthRed & 1) == 1;
      int heartsNormal = Mth.ceil((float) maxHealth / 2.0F);
      int heartsYellow = Mth.ceil((float) healthYellow / 2.0F);
      boolean lastYellowHalf = (healthYellow & 1) == 1;
      int heartsTotal = heartsNormal + heartsYellow;
      int heartsPerRow = Math.max(1, config.icons_per_row);
      int pixelsTotal = Math.min(heartsTotal, heartsPerRow) * 8 + 1;
      float maxX = (float) pixelsTotal / 2.0F;
      float scale = config.size;
      double heartDensity = 50.0 - Math.max(4.0 - Math.ceil((double) heartsTotal / (double) heartsPerRow), -3.0) * 5.0;
      double h = 0.0;
      double maxRowH = 0.0;
      boolean shouldRenderThroughWalls = config.show_through_walls && RenderTracker.isOkayToRenderThroughWalls(livingEntity);

      for (int isDrawingEmpty = 0; isDrawingEmpty < 2; ++isDrawingEmpty) {
         OrderedSubmitNodeCollector targetQueue = shouldRenderThroughWalls ? orderedRenderCommandQueue.order(isDrawingEmpty) : orderedRenderCommandQueue;

         for (int heart = 0; heart < heartsTotal; ++heart) {
            if (heart % heartsPerRow == 0) {
               h = (double) heart / heartDensity;
               if (h > maxRowH) {
                  maxRowH = h;
               }
            }

            matrixStack.pushPose();
            matrixStack.translate(0.0, (double) (livingEntity.getBbHeight() + 0.5F) + h, 0.0);
            if (livingEntity.hasEffect(MobEffects.REGENERATION) && config.show_heart_effects && HeartJumpData.getWhichHeartJumping(livingEntity) == heart) {
               matrixStack.translate(0.0, 1.15F * scale, 0.0);
            }

            if ((this.shouldShowName(entAsT, d) || (config.force_higher_offset_for_players && livingEntity instanceof Player && livingEntity != this.client.player)) && d <= 4096.0) {
               matrixStack.translate(0.0, 10.349999F * scale, 0.0);
               if (d < 100.0 && livingEntity instanceof Player && livingEntity.level().getScoreboard().getDisplayObjective(DisplaySlot.BELOW_NAME) != null) {
                  matrixStack.translate(0.0, 10.349999F * scale, 0.0);
               }
            }

            Compat.applyCameraRotation(matrixStack, this.entityRenderDispatcher);
            matrixStack.scale(-scale, scale, scale);
            matrixStack.translate(0.0, config.display_offset, 0.0);
            float x = maxX - (float) (heart % heartsPerRow * 8);
            if (isDrawingEmpty == 0) {
               HeartTypeEnum type = HeartTypeEnum.EMPTY;
               Identifier heartTextureId = config.use_vanilla_textures
                  ? Identifier.fromNamespaceAndPath("saturate", "textures/gui/heart/" + type.icon + ".png")
                  : Identifier.fromNamespaceAndPath("minecraft", "textures/gui/sprites/hud/heart/" + type.icon + ".png");
               RenderType renderLayer = shouldRenderThroughWalls ? RenderTypes.textSeeThrough(heartTextureId) : RenderTypes.text(heartTextureId);
               float opacity = (float) config.health_bar_opacity / 100.0F;
               targetQueue.submitCustomGeometry(matrixStack, renderLayer, (matricesEntry, vertexConsumer) -> {
                  Matrix4f m = matricesEntry.pose();
                  RenderUtils.drawHeart(m, vertexConsumer, x, type, livingEntity, opacity, d, shouldRenderThroughWalls);
               });
            } else {
               final HeartTypeEnum type;
               if (heart < heartsRed) {
                  type = (heart == heartsRed - 1 && lastRedHalf) ? HeartTypeEnum.RED_HALF : HeartTypeEnum.RED_FULL;
               } else if (heart < heartsNormal) {
                  type = HeartTypeEnum.EMPTY;
               } else {
                  type = (heart == heartsTotal - 1 && lastYellowHalf) ? HeartTypeEnum.YELLOW_HALF : HeartTypeEnum.YELLOW_FULL;
               }

               if (type != HeartTypeEnum.EMPTY) {
                  String additionalIconEffects = "";
                  if (type != HeartTypeEnum.YELLOW_FULL && type != HeartTypeEnum.YELLOW_HALF && config.show_heart_effects) {
                     additionalIconEffects = HeartTypeEnum.addStatusIcon(livingEntity) + HeartTypeEnum.addHardcoreIcon(livingEntity);
                  }

                  Identifier heartTextureId = config.use_vanilla_textures
                     ? Identifier.fromNamespaceAndPath("saturate", "textures/gui/heart/" + additionalIconEffects + type.icon + ".png")
                     : Identifier.fromNamespaceAndPath("minecraft", "textures/gui/sprites/hud/heart/" + additionalIconEffects + type.icon + ".png");
                  RenderType renderLayer = shouldRenderThroughWalls ? RenderTypes.textSeeThrough(heartTextureId) : RenderTypes.text(heartTextureId);
                  float baseOpacity = (float) config.health_bar_opacity / 100.0F;
                  boolean isUncertainRedHeart = showUncertainty
                     && (type == HeartTypeEnum.RED_FULL || type == HeartTypeEnum.RED_HALF)
                     && heart >= certainRedHearts;
                  float opacity = isUncertainRedHeart ? (baseOpacity * 0.45F) : baseOpacity;
                  targetQueue.submitCustomGeometry(matrixStack, renderLayer, (matricesEntry, vertexConsumer) -> {
                     Matrix4f m = matricesEntry.pose();
                     RenderUtils.drawHeart(m, vertexConsumer, x, type, livingEntity, opacity, d, shouldRenderThroughWalls);
                  });
               }
            }

            matrixStack.popPose();
         }
      }

      return maxRowH + (double) (scale * 10.0F);
   }

   @Unique
   @SuppressWarnings("unchecked")
   private double renderNumber(LivingEntity livingEntity, float yaw, float tickDelta, PoseStack matrixStack, SubmitNodeCollector orderedRenderCommandQueue, double baseOffset) {
      ModConfig config = ModConfig.HANDLER.instance();
      double d = this.entityRenderDispatcher.distanceToSqr(livingEntity);
      T entAsT = (T) livingEntity;
      String healthText = RenderUtils.getHealthText(livingEntity);
      boolean shouldRenderThroughWalls = config.show_through_walls && RenderTracker.isOkayToRenderThroughWalls(livingEntity);
      matrixStack.pushPose();
      float scale = config.size;
      matrixStack.translate(0.0, (double) (livingEntity.getBbHeight() + 0.5F) + baseOffset, 0.0);
      if ((this.shouldShowName(entAsT, d) || (config.force_higher_offset_for_players && livingEntity instanceof Player && livingEntity != this.client.player)) && d <= 4096.0) {
         matrixStack.translate(0.0, 10.349999F * scale, 0.0);
         if (d < 100.0 && livingEntity instanceof Player && livingEntity.level().getScoreboard().getDisplayObjective(DisplaySlot.BELOW_NAME) != null) {
            matrixStack.translate(0.0, 10.349999F * scale, 0.0);
         }
      }

      Compat.applyCameraRotation(matrixStack, this.entityRenderDispatcher);
      matrixStack.scale(scale, -scale, scale);
      matrixStack.translate(0.0, -config.display_offset, 0.0);
      Font textRenderer = Minecraft.getInstance().font;
      float x = (float) (-textRenderer.width(healthText)) / 2.0F;
      Font.DisplayMode textLayerType = shouldRenderThroughWalls ? DisplayMode.SEE_THROUGH : DisplayMode.NORMAL;
      int backgroundColor = config.render_number_display_background_color ? config.number_display_background_color.getRGB() : 0;
      int textColor = config.number_color.getRGB();
      int opacity = config.health_bar_opacity;
      textColor = (textColor & 0xFFFFFF) | ((opacity * 255 / 100) << 24);
      orderedRenderCommandQueue.submitText(matrixStack, x, 0.0F, Component.literal(healthText).getVisualOrderText(), config.render_number_display_shadow, textLayerType, 15728880, textColor, backgroundColor, 0);
      matrixStack.popPose();
      return scale * 10.0F;
   }

   @Unique
   @SuppressWarnings("unchecked")
   private double renderSaturationPoints(Player player, float yaw, float tickDelta, PoseStack matrixStack, SubmitNodeCollector orderedRenderCommandQueue, double baseOffset) {
      ModConfig config = ModConfig.HANDLER.instance();
      boolean known = PlayerSaturationTracker.isSaturationKnown(player);
      if (!known) {
         if (!config.show_unknown_saturation_faded || PlayerSaturationTracker.getEstimationMode() == EstimationModeEnum.NEVER) {
            return 0.0;
         }
      }

      double d = this.entityRenderDispatcher.distanceToSqr(player);
      T entAsT = (T) player;
      int saturation = known ? Mth.ceil(PlayerSaturationTracker.getSaturation(player)) : 0;
      int saturationIcons = Mth.ceil((float) saturation / 2.0F);
      boolean lastPointHalf = (saturation & 1) == 1;
      int pointsTotal = 10;
      int pointsPerRow = Math.max(1, config.icons_per_row);
      int pixelsTotal = Math.min(pointsTotal, pointsPerRow) * 8 + 1;
      float maxX = (float) pixelsTotal / 2.0F;
      float scale = config.size;
      boolean shouldRenderThroughWalls = config.show_through_walls && RenderTracker.isOkayToRenderThroughWalls(player);
      double h = 0.0;
      double maxRowH = 0.0;

      for (int isDrawingEmpty = 0; isDrawingEmpty < (known ? 2 : 1); ++isDrawingEmpty) {
         OrderedSubmitNodeCollector targetQueue = shouldRenderThroughWalls ? orderedRenderCommandQueue.order(isDrawingEmpty + 2) : orderedRenderCommandQueue;

         for (int pointCount = 0; pointCount < pointsTotal; ++pointCount) {
            if (pointCount % pointsPerRow == 0) {
               h = (double) (pointCount / pointsPerRow) * (double) (scale * 10.0F);
               if (h > maxRowH) {
                  maxRowH = h;
               }
            }

            matrixStack.pushPose();
            matrixStack.translate(0.0, (double) (player.getBbHeight() + 0.5F) + baseOffset + h, 0.0);
            if ((this.shouldShowName(entAsT, d) || (config.force_higher_offset_for_players && player != this.client.player)) && d <= 4096.0) {
               matrixStack.translate(0.0, 10.349999F * scale, 0.0);
               if (d < 100.0 && player.level().getScoreboard().getDisplayObjective(DisplaySlot.BELOW_NAME) != null) {
                  matrixStack.translate(0.0, 10.349999F * scale, 0.0);
               }
            }

            Compat.applyCameraRotation(matrixStack, this.entityRenderDispatcher);
            matrixStack.scale(-scale, scale, scale);
            matrixStack.translate(0.0, config.display_offset, 0.0);
            float x = maxX - (float) (pointCount % pointsPerRow * 8);
            SaturationTypeEnum type = isDrawingEmpty == 0
               ? SaturationTypeEnum.EMPTY
               : (pointCount < saturationIcons ? (pointCount == saturationIcons - 1 && lastPointHalf ? SaturationTypeEnum.HALF : SaturationTypeEnum.FULL) : null);

            if (type != null) {
               Identifier textureId = (type == SaturationTypeEnum.EMPTY && !config.use_vanilla_textures) ? type.vanillaContainerIcon : type.icon;
               RenderType renderLayer = shouldRenderThroughWalls ? RenderTypes.textSeeThrough(textureId) : RenderTypes.text(textureId);
               float baseOpacity = (float) config.health_bar_opacity / 100.0F;
               float opacity = !known ? (baseOpacity * 0.25F) : baseOpacity;
               targetQueue.submitCustomGeometry(matrixStack, renderLayer, (matricesEntry, vertexConsumer) -> {
                  Matrix4f m = matricesEntry.pose();
                  RenderUtils.drawSaturation(m, vertexConsumer, x, type, opacity, d, shouldRenderThroughWalls);
               });
            }

            matrixStack.popPose();
         }
      }

      if (!known) {
         matrixStack.pushPose();
         matrixStack.translate(0.0, (double) (player.getBbHeight() + 0.5F) + baseOffset, 0.0);
         if ((this.shouldShowName(entAsT, d) || (config.force_higher_offset_for_players && player != this.client.player)) && d <= 4096.0) {
            matrixStack.translate(0.0, 10.349999F * scale, 0.0);
            if (d < 100.0 && player.level().getScoreboard().getDisplayObjective(DisplaySlot.BELOW_NAME) != null) {
               matrixStack.translate(0.0, 10.349999F * scale, 0.0);
            }
         }
         Compat.applyCameraRotation(matrixStack, this.entityRenderDispatcher);
         matrixStack.scale(scale, -scale, scale);
         matrixStack.translate(0.0, -config.display_offset, 0.0);
         Font font = Minecraft.getInstance().font;
         float qX = (float) (-font.width("?")) / 2.0F;
         Font.DisplayMode textLayerType = shouldRenderThroughWalls ? DisplayMode.SEE_THROUGH : DisplayMode.NORMAL;
         int qColor = 0xAAFFAA00;
         orderedRenderCommandQueue.submitText(matrixStack, qX, 0.0F, Component.literal("?").getVisualOrderText(), false, textLayerType, 15728880, qColor, 0, 0);
         matrixStack.popPose();
      }

      return maxRowH + (double) (scale * 10.0F);
   }

   @Unique
   @SuppressWarnings("unchecked")
   private double renderFoodPoints(Player player, float yaw, float tickDelta, PoseStack matrixStack, SubmitNodeCollector orderedRenderCommandQueue, double baseOffset) {
      ModConfig config = ModConfig.HANDLER.instance();
      if (!PlayerSaturationTracker.isFoodKnown(player)) {
         return 0.0;
      }
      double d = this.entityRenderDispatcher.distanceToSqr(player);
      T entAsT = (T) player;
      int food = Mth.clamp(PlayerSaturationTracker.getFoodLevel(player), 0, 20);
      int foodIcons = Mth.ceil((float) food / 2.0F);
      boolean lastPointHalf = (food & 1) == 1;
      boolean foodUncertain = config.show_uncertainty_indicator && PlayerSaturationTracker.hasWideFoodUncertainty(player);
      int minFoodCertain = Mth.clamp(PlayerSaturationTracker.getFoodMin(player), 0, 20);
      int certainFoodIcons = foodUncertain ? Math.min(foodIcons, minFoodCertain / 2) : foodIcons;
      int pointsTotal = 10;
      int pointsPerRow = Math.max(1, config.icons_per_row);
      int pixelsTotal = Math.min(pointsTotal, pointsPerRow) * 8 + 1;
      float maxX = (float) pixelsTotal / 2.0F;
      float scale = config.size;
      boolean shouldRenderThroughWalls = config.show_through_walls && RenderTracker.isOkayToRenderThroughWalls(player);
      double h = 0.0;
      double maxRowH = 0.0;

      for (int isDrawingEmpty = 0; isDrawingEmpty < 2; ++isDrawingEmpty) {
         OrderedSubmitNodeCollector targetQueue = shouldRenderThroughWalls ? orderedRenderCommandQueue.order(isDrawingEmpty + 2) : orderedRenderCommandQueue;

         for (int pointCount = 0; pointCount < pointsTotal; ++pointCount) {
            if (pointCount % pointsPerRow == 0) {
               h = (double) (pointCount / pointsPerRow) * (double) (scale * 10.0F);
               if (h > maxRowH) {
                  maxRowH = h;
               }
            }

            matrixStack.pushPose();
            matrixStack.translate(0.0, (double) (player.getBbHeight() + 0.5F) + baseOffset + h, 0.0);
            if ((this.shouldShowName(entAsT, d) || (config.force_higher_offset_for_players && player != this.client.player)) && d <= 4096.0) {
               matrixStack.translate(0.0, 10.349999F * scale, 0.0);
               if (d < 100.0 && player.level().getScoreboard().getDisplayObjective(DisplaySlot.BELOW_NAME) != null) {
                  matrixStack.translate(0.0, 10.349999F * scale, 0.0);
               }
            }

            Compat.applyCameraRotation(matrixStack, this.entityRenderDispatcher);
            matrixStack.scale(-scale, scale, scale);
            matrixStack.translate(0.0, config.display_offset, 0.0);
            float x = maxX - (float) (pointCount % pointsPerRow * 8);
            FoodTypeEnum type = isDrawingEmpty == 0
               ? FoodTypeEnum.EMPTY
               : (pointCount < foodIcons ? (pointCount == foodIcons - 1 && lastPointHalf ? FoodTypeEnum.HALF : FoodTypeEnum.FULL) : null);

            if (type != null) {
               Identifier textureId = type.icon;
               RenderType renderLayer = shouldRenderThroughWalls ? RenderTypes.textSeeThrough(textureId) : RenderTypes.text(textureId);
               float baseOpacity = (float) config.health_bar_opacity / 100.0F;
               float opacity = (type != FoodTypeEnum.EMPTY && foodUncertain && pointCount >= certainFoodIcons) ? (baseOpacity * 0.45F) : baseOpacity;
               targetQueue.submitCustomGeometry(matrixStack, renderLayer, (matricesEntry, vertexConsumer) -> {
                  Matrix4f m = matricesEntry.pose();
                  RenderUtils.drawSaturation(m, vertexConsumer, x, type.geometryType(), opacity, d, shouldRenderThroughWalls);
               });
            }

            matrixStack.popPose();
         }
      }

      return maxRowH + (double) (scale * 10.0F);
   }

   @Unique
   @SuppressWarnings("unchecked")
   private void renderArmorPoints(LivingEntity livingEntity, float yaw, float tickDelta, PoseStack matrixStack, SubmitNodeCollector orderedRenderCommandQueue, double baseOffset) {
      ModConfig config = ModConfig.HANDLER.instance();
      double d = this.entityRenderDispatcher.distanceToSqr(livingEntity);
      T entAsT = (T) livingEntity;
      int armor = Mth.ceil((float) PlayerSaturationTracker.getEffectiveArmor(livingEntity));
      if (armor != 0) {
         int armorPoints = Mth.ceil((float) armor / 2.0F);
         boolean lastPointHalf = (armor & 1) == 1;
         int pointsTotal = 10;
         int pointsPerRow = Math.max(1, config.icons_per_row);
         int pixelsTotal = Math.min(pointsTotal, pointsPerRow) * 8 + 1;
         float maxX = (float) pixelsTotal / 2.0F;
         float scale = config.size;
         boolean shouldRenderThroughWalls = config.show_through_walls && RenderTracker.isOkayToRenderThroughWalls(livingEntity);
         double h = 0.0;

         for (int isDrawingEmpty = 0; isDrawingEmpty < 2; ++isDrawingEmpty) {
            OrderedSubmitNodeCollector targetQueue = shouldRenderThroughWalls ? orderedRenderCommandQueue.order(isDrawingEmpty + 4) : orderedRenderCommandQueue;

            for (int pointCount = 0; pointCount < pointsTotal; ++pointCount) {
               if (pointCount % pointsPerRow == 0) {
                  h = (double) (pointCount / pointsPerRow) * (double) (scale * 10.0F);
               }

               matrixStack.pushPose();
               matrixStack.translate(0.0, (double) (livingEntity.getBbHeight() + 0.5F) + baseOffset + h, 0.0);
               if ((this.shouldShowName(entAsT, d) || (config.force_higher_offset_for_players && livingEntity instanceof Player && livingEntity != this.client.player)) && d <= 4096.0) {
                  matrixStack.translate(0.0, 10.349999F * scale, 0.0);
                  if (d < 100.0 && livingEntity instanceof Player && livingEntity.level().getScoreboard().getDisplayObjective(DisplaySlot.BELOW_NAME) != null) {
                     matrixStack.translate(0.0, 10.349999F * scale, 0.0);
                  }
               }

               Compat.applyCameraRotation(matrixStack, this.entityRenderDispatcher);
               matrixStack.scale(-scale, scale, scale);
               matrixStack.translate(0.0, config.display_offset, 0.0);
               float x = maxX - (float) (pointCount % pointsPerRow * 8);
               ArmorTypeEnum type = isDrawingEmpty == 0 ? ArmorTypeEnum.EMPTY : (pointCount < armorPoints ? (pointCount == armorPoints - 1 && lastPointHalf ? ArmorTypeEnum.HALF : ArmorTypeEnum.FULL) : null);
               if (type != null) {
                  Identifier armorTextureId = config.use_vanilla_textures ? type.vanillaIcon : type.icon;
                  RenderType renderLayer = shouldRenderThroughWalls ? RenderTypes.textSeeThrough(armorTextureId) : RenderTypes.text(armorTextureId);
                  float opacity = (float) config.health_bar_opacity / 100.0F;
                  targetQueue.submitCustomGeometry(matrixStack, renderLayer, (matricesEntry, vertexConsumer) -> {
                     Matrix4f m = matricesEntry.pose();
                     RenderUtils.drawArmor(m, vertexConsumer, x, type, opacity, d, shouldRenderThroughWalls);
                  });
               }

               matrixStack.popPose();
            }
         }
      }
   }
}
