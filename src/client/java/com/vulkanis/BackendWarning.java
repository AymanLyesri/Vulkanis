package com.vulkanis;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Warn-once-per-session notice when the game runs on the OpenGL backend.
 *  Toast at startup plus a red chat line on world join. Warn-only:
 *  nothing is disabled or switched. */
public final class BackendWarning {
  private static final Logger LOG = LoggerFactory.getLogger("vulkanis");
  private static boolean toastShown;
  private static boolean chatShown;

  private BackendWarning() {
  }

  public static void maybeShowStartupToast(Minecraft client) {
    if (toastShown) {
      return;
    }
    BackendCheck.Backend backend = BackendCheck.current();
    if (backend == null) {
      return;
    }
    if (backend == BackendCheck.Backend.VULKAN) {
      toastShown = true;
      return;
    }
    toastShown = true;
    LOG.warn("vulkanis: running on {} backend ({}), shadows target Vulkan", backend, BackendCheck.detail());
    try {
      boolean openGL = backend == BackendCheck.Backend.OPENGL;
      client.gui.toastManager().addToast(new SystemToast(
        SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
        Component.literal(openGL ? "Vulkanis: OpenGL backend detected" : "Vulkanis: graphics backend unconfirmed")
          .withStyle(ChatFormatting.RED),
        Component.literal(openGL ? "Switch to Vulkan to test shadows" : "Could not confirm Vulkan - shadows target Vulkan")));
    } catch (Exception e) {
      LOG.warn("vulkanis: backend toast failed", e);
    }
  }

  public static void onWorldJoin(Minecraft client) {
    if (chatShown) {
      return;
    }
    BackendCheck.Backend backend = BackendCheck.current();
    if (backend == null) {
      return;
    }
    if (backend == BackendCheck.Backend.VULKAN) {
      chatShown = true;
      return;
    }
    chatShown = true;
    client.execute(() -> {
      try {
        client.gui.chatListener().handleSystemMessage(
          Component.literal("Vulkanis: running on " + backend + " backend - switch to Vulkan to test shadows")
            .withStyle(ChatFormatting.RED),
          false);
      } catch (Exception e) {
        LOG.warn("vulkanis: backend chat warning failed", e);
      }
    });
  }

  static void resetForTests() {
    toastShown = false;
    chatShown = false;
  }
}
