package local.saturate;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import local.saturate.config.ModConfig;
import local.saturate.enums.EstimationModeEnum;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

public final class PlayerSaturationTracker {
   private static final EquipmentSlot[] ARMOR_SLOTS = new EquipmentSlot[]{
      EquipmentSlot.HEAD,
      EquipmentSlot.CHEST,
      EquipmentSlot.LEGS,
      EquipmentSlot.FEET,
      EquipmentSlot.BODY
   };

   private static final EquipmentSlot[] HUMANOID_ARMOR_SLOTS = new EquipmentSlot[]{
      EquipmentSlot.HEAD,
      EquipmentSlot.CHEST,
      EquipmentSlot.LEGS,
      EquipmentSlot.FEET
   };

   // --- Calibrated estimation & uncertainty constants (verified against MC 26.1.2 CombatRules & FoodData) ---
   /** Confidence-interval width [maxHp - minHp] above which '~' or translucent hearts are displayed. */
   public static final float UNCERTAINTY_VISUAL_THRESHOLD_HP = 3.0F;
   /** Anomalies required on a single entity before per-entity estimation turns on without global AHI fingerprints. */
   private static final int ANOMALY_THRESHOLD = 3;
   /** Vanilla sprint needs food > 6 (FoodData.hasEnoughFood). */
   private static final int FOOD_SPRINT_MIN_LEVEL = 7;
   /** Food interval width (hunger points) from which the food bar is drawn as uncertain. */
   private static final int FOOD_UNCERTAINTY_VISUAL_THRESHOLD = 4;
   /** Slow regen heals every 80 ticks; +10 margin before concluding food < 18. */
   private static final long FOOD_QUIET_WINDOW_TICKS = 90L;
   /** Sharpness bonus (+0.5*lvl + 0.5) when AHI hides enchantments: Sharp IV = +2.5, Sharp V = +3.0, Sharp III = +2.0. */
   private static final float SPOOFED_SHARP_CONFIRMED_MEAN = 2.75F;
   private static final float SPOOFED_SHARP_UNCONFIRMED_MEAN = 2.25F;
   private static final float SPOOFED_SHARP_MIN = 0.0F;
   private static final float SPOOFED_SHARP_CONFIRMED_MIN = 1.5F;
   private static final float SPOOFED_SHARP_MAX = 3.0F;
   /** Protection EPF per enchanted armor piece under AHI: Prot IV = 4 (meta Diamond/Netherite), lower tiers = 2.5. */
   private static final float SPOOFED_PROT_HIGH_TIER_MEAN = 3.75F;
   private static final float SPOOFED_PROT_LOW_TIER_MEAN = 2.5F;
   private static final float SPOOFED_PROT_MIN_EPF = 1.0F;
   private static final float SPOOFED_PROT_MAX_EPF = 4.0F;
   /** Conservative fallback when PLAYER_BURP fires but the eaten item was already swapped out of sight. */
   private static final int UNKNOWN_FOOD_NUTRITION_FALLBACK = 2;
   private static final float UNKNOWN_FOOD_SATURATION_FALLBACK = 1.2F;

   private record SaturationSnapshot(
      float saturation,
      int foodMin,
      int foodEst,
      int foodMax,
      float exhaustion,
      boolean foodExact,
      boolean satZeroConfirmed,
      long gameTime
   ) {}

   private static final ConcurrentHashMap<UUID, TrackedPlayerState> PLAYER_STATES = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<UUID, TrackedMobState> MOB_STATES = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<BlockPos, MiningEntry> ACTIVE_MINERS = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<UUID, Long> PENDING_RESPAWN = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<UUID, SaturationSnapshot> SATURATION_SNAPSHOTS = new ConcurrentHashMap<>();
   private static final long RESPAWN_WINDOW_TICKS = 600L;
   private static final long SNAPSHOT_TTL_TICKS = 1200L;

   /** Latched ONLY on unambiguous AHI fingerprints (airSupply==1 on land, Fortune on armor/sword, NaN sat, HEARTS=-1). */
   private static volatile boolean serverHealthObfuscated = false;
   private static volatile boolean serverEquipmentObfuscated = false;
   private static volatile boolean serverHasNaturalRegen = false;
   private static volatile Difficulty currentDifficulty = Difficulty.NORMAL;
   private static ClientPacketListener lastConnection = null;

   private PlayerSaturationTracker() {
   }

   public static void onDifficultyChange(Difficulty difficulty) {
      if (difficulty != null) {
         currentDifficulty = difficulty;
         Minecraft client = Minecraft.getInstance();
         long gameTime = client.level != null ? client.level.getGameTime() : 0L;
         DebugLogger.logEvent(
            gameTime, "DIFFICULTY_CHANGE", "-",
            20.0F, 20.0F, 20.0F, 20.0F,
            5.0F, 20, 20, 0.0F, 1.0F, serverHealthObfuscated,
            "newDifficulty=" + difficulty.name()
         );
      }
   }

   public static Difficulty getDifficulty() {
      Minecraft client = Minecraft.getInstance();
      if (client.level != null && client.level.getLevelData() != null) {
         try {
            Difficulty d = client.level.getLevelData().getDifficulty();
            if (d != null) {
               currentDifficulty = d;
               return d;
            }
         } catch (Throwable ignored) {
         }
      }
      return currentDifficulty;
   }

   public static boolean isServerHealthObfuscated() {
      return serverHealthObfuscated;
   }

   public static boolean isServerEquipmentObfuscated() {
      return serverEquipmentObfuscated;
   }

   public static float getSaturation(Player player) {
      Minecraft client = Minecraft.getInstance();
      MinecraftServer integratedServer = client.getSingleplayerServer();
      if (integratedServer != null && getEstimationMode() != EstimationModeEnum.ALWAYS) {
         ServerPlayer serverPlayer = integratedServer.getPlayerList().getPlayer(player.getUUID());
         if (serverPlayer != null) {
            return Mth.clamp(serverPlayer.getFoodData().getSaturationLevel(), 0.0F, 20.0F);
         }
      }

      if (player == client.player) {
         float rawSat = player.getFoodData().getSaturationLevel();
         if (!Float.isNaN(rawSat)) {
            float clamped = Mth.clamp(rawSat, 0.0F, 20.0F);
            TrackedPlayerState selfState = PLAYER_STATES.get(player.getUUID());
            if (selfState != null) {
               selfState.saturationLevel = clamped;
               selfState.saturationKnown = true;
               if (clamped <= 0.0F) {
                  selfState.exhaustion = 0.0F;
               }
            }
            return clamped;
         }

         // AHI FoodSaturationSpoofer sends Float.NaN iff saturation > 0.0F, and leaves 0.0F untouched.
         serverHealthObfuscated = true;
         TrackedPlayerState selfState = PLAYER_STATES.get(player.getUUID());
         float tracked = selfState != null ? selfState.saturationLevel : 5.0F;
         if (Float.isNaN(tracked)) {
            tracked = 5.0F;
         }
         return Mth.clamp(Math.max(0.5F, tracked), 0.5F, (float) player.getFoodData().getFoodLevel());
      }

      if (getEstimationMode() == EstimationModeEnum.NEVER) {
         return 0.0F;
      }

      TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
      if (state == null || !state.saturationKnown || Float.isNaN(state.saturationLevel)) {
         return 0.0F;
      }
      // Round estimated saturation to 0.5 steps to avoid false precision
      float rounded = Math.round(state.saturationLevel * 2.0F) / 2.0F;
      // saturation can never exceed the (estimated) food level
      return Mth.clamp(rounded, 0.0F, (float) Math.min(20, state.foodLevelEst));
   }

   public static boolean isSaturationKnown(Player player) {
      if (player == null) {
         return false;
      }
      Minecraft client = Minecraft.getInstance();
      if (player == client.player) {
         return true;
      }
      if (client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS) {
         return true;
      }
      if (getEstimationMode() == EstimationModeEnum.NEVER) {
         return false;
      }
      TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
      return state != null && state.saturationKnown;
   }

   public static boolean isFoodKnown(Player player) {
      if (player == null) {
         return false;
      }
      Minecraft client = Minecraft.getInstance();
      if (player == client.player) {
         return true;
      }
      if (client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS) {
         return true;
      }
      if (getEstimationMode() == EstimationModeEnum.NEVER) {
         return false;
      }
      TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
      return state != null && state.foodKnown;
   }

   public static boolean isFoodExact(Player player) {
      if (player == null) {
         return false;
      }
      Minecraft client = Minecraft.getInstance();
      if (player == client.player) {
         return true;
      }
      if (client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS) {
         return true;
      }
      if (getEstimationMode() == EstimationModeEnum.NEVER) {
         return false;
      }
      TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
      return state != null && state.foodExact;
   }

   /** Food level shown for a player: real value for self / integrated server, otherwise the tracked estimate. */
   public static int getFoodLevel(Player player) {
      int real = getRealFoodLevel(player);
      if (real >= 0) {
         return real;
      }
      if (getEstimationMode() == EstimationModeEnum.NEVER) {
         return 20;
      }
      TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
      return state != null ? Mth.clamp(state.foodLevelEst, 0, 20) : 20;
   }

   public static int getFoodMin(Player player) {
      int real = getRealFoodLevel(player);
      if (real >= 0) {
         return real;
      }
      TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
      return state != null ? state.foodLevelMin : 0;
   }

   public static int getFoodMax(Player player) {
      int real = getRealFoodLevel(player);
      if (real >= 0) {
         return real;
      }
      TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
      return state != null ? state.foodLevelMax : 20;
   }

   /** True when the food interval is wide enough that the bar should be drawn as uncertain. */
   public static boolean hasWideFoodUncertainty(Player player) {
      return getFoodMax(player) - getFoodMin(player) >= FOOD_UNCERTAINTY_VISUAL_THRESHOLD;
   }

   /** Exact food level when the client really knows it (self, integrated server); -1 when only an estimate exists. */
   public static int getRealFoodLevel(Player player) {
      Minecraft client = Minecraft.getInstance();
      MinecraftServer integratedServer = client.getSingleplayerServer();
      if (integratedServer != null && getEstimationMode() != EstimationModeEnum.ALWAYS) {
         ServerPlayer serverPlayer = integratedServer.getPlayerList().getPlayer(player.getUUID());
         if (serverPlayer != null) {
            return serverPlayer.getFoodData().getFoodLevel();
         }
      }
      if (player == client.player) {
         return player.getFoodData().getFoodLevel();
      }
      return -1;
   }

   /** Ground truth for debug comparison, only from an integrated server (even in ALWAYS mode); -1 if unavailable. */
   public static int getIntegratedServerFoodLevel(Player player) {
      MinecraftServer integratedServer = Minecraft.getInstance().getSingleplayerServer();
      if (integratedServer == null) {
         return -1;
      }
      ServerPlayer serverPlayer = integratedServer.getPlayerList().getPlayer(player.getUUID());
      return serverPlayer != null ? serverPlayer.getFoodData().getFoodLevel() : -1;
   }

   public static boolean isHealthEstimated(LivingEntity entity) {
      Minecraft client = Minecraft.getInstance();
      if (entity == null || entity == client.player) {
         return false;
      }

      EstimationModeEnum mode = getEstimationMode();
      if (mode == EstimationModeEnum.NEVER) {
         return false;
      }
      if (mode == EstimationModeEnum.ALWAYS) {
         return true;
      }

      if (client.getSingleplayerServer() != null) {
         return false;
      }
      if (!entity.isAlive() || entity.getHealth() <= 0.0F) {
         return false;
      }
      if (isNeverSpoofedByAhi(entity, client.player)) {
         return false;
      }

      if (entity instanceof Player player) {
         if (getScoreboardHealth(player, client.player) != null) {
            return false;
         }
         boolean fp = checkEntityObfuscationFingerprints(player);
         TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
         return serverHealthObfuscated || fp || (state != null && state.healthObfuscated) || Float.isNaN(player.getHealth());
      }

      // Note: AHI has players-only mode, so mobs only enter estimation if mob fingerprints or mob anomalies trigger
      boolean mobFp = checkMobObfuscationFingerprint(entity);
      TrackedMobState mobState = MOB_STATES.get(entity.getUUID());
      return mobFp || (mobState != null && mobState.healthObfuscated) || Float.isNaN(entity.getHealth());
   }

   public static float getEffectiveHealth(LivingEntity entity) {
      Minecraft client = Minecraft.getInstance();
      if (entity == client.player) {
         float hp = entity.getHealth();
         return Float.isNaN(hp) ? entity.getMaxHealth() : hp;
      }

      EstimationModeEnum mode = getEstimationMode();
      MinecraftServer integratedServer = client.getSingleplayerServer();
      if (integratedServer != null && mode != EstimationModeEnum.ALWAYS) {
         if (entity instanceof Player player) {
            ServerPlayer sp = integratedServer.getPlayerList().getPlayer(player.getUUID());
            if (sp != null) {
               return sp.getHealth();
            }
         } else {
            ServerLevel serverLevel = integratedServer.getLevel(entity.level().dimension());
            if (serverLevel != null) {
               Entity serverEntity = serverLevel.getEntity(entity.getUUID());
               if (serverEntity instanceof LivingEntity serverLiving) {
                  return serverLiving.getHealth();
               }
            }
         }
      }

      float rawHealth = entity.getHealth();
      if (!entity.isAlive() || rawHealth <= 0.0F) {
         return 0.0F;
      }

      if (mode == EstimationModeEnum.NEVER) {
         return Float.isNaN(rawHealth) ? entity.getMaxHealth() : Mth.clamp(rawHealth, 0.5F, entity.getMaxHealth());
      }

      if (entity instanceof Player player) {
         if (mode == EstimationModeEnum.AUTO) {
            Float scoreboardTotalHp = getScoreboardHealth(player, client.player);
            if (scoreboardTotalHp != null) {
               return Mth.clamp(scoreboardTotalHp, 0.5F, player.getMaxHealth());
            }
         }

         if (isHealthEstimated(player)) {
            TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
            float est = state != null ? state.estimatedHealth : player.getMaxHealth();
            // Point 3: Mostra al massimo "bassa vita" (<= 4.0 HP) solo se confermata da evidenze,
            // altrimenti mostra il punto medio dell'intervallo [min..max]
            if (state != null && est <= 4.0F && client.level != null && !state.isLowHealthConfirmed(client.level.getGameTime())) {
               est = (state.minEstimatedHealth + state.maxEstimatedHealth) / 2.0F;
            }
            // Round point estimate to nearest half-heart (0.5 HP) so numbers never fake 0.01 precision
            float rounded = Math.round(est * 2.0F) / 2.0F;
            return Mth.clamp(rounded, 0.5F, player.getMaxHealth());
         }
         return Mth.clamp(rawHealth, 0.5F, player.getMaxHealth());
      }

      if (isHealthEstimated(entity)) {
         TrackedMobState mobState = MOB_STATES.get(entity.getUUID());
         float est = mobState != null ? mobState.estimatedHealth : entity.getMaxHealth();
         float rounded = Math.round(est * 2.0F) / 2.0F;
         return Mth.clamp(rounded, 0.5F, entity.getMaxHealth());
      }
      return Mth.clamp(rawHealth, 0.5F, entity.getMaxHealth());
   }

   public static float getHealthUncertaintyMin(LivingEntity entity) {
      if (!isHealthEstimated(entity)) {
         return getEffectiveHealth(entity);
      }
      if (entity instanceof Player player) {
         TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
         return state != null ? Mth.clamp(state.minEstimatedHealth, 0.5F, player.getMaxHealth()) : player.getMaxHealth();
      }
      TrackedMobState mobState = MOB_STATES.get(entity.getUUID());
      return mobState != null ? Mth.clamp(mobState.minEstimatedHealth, 0.5F, entity.getMaxHealth()) : entity.getMaxHealth();
   }

   public static float getHealthUncertaintyMax(LivingEntity entity) {
      if (!isHealthEstimated(entity)) {
         return getEffectiveHealth(entity);
      }
      if (entity instanceof Player player) {
         TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
         return state != null ? Mth.clamp(state.maxEstimatedHealth, 0.5F, player.getMaxHealth()) : player.getMaxHealth();
      }
      TrackedMobState mobState = MOB_STATES.get(entity.getUUID());
      return mobState != null ? Mth.clamp(mobState.maxEstimatedHealth, 0.5F, entity.getMaxHealth()) : entity.getMaxHealth();
   }

   public static boolean hasWideUncertainty(LivingEntity entity) {
      if (!isHealthEstimated(entity)) {
         return false;
      }
      if (entity instanceof Player player) {
         TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
         if (state != null) {
            if (state.hitsSinceResync >= 3 || (state.maxEstimatedHealth - state.minEstimatedHealth) >= UNCERTAINTY_VISUAL_THRESHOLD_HP) {
               return true;
            }
         }
      }
      float spread = getHealthUncertaintyMax(entity) - getHealthUncertaintyMin(entity);
      return spread >= UNCERTAINTY_VISUAL_THRESHOLD_HP;
   }

