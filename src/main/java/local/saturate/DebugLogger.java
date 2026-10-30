package local.saturate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import local.saturate.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Timestamped TSV logger for comparing estimated health/saturation against raw server packets.
 * Output file: &lt;gameDir&gt;/logs/saturate-debug.log
 */
public final class DebugLogger {
   private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
   private static final Object LOCK = new Object();
   private static BufferedWriter writer;
   private static Path currentLogPath;
   private static boolean headerWritten = false;

   private DebugLogger() {
   }

   public static boolean isEnabled() {
      try {
         return ModConfig.HANDLER.instance().debug_log_to_file;
      } catch (Throwable ignored) {
         return false;
      }
   }

   public static Path getLogPath() {
      if (currentLogPath == null) {
         currentLogPath = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("saturate-debug.log");
      }
      return currentLogPath;
   }

   public static void logEvent(
      long gameTime,
      String event,
      String entityName,
      float estHp,
      float minHp,
      float maxHp,
      float rawServerHp,
      float rawDelta,
      float estSat,
      int foodMin,
      int foodMax,
      float lastDamage,
      float attackScale,
      boolean obfuscated,
      String details
   ) {
      if (!isEnabled()) {
         return;
      }

      String timestamp = LocalDateTime.now().format(TS_FORMAT);
      String diffName = PlayerSaturationTracker.getDifficulty().name();
      float diff = Float.isNaN(rawServerHp) ? Float.NaN : (estHp - rawServerHp);
      String line = String.format(
         Locale.ROOT,
         "%s\t%d\t%s\t%s\t%s\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%d..%d\t%.2f\t%.2f\t%s\t%s%n",
         timestamp,
         gameTime,
         event,
         sanitize(entityName),
         diffName,
         estHp,
         minHp,
         maxHp,
         rawServerHp,
         rawDelta,
         diff,
         estSat,
         foodMin,
         foodMax,
         lastDamage,
         attackScale,
         obfuscated ? "OBF" : "REAL",
         sanitize(details)
      );

      synchronized (LOCK) {
         try {
            ensureOpen();
            if (writer != null) {
               writer.write(line);
               writer.flush();
            }
         } catch (IOException ignored) {
         }
      }
   }

   public static void logEvent(
      long gameTime,
      String event,
      String entityName,
      float estHp,
      float minHp,
      float maxHp,
      float rawServerHp,
      float estSat,
      int foodMin,
      int foodMax,
      float lastDamage,
      float attackScale,
      boolean obfuscated,
      String details
   ) {
      logEvent(
         gameTime,
         event,
         entityName,
         estHp,
         minHp,
         maxHp,
         rawServerHp,
         0.0F,
         estSat,
         foodMin,
         foodMax,
         lastDamage,
         attackScale,
         obfuscated,
         details
      );
   }

   public static void close() {
      synchronized (LOCK) {
         if (writer != null) {
            try {
               writer.flush();
               writer.close();
            } catch (IOException ignored) {
            } finally {
               writer = null;
               headerWritten = false;
            }
         }
      }
   }

   private static void ensureOpen() throws IOException {
      if (writer != null) {
         return;
      }
      Path path = getLogPath();
      if (path.getParent() != null) {
         Files.createDirectories(path.getParent());
      }
      boolean existsNonEmpty = Files.exists(path) && Files.size(path) > 0L;
      writer = Files.newBufferedWriter(
         path,
         StandardCharsets.UTF_8,
         StandardOpenOption.CREATE,
         StandardOpenOption.WRITE,
         StandardOpenOption.APPEND
      );
      if (!existsNonEmpty && !headerWritten) {
         writer.write("timestamp\tgameTime\tevent\tplayer\tdifficulty\testHp\tminHp\tmaxHp\trawServerHp\trawDelta\testMinusRaw\testSat\tfoodRange\tlastDmg\tattackScale\tmode\tdetails\n");
         headerWritten = true;
      }
   }

   private static String sanitize(String value) {
      if (value == null) {
         return "-";
      }
      return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
   }
}

