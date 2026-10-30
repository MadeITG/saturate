package local.saturate.util;

import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

public class Util {
   public static double truncate(double number, int places) {
      double factor = Math.pow(10.0, places);
      return Math.floor(number * factor) / factor;
   }

   public static Entity getEntityFromName(ClientLevel world, String entityName) {
      for (Entity entity : world.entitiesForRendering()) {
         if (entity.hasCustomName() && Objects.equals(entity.getCustomName().tryCollapseToString(), entityName)) {
            return entity;
         }

         if (entity.isAlwaysTicking() && Objects.equals(entity.getDisplayName().getString(), entityName)) {
            return entity;
         }
      }

      return null;
   }
}

