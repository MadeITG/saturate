package local.saturate.config;

import com.google.common.collect.Lists;
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.autogen.AutoGen;
import dev.isxander.yacl3.config.v2.api.autogen.Boolean;
import dev.isxander.yacl3.config.v2.api.autogen.Boolean.Formatter;
import dev.isxander.yacl3.config.v2.api.autogen.ColorField;
import dev.isxander.yacl3.config.v2.api.autogen.CustomImage;
import dev.isxander.yacl3.config.v2.api.autogen.DoubleField;
import dev.isxander.yacl3.config.v2.api.autogen.DoubleSlider;
import dev.isxander.yacl3.config.v2.api.autogen.EnumCycler;
import dev.isxander.yacl3.config.v2.api.autogen.FloatSlider;
import dev.isxander.yacl3.config.v2.api.autogen.IntField;
import dev.isxander.yacl3.config.v2.api.autogen.IntSlider;
import dev.isxander.yacl3.config.v2.api.autogen.Label;
import dev.isxander.yacl3.config.v2.api.autogen.ListGroup;
import dev.isxander.yacl3.config.v2.api.autogen.MasterTickBox;
import dev.isxander.yacl3.config.v2.api.autogen.TickBox;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import java.awt.Color;
import java.nio.file.Path;
import java.util.List;
import local.saturate.enums.EstimationModeEnum;
import local.saturate.enums.HealthDisplayTypeEnum;
import local.saturate.enums.MessageTypeEnum;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

public class ModConfig {
   public static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("saturate.json");
   public static final ConfigClassHandler<ModConfig> HANDLER = ConfigClassHandler.createBuilder(ModConfig.class)
      .id(Identifier.fromNamespaceAndPath("saturate", "config"))
      .serializer(config -> GsonConfigSerializerBuilder.create(config).setPath(CONFIG_PATH).build())
      .build();

   @Label
   @AutoGen(category = "filters")
   private final Component filtersProTip = Component.literal("Pro Tip: You can temporarily override the below criteria and force health display for all living entities by holding the Right-Arrow key (customizable)").withStyle(ChatFormatting.GOLD);

   @Label
   private final Component filtersTypeLabel = Component.literal("Enable health display based on entity type").withStyle(ChatFormatting.BOLD, ChatFormatting.AQUA);

   @SerialEntry
   @AutoGen(category = "filters", group = "entity_type")
   @TickBox
   public boolean passive_mobs = true;

   @SerialEntry
   @AutoGen(category = "filters", group = "entity_type")
   @TickBox
   public boolean hostile_mobs = true;

   @SerialEntry
   @AutoGen(category = "filters", group = "entity_type")
   @MasterTickBox({"override_players"})
   public boolean players = true;

   @SerialEntry
   @AutoGen(category = "filters", group = "entity_type")
   @TickBox
   public boolean self = false;

   @SerialEntry
   @AutoGen(category = "filters", group = "entity_type")
   @TickBox
   public boolean show_through_walls = false;

   @SerialEntry
   @AutoGen(category = "filters")
   @Boolean(formatter = Formatter.CUSTOM)
   public boolean blacklistOrWhitelist = true;

   @SerialEntry
   @AutoGen(category = "filters")
   @ListGroup(valueFactory = EntitiesListGroup.class, controllerFactory = EntitiesListGroup.class)
   public List<String> list = Lists.newArrayList("minecraft:armor_stand");

   @Label
   @AutoGen(category = "filters", group = "advanced")
   private final Component filtersAdvancedLabel = Component.literal("Show for...").withStyle(ChatFormatting.AQUA);

   @SerialEntry
   @AutoGen(category = "filters", group = "advanced")
   @MasterTickBox({"override_players", "time_after_hit"})
   public boolean after_attack = false;

   @SerialEntry
   @AutoGen(category = "filters", group = "advanced")
   @IntSlider(min = 0, max = 120, step = 1)
   public int time_after_hit = 60;

   @Label
   @AutoGen(category = "filters", group = "advanced")
   private final Component damaged_only_label = Component.literal(" ").withStyle(ChatFormatting.ITALIC);

   @SerialEntry
   @AutoGen(category = "filters", group = "advanced")
   @MasterTickBox({"override_players", "max_health_percentage"})
   public boolean damaged_only = false;

   @SerialEntry
   @AutoGen(category = "filters", group = "advanced")
   @IntSlider(min = 0, max = 100, step = 1)
   public int max_health_percentage = 100;

   @Label
   @AutoGen(category = "filters", group = "advanced")
   private final Component looking_at_label = Component.literal(" ").withStyle(ChatFormatting.ITALIC);

   @SerialEntry
   @AutoGen(category = "filters", group = "advanced")
   @MasterTickBox({"override_players", "reach"})
   public boolean looking_at = false;

   @SerialEntry
   @AutoGen(category = "filters", group = "advanced")
   @IntField(min = 0, max = 1024)
   public int reach = 3;

   @Label
   @AutoGen(category = "filters", group = "advanced")
   private final Component distance_label = Component.literal(" ").withStyle(ChatFormatting.ITALIC);

   @SerialEntry
   @AutoGen(category = "filters", group = "advanced")
   @MasterTickBox({"override_players", "distance"})
   public boolean within_distance = false;

   @SerialEntry
   @AutoGen(category = "filters", group = "advanced")
   @IntSlider(min = 0, max = 512, step = 4)
   public int distance = 64;

