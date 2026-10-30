package local.saturate.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.world.phys.Vec3;

/** Per-Minecraft-version shims (this file exists once per src/compat/mcXXX source set). */
public final class Compat {
   private Compat() {
   }

   public static Screen currentScreen(Minecraft client) {
      return client.gui.screen();
   }

   public static void setScreen(Minecraft client, Screen screen) {
      client.gui.setScreen(screen);
   }

   public static void clearOverlayMessage(Minecraft client) {
      client.gui.hud.setOverlayMessage(Component.literal(""), false);
   }

   public static void applyCameraRotation(PoseStack poseStack, EntityRenderDispatcher dispatcher) {
      poseStack.mulPose(new org.joml.Matrix4f().rotation(dispatcher.camera.rotation()));
   }

   /** True when an Animate packet action is a main/off-hand swing (26.3 moved swings to their own packet). */
   public static boolean isSwingAnimateAction(int action) {
      return false;
   }

   public static Vec3 positionSyncPosition(ClientboundEntityPositionSyncPacket packet) {
      return packet.position().endPosition();
   }
}
