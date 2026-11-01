package moe.phrolova.client;

import moe.phrolova.engine.ModelProgress;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Replaces the world loading screen while the integrated server downloads, converts or loads the
 * model, which happens before any chunk progress exists.
 */
final class ModelLoadingOverlay {
    private static final int BAR_WIDTH = 240, BAR_HEIGHT = 8;

    private ModelLoadingOverlay() {}

    static void onRender(ScreenEvent.Render.Pre event) {
        ModelProgress.Stage stage = ModelProgress.stage();
        Screen screen = event.getScreen();
        if (stage == ModelProgress.Stage.IDLE || !(screen instanceof LevelLoadingScreen || screen instanceof GenericMessageScreen)) return;
        event.setCanceled(true);

        GuiGraphics g = event.getGuiGraphics();
        Font font = screen.getMinecraft().font;
        screen.renderBackground(g, event.getMouseX(), event.getMouseY(), event.getPartialTick());
        int cx = screen.width / 2, y = screen.height / 2 - 20;

        Component title, detail;
        float fraction;
        switch (stage) {
            case DOWNLOADING -> {
                long done = ModelProgress.done(), total = Math.max(1, ModelProgress.total());
                fraction = (float) done / total;
                title = Component.translatable("fastdiffusion.loading.downloading");
                detail = Component.translatable("fastdiffusion.loading.bytes", done >> 20, total >> 20);
            }
            case CONVERTING -> {
                fraction = -1;
                title = Component.translatable("fastdiffusion.loading.converting");
                detail = Component.translatable("fastdiffusion.loading.converting.detail");
            }
            default -> {
                fraction = -1;
                title = Component.translatable("fastdiffusion.loading.loading");
                detail = Component.empty();
            }
        }

        g.drawCenteredString(font, title, cx, y, 0xFFFFFF);
        int x0 = cx - BAR_WIDTH / 2, by = y + font.lineHeight + 8;
        g.fill(x0 - 1, by - 1, x0 + BAR_WIDTH + 1, by + BAR_HEIGHT + 1, 0xFFFFFFFF);
        g.fill(x0, by, x0 + BAR_WIDTH, by + BAR_HEIGHT, 0xFF000000);
        if (fraction >= 0) {
            g.fill(x0, by, x0 + Math.round(BAR_WIDTH * Math.min(1, fraction)), by + BAR_HEIGHT, 0xFF5BBF4A);
            g.drawCenteredString(font, (int) (fraction * 100) + "%", cx, by + BAR_HEIGHT + 6, 0xFFFFFF);
        } else {
            // Indeterminate: a block sweeping across the bar.
            int seg = BAR_WIDTH / 4, period = 1600;
            int pos = (int) ((Util.getMillis() % period) * (BAR_WIDTH + seg) / period) - seg;
            g.fill(x0 + Math.max(0, pos), by, x0 + Math.min(BAR_WIDTH, pos + seg), by + BAR_HEIGHT, 0xFF5BBF4A);
        }
        g.drawCenteredString(font, detail, cx, by + BAR_HEIGHT + 6 + (fraction >= 0 ? font.lineHeight + 4 : 0), 0xA0A0A0);
    }
}