   public static float getEffectiveAbsorption(LivingEntity entity) {
      Minecraft client = Minecraft.getInstance();
      if (entity == client.player) {
         float abs = entity.getAbsorptionAmount();
         return Float.isNaN(abs) ? 0.0F : Math.max(0.0F, abs);
      }

      MinecraftServer integratedServer = client.getSingleplayerServer();
      if (integratedServer != null && getEstimationMode() != EstimationModeEnum.ALWAYS && entity instanceof Player player) {
         ServerPlayer sp = integratedServer.getPlayerList().getPlayer(player.getUUID());
         if (sp != null) {
            return sp.getAbsorptionAmount();
         }
      }

      float rawAbsorption = entity.getAbsorptionAmount();
      if (Float.isNaN(rawAbsorption) || rawAbsorption < 0.0F) {
         rawAbsorption = 0.0F;
      }
      if (getEstimationMode() == EstimationModeEnum.NEVER) {
         return rawAbsorption;
      }

      if (entity instanceof Player player) {
         Float scoreboardTotalHp = getScoreboardHealth(player, client.player);
         float scoreboardExtra = (scoreboardTotalHp != null && scoreboardTotalHp > player.getMaxHealth())
            ? (scoreboardTotalHp - player.getMaxHealth())
            : 0.0F;

         TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
         float estimated = (state != null && state.absorptionTicks > 0) ? state.estimatedAbsorption : 0.0F;
         return Math.max(rawAbsorption, Math.max(scoreboardExtra, estimated));
      }

      TrackedMobState mobState = MOB_STATES.get(entity.getUUID());
      float estimatedMobAbs = (mobState != null && mobState.absorptionTicks > 0) ? mobState.estimatedAbsorption : 0.0F;
      return Math.max(rawAbsorption, estimatedMobAbs);
   }

   public static int getEffectiveArmor(LivingEntity entity) {
      int vanillaArmor = entity.getArmorValue();
      double computedArmor = computeEquipmentAttribute(entity, Attributes.ARMOR);
      return Math.max(vanillaArmor, Mth.floor(computedArmor));
   }

   public static void clear() {
      PLAYER_STATES.clear();
      MOB_STATES.clear();
      ACTIVE_MINERS.clear();
      PENDING_RESPAWN.clear();
      SATURATION_SNAPSHOTS.clear();
      serverHealthObfuscated = false;
      serverEquipmentObfuscated = false;
      serverHasNaturalRegen = false;
      currentDifficulty = Difficulty.NORMAL;
      lastConnection = null;
   }

   public static void removeEntity(Entity entity) {
      if (entity instanceof Player player) {
         TrackedPlayerState state = PLAYER_STATES.remove(player.getUUID());
         if (state != null) {
            long gameTime = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0L;
            if (player != Minecraft.getInstance().player) {
               if (state.isDead) {
                  PENDING_RESPAWN.put(player.getUUID(), gameTime);
               } else if (state.saturationKnown) {
                  SATURATION_SNAPSHOTS.put(
                     player.getUUID(),
                     new SaturationSnapshot(
                        state.saturationLevel,
                        state.foodLevelMin,
                        state.foodLevelEst,
                        state.foodLevelMax,
                        state.exhaustion,
                        state.foodExact,
                        state.satZeroConfirmed,
                        gameTime
                     )
                  );
               }
            }
            DebugLogger.logEvent(
               gameTime, "PLAYER_REMOVE", player.getScoreboardName(),
               state.estimatedHealth, state.minEstimatedHealth, state.maxEstimatedHealth,
               player.getHealth(), 0.0F, state.saturationLevel,
               state.foodLevelMin, state.foodLevelMax, state.lastDamageTaken, state.lastAttackerScale,
               isHealthEstimated(player), "removed_from_world"
            );
         }
      } else {
         MOB_STATES.remove(entity.getUUID());
      }
   }

   public static void onEntityDeath(LivingEntity entity) {
      if (entity instanceof Player player) {
         TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
         if (state != null) {
            state.onDeath(player);
         }
      } else {
         MOB_STATES.remove(entity.getUUID());
      }
   }

   public static void onPlayerRespawn(Player player) {
      TrackedPlayerState state = PLAYER_STATES.get(player.getUUID());
      if (state != null) {
         state.resetToRespawn(player);
      }
   }

