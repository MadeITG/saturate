package local.saturate;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import local.saturate.config.Config;
import local.saturate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

public class RenderTracker {
   private static final Minecraft client = Minecraft.getInstance();
   private static final ConcurrentHashMap<UUID, Integer> UUIDS = new ConcurrentHashMap<>();
   public static boolean after_attack = ModConfig.HANDLER.instance().after_attack;
   private static LivingEntity trackedEntity;

   public static LivingEntity getTrackedEntity() {
      return trackedEntity;
   }

   public static void setTrackedEntity(LivingEntity trackedEntity) {
      RenderTracker.trackedEntity = trackedEntity;
   }

   public static void tick(Minecraft client) {
      if (client.player != null && client.level != null) {
         if (Config.getRenderingEnabled()) {
            for (Entity entity : client.level.entitiesForRendering()) {
               if (entity instanceof LivingEntity livingEntity) {
                  if (satisfiesAdvancedCriteria(client.player, livingEntity) && satisfiesList(client.player, livingEntity)) {
                     addToUUIDS(livingEntity);
                     continue;
                  }
               }

               removeFromUUIDS(entity.getUUID());
            }
         }

         trimEntities(client.level);
         if (ModConfig.HANDLER.instance().after_attack != after_attack) {
            UUIDS.clear();
            after_attack = ModConfig.HANDLER.instance().after_attack;
         }

         if (getTrackedEntity() == null || getTrackedEntity().isDeadOrDying() || getTrackedEntity().isRemoved()) {
            setTrackedEntity(null);
         }
      }
   }

   public static void onDamage(DamageSource damageSource, LivingEntity livingEntity) {
      if (damageSource.getEntity() instanceof Player) {
         assert client.level != null;

         ModConfig config = ModConfig.HANDLER.instance();
         if (config.after_attack && livingEntity != null && isEntityTypeAllowed(livingEntity, client.player) && satisfiesList(client.player, livingEntity) && !addToUUIDS(livingEntity)) {
            UUIDS.replace(livingEntity.getUUID(), config.time_after_hit * 20);
         }
      }
   }

   public static void trimEntities(ClientLevel world) {
      Iterator<Map.Entry<UUID, Integer>> iterator = UUIDS.entrySet().iterator();

      while (iterator.hasNext()) {
         Map.Entry<UUID, Integer> entry = iterator.next();
         entry.setValue(entry.getValue() - 1);
         if (entry.getValue() <= 0) {
            iterator.remove();
         }
      }

      UUIDS.entrySet().removeIf(entry -> isInvalid(getEntityFromUUID(entry.getKey(), world)) || !Config.getRenderingEnabled());
      if (UUIDS.size() >= 1536) {
         UUIDS.clear();
      }
   }

   public static void removeFromUUIDS(Entity entity) {
      UUIDS.remove(entity.getUUID());
   }

   public static void removeFromUUIDS(UUID uuid) {
      UUIDS.remove(uuid);
   }

   public static boolean addToUUIDS(LivingEntity livingEntity) {
      if (!UUIDS.containsKey(livingEntity.getUUID())) {
         ModConfig config = ModConfig.HANDLER.instance();
         UUIDS.put(livingEntity.getUUID(), config.after_attack ? config.time_after_hit * 20 : 2400);
         return true;
      } else {
         return false;
      }
   }

   public static boolean isInUUIDS(LivingEntity livingEntity) {
      return UUIDS.containsKey(livingEntity.getUUID());
   }

   public static boolean overridePlayers(LocalPlayer playerEntity, LivingEntity livingEntity) {
      ModConfig config = ModConfig.HANDLER.instance();
      return (config.override_players && livingEntity instanceof Player && livingEntity != client.player) || (livingEntity == client.player && config.self);
   }

   public static boolean isEntityTypeAllowed(LivingEntity livingEntity, Player self) {
      ModConfig config = ModConfig.HANDLER.instance();
      if (!config.passive_mobs && livingEntity instanceof AgeableMob) {
         return false;
      } else if (!config.hostile_mobs && livingEntity instanceof Monster) {
         return false;
      } else if (!config.players && livingEntity instanceof Player) {
         return false;
      } else {
         return config.self || livingEntity != self;
      }
   }