   @Label
   @AutoGen(category = "filters", group = "advanced")
   private final Component override_players_label = Component.literal("Overrides").withStyle(ChatFormatting.ITALIC);

   @SerialEntry
   @AutoGen(category = "filters", group = "advanced")
   @TickBox
   public boolean override_players = false;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @EnumCycler
   public HealthDisplayTypeEnum indicator_type = HealthDisplayTypeEnum.HEARTS;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @IntSlider(min = 10, max = 200, step = 2)
   public int dynamic_health_threshold = 100;

   @AutoGen(category = "appearance", group = "indicator_type")
   @Label
   private final Component heart_type_settings_label = Component.literal("Settings for the heart-type indicator");

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @IntField
   public int icons_per_row = 10;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @Boolean
   public boolean use_vanilla_textures = false;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @IntSlider(min = 0, max = 100, step = 1)
   public int health_bar_opacity = 100;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @Boolean
   public boolean show_heart_effects = true;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @Boolean
   public boolean show_saturation_for_players = true;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @Boolean
   public boolean show_food_for_players = false;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @Boolean
   public boolean show_unknown_saturation_faded = false;

   @SerialEntry
   public float respawn_saturation = 5.0F;

   @SerialEntry
   @AutoGen(category = "appearance", group = "estimation")
   @EnumCycler
   public EstimationModeEnum estimation_mode = EstimationModeEnum.AUTO;

   @SerialEntry
   @AutoGen(category = "appearance", group = "estimation")
   @Boolean
   public boolean show_uncertainty_indicator = true;

   @SerialEntry
   @AutoGen(category = "appearance", group = "estimation")
   @Boolean
   public boolean debug_log_to_file = false;

   @AutoGen(category = "appearance", group = "indicator_type")
   @Label
   private final Component number_type_settings_label = Component.literal("Settings for the number-type indicator");

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @ColorField
   public Color number_color = Color.RED;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @Boolean
   public boolean render_number_display_shadow = false;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @IntSlider(min = 0, max = 12, step = 1)
   public int decimal_places = 1;

   public boolean render_number_display_background_color = false;
   public Color number_display_background_color = Color.BLACK;

   @AutoGen(category = "appearance", group = "indicator_type")
   @Label
   private final Component common_type_settings_label = Component.literal("Common settings for all indicator types");

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @MasterTickBox({"max_health"})
   public boolean percentage_based_health = false;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @IntSlider(min = 1, max = 100, step = 1)
   public int max_health = 20;

   @SerialEntry
   @AutoGen(category = "appearance", group = "indicator_type")
   @FloatSlider(min = 0.0F, max = 0.1F, step = 0.005F, format = "%.3f")
   public float size = 0.025F;

   @SerialEntry
   @AutoGen(category = "appearance", group = "offset")
   @DoubleField
   public double display_offset = 0.0;

   @SerialEntry
   @AutoGen(category = "appearance", group = "offset")
   @DoubleSlider(min = 0.0, max = 10.0, step = 0.5)
   public double offset_step_size = 1.0;

   @SerialEntry
   @AutoGen(category = "appearance", group = "offset")
   @Boolean
   public boolean force_higher_offset_for_players = false;

   @SerialEntry
   @AutoGen(category = "messages", group = "messages_appearance")
   @EnumCycler
   public MessageTypeEnum message_type = MessageTypeEnum.ACTIONBAR;

   @SerialEntry
   @AutoGen(category = "messages", group = "messages_appearance")
   @Boolean(colored = true)
   public boolean colored_messages = true;

   @Label
   @AutoGen(category = "messages", group = "commands")
   private final Component commandsRestartWarning = Component.literal("For this section, a restart is required to apply any modifications").withStyle(ChatFormatting.RED);

   @SerialEntry
   @AutoGen(category = "messages", group = "commands")
   @Boolean(formatter = Formatter.YES_NO)
   public boolean enable_commands = true;

   @SerialEntry
   @AutoGen(category = "damage_direction_indicators")
   @CustomImage(value = "images/damage_direction_indicator_example.png", width = 540, height = 540)
   @Boolean
   public boolean enable_damage_direction_indicators = false;

   @SerialEntry
   @AutoGen(category = "damage_direction_indicators")
   @IntSlider(min = 1, max = 8, step = 1)
   public int damage_direction_indicators_scale = 2;

   @SerialEntry
   @AutoGen(category = "damage_direction_indicators")
   @ColorField
   public Color damage_direction_indicators_color = Color.RED;

   @SerialEntry
   @AutoGen(category = "damage_direction_indicators")
   @IntSlider(min = 1, max = 8, step = 1)
   public int damage_direction_indicators_visibility_time = 4;

   @SerialEntry
   @AutoGen(category = "damage_direction_indicators")
   @MasterTickBox({"damage_direction_indicators_fade_out_time"})
   public boolean damage_direction_indicators_fade_out = true;

   @SerialEntry
   @AutoGen(category = "damage_direction_indicators")
   @IntSlider(min = 0, max = 7, step = 1)
   public int damage_direction_indicators_fade_out_time = 3;

   public static Screen createScreen(@Nullable Screen parent) {
      return HANDLER.generateGui().generateScreen(parent);
   }

   public Screen createConfigScreen(Screen parent) {
      return FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3") ? createScreen(parent) : null;
   }
}