   public static void tick(Minecraft client) {
      ClientPacketListener currentConnection = client.getConnection();
      if (currentConnection == null) {
         if (lastConnection != null || !PLAYER_STATES.isEmpty() || !MOB_STATES.isEmpty()) {
            clear();
            DebugLogger.close();
         }
         return;
      }

      if (currentConnection != lastConnection) {
         clear();
         lastConnection = currentConnection;
      }

      if (client.level == null || client.player == null) {
         MOB_STATES.clear();
         ACTIVE_MINERS.clear();
         return;
      }

      if (client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS) {
         return;
      }

      if (Float.isNaN(client.player.getFoodData().getSaturationLevel())) {
         serverHealthObfuscated = true;
      }

      long gameTime = client.level.getGameTime();

      TrackedPlayerState selfState = PLAYER_STATES.computeIfAbsent(client.player.getUUID(), uuid -> new TrackedPlayerState(client.player));
      selfState.tickSelf(client.player, gameTime);

      for (Player player : client.level.players()) {
         if (player == client.player) {
            continue;
         }
         checkEntityObfuscationFingerprints(player);
         TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), uuid -> new TrackedPlayerState(player));
         state.tickRemote(player, client.player, gameTime);
         if (gameTime % 100L == 0L) {
            int truth = getIntegratedServerFoodLevel(player);
            if (truth >= 0) {
               DebugLogger.logEvent(
                  gameTime, "FOOD_COMPARE", player.getScoreboardName(),
                  state.estimatedHealth, state.minEstimatedHealth, state.maxEstimatedHealth, player.getHealth(),
                  state.saturationLevel, state.foodLevelMin, state.foodLevelMax, state.lastDamageTaken, state.lastAttackerScale,
                  isHealthEstimated(player), "foodEst=" + state.foodLevelEst + " foodReal=" + truth + " inInterval=" + (truth >= state.foodLevelMin && truth <= state.foodLevelMax)
               );
            }
         }
      }

      for (Entity entity : client.level.entitiesForRendering()) {
         if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
            checkMobObfuscationFingerprint(living);
            TrackedMobState mobState = MOB_STATES.get(living.getUUID());
            if (mobState != null) {
               mobState.tick(living, gameTime);
            }
         }
      }

      if (gameTime % 100L == 0L) {
         UUID selfUuid = client.player.getUUID();
         // Use client.level.getPlayerByUUID so custom tab-list servers do not purge visible players
         PLAYER_STATES.keySet().removeIf(uuid -> !uuid.equals(selfUuid) && client.level.getPlayerByUUID(uuid) == null);
         PENDING_RESPAWN.entrySet().removeIf(entry -> gameTime - entry.getValue() > RESPAWN_WINDOW_TICKS);
         SATURATION_SNAPSHOTS.entrySet().removeIf(entry -> gameTime - entry.getValue().gameTime() > SNAPSHOT_TTL_TICKS);
         MOB_STATES.entrySet().removeIf(entry -> entry.getValue().staleTicks > 200);
         ACTIVE_MINERS.entrySet().removeIf(entry -> gameTime - entry.getValue().gameTime() > 120L);
      }
   }

   public static void onPlayerSwing(Player player) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null || (client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS)) {
         return;
      }
      long gameTime = client.level.getGameTime();
      TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), uuid -> new TrackedPlayerState(player));
      state.recordSwing(gameTime);
      // Attacco a vuoto / air-swing exhaustion = 0.1F
      if (player != client.player) {
         state.addExhaustion(0.1F);
      }
   }

   public static void onCombatAnimation(LivingEntity target, int action) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      long gameTime = client.level.getGameTime();
      if (target instanceof Player player) {
         TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), uuid -> new TrackedPlayerState(player));
         if (action == ClientboundAnimatePacket.CRITICAL_HIT) {
            state.lastReceivedCritAnimGameTime = gameTime;
         } else if (action == ClientboundAnimatePacket.MAGIC_CRITICAL_HIT) {
            state.lastReceivedMagicCritAnimGameTime = gameTime;
            if (state.lastAttackerUuid != null && gameTime - state.lastDamageGameTime <= 1) {
               TrackedPlayerState attackerState = PLAYER_STATES.get(state.lastAttackerUuid);
               if (attackerState != null) {
                  attackerState.confirmedSharpnessWeaponItem = attackerState.lastAttackWeaponItem;
               }
            }
         }
      }
   }

   public static void onAttackSound(SoundEvent sound, double x, double y, double z) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      long gameTime = client.level.getGameTime();
      Player closest = findClosestPlayerByServerPos(client, x, y, z, 16.0);
      if (closest == null) {
         return;
      }
      TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(closest.getUUID(), uuid -> new TrackedPlayerState(closest));
      state.lastAttackSoundGameTime = gameTime;
      if (sound == SoundEvents.PLAYER_ATTACK_CRIT) {
         state.lastAttackSoundHint = AttackSoundHint.CRIT;
      } else if (sound == SoundEvents.PLAYER_ATTACK_WEAK) {
         state.lastAttackSoundHint = AttackSoundHint.WEAK;
      } else {
         state.lastAttackSoundHint = AttackSoundHint.STRONG;
      }
   }

   public static void onShieldBlock(LivingEntity defender) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null || !(defender instanceof Player player)) {
         return;
      }
      TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), uuid -> new TrackedPlayerState(player));
      state.lastShieldBlockGameTime = client.level.getGameTime();
   }

   public static void onShieldBlockSound(double x, double y, double z) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      Player defender = findClosestPlayerByServerPos(client, x, y, z, 9.0);
      if (defender != null) {
         TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(defender.getUUID(), uuid -> new TrackedPlayerState(defender));
         state.lastShieldBlockGameTime = client.level.getGameTime();
      }
   }

   public static void onServerMovePacket(
      Player player,
      double packetX,
      double packetY,
      double packetZ,
      boolean packetOnGround,
      boolean isTeleport,
      boolean hasPosition
   ) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null || (client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS)) {
         return;
      }
      TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), uuid -> new TrackedPlayerState(player));
      state.onServerMove(player, packetX, packetY, packetZ, packetOnGround, isTeleport, hasPosition);
   }

   public static void onBlockDestructionProgress(int breakerEntityId, BlockPos pos, int progress) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null) {
         return;
      }
      Entity entity = client.level.getEntity(breakerEntityId);
      if (entity instanceof Player player) {
         ACTIVE_MINERS.put(pos.immutable(), new MiningEntry(player.getUUID(), client.level.getGameTime()));
      }
   }

   public static void onBlockBroken(BlockPos pos) {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null || (client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS)) {
         return;
      }
      MiningEntry miner = ACTIVE_MINERS.remove(pos);
      Player breaker = null;
      if (miner != null && client.level.getGameTime() - miner.gameTime <= 40L) {
         for (Player p : client.level.players()) {
            if (p.getUUID().equals(miner.playerUuid)) {
               breaker = p;
               break;
            }
         }
      }
      if (breaker == null) {
         // Fallback: closest swinging player within survival mining reach (6 blocks)
         double bestSq = 36.0;
         long now = client.level.getGameTime();
         for (Player p : client.level.players()) {
            TrackedPlayerState st = PLAYER_STATES.get(p.getUUID());
            if (st != null && now - st.lastSwingOrAttackGameTime <= 6L) {
               double dx = st.serverX - (pos.getX() + 0.5);
               double dy = (st.serverY + p.getEyeHeight()) - (pos.getY() + 0.5);
               double dz = st.serverZ - (pos.getZ() + 0.5);
               double sq = dx * dx + dy * dy + dz * dz;
               if (sq < bestSq) {
                  bestSq = sq;
                  breaker = p;
               }
            }
         }
      }
      if (breaker != null && breaker != client.player) {
         final Player finalBreaker = breaker;
          TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(finalBreaker.getUUID(), uuid -> new TrackedPlayerState(finalBreaker));
         // Vanilla Block.playerDestroy exhaustion = 0.005F
         state.addExhaustion(0.005F);
      }
   }

   public static void onTotemPop(LivingEntity entity) {
      Minecraft client = Minecraft.getInstance();
      if ((client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS)
         || client.level == null
         || entity == client.player) {
         return;
      }

      long gameTime = client.level.getGameTime();
      if (entity instanceof Player player) {
         TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), uuid -> new TrackedPlayerState(player));
         // Exact ground-truth Totem resync: 1.0 HP, 4.0 Absorption (5s), Regeneration II (45s)
         state.estimatedHealth = 1.0F;
         state.minEstimatedHealth = 1.0F;
         state.maxEstimatedHealth = 1.0F;
         state.estimatedAbsorption = 4.0F;
         state.absorptionTicks = 100;
         state.regen2Ticks = 900;
         state.inFastRegenStreak = false;
         state.hitsSinceResync = 0;
         state.lowHealthConfirmed = true;
         DebugLogger.logEvent(
            gameTime, "TOTEM_RESYNC", player.getScoreboardName(),
            1.0F, 1.0F, 1.0F, player.getHealth(), state.saturationLevel,
            state.foodLevelMin, state.foodLevelMax, state.lastDamageTaken, state.lastAttackerScale,
            isHealthEstimated(player), "totem_pop_exact_resync"
         );
      } else {
         TrackedMobState mobState = MOB_STATES.computeIfAbsent(entity.getUUID(), uuid -> new TrackedMobState(entity));
         mobState.estimatedHealth = 1.0F;
         mobState.minEstimatedHealth = 1.0F;
         mobState.maxEstimatedHealth = 1.0F;
         mobState.estimatedAbsorption = 4.0F;
         mobState.absorptionTicks = 100;
         mobState.regen2Ticks = 900;
      }
   }

   public static void onDamage(DamageSource damageSource, LivingEntity target) {
      Minecraft client = Minecraft.getInstance();
      if ((client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS) || client.level == null) {
         return;
      }

      long gameTime = client.level.getGameTime();
      DamageEstimate estimate = estimateIncomingDamage(damageSource, target, client.player, gameTime);

      if (target instanceof Player damagedPlayer) {
         TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(damagedPlayer.getUUID(), uuid -> new TrackedPlayerState(damagedPlayer));
         state.addExhaustion(0.1F);
         if (damageSource.getEntity() instanceof Player attackerPlayer) {
            state.lastAttackerUuid = attackerPlayer.getUUID();
         } else {
            state.lastAttackerUuid = null;
         }
         if (damagedPlayer != client.player) {
            state.applyEstimatedDamage(damagedPlayer, estimate, gameTime);
            DebugLogger.logEvent(
               gameTime, "DAMAGE", damagedPlayer.getScoreboardName(),
               state.estimatedHealth, state.minEstimatedHealth, state.maxEstimatedHealth,
               damagedPlayer.getHealth(), state.saturationLevel, state.foodLevelMin, state.foodLevelMax,
               estimate.mean, estimate.attackScale, isHealthEstimated(damagedPlayer),
               String.format(Locale.ROOT, "dmg=[%.2f..%.2f] crit=%s src=%s", estimate.min, estimate.max, estimate.crit, damageSource.getMsgId())
            );
         }
      } else {
         TrackedMobState mobState = MOB_STATES.computeIfAbsent(target.getUUID(), uuid -> new TrackedMobState(target));
         mobState.applyEstimatedDamage(target, estimate, gameTime);
      }

      // Vanilla Player.attack adds 0.1F exhaustion ONLY when an attack actually lands on an entity
      if (damageSource.getEntity() instanceof Player attackerPlayer) {
         TrackedPlayerState attackerState = PLAYER_STATES.computeIfAbsent(attackerPlayer.getUUID(), uuid -> new TrackedPlayerState(attackerPlayer));
         attackerState.addExhaustion(0.1F);
         attackerState.lastAttackWeaponItem = attackerPlayer.getMainHandItem().getItem();
      }
   }

   public static void onHealthMetadataPacket(LivingEntity entity, float hpVal) {
      Minecraft client = Minecraft.getInstance();
      if ((client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS) || client.level == null) {
         return;
      }

      long gameTime = client.level.getGameTime();

      if (entity == client.player) {
         TrackedPlayerState selfState = PLAYER_STATES.get(client.player.getUUID());
         if (selfState != null && !Float.isNaN(hpVal) && hpVal > selfState.lastPacketHealth + 0.05F && selfState.lastPacketHealth > 0.0F) {
            long interval = selfState.lastHealPacketGameTime > 0 ? (gameTime - selfState.lastHealPacketGameTime) : -1L;
            if ((interval >= 7 && interval <= 17) || (interval >= 65 && interval <= 105)) {
               serverHasNaturalRegen = true;
            }
            selfState.lastHealPacketGameTime = gameTime;
         }
         if (selfState != null && !Float.isNaN(hpVal)) {
            selfState.lastPacketHealth = hpVal;
         }
         return;
      }

      if (Float.isNaN(hpVal)) {
         serverHealthObfuscated = true;
         return;
      }

      if (entity instanceof Player player) {
         TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), uuid -> new TrackedPlayerState(player));
         state.onHealthPacket(player, hpVal, gameTime);
      } else {
         TrackedMobState mobState = MOB_STATES.computeIfAbsent(entity.getUUID(), uuid -> new TrackedMobState(entity));
         mobState.onHealthPacket(entity, hpVal, gameTime);
      }
   }

   public static void onBurpSound(double x, double y, double z) {
      Minecraft client = Minecraft.getInstance();
      if ((client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS) || client.level == null) {
         return;
      }

      long gameTime = client.level.getGameTime();

      // Duel-aware candidate scoring within 3.5 blocks using exact server packet position + active/recent food state
      Player bestPlayer = null;
      double bestScore = Double.NEGATIVE_INFINITY;
      for (Player p : client.level.players()) {
         TrackedPlayerState st = PLAYER_STATES.get(p.getUUID());
         double px = (st != null && st.hasServerPos) ? st.serverX : p.getX();
         double py = (st != null && st.hasServerPos) ? st.serverY : p.getY();
         double pz = (st != null && st.hasServerPos) ? st.serverZ : p.getZ();

         double dx = px - x;
         double dy = py - y;
         double dz = pz - z;
         double distSq = dx * dx + dy * dy + dz * dz;
         if (distSq > 12.25) { // 3.5 blocks max radius
            continue;
         }

         if (st != null && st.recentEatCooldown > 0) {
            continue;
         }

         double score = -distSq * 4.0;
         if (st != null && st.usingFood != null) {
            score += 30.0 + Math.min(32, st.ticksUsed);
         } else if (resolveFoodProperties(p.getUseItem()) != null) {
            score += 25.0;
         } else if (resolveFoodProperties(p.getMainHandItem()) != null || resolveFoodProperties(p.getOffhandItem()) != null) {
            score += 12.0;
         } else if (st != null && st.lastHeldFoodAge <= 10) {
            score += 10.0 - st.lastHeldFoodAge * 0.5;
         }

         if (score > bestScore) {
            bestScore = score;
            bestPlayer = p;
         }
      }

      if (bestPlayer == null) {
         return;
      }

      if (bestPlayer == client.player && !Float.isNaN(client.player.getFoodData().getSaturationLevel())) {
         return;
      }

      final Player targetPlayer = bestPlayer;
      TrackedPlayerState state = PLAYER_STATES.computeIfAbsent(targetPlayer.getUUID(), uuid -> new TrackedPlayerState(targetPlayer));
      if (state.recentEatCooldown > 0) {
         return;
      }

      ItemStack candidate = ItemStack.EMPTY;
      FoodProperties food = null;

      if (state.usingFood != null) {
         food = state.usingFood;
         candidate = state.usingStack;
      }
      if (food == null) {
         ItemStack useItem = targetPlayer.getUseItem();
         food = resolveFoodProperties(useItem);
         if (food != null) {
            candidate = useItem;
         }
      }
      if (food == null) {
         ItemStack mainHand = targetPlayer.getMainHandItem();
         food = resolveFoodProperties(mainHand);
         if (food != null) {
            candidate = mainHand;
         }
      }
      if (food == null) {
         ItemStack offHand = targetPlayer.getOffhandItem();
         food = resolveFoodProperties(offHand);
         if (food != null) {
            candidate = offHand;
         }
      }
      if (food == null && state.lastHeldFoodAge <= 20) {
         food = resolveFoodProperties(state.lastHeldFoodStack);
         if (food != null) {
            candidate = state.lastHeldFoodStack;
         }
      }

      if (food != null) {
         state.applyFood(food, candidate, targetPlayer, gameTime);
         state.usingFood = null;
         state.usingStack = ItemStack.EMPTY;
         state.ticksUsed = 0;
         state.lastVanillaTicksUsing = 0;
      } else {
         // Conservative unknown-food fallback (+2 food, +1.2 saturation) instead of aggressive +6/+6
         state.foodLevelMin = Mth.clamp(state.foodLevelMin + UNKNOWN_FOOD_NUTRITION_FALLBACK, 0, 20);
         state.foodLevelEst = Mth.clamp(state.foodLevelEst + UNKNOWN_FOOD_NUTRITION_FALLBACK, 0, 20);
         state.foodLevelMax = Mth.clamp(state.foodLevelMax + 4, 0, 20);
         state.satZeroConfirmed = false;
          state.saturationLevel = Mth.clamp(state.saturationLevel + UNKNOWN_FOOD_SATURATION_FALLBACK, 0.0F, (float) state.foodLevelEst);
         state.hasObservedEat = true;
         state.recentEatCooldown = 12;
         DebugLogger.logEvent(
            gameTime, "EAT_UNKNOWN", targetPlayer.getScoreboardName(),
            state.estimatedHealth, state.minEstimatedHealth, state.maxEstimatedHealth,
            targetPlayer.getHealth(), state.saturationLevel, state.foodLevelMin, state.foodLevelMax,
            state.lastDamageTaken, state.lastAttackerScale, isHealthEstimated(targetPlayer), "burp_unknown_food_conservative"
         );
      }
   }

   public static List<Component> buildDebugReport(Minecraft client) {
      List<Component> lines = new ArrayList<>();
      if (client.level == null || client.player == null) {
         lines.add(Component.literal("[Saturate Debug] No active world.").withStyle(ChatFormatting.RED));
         return lines;
      }

      EstimationModeEnum mode = getEstimationMode();
      long gameTime = client.level.getGameTime();
      lines.add(
         Component.literal(
            String.format(
               Locale.ROOT,
               "[Saturate Debug] mode=%s | diff=%s | serverAHI=%s | equipAHI=%s | natRegen=%s | fileLog=%s",
               mode.name(),
               getDifficulty().name(),
               serverHealthObfuscated,
               serverEquipmentObfuscated,
               serverHasNaturalRegen,
               DebugLogger.isEnabled() ? "ON" : "OFF"
            )
         ).withStyle(ChatFormatting.GOLD)
      );

      List<Player> others = new ArrayList<>();
      for (Player p : client.level.players()) {
         if (p != client.player) {
            others.add(p);
         }
      }
      others.sort(Comparator.comparingDouble(p -> p.distanceToSqr(client.player)));

      if (others.isEmpty()) {
         lines.add(Component.literal("  No other players in client render range.").withStyle(ChatFormatting.GRAY));
         return lines;
      }

      for (Player p : others) {
         TrackedPlayerState st = PLAYER_STATES.get(p.getUUID());
         boolean estActive = isHealthEstimated(p);
         boolean airFp = p.getAirSupply() == 1 && !p.isUnderWater() && !p.isInWater();
         boolean gearFp = hasSpoofedFortuneGear(p);
         float effHp = getEffectiveHealth(p);
         float minHp = getHealthUncertaintyMin(p);
         float maxHp = getHealthUncertaintyMax(p);
         float rawHp = p.getHealth();
         float sat = getSaturation(p);
         float abs = getEffectiveAbsorption(p);
         int armor = getEffectiveArmor(p);
         int anomalies = st != null ? st.anomalyCount : 0;
         float lastDmg = st != null ? st.lastDamageTaken : 0.0F;
         float lastScale = st != null ? st.lastAttackerScale : 1.0F;
         int foodMin = st != null ? st.foodLevelMin : 20;
         int foodMax = st != null ? st.foodLevelMax : 20;

         String rawLabel = (serverHealthObfuscated || airFp || gearFp || (st != null && st.healthObfuscated))
            ? String.format(Locale.ROOT, "%.2f(SPOOF)", rawHp)
            : String.format(Locale.ROOT, "%.2f(REAL)", rawHp);

         boolean satKnown = isSaturationKnown(p);
         boolean foodK = isFoodKnown(p);
         String satLabel = satKnown ? String.format(Locale.ROOT, "%.1f", sat) : "?";
         String foodLabel = foodK
            ? String.format(Locale.ROOT, "%d [%d..%d]%s", getFoodLevel(p), foodMin, foodMax, (st != null && st.foodExact ? "(EXACT)" : "(EST)"))
            : String.format(Locale.ROOT, "? [%d..%d](UNKN)", foodMin, foodMax);

         String summary = String.format(
            Locale.ROOT,
            " - %s (%.1fm): show=%.1f [%.1f..%.1f]%s | raw=%s | abs=%.1f | arm=%d | sat=%s food=%s real=%s | lastDmg=%.2f(s=%.2f) | flags=[est=%s,air1=%s,fort=%s,anom=%d/%d]",
            p.getScoreboardName(),
            p.distanceTo(client.player),
            effHp,
            minHp,
            maxHp,
            hasWideUncertainty(p) ? "~" : "",
            rawLabel,
            abs,
            armor,
            satLabel,
            foodLabel,
            realFoodLabel(p),
            lastDmg,
            lastScale,
            estActive,
            airFp,
            gearFp,
            anomalies,
            ANOMALY_THRESHOLD
         );
         lines.add(Component.literal(summary).withStyle(estActive ? ChatFormatting.YELLOW : ChatFormatting.GREEN));

         DebugLogger.logEvent(
            gameTime, "DEBUG_SNAPSHOT", p.getScoreboardName(),
            effHp, minHp, maxHp, rawHp, sat, foodMin, foodMax, lastDmg, lastScale, estActive,
            String.format(Locale.ROOT, "air1=%s fort=%s anom=%d", airFp, gearFp, anomalies)
         );
      }

      return lines;
   }

   private static String realFoodLabel(Player p) {
      int real = getRealFoodLevel(p);
      if (real >= 0) {
         return String.valueOf(real);
      }
      int server = getIntegratedServerFoodLevel(p);
      return server >= 0 ? String.valueOf(server) : "n/a";
   }

   public static EstimationModeEnum getEstimationMode() {
      try {
         EstimationModeEnum mode = ModConfig.HANDLER.instance().estimation_mode;
         return mode != null ? mode : EstimationModeEnum.AUTO;
      } catch (Throwable ignored) {
         return EstimationModeEnum.AUTO;
      }
   }

   private static boolean isNeverSpoofedByAhi(LivingEntity entity, Player self) {
      // Verified in Bram1903/AntiHealthIndicator MetadataSpoofer.shouldIgnoreEntity:
      // WITHER, ENDER_DRAGON, WOLF, and the viewing player's current vehicle are never spoofed.
      if (entity instanceof WitherBoss || entity instanceof EnderDragon || entity instanceof Wolf) {
         return true;
      }
      return self != null && self.getVehicle() == entity;
   }

   private static boolean checkEntityObfuscationFingerprints(LivingEntity entity) {
      if (entity instanceof WitherBoss || entity instanceof EnderDragon || entity instanceof Wolf) {
         return false;
      }
      if (entity.getAirSupply() == 1 && !entity.isUnderWater() && !entity.isInWater()) {
         serverHealthObfuscated = true;
         return true;
      }
      if (hasSpoofedFortuneGear(entity)) {
         serverEquipmentObfuscated = true;
         serverHealthObfuscated = true;
         return true;
      }
      return false;
   }

   private static boolean checkMobObfuscationFingerprint(LivingEntity mob) {
      if (mob instanceof WitherBoss || mob instanceof EnderDragon || mob instanceof Wolf) {
         return false;
      }
      // For mobs, do NOT inherit serverHealthObfuscated blindly because AHI supports players-only: true.
      // Instead check the mob's own air-ticks == 1 or Fortune armor/sword fingerprint!
      if (mob.getAirSupply() == 1 && !mob.isUnderWater() && !mob.isInWater()) {
         serverHealthObfuscated = true;
         return true;
      }
      if (hasSpoofedFortuneGear(mob)) {
         serverEquipmentObfuscated = true;
         serverHealthObfuscated = true;
         return true;
      }
      return false;
   }

   private static boolean hasSpoofedFortuneGear(LivingEntity entity) {
      for (EquipmentSlot slot : HUMANOID_ARMOR_SLOTS) {
         ItemStack armor = entity.getItemBySlot(slot);
         if (!armor.isEmpty() && getEnchantmentLevel(armor, Enchantments.FORTUNE) > 0) {
            return true;
         }
      }
      ItemStack mainHand = entity.getMainHandItem();
      return !mainHand.isEmpty() && isNonToolWeaponWithFortune(mainHand);
   }

   private static boolean isNonToolWeaponWithFortune(ItemStack stack) {
      if (getEnchantmentLevel(stack, Enchantments.FORTUNE) <= 0) {
         return false;
      }
      return stack.is(ItemTags.SWORDS)
         || stack.is(Items.BOW)
         || stack.is(Items.CROSSBOW)
         || stack.is(Items.TRIDENT)
         || stack.is(Items.MACE);
   }

   private static Player findClosestPlayerByServerPos(Minecraft client, double x, double y, double z, double maxDistSq) {
      Player best = null;
      double bestSq = maxDistSq;
      for (Player p : client.level.players()) {
         TrackedPlayerState st = PLAYER_STATES.get(p.getUUID());
         double px = (st != null && st.hasServerPos) ? st.serverX : p.getX();
         double py = (st != null && st.hasServerPos) ? st.serverY : p.getY();
         double pz = (st != null && st.hasServerPos) ? st.serverZ : p.getZ();
         double sq = (px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z);
         if (sq < bestSq) {
            bestSq = sq;
            best = p;
         }
      }
      return best;
   }

   private static Float getScoreboardHealth(Player target, Player self) {
      Scoreboard scoreboard = target.level().getScoreboard();
      for (DisplaySlot slot : new DisplaySlot[]{DisplaySlot.BELOW_NAME, DisplaySlot.LIST}) {
         Objective objective = scoreboard.getDisplayObjective(slot);
         if (objective == null) {
            continue;
         }

         boolean isHeartsRender = objective.getRenderType() == ObjectiveCriteria.RenderType.HEARTS;
         boolean looksLikeHealth = isHeartsRender || objective.getCriteria() == ObjectiveCriteria.HEALTH;

         if (!looksLikeHealth) {
            String combined = (objective.getName() + " " + objective.getDisplayName().getString()).toLowerCase(Locale.ROOT);
            looksLikeHealth = combined.contains("health")
               || combined.contains("hp")
               || combined.contains("heart")
               || combined.contains("❤")
               || combined.contains("♥")
               || combined.contains("vita");
         }

         if (!looksLikeHealth) {
            continue;
         }

         ReadOnlyScoreInfo targetScore = scoreboard.getPlayerScoreInfo(target, objective);
         if (targetScore == null) {
            continue;
         }

         int rawValue = targetScore.value();
         if (isHeartsRender && rawValue == -1) {
            // Unambiguous AHI ScoreboardSpoofer fingerprint (sets RenderType.HEARTS scores to -1)
            serverHealthObfuscated = true;
            continue;
         }
         if (rawValue <= 0 || rawValue > 200) {
            continue;
         }

         float scale = 1.0F;
         if (self != null) {
            ReadOnlyScoreInfo selfScore = scoreboard.getPlayerScoreInfo(self, objective);
            if (selfScore != null && self.getHealth() > 2.0F) {
               float selfRealTotal = self.getHealth() + self.getAbsorptionAmount();
               int selfVal = selfScore.value();
               if (selfVal <= 0) {
                  continue;
               }
               if (Math.abs(selfVal - selfRealTotal) <= 2.5F) {
                  scale = 1.0F;
               } else if (Math.abs(selfVal * 2.0F - selfRealTotal) <= 2.5F) {
                  scale = 2.0F;
               } else if (Math.abs((selfVal / 100.0F) * self.getMaxHealth() - self.getHealth()) <= 2.5F) {
                  scale = self.getMaxHealth() / 100.0F;
               } else if (!isHeartsRender && objective.getCriteria() != ObjectiveCriteria.HEALTH) {
                  continue;
               }
            }
         }

         return rawValue * scale;
      }

      return null;
   }

   private static FoodProperties resolveFoodProperties(ItemStack stack) {
      if (stack.isEmpty()) {
         return null;
      }
      FoodProperties direct = stack.get(DataComponents.FOOD);
      if (direct != null) {
         return direct;
      }
      return stack.getItem().components().get(DataComponents.FOOD);
   }

   private static Consumable resolveConsumable(ItemStack stack) {
      if (stack.isEmpty()) {
         return null;
      }
      Consumable direct = stack.get(DataComponents.CONSUMABLE);
      if (direct != null) {
         return direct;
      }
      return stack.getItem().components().get(DataComponents.CONSUMABLE);
   }

   private static ItemAttributeModifiers resolveAttributeModifiers(ItemStack stack) {
      ItemAttributeModifiers direct = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
      if (direct != null && !direct.modifiers().isEmpty()) {
         return direct;
      }
      ItemAttributeModifiers prototype = stack.getItem().components().get(DataComponents.ATTRIBUTE_MODIFIERS);
      return prototype != null ? prototype : ItemAttributeModifiers.EMPTY;
   }

   private static double computeEquipmentAttribute(LivingEntity entity, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
      double total = 0.0;
      for (EquipmentSlot slot : ARMOR_SLOTS) {
         ItemStack stack = entity.getItemBySlot(slot);
         if (!stack.isEmpty()) {
            ItemAttributeModifiers modifiers = resolveAttributeModifiers(stack);
            total = modifiers.compute(attribute, total, slot);
         }
      }
      return total;
   }

   private static int getEnchantmentLevel(ItemStack stack, ResourceKey<Enchantment> key) {
      ItemEnchantments enchantments = stack.getEnchantments();
      for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
         if (entry.getKey().is(key)) {
            return entry.getIntValue();
         }
      }
      return 0;
   }

   private static DamageEstimate estimateIncomingDamage(DamageSource source, LivingEntity target, Player localPlayer, long gameTime) {
      if (target instanceof Player targetPlayer) {
         TrackedPlayerState targetState = PLAYER_STATES.get(targetPlayer.getUUID());
         if (targetState != null && gameTime - targetState.lastShieldBlockGameTime <= 1L && !source.is(DamageTypeTags.BYPASSES_SHIELD)) {
            return new DamageEstimate(0.0F, 0.0F, 0.0F, 1.0F, false);
         }
      }

      if (target.isBlocking() && !source.is(DamageTypeTags.BYPASSES_SHIELD)) {
         Vec3 sourcePos = source.getSourcePosition();
         if (sourcePos != null) {
            Vec3 view = target.getViewVector(1.0F);
            Vec3 toSource = sourcePos.subtract(target.position()).normalize();
            if (new Vec3(toSource.x, 0.0, toSource.z).dot(new Vec3(view.x, 0.0, view.z)) > 0.0) {
               return new DamageEstimate(0.0F, 0.0F, 0.0F, 1.0F, false);
            }
         }
      }

      float rawMean = 2.0F;
      float rawMin = 1.5F;
      float rawMax = 2.5F;
      float attackScale = 1.0F;
      boolean isCrit = false;

      Entity direct = source.getDirectEntity();
      Entity attacker = source.getEntity();

      if (direct instanceof ThrownTrident trident) {
         rawMean = 8.0F;
         rawMin = 8.0F;
         rawMax = 8.0F;
         ItemStack tridentStack = trident.getWeaponItem() != null ? trident.getWeaponItem() : ItemStack.EMPTY;
         if (!tridentStack.isEmpty()) {
            if (!serverEquipmentObfuscated && !isNonToolWeaponWithFortune(tridentStack)) {
               int impaling = getEnchantmentLevel(tridentStack, Enchantments.IMPALING);
               if (impaling > 0 && target.isInWaterOrRain()) {
                  float bonus = impaling * 2.5F;
                  rawMean += bonus;
                  rawMin += bonus;
                  rawMax += bonus;
               }
            } else if (tridentStack.hasFoil() && target.isInWaterOrRain()) {
               rawMean += 7.5F;
               rawMax += 12.5F;
            }
         }
      } else if (direct instanceof AbstractArrow arrow) {
         double speed = arrow.getDeltaMovement().length();
         float baseArrow = (float) Mth.ceil(Mth.clamp(speed * 2.0, 2.0, 10.0));
         rawMean = baseArrow;
         rawMin = Math.max(1.0F, baseArrow - 1.0F);
         rawMax = baseArrow + 1.0F;
         if (arrow.isCritArrow()) {
            rawMean += rawMean * 0.25F + 1.0F;
            rawMin += 1.0F;
            rawMax += rawMax * 0.5F + 1.0F;
         }
         if (attacker instanceof LivingEntity shooter) {
            ItemStack bow = shooter.getMainHandItem().hasFoil() ? shooter.getMainHandItem() : shooter.getOffhandItem();
            if (shooter == localPlayer || (!serverEquipmentObfuscated && !isNonToolWeaponWithFortune(bow))) {
               int power = getEnchantmentLevel(bow, Enchantments.POWER);
               if (power > 0) {
                  float pBonus = (float) (0.5 * (power + 1));
                  rawMean += pBonus;
                  rawMin += pBonus;
                  rawMax += pBonus;
               }
            } else if (bow.hasFoil()) {
               // Spoofed bow: Power I (+1.0) to Power V (+3.0), mean +2.5
               rawMean += 2.5F;
               rawMin += 0.0F;
               rawMax += 3.0F;
            }
         }
      } else if (source.is(DamageTypeTags.IS_EXPLOSION)) {
         Vec3 pos = source.getSourcePosition();
         if (pos != null) {
            double dist = Math.sqrt(target.distanceToSqr(pos));
            double radius = 12.0;
            double rawImpact = Math.max(0.0, 1.0 - (dist / radius));
            double meanExposureImpact = rawImpact * 0.7;
            double minExposureImpact = rawImpact * 0.35;
            double maxExposureImpact = rawImpact * 1.0;
            rawMean = (float) ((meanExposureImpact * meanExposureImpact + meanExposureImpact) / 2.0 * 7.0 * radius + 1.0);
            rawMin = (float) ((minExposureImpact * minExposureImpact + minExposureImpact) / 2.0 * 7.0 * radius + 1.0);
            rawMax = (float) ((maxExposureImpact * maxExposureImpact + maxExposureImpact) / 2.0 * 7.0 * radius + 1.0);
         } else {
            rawMean = 12.0F;
            rawMin = 6.0F;
            rawMax = 20.0F;
         }
      } else if (attacker instanceof LivingEntity livingAttacker) {
         ItemStack weapon = livingAttacker.getMainHandItem();
         ItemAttributeModifiers modifiers = resolveAttributeModifiers(weapon);
         double baseAttack = (livingAttacker instanceof Player) ? 1.0 : 3.0;
         float baseDmg = (float) modifiers.compute(Attributes.ATTACK_DAMAGE, baseAttack, EquipmentSlot.MAINHAND);

         if (livingAttacker == localPlayer) {
            attackScale = localPlayer.getAttackStrengthScale(0.5F);
            // Note: on the client, localPlayer may have already reset attackStrengthTicker when clicking;
            // consult selfState swing interval if localPlayer ticker already reset to 0.
            TrackedPlayerState selfState = PLAYER_STATES.get(localPlayer.getUUID());
            if (attackScale < 0.25F && selfState != null) {
               attackScale = selfState.consumeAttackCooldownScale(weapon, gameTime);
            }
            MobEffectInstance strength = localPlayer.getEffect(MobEffects.STRENGTH);
            if (strength != null) {
               baseDmg += 3.0F * (strength.getAmplifier() + 1);
            }
            MobEffectInstance weakness = localPlayer.getEffect(MobEffects.WEAKNESS);
            if (weakness != null) {
               baseDmg -= 4.0F * (weakness.getAmplifier() + 1);
            }
         } else if (livingAttacker instanceof Player remoteAttacker) {
            TrackedPlayerState attackerState = PLAYER_STATES.computeIfAbsent(remoteAttacker.getUUID(), uuid -> new TrackedPlayerState(remoteAttacker));
            attackScale = attackerState.consumeAttackCooldownScale(weapon, gameTime);
         }

         float cooldownMultiplier = (livingAttacker instanceof Player)
            ? (0.2F + attackScale * attackScale * 0.8F)
            : 1.0F;

         float scaledBase = Math.max(0.2F, baseDmg * cooldownMultiplier);

         // Mob damage scaling against players based on server difficulty (Player vs Player does NOT scale)
         if (target instanceof Player && !(livingAttacker instanceof Player)) {
            Difficulty diff = getDifficulty();
            if (diff == Difficulty.PEACEFUL) {
               scaledBase = 0.0F;
            } else if (diff == Difficulty.EASY) {
               scaledBase = Math.min(scaledBase / 2.0F + 1.0F, scaledBase);
            } else if (diff == Difficulty.HARD) {
               scaledBase = scaledBase * 1.5F;
            }
         }

         if (scaledBase <= 0.0F && target instanceof Player) {
            return new DamageEstimate(0.0F, 0.0F, 0.0F, attackScale, false);
         }

         // Critical hit check: in MC 26.1.2 Player.attack, crits strictly require s > 0.9F!
         if (livingAttacker instanceof Player attackerPlayer && attackScale > 0.9F) {
            TrackedPlayerState attackerState = PLAYER_STATES.get(attackerPlayer.getUUID());
            TrackedPlayerState targetState = (target instanceof Player tp) ? PLAYER_STATES.get(tp.getUUID()) : null;

            boolean confirmedCritSound = attackerState != null
               && attackerState.lastAttackSoundGameTime >= gameTime - 1
               && attackerState.lastAttackSoundHint == AttackSoundHint.CRIT;
            boolean confirmedNonCritSound = attackerState != null
               && attackerState.lastAttackSoundGameTime >= gameTime - 1
               && (attackerState.lastAttackSoundHint == AttackSoundHint.STRONG || attackerState.lastAttackSoundHint == AttackSoundHint.WEAK);
            boolean confirmedCritAnim = targetState != null && targetState.lastReceivedCritAnimGameTime >= gameTime - 1;

            double attackerDy = (attackerPlayer != localPlayer && attackerState != null && attackerState.hasServerPos)
               ? attackerState.lastServerDy
               : attackerPlayer.getDeltaMovement().y;
            boolean attackerOnGround = (attackerPlayer != localPlayer && attackerState != null && attackerState.hasServerPos)
               ? attackerState.serverOnGround
               : attackerPlayer.onGround();

            boolean kinematicCrit = !attackerOnGround
               && attackerDy < -0.02
               && !attackerPlayer.isSprinting()
               && !attackerPlayer.isInWater()
               && !attackerPlayer.onClimbable()
               && !attackerPlayer.isPassenger();

            isCrit = confirmedCritSound || confirmedCritAnim || (!confirmedNonCritSound && kinematicCrit);
         }

         float critMean = isCrit ? scaledBase * 1.5F : scaledBase;
         float critMin = critMean;
         float critMax = (!isCrit && attackScale > 0.85F && !livingAttacker.onGround()) ? scaledBase * 1.5F : critMean;

         // Enchantment bonus (scaled linearly by attackScale s per Player.attack bytecode)
         float enchMean = 0.0F;
         float enchMin = 0.0F;
         float enchMax = 0.0F;
         boolean weaponSpoofed = livingAttacker != localPlayer && (serverEquipmentObfuscated || isNonToolWeaponWithFortune(weapon));
         if (!weaponSpoofed) {
            int sharpness = getEnchantmentLevel(weapon, Enchantments.SHARPNESS);
            if (sharpness > 0) {
               float bonus = (0.5F * sharpness + 0.5F) * attackScale;
               enchMean = bonus;
               enchMin = bonus;
               enchMax = bonus;
            }
         } else if (weapon.hasFoil()) {
            TrackedPlayerState attackerState = (livingAttacker instanceof Player ap) ? PLAYER_STATES.get(ap.getUUID()) : null;
            TrackedPlayerState targetState = (target instanceof Player tp) ? PLAYER_STATES.get(tp.getUUID()) : null;
            boolean magicCritSeen = (targetState != null && targetState.lastReceivedMagicCritAnimGameTime >= gameTime - 1)
               || (attackerState != null && attackerState.confirmedSharpnessWeaponItem == weapon.getItem());
            if (magicCritSeen) {
               enchMean = SPOOFED_SHARP_CONFIRMED_MEAN * attackScale;
               enchMin = SPOOFED_SHARP_CONFIRMED_MIN * attackScale;
               enchMax = SPOOFED_SHARP_MAX * attackScale;
            } else {
               enchMean = SPOOFED_SHARP_UNCONFIRMED_MEAN * attackScale;
               enchMin = SPOOFED_SHARP_MIN * attackScale;
               enchMax = SPOOFED_SHARP_MAX * attackScale;
            }
         }

         rawMean = critMean + enchMean;
         rawMin = critMin + enchMin;
         rawMax = critMax + enchMax;
      } else if (source.is(DamageTypeTags.IS_FALL)) {
         float fall = 3.0F;
         if (target instanceof Player p) {
            TrackedPlayerState st = PLAYER_STATES.get(p.getUUID());
            if (st != null && st.peakFallDistance > 3.0F) {
               fall = st.peakFallDistance - 3.0F;
            }
         }
         rawMean = Math.max(1.0F, fall);
         rawMin = Math.max(0.5F, rawMean - 1.5F);
         rawMax = rawMean + 1.5F;
      } else if (source.is(DamageTypeTags.IS_FIRE)) {
         rawMean = target.isInLava() ? 4.0F : 1.0F;
         rawMin = rawMean;
         rawMax = rawMean;
      } else if (source.is(DamageTypeTags.BYPASSES_ARMOR)) {
         rawMean = 1.5F;
         rawMin = 1.0F;
         rawMax = 4.0F;
      }

      rawMean = Math.max(0.5F, rawMean);
      rawMin = Math.max(0.25F, rawMin);
      rawMax = Math.max(rawMean, rawMax);

      // Apply armor, toughness, Resistance, and Protection EPF bounds
      float armor = (float) getEffectiveArmor(target);
      float toughness = (float) Math.max(
         target.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
         computeEquipmentAttribute(target, Attributes.ARMOR_TOUGHNESS)
      );

      float afterArmorMean = rawMean;
      float afterArmorMin = rawMin;
      float afterArmorMax = rawMax;
      if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
         afterArmorMean = CombatRules.getDamageAfterAbsorb(target, rawMean, source, armor, toughness);
         afterArmorMin = CombatRules.getDamageAfterAbsorb(target, rawMin, source, armor, toughness);
         afterArmorMax = CombatRules.getDamageAfterAbsorb(target, rawMax, source, armor, toughness);
      }

      MobEffectInstance resistance = target.getEffect(MobEffects.RESISTANCE);
      if (resistance != null && !source.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
         int resistFactor = 25 - (resistance.getAmplifier() + 1) * 5;
         if (resistFactor <= 0) {
            return new DamageEstimate(0.0F, 0.0F, 0.0F, attackScale, isCrit);
         }
         float mult = (float) resistFactor / 25.0F;
         afterArmorMean *= mult;
         afterArmorMin *= mult;
         afterArmorMax *= mult;
      }

      if (!source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
         float epfMean = 0.0F;
         float epfMinDefense = 0.0F; // lower defense -> used for maxDmg
         float epfMaxDefense = 0.0F; // higher defense -> used for minDmg

         for (EquipmentSlot slot : HUMANOID_ARMOR_SLOTS) {
            ItemStack piece = target.getItemBySlot(slot);
            if (piece.isEmpty()) {
               continue;
            }
            boolean pieceSpoofed = serverEquipmentObfuscated || getEnchantmentLevel(piece, Enchantments.FORTUNE) > 0;
            if (!pieceSpoofed) {
               float pieceEpf = getEnchantmentLevel(piece, Enchantments.PROTECTION);
               if (source.is(DamageTypeTags.IS_FIRE)) {
                  pieceEpf += getEnchantmentLevel(piece, Enchantments.FIRE_PROTECTION) * 2.0F;
               }
               if (source.is(DamageTypeTags.IS_FALL)) {
                  pieceEpf += getEnchantmentLevel(piece, Enchantments.FEATHER_FALLING) * 3.0F;
               }
               if (source.is(DamageTypeTags.IS_EXPLOSION)) {
                  pieceEpf += getEnchantmentLevel(piece, Enchantments.BLAST_PROTECTION) * 2.0F;
               }
               if (source.is(DamageTypeTags.IS_PROJECTILE)) {
                  pieceEpf += getEnchantmentLevel(piece, Enchantments.PROJECTILE_PROTECTION) * 2.0F;
               }
               epfMean += pieceEpf;
               epfMinDefense += pieceEpf;
               epfMaxDefense += pieceEpf;
            } else if (piece.hasFoil()) {
               double pieceToughness = resolveAttributeModifiers(piece).compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot);
               float baseMean = pieceToughness >= 2.0 ? SPOOFED_PROT_HIGH_TIER_MEAN : SPOOFED_PROT_LOW_TIER_MEAN;
               epfMean += baseMean;
               epfMinDefense += SPOOFED_PROT_MIN_EPF;
               epfMaxDefense += SPOOFED_PROT_MAX_EPF;

               if (source.is(DamageTypeTags.IS_EXPLOSION) && slot == EquipmentSlot.LEGS) {
                  epfMean += 3.0F;
                  epfMaxDefense += 4.0F;
               } else if (source.is(DamageTypeTags.IS_FALL) && slot == EquipmentSlot.FEET) {
                  epfMean += 4.0F;
                  epfMaxDefense += 8.0F;
               }
            }
         }

         if (epfMean > 0.0F || epfMaxDefense > 0.0F) {
            afterArmorMean = CombatRules.getDamageAfterMagicAbsorb(afterArmorMean, Math.min(20.0F, epfMean));
            afterArmorMin = CombatRules.getDamageAfterMagicAbsorb(afterArmorMin, Math.min(20.0F, epfMaxDefense));
            afterArmorMax = CombatRules.getDamageAfterMagicAbsorb(afterArmorMax, Math.min(20.0F, epfMinDefense));
         }
      }

      float dMin = Math.max(0.2F, Math.min(afterArmorMin, afterArmorMean));
      float dMax = Math.max(afterArmorMean, afterArmorMax);
      return new DamageEstimate(afterArmorMean, dMin, dMax, attackScale, isCrit);
   }

   private enum AttackSoundHint {
      NONE,
      WEAK,
      STRONG,
      CRIT
   }

   private record MiningEntry(UUID playerUuid, long gameTime) {
   }

   private record DamageEstimate(float mean, float min, float max, float attackScale, boolean crit) {
   }

   private static final class TrackedPlayerState {
      // Food & saturation state
      int foodLevelMin = 0;
      int foodLevelEst = 20;
      int foodLevelMax = 20;
      float saturationLevel = 0.0F;
      boolean saturationKnown = false;
      boolean foodKnown = false;
      boolean foodExact = false;
      boolean isDead = false;
      int hitsSinceResync = 0;
      boolean lowHealthConfirmed = false;
      float exhaustion = 0.0F;
      // True once saturation == 0 was proven (slow-regen with food 20); allows lowering foodMax on exhaustion drain
      boolean satZeroConfirmed = false;
      long lastHealthPacketGameTime = -1000L;

      // Health confidence interval [minEstimatedHealth .. estimatedHealth .. maxEstimatedHealth]
      float estimatedHealth = 20.0F;
      float minEstimatedHealth = 20.0F;
      float maxEstimatedHealth = 20.0F;
      float estimatedAbsorption = 0.0F;
      int absorptionTicks = 0;
      int regen2Ticks = 0;

      // Per-entity AHI anomaly detection (requires ANOMALY_THRESHOLD=3 before turning on without global fingerprints)
      boolean healthObfuscated = false;
      int anomalyCount = 0;
      boolean hasObservedEat = false;
      boolean hasObservedSubMaxFoodEat = false;
      boolean inFastRegenStreak = false;

      // Damage, heal & same-tick packet reconciliation
      long lastDamageGameTime = -1000L;
      int pendingDamageMetadataPackets = 0;
      long lastHealPacketGameTime = -1000L;
      long lastSameTickPacketGameTime = -1000L;
      float lastSameTickDeltaMean = 0.0F;
      float lastPacketHealth = -1.0F;
      float lastDamageTaken = 0.0F;
      float lastAttackerScale = 1.0F;
      UUID lastAttackerUuid = null;

      // Attack swing cooldown & combat confirmation signals
      long prevSwingOrAttackGameTime = -1000L;
      long lastSwingOrAttackGameTime = -1000L;
      long lastAttackSoundGameTime = -1000L;
      AttackSoundHint lastAttackSoundHint = AttackSoundHint.NONE;
      long lastReceivedCritAnimGameTime = -1000L;
      long lastReceivedMagicCritAnimGameTime = -1000L;
      long lastShieldBlockGameTime = -1000L;
      Item lastAttackWeaponItem = Items.AIR;
      Item confirmedSharpnessWeaponItem = null;

      int damageInvulnerabilityTicks = 0;
      float lastHitMeanInWindow = 0.0F;
      float lastHitMinInWindow = 0.0F;
      float lastHitMaxInWindow = 0.0F;

      // Exact server packet position tracking (ClientboundMoveEntity / Teleport / PositionSync)
      boolean hasServerPos = false;
      double serverX;
      double serverY;
      double serverZ;
      double lastServerDy = 0.0;
      boolean serverOnGround;
      int serverGroundGraceTicks = 0;
      int jumpCooldown = 0;
      float peakFallDistance = 0.0F;

      // Self fallback tracking
      double selfLastX;
      double selfLastY;
      double selfLastZ;
      boolean selfWasOnGround;
      boolean wasDead;
      float lastSelfHealth;
      float lastServerAbsorption;

      // Eating tracking
      FoodProperties usingFood;
      ItemStack usingStack = ItemStack.EMPTY;
      ItemStack lastHeldFoodStack = ItemStack.EMPTY;
      int lastHeldFoodAge = 100;
      int ticksUsed;
      int lastVanillaTicksUsing;
      int requiredConsumeTicks = 32;
      int recentEatCooldown;

      TrackedPlayerState(Player player) {
         Vec3 codecBase = player.getPositionCodec().getBase();
         if (codecBase.lengthSqr() > 0.001) {
            this.serverX = codecBase.x;
            this.serverY = codecBase.y;
            this.serverZ = codecBase.z;
            this.hasServerPos = true;
         } else {
            this.serverX = player.getX();
            this.serverY = player.getY();
            this.serverZ = player.getZ();
         }
         this.serverOnGround = player.onGround();
         this.selfLastX = player.getX();
         this.selfLastY = player.getY();
         this.selfLastZ = player.getZ();
         this.selfWasOnGround = player.onGround();
         this.wasDead = !player.isAlive() || player.getHealth() <= 0.0F;
         this.lastSelfHealth = player.getHealth();
         this.lastPacketHealth = player.getHealth();
         this.lastServerAbsorption = player.getAbsorptionAmount();
         float maxHp = player.getMaxHealth();
         this.estimatedHealth = maxHp;
         this.minEstimatedHealth = maxHp;
         this.maxEstimatedHealth = maxHp;

         Minecraft client = Minecraft.getInstance();
         if (player == client.player) {
            this.saturationKnown = true;
            this.foodKnown = true;
            this.foodExact = true;
            this.saturationLevel = Mth.clamp(player.getFoodData().getSaturationLevel(), 0.0F, 20.0F);
            int exactFood = player.getFoodData().getFoodLevel();
            this.foodLevelMin = exactFood;
            this.foodLevelEst = exactFood;
            this.foodLevelMax = exactFood;
         } else if (client.getSingleplayerServer() != null && getEstimationMode() != EstimationModeEnum.ALWAYS) {
            this.saturationKnown = true;
            this.foodKnown = true;
            this.foodExact = true;
            int realFood = getRealFoodLevel(player);
            this.foodLevelMin = realFood >= 0 ? realFood : 20;
            this.foodLevelEst = this.foodLevelMin;
            this.foodLevelMax = this.foodLevelMin;
            this.saturationLevel = getSaturation(player);
         } else {
            long now = client.level != null ? client.level.getGameTime() : 0L;
            Long deathTime = PENDING_RESPAWN.remove(player.getUUID());
            float respawnSat = ModConfig.HANDLER.instance().respawn_saturation;
            if (deathTime != null && now - deathTime <= RESPAWN_WINDOW_TICKS && respawnSat >= 0.0F && player.getHealth() >= maxHp - 0.01F) {
               this.saturationKnown = true;
               this.foodKnown = true;
               this.foodExact = true;
               this.saturationLevel = Math.min(respawnSat, 20.0F);
               this.exhaustion = 0.0F;
               this.foodLevelMin = 20;
               this.foodLevelEst = 20;
               this.foodLevelMax = 20;
            } else {
               SaturationSnapshot snapshot = SATURATION_SNAPSHOTS.remove(player.getUUID());
               if (snapshot != null && now - snapshot.gameTime() <= SNAPSHOT_TTL_TICKS) {
                  this.saturationKnown = true;
                  this.foodKnown = true;
                  this.foodExact = snapshot.foodExact();
                  this.saturationLevel = snapshot.saturation();
                  this.exhaustion = snapshot.exhaustion();
                  this.satZeroConfirmed = snapshot.satZeroConfirmed();
                  this.foodLevelMin = snapshot.foodMin();
                  this.foodLevelEst = snapshot.foodEst();
                  this.foodLevelMax = snapshot.foodMax();
               } else {
                  this.saturationKnown = false;
                  this.foodKnown = false;
                  this.foodExact = false;
                  this.saturationLevel = 0.0F;
                  this.foodLevelMin = 0;
                  this.foodLevelEst = 20;
                  this.foodLevelMax = 20;
               }
            }
         }
      }

      void onDeath(Player player) {
         this.isDead = true;
         this.wasDead = true;
         this.estimatedHealth = 0.0F;
         this.minEstimatedHealth = 0.0F;
         this.maxEstimatedHealth = 0.0F;
         this.estimatedAbsorption = 0.0F;
         this.foodLevelMin = 0;
         this.foodLevelEst = 20;
         this.foodLevelMax = 20;
         this.foodKnown = false;
         this.foodExact = false;
         this.saturationLevel = 0.0F;
         this.saturationKnown = false;
         this.exhaustion = 0.0F;
         this.satZeroConfirmed = false;
         this.pendingDamageMetadataPackets = 0;
         this.inFastRegenStreak = false;
         this.hitsSinceResync = 0;
         this.lowHealthConfirmed = false;
         long gameTime = player.level().getGameTime();
         DebugLogger.logEvent(
            gameTime, "PLAYER_DEATH", player.getScoreboardName(),
            0.0F, 0.0F, 0.0F, player.getHealth(), 0.0F,
            0.0F, 0, 20, 0.0F, 1.0F,
            isHealthEstimated(player), "event_id_3"
         );
      }

      void resetToRespawn(Player player) {
         this.isDead = false;
         this.wasDead = false;
         float maxHp = player.getMaxHealth();
         this.estimatedHealth = maxHp;
         this.minEstimatedHealth = maxHp;
         this.maxEstimatedHealth = maxHp;
         this.estimatedAbsorption = 0.0F;
         this.foodLevelMin = 18;
         this.foodLevelEst = 20;
         this.foodLevelMax = 20;
         this.foodKnown = false;
         this.foodExact = false;
         this.saturationLevel = 0.0F;
         this.saturationKnown = false;
         this.exhaustion = 0.0F;
         this.satZeroConfirmed = false;
         this.lastDamageTaken = 0.0F;
         this.pendingDamageMetadataPackets = 0;
         this.inFastRegenStreak = false;
         this.hitsSinceResync = 0;
         this.lowHealthConfirmed = false;
         this.lastPacketHealth = player.getHealth();
         long gameTime = player.level().getGameTime();
         DebugLogger.logEvent(
            gameTime, "PLAYER_RESPAWN", player.getScoreboardName(),
            maxHp, maxHp, maxHp, player.getHealth(), 0.0F,
            0.0F, 18, 20, 0.0F, 1.0F,
            isHealthEstimated(player), "respawn_reset"
         );
      }

      void markSaturationKnown(float value, String reason, Player player, long gameTime) {
         boolean wasKnown = this.saturationKnown;
         this.saturationLevel = Math.max(0.0F, value);
         this.saturationKnown = true;
         capSaturationToFood();
         if (!wasKnown) {
            DebugLogger.logEvent(
               gameTime, "SAT_KNOWN", player.getScoreboardName(),
               this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth,
               player.getHealth(), 0.0F, this.saturationLevel,
               this.foodLevelMin, this.foodLevelMax, this.lastDamageTaken, this.lastAttackerScale,
               isHealthEstimated(player), "reason=" + reason + " sat=" + String.format(Locale.ROOT, "%.1f", this.saturationLevel)
            );
         }
      }

      boolean isLowHealthConfirmed(long gameTime) {
         if (this.lowHealthConfirmed) {
            return true;
         }
         if (this.hitsSinceResync <= 1) {
            return true;
         }
         // Prolonged time without healing after damage proves low food / confirmed low health
         if (serverHasNaturalRegen && this.lastDamageGameTime > 0L && (gameTime - this.lastDamageGameTime > FOOD_QUIET_WINDOW_TICKS)) {
            return true;
         }
         return false;
      }

      void flagAnomaly() {
         if (++this.anomalyCount >= ANOMALY_THRESHOLD) {
            this.healthObfuscated = true;
         }
      }

      void recordSwing(long gameTime) {
         if (this.lastSwingOrAttackGameTime < gameTime) {
            this.prevSwingOrAttackGameTime = this.lastSwingOrAttackGameTime;
            this.lastSwingOrAttackGameTime = gameTime;
         }
      }

      float consumeAttackCooldownScale(ItemStack weapon, long gameTime) {
         double attackSpeed = Math.max(
            0.1,
            resolveAttributeModifiers(weapon).compute(Attributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND)
         );
         float delayTicks = (float) (20.0 / attackSpeed);

         long elapsedTicks;
         if (this.lastSwingOrAttackGameTime < 0L) {
            elapsedTicks = 100L;
            this.lastSwingOrAttackGameTime = gameTime;
         } else if (this.lastSwingOrAttackGameTime == gameTime) {
            elapsedTicks = this.prevSwingOrAttackGameTime < 0L ? 100L : (gameTime - this.prevSwingOrAttackGameTime);
         } else {
            elapsedTicks = gameTime - this.lastSwingOrAttackGameTime;
            this.prevSwingOrAttackGameTime = this.lastSwingOrAttackGameTime;
            this.lastSwingOrAttackGameTime = gameTime;
         }

         float s = Mth.clamp(((float) elapsedTicks + 0.5F) / delayTicks, 0.0F, 1.0F);
         if (this.lastAttackSoundGameTime >= gameTime - 1L) {
            if (this.lastAttackSoundHint == AttackSoundHint.WEAK) {
               s = Math.min(s, 0.80F);
            } else if (this.lastAttackSoundHint == AttackSoundHint.STRONG || this.lastAttackSoundHint == AttackSoundHint.CRIT) {
               s = Math.max(s, 0.92F);
            }
         }
         return s;
      }

      void onServerMove(
         Player player,
         double packetX,
         double packetY,
         double packetZ,
         boolean packetOnGround,
         boolean isTeleport,
         boolean hasPosition
      ) {
         if (!this.hasServerPos || isTeleport) {
            this.serverX = packetX;
            this.serverY = packetY;
            this.serverZ = packetZ;
            this.serverOnGround = packetOnGround;
            this.hasServerPos = true;
            if (packetOnGround) {
               this.peakFallDistance = 0.0F;
            }
            return;
         }

         double dx = hasPosition ? (packetX - this.serverX) : 0.0;
         double dy = hasPosition ? (packetY - this.serverY) : 0.0;
         double dz = hasPosition ? (packetZ - this.serverZ) : 0.0;
         this.lastServerDy = dy;

         boolean wasGrounded = this.serverOnGround || this.serverGroundGraceTicks > 0;
         if (packetOnGround) {
            this.serverGroundGraceTicks = 2;
         } else if (this.serverGroundGraceTicks > 0) {
            --this.serverGroundGraceTicks;
         }

         if (packetOnGround || player.isInWater() || player.onClimbable()) {
            this.peakFallDistance = 0.0F;
         } else if (dy < 0.0) {
            this.peakFallDistance += (float) (-dy);
         }

         double horizDistSq = dx * dx + dz * dz;
         if (horizDistSq > 1.0E-5 && horizDistSq < 16.0 && !player.isPassenger()) {
            float horizDist = (float) Math.sqrt(horizDistSq);
            if (player.isSwimming() || player.isUnderWater() || player.isInWater()) {
               addExhaustion(0.01F * horizDist);
            } else if (packetOnGround && player.isSprinting()) {
               // In vanilla, sprint exhaustion (0.1 per block) only applies when on ground
               addExhaustion(0.1F * horizDist);
            }
         }

         // Server-packet jump detection (initial jump dy in vanilla is +0.42 blocks; ignore knockback when hurtTime > 0)
         if (this.jumpCooldown == 0
            && wasGrounded
            && !packetOnGround
            && dy > 0.10
            && player.hurtTime == 0
            && !player.isInWater()
            && !player.onClimbable()
            && !player.isPassenger()) {
            addExhaustion(player.isSprinting() ? 0.2F : 0.05F);
            this.jumpCooldown = 8;
            this.serverGroundGraceTicks = 0;
         }

         this.serverX = packetX;
         this.serverY = packetY;
         this.serverZ = packetZ;
         this.serverOnGround = packetOnGround;
      }

      void applyEstimatedDamage(Player player, DamageEstimate estimate, long gameTime) {
         if (this.isDead || (this.estimatedHealth <= 1.0F && player.getHealth() >= 19.0F)) {
            resetToRespawn(player);
         }
         if (estimate.mean <= 0.0F && estimate.max <= 0.0F) {
            return;
         }

         float effMean = estimate.mean;
         float effMin = estimate.min;
         float effMax = estimate.max;

         if (this.damageInvulnerabilityTicks > 0) {
            if (effMean <= this.lastHitMeanInWindow && effMax <= this.lastHitMaxInWindow) {
               return;
            }
            effMean = Math.max(0.0F, effMean - this.lastHitMeanInWindow);
            effMin = Math.max(0.0F, effMin - this.lastHitMinInWindow);
            effMax = Math.max(0.0F, effMax - this.lastHitMaxInWindow);
            this.lastHitMeanInWindow = Math.max(this.lastHitMeanInWindow, estimate.mean);
            this.lastHitMinInWindow = Math.max(this.lastHitMinInWindow, estimate.min);
            this.lastHitMaxInWindow = Math.max(this.lastHitMaxInWindow, estimate.max);
         } else {
            this.damageInvulnerabilityTicks = 10;
            this.lastHitMeanInWindow = estimate.mean;
            this.lastHitMinInWindow = estimate.min;
            this.lastHitMaxInWindow = estimate.max;
         }

         // If SetEntityData arrived earlier in this exact tick before DamageEvent, undo its provisional delta
         if (this.lastSameTickPacketGameTime == gameTime) {
            this.estimatedHealth = Mth.clamp(this.estimatedHealth - this.lastSameTickDeltaMean, 0.5F, player.getMaxHealth());
            this.lastSameTickDeltaMean = 0.0F;
            this.pendingDamageMetadataPackets = 0;
         } else {
            this.pendingDamageMetadataPackets = 1;
         }

         // Same-tick damage + scheduled 10-tick heal: if a 10-tick regen tick coincided with this hit,
         // both merge into a single end-of-tick metadata packet on the server, so preserve upper bound!
         if (this.inFastRegenStreak && this.lastHealPacketGameTime > 0) {
            long sinceHeal = gameTime - this.lastHealPacketGameTime;
            if (sinceHeal >= 9L && sinceHeal <= 11L) {
               float possibleHeal = Math.min(Math.max(this.saturationLevel, 1.0F), 6.0F) / 6.0F;
               this.maxEstimatedHealth = Math.min(player.getMaxHealth(), this.maxEstimatedHealth + possibleHeal);
               this.lastHealPacketGameTime = gameTime;
            }
         }

         this.lastDamageGameTime = gameTime;
         this.lastDamageTaken = effMean;
         this.lastAttackerScale = estimate.attackScale;

         if (this.estimatedAbsorption > 0.0F && this.absorptionTicks > 0) {
            float absorbedMean = Math.min(this.estimatedAbsorption, effMean);
            float absorbedMin = Math.min(this.estimatedAbsorption, effMin);
            float absorbedMax = Math.min(this.estimatedAbsorption, effMax);
            this.estimatedAbsorption -= absorbedMean;
            effMean -= absorbedMean;
            effMin -= absorbedMin;
            effMax -= absorbedMax;
            if (this.estimatedAbsorption <= 0.0F) {
               this.absorptionTicks = 0;
            }
         }

         float maxHp = player.getMaxHealth();
         float preDamageEstHp = this.estimatedHealth;
         ++this.hitsSinceResync;

         // Check for FLOOR_HIT: hit was considered lethal or brought health down near floor (<= 2.5 HP),
         // but player survived without dying! The estimate was wrong per difetto: raise estHp and widen [min..max].
         boolean nearFloor = (preDamageEstHp <= 2.5F || effMean >= preDamageEstHp);
         if (nearFloor && player.isAlive() && !player.isDeadOrDying()) {
            float floor = 1.0F;
            float targetMax = Math.min(maxHp, Math.max(this.maxEstimatedHealth, Math.min(maxHp, 4.0F + this.hitsSinceResync * 1.5F)));
            this.maxEstimatedHealth = targetMax;
            this.minEstimatedHealth = floor;
            // Alza estHp a meta' tra il pavimento e la vita massima stimata
            this.estimatedHealth = (floor + targetMax) / 2.0F;
            this.lowHealthConfirmed = false;
            normalizeBounds(maxHp);

            DebugLogger.logEvent(
               gameTime, "FLOOR_HIT", player.getScoreboardName(),
               this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth,
               player.getHealth(), 0.0F, this.saturationLevel,
               this.foodLevelMin, this.foodLevelMax, effMean, this.lastAttackerScale,
               true, String.format(Locale.ROOT, "hitsSinceResync=%d preEst=%.2f effMean=%.2f", this.hitsSinceResync, preDamageEstHp, effMean)
            );
         } else {
            this.estimatedHealth = Math.max(0.5F, this.estimatedHealth - effMean);
            this.minEstimatedHealth = Math.max(0.5F, this.minEstimatedHealth - effMax);
            // Far crescere l'incertezza con il numero di colpi dall'ultimo resync (non lasciare min=max=0.5)
            float spread = 0.5F * this.hitsSinceResync;
            this.maxEstimatedHealth = Math.min(maxHp, Math.max(this.minEstimatedHealth + spread, this.maxEstimatedHealth - effMin));
            normalizeBounds(maxHp);
         }
      }

      void onHealthPacket(Player player, float hpVal, long gameTime) {
         float maxHp = player.getMaxHealth();
         this.lastHealthPacketGameTime = gameTime;
         if (hpVal <= 0.0F) {
            if (!this.isDead) {
               onDeath(player);
            }
            this.lastPacketHealth = 0.0F;
            return;
         }

         float prevHp = this.lastPacketHealth;
         float rawDelta = prevHp >= 0.0F ? (hpVal - prevHp) : 0.0F;
         this.lastPacketHealth = hpVal;

         boolean estimating = isHealthEstimated(player);

         // Log every health packet with exact raw delta
         DebugLogger.logEvent(
            gameTime, "HEALTH_PKT", player.getScoreboardName(),
            this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth,
            hpVal, rawDelta, this.saturationLevel, this.foodLevelMin, this.foodLevelMax,
            this.lastDamageTaken, this.lastAttackerScale, estimating,
            String.format(Locale.ROOT, "rawDelta=%.2f prevHp=%.2f", rawDelta, prevHp)
         );

         if (this.isDead || this.wasDead || (this.estimatedHealth <= 1.0F && hpVal >= 19.0F)) {
            resetToRespawn(player);
         }

         long ticksSinceDamage = gameTime - this.lastDamageGameTime;
         boolean isDamagePacket = this.pendingDamageMetadataPackets > 0 && ticksSinceDamage >= 0L && ticksSinceDamage <= 1L;
         if (isDamagePacket) {
            --this.pendingDamageMetadataPackets;
         }
         boolean justSpawned = player.tickCount <= 5 || prevHp <= 0.0F;

         // Per-player anomaly detection (requires 3 anomalies before latching healthObfuscated without global AHI fingerprints)
         boolean hasAbsorption = player.getAbsorptionAmount() > 0.0F || this.estimatedAbsorption > 0.0F || this.absorptionTicks > 0;
         if (!justSpawned) {
            if (Math.abs(hpVal - prevHp) < 0.001F) {
               if (!hasAbsorption) {
                  flagAnomaly();
               }
            } else if ((isDamagePacket || ticksSinceDamage == 0L) && hpVal > prevHp + 0.05F) {
               flagAnomaly();
            } else if (!isDamagePacket && ticksSinceDamage > 6L && player.hurtTime == 0 && hpVal < prevHp - 0.05F) {
               flagAnomaly();
            } else if (!isDamagePacket && hpVal > prevHp + 1.08F && hpVal < maxHp - 0.05F) {
               float delta = hpVal - prevHp;
               if (Math.abs(delta - Math.round(delta)) > 0.12F) {
                  flagAnomaly();
               }
            }
         }

         if (justSpawned) {
            if (!estimating) {
               this.estimatedHealth = Mth.clamp(hpVal, 0.5F, maxHp);
               this.minEstimatedHealth = this.estimatedHealth;
               this.maxEstimatedHealth = this.estimatedHealth;
            }
            return;
         }

         if (isDamagePacket) {
            if (!estimating) {
               this.estimatedHealth = Mth.clamp(hpVal, 0.5F, maxHp);
               this.minEstimatedHealth = this.estimatedHealth;
               this.maxEstimatedHealth = this.estimatedHealth;
            }
            return;
         }

         boolean hasRegenEffect = this.regen2Ticks > 0 || player.hasEffect(MobEffects.REGENERATION);
         long interval = this.lastHealPacketGameTime > 0L ? (gameTime - this.lastHealPacketGameTime) : -1L;

         if (!estimating) {
            // Non-AHI server: hpVal is exact server health! Sync health and calibrate saturation from partial heals.
            float healed = hpVal - prevHp;
            this.estimatedHealth = Mth.clamp(hpVal, 0.5F, maxHp);
            this.minEstimatedHealth = this.estimatedHealth;
            this.maxEstimatedHealth = this.estimatedHealth;
            if (healed <= 0.01F) {
               return;
            }

            this.lastHealPacketGameTime = gameTime;
            if (hasRegenEffect) {
               return;
            }

            if ((interval >= 65L && interval <= 105L) || (interval < 0L && ticksSinceDamage >= 65L && ticksSinceDamage <= 105L)) {
               serverHasNaturalRegen = true;
               this.inFastRegenStreak = false;
               onSlowRegenProof(player, gameTime);
               addExhaustion(6.0F);
            } else if (healed <= 1.05F && ((interval >= 7L && interval <= 17L) || (ticksSinceDamage >= 7L && ticksSinceDamage <= 18L))) {
               serverHasNaturalRegen = true;
               this.inFastRegenStreak = true;
               setFoodExact(20);
               if (hpVal < maxHp - 0.03F && healed < 0.98F) {
                  // Exact FoodData formula readback: healed == saturationLevel / 6.0F
                  float exactSat = Mth.clamp(healed * 6.0F, 0.0F, 6.0F);
                  markSaturationKnown(exactSat, "fast_regen_exact_vanilla", player, gameTime);
                  this.exhaustion = 0.0F;
                  addExhaustion(exactSat);
               } else {
                  float sat = (!this.hasObservedEat && this.saturationLevel < 6.0F) ? 6.0F : this.saturationLevel;
                  markSaturationKnown(sat, "fast_regen_10t", player, gameTime);
                  addExhaustion(6.0F * healed);
               }
            }
            return;
         }

         // --- Obfuscated / Estimation mode packet classification ---
         boolean hasPriorCombatOrHealReference = this.lastDamageGameTime > 0L || this.lastHealPacketGameTime > 0L;
         Difficulty diff = getDifficulty();
         boolean matchesPeacefulCadence = (diff == Difficulty.PEACEFUL) && (
            (interval >= 18L && interval <= 22L) || (interval < 0L && ticksSinceDamage >= 18L && ticksSinceDamage <= 22L)
         );
         boolean matchesRegenCadence = hasRegenEffect && (
            (interval >= 18L && interval <= 34L)
               || (interval >= 42L && interval <= 58L)
               || (interval < 0L && this.regen2Ticks > 0)
         );
         boolean matchesFastSaturationCadence = hasPriorCombatOrHealReference && (
            (interval >= 7L && interval <= 17L)
               || (interval < 0L && ticksSinceDamage >= 7L && ticksSinceDamage <= 17L)
         );
         boolean matchesSlowHungerCadence = (interval >= 65L && interval <= 105L)
            || (interval < 0L && ticksSinceDamage >= 65L && ticksSinceDamage <= 105L);

         if (matchesPeacefulCadence) {
            serverHasNaturalRegen = true;
            this.lastHealPacketGameTime = gameTime;
            float before = this.estimatedHealth;
            this.estimatedHealth = Math.min(maxHp, this.estimatedHealth + 1.0F);
            this.minEstimatedHealth = Math.min(maxHp, this.minEstimatedHealth + 1.0F);
            this.maxEstimatedHealth = Math.min(maxHp, this.maxEstimatedHealth + 1.0F);
            normalizeBounds(maxHp);
            recordSameTickDelta(gameTime, this.estimatedHealth - before);
            DebugLogger.logEvent(
               gameTime, "HEAL_PEACEFUL_20T", player.getScoreboardName(),
               this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth, hpVal,
               this.saturationLevel, this.foodLevelMin, this.foodLevelMax, this.lastDamageTaken, this.lastAttackerScale,
               true, "interval=" + interval
            );
         } else if (matchesRegenCadence) {
            this.lastHealPacketGameTime = gameTime;
            float before = this.estimatedHealth;
            this.estimatedHealth = Math.min(maxHp, this.estimatedHealth + 1.0F);
            this.minEstimatedHealth = Math.min(maxHp, this.minEstimatedHealth + 1.0F);
            this.maxEstimatedHealth = Math.min(maxHp, this.maxEstimatedHealth + 1.0F);
            normalizeBounds(maxHp);
            recordSameTickDelta(gameTime, this.estimatedHealth - before);
            DebugLogger.logEvent(
               gameTime, "HEAL_REGEN", player.getScoreboardName(),
               this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth, hpVal,
               this.saturationLevel, this.foodLevelMin, this.foodLevelMax, this.lastDamageTaken, this.lastAttackerScale,
               true, "interval=" + interval
            );
         } else if (matchesFastSaturationCadence) {
            // Strict 7..17 tick cadence after damage or previous heal (no unconditional fallback!)
            serverHasNaturalRegen = true;
            this.inFastRegenStreak = true;
            this.lastHealPacketGameTime = gameTime;
            // 10-tick regen in FoodData.tick strictly proves foodLevel == 20 and saturation > 0 on the server
            setFoodExact(20);
            float satVal = (!this.hasObservedEat && this.saturationLevel < 1.0F && interval >= 7L && interval <= 17L) ? 2.0F : Math.max(1.0F, this.saturationLevel);
            markSaturationKnown(satVal, "fast_regen_10t", player, gameTime);
            float f = Math.min(Math.max(this.saturationLevel, 1.0F), 6.0F);
            addExhaustion(f);
            // Point 3: Treat heal cadence amount as uncertain (SMP servers often nerf heal to 0.01..0.02)
            // Widen max bound without aggressively pushing the point estimate
            this.maxEstimatedHealth = Math.min(maxHp, this.maxEstimatedHealth + 1.0F);
            normalizeBounds(maxHp);
            recordSameTickDelta(gameTime, 0.0F);
            DebugLogger.logEvent(
               gameTime, "HEAL_FAST_10T", player.getScoreboardName(),
               this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth, hpVal, rawDelta,
               this.saturationLevel, this.foodLevelMin, this.foodLevelMax, this.lastDamageTaken, this.lastAttackerScale,
               true, String.format(Locale.ROOT, "interval=%d healUncertain=true rawDelta=%.2f", interval, rawDelta)
            );
         } else if (matchesSlowHungerCadence) {
            // 80-tick slow hunger regen strictly proves server saturationLevel == 0.0F and foodLevel >= 18!
            serverHasNaturalRegen = true;
            this.inFastRegenStreak = false;
            this.lastHealPacketGameTime = gameTime;
            onSlowRegenProof(player, gameTime);
            addExhaustion(6.0F);
            this.lowHealthConfirmed = true;
            this.hitsSinceResync = Math.max(0, this.hitsSinceResync - 2);
            // Point 3: Treat heal cadence amount as uncertain (SMP servers often nerf heal to 0.01..0.02)
            this.maxEstimatedHealth = Math.min(maxHp, this.maxEstimatedHealth + 1.0F);
            normalizeBounds(maxHp);
            recordSameTickDelta(gameTime, 0.0F);
            DebugLogger.logEvent(
               gameTime, "HEAL_SLOW_80T_RESYNC_SAT0", player.getScoreboardName(),
               this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth, hpVal, rawDelta,
               this.saturationLevel, this.foodLevelMin, this.foodLevelMax, this.lastDamageTaken, this.lastAttackerScale,
               true, String.format(Locale.ROOT, "interval=%d healUncertain=true rawDelta=%.2f", interval, rawDelta)
            );
         } else {
            // Out-of-cadence packet with no DamageEvent: unknown environmental/DoT damage (fire, poison, wither, starve, drowning)
            // or pre-injured player regenerating right on first sighting. Never raise point estimate; lower prudently & widen uncertainty!
            this.inFastRegenStreak = false;
            boolean isStarving = diff != Difficulty.PEACEFUL && this.foodLevelEst <= 0;
            boolean visibleDot = player.isOnFire()
               || player.hasEffect(MobEffects.POISON)
               || player.hasEffect(MobEffects.WITHER)
               || player.isUnderWater()
               || isStarving;

            float lowerBound = 0.5F;
            if (isStarving && !player.isOnFire() && !player.hasEffect(MobEffects.POISON) && !player.hasEffect(MobEffects.WITHER) && !player.isUnderWater()) {
               if (diff == Difficulty.EASY) {
                  lowerBound = 10.0F;
               } else if (diff == Difficulty.NORMAL) {
                  lowerBound = 1.0F;
               }
            }
            if (visibleDot) {
               float before = this.estimatedHealth;
               this.estimatedHealth = Mth.clamp(this.estimatedHealth - 1.0F, lowerBound, maxHp);
               this.minEstimatedHealth = Mth.clamp(this.minEstimatedHealth - 2.0F, lowerBound, maxHp);
               normalizeBounds(maxHp);
               recordSameTickDelta(gameTime, this.estimatedHealth - before);
               DebugLogger.logEvent(
                  gameTime, "UNKNOWN_DOT_PKT", player.getScoreboardName(),
                  this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth, hpVal, rawDelta,
                  this.saturationLevel, this.foodLevelMin, this.foodLevelMax, this.lastDamageTaken, this.lastAttackerScale,
                  true, String.format(Locale.ROOT, "interval=%d ticksSinceDmg=%d dot=true rawDelta=%.2f", interval, ticksSinceDamage, rawDelta)
               );
            } else {
               // Point 2: UNCERTAIN direction: DO NOT move estimatedHealth! Only widen [min..max]
               this.minEstimatedHealth = Mth.clamp(this.minEstimatedHealth - 2.0F, lowerBound, maxHp);
               this.maxEstimatedHealth = Mth.clamp(this.maxEstimatedHealth + 1.0F, lowerBound, maxHp);
               if (!hasPriorCombatOrHealReference) {
                  this.lastHealPacketGameTime = gameTime;
                  this.minEstimatedHealth = Math.min(this.minEstimatedHealth, maxHp * 0.5F);
               }
               normalizeBounds(maxHp);
               recordSameTickDelta(gameTime, 0.0F);
               DebugLogger.logEvent(
                  gameTime, "UNCERTAIN_PKT", player.getScoreboardName(),
                  this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth, hpVal, rawDelta,
                  this.saturationLevel, this.foodLevelMin, this.foodLevelMax, this.lastDamageTaken, this.lastAttackerScale,
                  true, String.format(Locale.ROOT, "interval=%d ticksSinceDmg=%d dot=false rawDelta=%.2f", interval, ticksSinceDamage, rawDelta)
               );
            }
         }
      }

      private void recordSameTickDelta(long gameTime, float deltaMean) {
         this.lastSameTickPacketGameTime = gameTime;
         this.lastSameTickDeltaMean = deltaMean;
      }

      private void normalizeBounds(float maxHp) {
         this.minEstimatedHealth = Mth.clamp(this.minEstimatedHealth, 0.5F, maxHp);
         this.maxEstimatedHealth = Mth.clamp(Math.max(this.minEstimatedHealth, this.maxEstimatedHealth), 0.5F, maxHp);
         this.estimatedHealth = Mth.clamp(this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth);
      }

      void tickSelf(Player self, long gameTime) {
         if (this.recentEatCooldown > 0) {
            --this.recentEatCooldown;
         }
         if (this.jumpCooldown > 0) {
            --this.jumpCooldown;
         }
         updateRememberedFood(self);

         if (!self.isAlive() || self.getHealth() <= 0.0F) {
            this.wasDead = true;
            this.saturationLevel = 0.0F;
            this.exhaustion = 0.0F;
            this.lastSelfHealth = 0.0F;
            return;
         }

         if (this.wasDead) {
            this.wasDead = false;
            setFoodExact(20);
            this.saturationLevel = 5.0F;
            this.saturationKnown = true;
            this.exhaustion = 0.0F;
            this.selfLastX = self.getX();
            this.selfLastY = self.getY();
            this.selfLastZ = self.getZ();
            this.selfWasOnGround = self.onGround();
            this.lastSelfHealth = self.getHealth();
            return;
         }

         int exactFood = self.getFoodData().getFoodLevel();
         this.foodLevelMin = exactFood;
         this.foodLevelEst = exactFood;
         this.foodLevelMax = exactFood;
         this.foodKnown = true;
         this.foodExact = true;
         this.saturationKnown = true;

         float rawSat = self.getFoodData().getSaturationLevel();
         if (!Float.isNaN(rawSat)) {
            this.saturationLevel = Mth.clamp(rawSat, 0.0F, 20.0F);
            if (this.saturationLevel <= 0.0F) {
               this.exhaustion = 0.0F;
            }
         } else {
            handleEating(self, true, gameTime);
            float healed = self.getHealth() - this.lastSelfHealth;
            if (healed > 0.01F && healed <= 1.05F && !self.hasEffect(MobEffects.REGENERATION)) {
               if (self.getHealth() < self.getMaxHealth() - 0.03F && healed < 0.98F) {
                  float exactSat = Mth.clamp(healed * 6.0F, 0.5F, 6.0F);
                  this.saturationLevel = exactSat;
                  this.exhaustion = 0.0F;
                  addExhaustion(exactSat);
               } else {
                  addExhaustion(6.0F * healed);
               }
            }
            handleSelfMovementAndEffects(self);
            drainExhaustionSingleTick();
            this.saturationLevel = Mth.clamp(Math.max(0.5F, this.saturationLevel), 0.5F, (float) exactFood);
         }

         this.selfLastX = self.getX();
         this.selfLastY = self.getY();
         this.selfLastZ = self.getZ();
         this.selfWasOnGround = self.onGround();
         this.lastSelfHealth = self.getHealth();
      }

      private void handleSelfMovementAndEffects(Player self) {
         double dx = self.getX() - this.selfLastX;
         double dy = self.getY() - this.selfLastY;
         double dz = self.getZ() - this.selfLastZ;
         double horizSq = dx * dx + dz * dz;
         if (horizSq > 1.0E-4 && horizSq < 16.0 && !self.isPassenger()) {
            float horiz = (float) Math.sqrt(horizSq);
            if (self.isSwimming() || self.isUnderWater() || self.isInWater()) {
               addExhaustion(0.01F * horiz);
            } else if (self.isSprinting()) {
               addExhaustion(0.1F * horiz);
            }
         }
         if (this.jumpCooldown == 0 && this.selfWasOnGround && !self.onGround() && dy > 0.10 && self.hurtTime == 0 && !self.isInWater() && !self.onClimbable()) {
            addExhaustion(self.isSprinting() ? 0.2F : 0.05F);
            this.jumpCooldown = 8;
         }
         MobEffectInstance hungerEffect = self.getEffect(MobEffects.HUNGER);
         if (hungerEffect != null) {
            addExhaustion(0.005F * (float) (hungerEffect.getAmplifier() + 1));
         }
      }

      void tickRemote(Player player, Player self, long gameTime) {
         if (this.recentEatCooldown > 0) {
            --this.recentEatCooldown;
         }
         if (this.damageInvulnerabilityTicks > 0) {
            --this.damageInvulnerabilityTicks;
            if (this.damageInvulnerabilityTicks == 0) {
               this.lastHitMeanInWindow = 0.0F;
               this.lastHitMinInWindow = 0.0F;
               this.lastHitMaxInWindow = 0.0F;
            }
         }
         if (this.absorptionTicks > 0) {
            --this.absorptionTicks;
            if (this.absorptionTicks == 0) {
               this.estimatedAbsorption = 0.0F;
            }
         }
         if (this.regen2Ticks > 0) {
            --this.regen2Ticks;
         }
         if (this.jumpCooldown > 0) {
            --this.jumpCooldown;
         }

         updateRememberedFood(player);

         if (!player.isAlive() || player.getHealth() <= 0.0F) {
            if (!this.isDead) {
               onDeath(player);
            }
            return;
         }

         if (this.isDead || this.wasDead || (this.estimatedHealth <= 1.0F && player.getHealth() >= 19.0F)) {
            resetToRespawn(player);
         }

         if (this.wasDead) {
            // Respawn reset: health is full, food and saturation unknown until evidence
            this.wasDead = false;
            this.foodLevelMin = 18;
            this.foodLevelEst = 20;
            this.foodLevelMax = 20;
            normalizeFood();
            this.foodKnown = false;
            this.foodExact = false;
            this.saturationLevel = 0.0F;
            this.saturationKnown = false;
            this.exhaustion = 0.0F;
            float maxHp = player.getMaxHealth();
            this.estimatedHealth = maxHp;
            this.minEstimatedHealth = maxHp;
            this.maxEstimatedHealth = maxHp;
            this.estimatedAbsorption = 0.0F;
            this.absorptionTicks = 0;
            this.regen2Ticks = 0;
            this.inFastRegenStreak = false;
            this.hasObservedEat = false;
            this.hasObservedSubMaxFoodEat = false;
            this.peakFallDistance = 0.0F;
            Vec3 base = player.getPositionCodec().getBase();
            this.serverX = base.x;
            this.serverY = base.y;
            this.serverZ = base.z;
            this.serverOnGround = player.onGround();
            this.lastPacketHealth = player.getHealth();
            this.lastServerAbsorption = player.getAbsorptionAmount();
            DebugLogger.logEvent(
               gameTime, "RESPAWN_RESYNC", player.getScoreboardName(),
               maxHp, maxHp, maxHp, player.getHealth(), 5.0F, 20, 20, 0.0F, 1.0F,
               isHealthEstimated(player), "respawn_full_reset"
            );
            return;
         }

         float serverHp = player.getHealth();
         float maxHp = player.getMaxHealth();

         // Per-player static maxHp spoofing anomaly check (requires 3 hits/anomalies before latching without fingerprints)
         long ticksSinceDamage = gameTime - this.lastDamageGameTime;
         boolean hasAbsorption = player.getAbsorptionAmount() > 0.0F || this.estimatedAbsorption > 0.0F || this.absorptionTicks > 0;
         if (ticksSinceDamage == 4L && this.estimatedHealth < maxHp - 0.4F && Math.abs(serverHp - maxHp) < 0.01F && !hasAbsorption) {
            flagAnomaly();
         }

         // Peaceful difficulty auto-regeneration and food/saturation recovery
         Difficulty currentDiff = getDifficulty();
         if (currentDiff == Difficulty.PEACEFUL && serverHasNaturalRegen) {
            if (player.tickCount % 20 == 0) {
               if (this.estimatedHealth < maxHp) {
                  this.estimatedHealth = Math.min(maxHp, this.estimatedHealth + 1.0F);
                  this.minEstimatedHealth = Math.min(maxHp, this.minEstimatedHealth + 1.0F);
                  this.maxEstimatedHealth = Math.min(maxHp, this.maxEstimatedHealth + 1.0F);
                  normalizeBounds(maxHp);
               }
               if (this.saturationLevel < 20.0F) {
                  this.saturationLevel = Math.min(20.0F, this.saturationLevel + 1.0F);
               }
            }
            if (player.tickCount % 10 == 0 && this.foodLevelEst < 20) {
               this.foodLevelMin = Math.min(20, this.foodLevelMin + 1);
               this.foodLevelEst = Math.min(20, this.foodLevelEst + 1);
               this.foodLevelMax = Math.min(20, this.foodLevelMax + 1);
               normalizeFood();
            }
         }

         boolean estimating = isHealthEstimated(player);

         Float scoreboardHp = (getEstimationMode() == EstimationModeEnum.AUTO) ? getScoreboardHealth(player, self) : null;
         if (scoreboardHp != null) {
            float clampedScoreHp = Mth.clamp(scoreboardHp, 0.5F, maxHp);
            float scoreHealed = clampedScoreHp - this.estimatedHealth;
            if (scoreHealed > 0.01F && scoreHealed <= 2.5F && this.regen2Ticks <= 0 && !player.hasEffect(MobEffects.REGENERATION)) {
               foodAtLeast(18);
               addExhaustion(6.0F * scoreHealed);
            }
            this.estimatedHealth = clampedScoreHp;
            this.minEstimatedHealth = clampedScoreHp;
            this.maxEstimatedHealth = clampedScoreHp;
         } else if (!estimating) {
            this.estimatedHealth = Mth.clamp(serverHp, 0.5F, maxHp);
            this.minEstimatedHealth = this.estimatedHealth;
            this.maxEstimatedHealth = this.estimatedHealth;
         } else {
            // Resync deduction when server heal-packet stream stops:
            long ticksSinceHeal = gameTime - this.lastHealPacketGameTime;
            if (this.inFastRegenStreak && ticksSinceHeal == 22L && ticksSinceDamage > 20L) {
               this.inFastRegenStreak = false;
               if (this.saturationLevel > 0.8F) {
                  // Player still had saturation when 10-tick heals stopped -> reached full health on server!
                  this.estimatedHealth = maxHp;
                  this.maxEstimatedHealth = maxHp;
                  this.minEstimatedHealth = Math.max(this.minEstimatedHealth, maxHp - 1.0F);
                  DebugLogger.logEvent(
                     gameTime, "FULL_HP_RESYNC_10T_STOP", player.getScoreboardName(),
                     this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth, serverHp,
                     this.saturationLevel, this.foodLevelMin, this.foodLevelMax, this.lastDamageTaken, this.lastAttackerScale,
                     true, "fast_regen_stopped_with_sat_remaining"
                  );
               } else {
                  this.saturationLevel = 0.0F;
               }
            } else if (this.lastHealPacketGameTime > this.lastDamageGameTime
               && ticksSinceHeal == 95L
               && ticksSinceDamage > 95L
               && this.foodLevelMin >= 18) {
               // Neither 10-tick nor 80-tick heal packets continued while food >= 18 -> player reached full health!
               this.estimatedHealth = maxHp;
               this.maxEstimatedHealth = maxHp;
               this.minEstimatedHealth = Math.max(this.minEstimatedHealth, maxHp - 1.5F);
               DebugLogger.logEvent(
                  gameTime, "FULL_HP_RESYNC_95T_QUIET", player.getScoreboardName(),
                  this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth, serverHp,
                  this.saturationLevel, this.foodLevelMin, this.foodLevelMax, this.lastDamageTaken, this.lastAttackerScale,
                  true, "no_further_regen_packets_with_food>=18"
               );
            }
         }

         // Zero-saturation deduction: if natural regen is confirmed on server, player was hurt, and no 10-tick heal fired
         if (serverHasNaturalRegen
            && ticksSinceDamage == 22L
            && this.lastHealPacketGameTime < this.lastDamageGameTime
            && this.maxEstimatedHealth < maxHp - 0.5F
            && !this.hasObservedSubMaxFoodEat) {
            this.saturationLevel = 0.0F;
            this.exhaustion = 0.0F;
         }

         // Absorption jump detection (for servers where absorption is not spoofed)
         float currentAbsorption = player.getAbsorptionAmount();
         if (!Float.isNaN(currentAbsorption) && currentAbsorption > this.lastServerAbsorption + 0.5F && this.recentEatCooldown == 0) {
            ItemStack mainHand = player.getMainHandItem();
            ItemStack offHand = player.getOffhandItem();
            ItemStack candidate = resolveFoodProperties(mainHand) != null ? mainHand : (resolveFoodProperties(offHand) != null ? offHand : this.lastHeldFoodStack);
            FoodProperties food = resolveFoodProperties(candidate);
            if (food != null && (candidate.is(Items.GOLDEN_APPLE) || candidate.is(Items.ENCHANTED_GOLDEN_APPLE) || currentAbsorption >= 3.5F)) {
               applyFood(food, candidate, player, gameTime);
               this.usingFood = null;
               this.ticksUsed = 0;
            }
         }

         boolean closeEnoughForBurpPacket = self != null && player.distanceToSqr(self) <= 225.0;
         handleEating(player, closeEnoughForBurpPacket, gameTime);

         MobEffectInstance hungerEffect = player.getEffect(MobEffects.HUNGER);
         if (hungerEffect != null) {
            addExhaustion(0.005F * (float) (hungerEffect.getAmplifier() + 1));
         }

         applyFoodConstraints(player, gameTime);
         drainExhaustionSingleTick();
         this.lastServerAbsorption = Float.isNaN(currentAbsorption) ? 0.0F : currentAbsorption;
      }

      private void updateRememberedFood(Player player) {
         ItemStack useItem = player.getUseItem();
         if (resolveFoodProperties(useItem) != null) {
            this.lastHeldFoodStack = useItem.copy();
            this.lastHeldFoodAge = 0;
            return;
         }
         ItemStack mainHand = player.getMainHandItem();
         if (resolveFoodProperties(mainHand) != null) {
            this.lastHeldFoodStack = mainHand.copy();
            this.lastHeldFoodAge = 0;
            return;
         }
         ItemStack offHand = player.getOffhandItem();
         if (resolveFoodProperties(offHand) != null) {
            this.lastHeldFoodStack = offHand.copy();
            this.lastHeldFoodAge = 0;
            return;
         }
         if (this.lastHeldFoodAge < 100) {
            ++this.lastHeldFoodAge;
         }
      }

      private void handleEating(Player player, boolean expectBurpSound, long gameTime) {
         int completionThreshold = expectBurpSound ? this.requiredConsumeTicks : Math.max(1, this.requiredConsumeTicks - 2);
         if (player.isUsingItem()) {
            ItemStack activeStack = player.getUseItem();
            if (activeStack.isEmpty()) {
               activeStack = player.getItemInHand(player.getUsedItemHand());
            }

            FoodProperties food = resolveFoodProperties(activeStack);
            if (food != null) {
               int vanillaTicks = player.getTicksUsingItem();
               if (this.usingFood != null && vanillaTicks < this.lastVanillaTicksUsing && this.ticksUsed >= completionThreshold) {
                  applyFood(this.usingFood, this.usingStack, player, gameTime);
                  this.ticksUsed = 0;
               }

               // Consumable.use only starts when Player.canEat(canAlwaysEat) => a non-always-edible food implies foodLevel < 20

               if ((this.usingFood == null || vanillaTicks < this.lastVanillaTicksUsing) && !food.canAlwaysEat()) {

                  foodAtMost(19);

               }

               this.usingFood = food;
               this.usingStack = activeStack.copy();
               Consumable consumable = resolveConsumable(activeStack);
               this.requiredConsumeTicks = consumable != null ? Math.max(1, consumable.consumeTicks()) : 32;
               this.ticksUsed = Math.max(this.ticksUsed + 1, vanillaTicks);
               this.lastVanillaTicksUsing = vanillaTicks;
            } else {
               this.usingFood = null;
               this.usingStack = ItemStack.EMPTY;
               this.ticksUsed = 0;
               this.lastVanillaTicksUsing = 0;
            }
         } else if (this.usingFood != null) {
            if (this.ticksUsed >= completionThreshold) {
               applyFood(this.usingFood, this.usingStack, player, gameTime);
            }
            this.usingFood = null;
            this.usingStack = ItemStack.EMPTY;
            this.ticksUsed = 0;
            this.lastVanillaTicksUsing = 0;
         }
      }

      void applyFood(FoodProperties food, ItemStack stack, Player player, long gameTime) {
         if (this.recentEatCooldown > 0) {
            return;
         }
         this.satZeroConfirmed = false;
         this.foodKnown = true;
         this.foodLevelMin = Mth.clamp(this.foodLevelMin + food.nutrition(), 0, 20);
         this.foodLevelEst = Mth.clamp(this.foodLevelEst + food.nutrition(), 0, 20);
         this.foodLevelMax = Mth.clamp(this.foodLevelMax + food.nutrition(), 0, 20);
         float baseSat = this.saturationKnown ? this.saturationLevel : 0.0F;
         markSaturationKnown(baseSat + food.saturation(), "eat_" + stack.getItem().toString(), player, gameTime);
         this.hasObservedEat = true;
         this.hasObservedSubMaxFoodEat = this.foodLevelEst < 20;
         this.recentEatCooldown = 12;

         if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
            this.regen2Ticks = Math.max(this.regen2Ticks, 400);
            this.estimatedAbsorption = Math.max(this.estimatedAbsorption, 16.0F);
            this.absorptionTicks = 2400;
         } else if (stack.is(Items.GOLDEN_APPLE)) {
            this.regen2Ticks = Math.max(this.regen2Ticks, 100);
            this.estimatedAbsorption = Math.max(this.estimatedAbsorption, 4.0F);
            this.absorptionTicks = 2400;
         }

         DebugLogger.logEvent(
            gameTime, "EAT", player.getScoreboardName(),
            this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth,
            player.getHealth(), this.saturationLevel, this.foodLevelMin, this.foodLevelMax,
            this.lastDamageTaken, this.lastAttackerScale, isHealthEstimated(player),
            String.format(Locale.ROOT, "item=%s +%d/%.1f", stack.getItem().toString(), food.nutrition(), food.saturation())
         );
      }

      void setFoodExact(int food) {
         int f = Mth.clamp(food, 0, 20);
         this.foodLevelMin = f;
         this.foodLevelEst = f;
         this.foodLevelMax = f;
         this.foodKnown = true;
         this.foodExact = true;
         capSaturationToFood();
      }

      /** Constraint food >= n (e.g. slow regen proves >= 18, sprint proves > 6). */
      void foodAtLeast(int n) {
         this.foodLevelMin = Math.max(this.foodLevelMin, Math.min(n, 20));
         if (this.foodLevelMax < this.foodLevelMin) {
            this.foodLevelMax = this.foodLevelMin;
         }
         normalizeFood();
      }

      /** Constraint food <= n (e.g. starting a non-always-edible food proves < 20). */
      void foodAtMost(int n) {
         this.foodLevelMax = Math.min(this.foodLevelMax, Math.max(n, 0));
         if (this.foodLevelMin > this.foodLevelMax) {
            this.foodLevelMin = this.foodLevelMax;
         }
         normalizeFood();
      }

      void normalizeFood() {
         this.foodLevelMin = Mth.clamp(this.foodLevelMin, 0, 20);
         this.foodLevelMax = Mth.clamp(this.foodLevelMax, this.foodLevelMin, 20);
         this.foodLevelEst = Mth.clamp(this.foodLevelEst, this.foodLevelMin, this.foodLevelMax);
         capSaturationToFood();
      }

      /** Vanilla invariant: saturation never exceeds the food level, so it can never exceed foodMax. */
      private void capSaturationToFood() {
         if (this.saturationLevel > (float) this.foodLevelMax) {
            this.saturationLevel = (float) this.foodLevelMax;
         }
      }

      /**
       * FoodData.tick (26.1.2): 10-tick heal needs sat > 0 and food >= 20; the 80-tick heal is the else-branch with food >= 18.
       * So a slow heal proves food >= 18, and proves saturation == 0 only when food is 20.
       */
      void onSlowRegenProof(Player player, long gameTime) {
         foodAtLeast(18);
         this.foodKnown = true;
         markSaturationKnown(0.0F, "slow_regen_80t_sat0", player, gameTime);
         this.exhaustion = 0.0F;
         this.satZeroConfirmed = true;
      }

      /** Per-tick constraints derived from observable behaviour. */
      private void applyFoodConstraints(Player player, long gameTime) {
         // Vanilla clients stop/refuse sprinting when food <= 6 (Player.hasEnoughFood: food > 6); creative/spectator/mayfly are exempt.
         if (player.isSprinting() && !player.isPassenger() && !player.isCreative() && !player.isSpectator()) {
            foodAtLeast(FOOD_SPRINT_MIN_LEVEL);
         }

         // Hurt + natural regen on + no health packet for 90+ ticks => FoodData slow heal did not run => food < 18.
         if (serverHasNaturalRegen
            && isHealthEstimated(player)
            && this.foodLevelMin < 18
            && this.lastHealthPacketGameTime > 0L
            && this.maxEstimatedHealth < player.getMaxHealth() - 0.5F) {
            long reference = Math.max(Math.max(this.lastHealthPacketGameTime, this.lastDamageGameTime), this.lastHealPacketGameTime);
            if (gameTime - reference == FOOD_QUIET_WINDOW_TICKS) {
               foodAtMost(17);
               DebugLogger.logEvent(
                  gameTime, "FOOD_LT_18_QUIET", player.getScoreboardName(),
                  this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth, player.getHealth(),
                  this.saturationLevel, this.foodLevelMin, this.foodLevelMax, this.lastDamageTaken, this.lastAttackerScale,
                  true, "hurt_no_heal_packet_" + FOOD_QUIET_WINDOW_TICKS + "t"
               );
            }
         }
      }

      void addExhaustion(float amount) {
         this.exhaustion = Math.min(this.exhaustion + amount, 40.0F);
      }

      private void drainExhaustionSingleTick() {
         if (this.exhaustion > 4.0F) {
            this.exhaustion -= 4.0F;
            if (this.saturationLevel > 0.0F) {
               this.saturationLevel = Math.max(this.saturationLevel - 1.0F, 0.0F);
            } else if (getDifficulty() != Difficulty.PEACEFUL) {
               this.foodLevelMin = Math.max(this.foodLevelMin - 1, 0);
               this.foodLevelEst = Math.max(this.foodLevelEst - 1, 0);
               if (this.satZeroConfirmed) {
                  this.foodLevelMax = Math.max(this.foodLevelMax - 1, 0);
               }
               this.foodExact = false;
               normalizeFood();
            }
         }
      }
   }

   private static final class TrackedMobState {
      float estimatedHealth;
      float minEstimatedHealth;
      float maxEstimatedHealth;
      float estimatedAbsorption = 0.0F;
      int absorptionTicks = 0;
      int regen2Ticks = 0;
      boolean healthObfuscated = false;
      int anomalyCount = 0;
      long lastDamageGameTime = -1000L;
      int pendingDamageMetadataPackets = 0;
      float lastPacketHealth = -1.0F;
      int damageInvulnerabilityTicks = 0;
      float lastHitMeanInWindow = 0.0F;
      int staleTicks = 0;

      TrackedMobState(LivingEntity entity) {
         float maxHp = entity.getMaxHealth();
         this.estimatedHealth = maxHp;
         this.minEstimatedHealth = maxHp;
         this.maxEstimatedHealth = maxHp;
         this.lastPacketHealth = entity.getHealth();
      }

      void flagAnomaly() {
         if (++this.anomalyCount >= ANOMALY_THRESHOLD) {
            this.healthObfuscated = true;
         }
      }

      void applyEstimatedDamage(LivingEntity entity, DamageEstimate estimate, long gameTime) {
         if (estimate.mean <= 0.0F && estimate.max <= 0.0F) {
            return;
         }

         float effMean = estimate.mean;
         float effMin = estimate.min;
         float effMax = estimate.max;
         if (this.damageInvulnerabilityTicks > 0) {
            if (effMean <= this.lastHitMeanInWindow) {
               return;
            }
            effMean -= this.lastHitMeanInWindow;
            effMin = Math.max(0.0F, effMin - this.lastHitMeanInWindow);
            effMax = Math.max(0.0F, effMax - this.lastHitMeanInWindow);
            this.lastHitMeanInWindow = estimate.mean;
         } else {
            this.damageInvulnerabilityTicks = 10;
            this.lastHitMeanInWindow = estimate.mean;
         }

         this.lastDamageGameTime = gameTime;
         this.pendingDamageMetadataPackets = 1;
         this.staleTicks = 0;

         if (this.estimatedAbsorption > 0.0F && this.absorptionTicks > 0) {
            float absorbed = Math.min(this.estimatedAbsorption, effMean);
            this.estimatedAbsorption -= absorbed;
            effMean -= absorbed;
            effMin = Math.max(0.0F, effMin - absorbed);
            effMax = Math.max(0.0F, effMax - absorbed);
         }

         float maxHp = entity.getMaxHealth();
         this.estimatedHealth = Mth.clamp(this.estimatedHealth - effMean, 0.5F, maxHp);
         this.minEstimatedHealth = Mth.clamp(this.minEstimatedHealth - effMax, 0.5F, maxHp);
         this.maxEstimatedHealth = Mth.clamp(Math.max(this.minEstimatedHealth, this.maxEstimatedHealth - effMin), 0.5F, maxHp);
         this.estimatedHealth = Mth.clamp(this.estimatedHealth, this.minEstimatedHealth, this.maxEstimatedHealth);
      }

      void onHealthPacket(LivingEntity entity, float hpVal, long gameTime) {
         float maxHp = entity.getMaxHealth();
         if (hpVal <= 0.0F) {
            this.estimatedHealth = 0.0F;
            this.minEstimatedHealth = 0.0F;
            this.maxEstimatedHealth = 0.0F;
            this.lastPacketHealth = 0.0F;
            return;
         }

         float prevHp = this.lastPacketHealth;
         this.lastPacketHealth = hpVal;

         // Iron Golem crack-tier resync: AHI MetadataSpoofer.spoofIronGolemMetadata sends 100f, 74f, 49f, or 24f
         if (entity instanceof IronGolem && isHealthEstimated(entity)) {
            applyIronGolemTierResync(hpVal, maxHp);
            return;
         }

         long ticksSinceDamage = gameTime - this.lastDamageGameTime;
         boolean isDamagePacket = this.pendingDamageMetadataPackets > 0 && ticksSinceDamage >= 0L && ticksSinceDamage <= 1L;
         if (isDamagePacket) {
            --this.pendingDamageMetadataPackets;
         }
         boolean justSpawned = entity.tickCount <= 5 || prevHp <= 0.0F;

         boolean hasMobAbs = entity.getAbsorptionAmount() > 0.0F || this.estimatedAbsorption > 0.0F || this.absorptionTicks > 0;
         if (!justSpawned) {
            if ((Math.abs(hpVal - prevHp) < 0.001F && !hasMobAbs)
               || ((isDamagePacket || ticksSinceDamage == 0L) && hpVal > prevHp + 0.05F)
               || (!isDamagePacket && ticksSinceDamage > 6L && entity.hurtTime == 0 && hpVal < prevHp - 0.05F)) {
               flagAnomaly();
            }
         }

         if (!isHealthEstimated(entity)) {
            this.estimatedHealth = Mth.clamp(hpVal, 0.5F, maxHp);
            this.minEstimatedHealth = this.estimatedHealth;
            this.maxEstimatedHealth = this.estimatedHealth;
         } else if (!justSpawned && !isDamagePacket && this.regen2Ticks > 0) {
            this.estimatedHealth = Math.min(maxHp, this.estimatedHealth + 1.0F);
            this.minEstimatedHealth = Math.min(maxHp, this.minEstimatedHealth + 1.0F);
            this.maxEstimatedHealth = Math.min(maxHp, this.maxEstimatedHealth + 1.0F);
         }
      }

      private void applyIronGolemTierResync(float spoofedHp, float maxHp) {
         float ratio = spoofedHp / Math.max(1.0F, maxHp);
         float tierMin;
         float tierMax;
         if (ratio >= 0.745F) {
            tierMin = maxHp * 0.75F;
            tierMax = maxHp;
         } else if (ratio >= 0.495F) {
            tierMin = maxHp * 0.50F;
            tierMax = maxHp * 0.749F;
         } else if (ratio >= 0.245F) {
            tierMin = maxHp * 0.25F;
            tierMax = maxHp * 0.499F;
         } else {
            tierMin = 0.5F;
            tierMax = maxHp * 0.249F;
         }
         this.minEstimatedHealth = Math.max(tierMin, Math.min(this.minEstimatedHealth, tierMax));
         this.maxEstimatedHealth = Math.min(tierMax, Math.max(this.maxEstimatedHealth, tierMin));
         this.estimatedHealth = Mth.clamp(this.estimatedHealth, tierMin, tierMax);
      }

      void tick(LivingEntity entity, long gameTime) {
         this.staleTicks = 0;
         if (this.damageInvulnerabilityTicks > 0) {
            --this.damageInvulnerabilityTicks;
            if (this.damageInvulnerabilityTicks == 0) {
               this.lastHitMeanInWindow = 0.0F;
            }
         }
         if (this.absorptionTicks > 0) {
            --this.absorptionTicks;
            if (this.absorptionTicks == 0) {
               this.estimatedAbsorption = 0.0F;
            }
         }
         if (this.regen2Ticks > 0) {
            --this.regen2Ticks;
         }

         float serverHp = entity.getHealth();
         float maxHp = entity.getMaxHealth();
         boolean hasMobAbs = entity.getAbsorptionAmount() > 0.0F || this.estimatedAbsorption > 0.0F || this.absorptionTicks > 0;
         if (gameTime - this.lastDamageGameTime == 4L && this.estimatedHealth < maxHp - 0.4F && Math.abs(serverHp - maxHp) < 0.01F && !hasMobAbs) {
            flagAnomaly();
         }

         if (!isHealthEstimated(entity)) {
            this.estimatedHealth = Mth.clamp(serverHp, 0.5F, maxHp);
            this.minEstimatedHealth = this.estimatedHealth;
            this.maxEstimatedHealth = this.estimatedHealth;
         }
      }
   }
}