   public static boolean satisfiesAdvancedCriteria(LocalPlayer player, LivingEntity livingEntity) {
      ModConfig config = ModConfig.HANDLER.instance();
      float effectiveHealth = PlayerSaturationTracker.getEffectiveHealth(livingEntity);
      float effectiveAbsorption = PlayerSaturationTracker.getEffectiveAbsorption(livingEntity);
      if (overridePlayers(player, livingEntity)) {
         return true;
      } else if (!isEntityTypeAllowed(livingEntity, player)) {
         return false;
      } else if (config.after_attack && !UUIDS.containsKey(livingEntity.getUUID())) {
         return false;
      } else if (config.damaged_only && (effectiveHealth == livingEntity.getMaxHealth() || effectiveHealth > livingEntity.getMaxHealth() * ((float) config.max_health_percentage / 100.0F)) && effectiveAbsorption <= 0.0F) {
         return false;
      } else if (config.looking_at && !isTargeted(livingEntity)) {
         return false;
      } else if (config.within_distance && livingEntity.distanceTo(player) > (float) config.distance) {
         return false;
      } else {
         return !isInvalid(livingEntity);
      }
   }

   public static boolean satisfiesList(LocalPlayer player, LivingEntity livingEntity) {
      ModConfig config = ModConfig.HANDLER.instance();
      if (!config.blacklistOrWhitelist) {
         if (config.list.isEmpty()) {
            return true;
         }

         if (config.list.contains("minecraft:player") && livingEntity instanceof Player) {
            return true;
         }
      }

      String[] filterEntries = config.list.toArray(new String[0]);
      return config.blacklistOrWhitelist
         ? Arrays.stream(filterEntries).noneMatch(s -> livingEntity instanceof Player ? Component.nullToEmpty(s).equals(Objects.requireNonNull(livingEntity.getName())) : s.equals(EntityType.getKey(livingEntity.getType()).toString()))
         : Arrays.stream(filterEntries).anyMatch(s -> livingEntity instanceof Player ? Component.nullToEmpty(s).equals(Objects.requireNonNull(livingEntity.getName())) : s.equals(EntityType.getKey(livingEntity.getType()).toString()));
   }

   public static boolean isTargeted(LivingEntity livingEntity) {
      Entity camera = client.getCameraEntity();
      if (camera == null) {
         return false;
      }
      double d = ModConfig.HANDLER.instance().reach;
      double e = Mth.square(d);
      Vec3 vec3d = camera.getEyePosition(0.0F);
      HitResult hitResult = camera.pick(d, 0.0F, false);
      double f = hitResult.getLocation().distanceToSqr(vec3d);
      if (hitResult.getType() != Type.MISS) {
         e = f;
         d = Math.sqrt(f);
      }

      Vec3 vec3d2 = camera.getViewVector(0.0F);
      Vec3 vec3d3 = vec3d.add(vec3d2.x * d, vec3d2.y * d, vec3d2.z * d);
      AABB box = camera.getBoundingBox().expandTowards(vec3d2.scale(d)).inflate(1.0, 1.0, 1.0);

      EntityHitResult entityHitResult = ProjectileUtil.getEntityHitResult(camera, vec3d, vec3d3, box, entity -> !entity.isSpectator() && entity.isPickable(), e);
      if (entityHitResult != null && entityHitResult.getEntity() instanceof LivingEntity hitLiving) {
         return hitLiving == livingEntity;
      }

      return false;
   }

   public static boolean isOkayToRenderThroughWalls(LivingEntity livingEntity) {
      return isTargeted(livingEntity) && !livingEntity.isAlwaysTicking();
   }

   public static boolean isInvalid(Entity entity) {
      return entity == null || !entity.isAlive() || !entity.showVehicleHealth() || entity.touchingUnloadedChunk() || !(entity instanceof LivingEntity) || client.player == null || client.player.getVehicle() == entity || entity.isInvisibleTo(client.player);
   }

   private static Entity getEntityFromUUID(UUID uuid, ClientLevel world) {
      for (Entity entity : world.entitiesForRendering()) {
         if (entity.getUUID().equals(uuid)) {
            return entity;
         }
      }

      return null;
   }
}

