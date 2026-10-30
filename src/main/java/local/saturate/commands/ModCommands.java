package local.saturate.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Objects;
import local.saturate.DebugLogger;
import local.saturate.SaturateCommon;
import local.saturate.PlayerSaturationTracker;
import local.saturate.enums.EstimationModeEnum;
import net.minecraft.client.Minecraft;
import local.saturate.RenderTracker;
import local.saturate.config.ModConfig;
import local.saturate.enums.HealthDisplayTypeEnum;
import local.saturate.util.ConfigUtils;
import local.saturate.util.Util;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class ModCommands {
   @Environment(EnvType.CLIENT)
   public static void registerCommands() {
      ClientCommandRegistrationCallback.EVENT.register(ModCommands::configCommands);
      ClientCommandRegistrationCallback.EVENT.register(ModCommands::openModMenuCommand);
   }

   private static void configCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext commandBuildContext) {
      dispatcher.register(
         ClientCommands.literal("saturate")
            .then(
               ClientCommands.literal("offset")
                  .then(
                     ClientCommands.argument("offset", DoubleArgumentType.doubleArg())
                        .executes(context -> {
                           ModConfig.HANDLER.instance().display_offset = DoubleArgumentType.getDouble(context, "offset");
                           ModConfig.HANDLER.save();
                           ConfigUtils.sendMessage(context.getSource().getPlayer(), Component.literal("Set heart offset to " + Util.truncate(ModConfig.HANDLER.instance().display_offset, 2)));
                           return 1;
                        })
                  )
            )
            .then(
               ClientCommands.literal("indicator-type")
                  .then(
                     ClientCommands.argument("indicator_type", StringArgumentType.string())
                        .suggests((context, builder) -> {
                           builder.suggest("heart");
                           builder.suggest("number");
                           return builder.buildFuture();
                        })
                        .executes(context -> {
                           String typeArg = StringArgumentType.getString(context, "indicator_type");
                           HealthDisplayTypeEnum displayTypeEnum;
                           if (typeArg.equals("heart")) {
                              displayTypeEnum = HealthDisplayTypeEnum.HEARTS;
                           } else if (typeArg.equals("number")) {
                              displayTypeEnum = HealthDisplayTypeEnum.NUMBER;
                           } else {
                              ConfigUtils.sendMessage(context.getSource().getPlayer(), Component.literal("Unknown argument, please try again."));
                              return 1;
                           }

                           ModConfig.HANDLER.instance().indicator_type = displayTypeEnum;
                           ModConfig.HANDLER.save();
                           ConfigUtils.sendMessage(context.getSource().getPlayer(), Component.literal("Set display type to " + ModConfig.HANDLER.instance().indicator_type));
                           return 1;
                        })
                  )
            )
            .then(
               ClientCommands.literal("monitor")
                  .then(
                     ClientCommands.argument("entity_name", StringArgumentType.string())
                        .suggests((context, builder) -> {
                           for (Entity entity : context.getSource().getLevel().entitiesForRendering()) {
                              if (entity.hasCustomName()) {
                                 builder.suggest(Objects.requireNonNull(entity.getCustomName()).getString());
                              }

                              if (entity instanceof Player) {
                                 builder.suggest(Objects.requireNonNull(entity.getDisplayName()).getString());
                              }
                           }

                           return builder.buildFuture();
                        })
                        .executes(context -> {
                           String entityName = StringArgumentType.getString(context, "entity_name");
                           Entity target = Util.getEntityFromName(context.getSource().getLevel(), entityName);
                           if (target instanceof LivingEntity livingTarget) {
                              ConfigUtils.sendMessage(context.getSource().getPlayer(), Component.literal("Now monitoring " + entityName));
                              RenderTracker.setTrackedEntity(livingTarget);
                           } else {
                              ConfigUtils.sendMessage(context.getSource().getPlayer(), Component.literal("There is no entity named " + entityName + " in the world. It may have died or gone out of render distance."));
                           }

                           return 0;
                        })
                  )
            )
            .then(
               ClientCommands.literal("stop-monitoring")
                  .executes(context -> {
                     RenderTracker.setTrackedEntity(null);
                     ConfigUtils.sendMessage(context.getSource().getPlayer(), Component.literal("Stopped monitoring "));
                     return 0;
                  })
            )
      );
   }

   /** Always registered (independent of enable_commands): /hi debug, /hi debug log, /hi mode. */
   @Environment(EnvType.CLIENT)
   public static void registerDebugCommands() {
      ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
         ClientCommands.literal("sat")
            .then(ClientCommands.literal("debug")
               .executes(context -> {
                  for (Component line : PlayerSaturationTracker.buildDebugReport(Minecraft.getInstance())) {
                     context.getSource().sendFeedback(line);
                  }
                  return 1;
               })
               .then(ClientCommands.literal("log")
                  .executes(context -> setLog(context.getSource(), !ModConfig.HANDLER.instance().debug_log_to_file))
                  .then(ClientCommands.literal("on").executes(context -> setLog(context.getSource(), true)))
                  .then(ClientCommands.literal("off").executes(context -> setLog(context.getSource(), false)))
               )
            )
            .then(ClientCommands.literal("mode")
               .then(ClientCommands.literal("auto").executes(context -> setMode(context.getSource(), EstimationModeEnum.AUTO)))
               .then(ClientCommands.literal("always").executes(context -> setMode(context.getSource(), EstimationModeEnum.ALWAYS)))
               .then(ClientCommands.literal("never").executes(context -> setMode(context.getSource(), EstimationModeEnum.NEVER)))
            )
            .then(ClientCommands.literal("respawnsat")
               .executes(context -> {
                  float cur = ModConfig.HANDLER.instance().respawn_saturation;
                  String text = cur < 0.0F ? "unknown" : String.format(java.util.Locale.ROOT, "%.1f", cur);
                  context.getSource().sendFeedback(Component.literal("[Saturate] Respawn saturation: " + text));
                  return 1;
               })
               .then(ClientCommands.literal("unknown").executes(context -> setRespawnSat(context.getSource(), -1.0F)))
               .then(ClientCommands.argument("value", FloatArgumentType.floatArg(0.0F, 20.0F))
                  .executes(context -> setRespawnSat(context.getSource(), FloatArgumentType.getFloat(context, "value")))
               )
            )
      ));
   }

   private static int setLog(FabricClientCommandSource source, boolean on) {
      ModConfig.HANDLER.instance().debug_log_to_file = on;
      ModConfig.HANDLER.save();
      if (!on) {
         DebugLogger.close();
      }
      source.sendFeedback(Component.literal("[Saturate] File log " + (on ? "ON -> " + DebugLogger.getLogPath() : "OFF")));
      return 1;
   }

   private static int setMode(FabricClientCommandSource source, EstimationModeEnum mode) {
      ModConfig.HANDLER.instance().estimation_mode = mode;
      ModConfig.HANDLER.save();
      source.sendFeedback(Component.literal("[Saturate] Estimation mode: " + mode.name()));
      return 1;
   }

   private static int setRespawnSat(FabricClientCommandSource source, float val) {
      ModConfig.HANDLER.instance().respawn_saturation = val;
      ModConfig.HANDLER.save();
      String text = val < 0.0F ? "unknown" : String.format(java.util.Locale.ROOT, "%.1f", val);
      source.sendFeedback(Component.literal("[Saturate] Respawn saturation: " + text));
      return 1;
   }

   private static void openModMenuCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext commandBuildContext) {
      dispatcher.register(
         ClientCommands.literal("saturate")
            .executes(context -> {
               SaturateCommon.openConfig();
               return 1;
            })
      );
   }
}

