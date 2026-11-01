package moe.phrolova.web;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.UUID;
import moe.phrolova.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Game events and the {@code /fastdiffusion web} command for the web map. */
public final class WebHooks {
    private static final Logger LOGGER = LogUtils.getLogger();

    private WebHooks() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Config.webAutoStart()) return;
        try {
            WebServer.start(event.getServer());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not start the FastDiffusion web map on {}:{}", Config.webBind(), Config.webPort(), e);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        WebServer.stop();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        WebServer.tick();
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.isNewChunk() && event.getLevel() instanceof ServerLevel level)
            WebServer.chunkGenerated(level.dimension().location().toString(), event.getChunk().getPos().x,
                    event.getChunk().getPos().z);
    }

    /** {@code web}: start if needed and send a login link; {@code web stop}; {@code web revoke}. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("web")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    WebServer web;
                    try {
                        web = WebServer.start(src.getServer());
                    } catch (IOException | RuntimeException e) {
                        src.sendFailure(Component.literal("Could not start the web map on " + Config.webBind() + ":"
                                + Config.webPort() + ": " + e.getMessage()));
                        return 0;
                    }
                    ServerPlayer player = src.getPlayer();
                    UUID id = player == null ? null : player.getUUID();
                    String link = web.loginLink(id, player == null ? "console" : player.getGameProfile().getName());
                    Component url = Component.literal("Open the FastDiffusion map").withStyle(s -> s
                            .withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(link))));
                    // Only the requester sees the link: it signs the browser in as them.
                    if (player != null) {
                        player.sendSystemMessage(url);
                        player.sendSystemMessage(Component.literal("One-time link, valid for 10 minutes.")
                                .withStyle(ChatFormatting.GRAY));
                    } else {
                        src.sendSystemMessage(Component.literal("Web map login (one-time, valid for 10 minutes): " + link));
                    }
                    return 1;
                })
                .then(Commands.literal("stop").executes(ctx -> {
                    WebServer.stop();
                    ctx.getSource().sendSuccess(() -> Component.literal("FastDiffusion web map stopped."), true);
                    return 1;
                }))
                .then(Commands.literal("revoke").executes(ctx -> {
                    WebServer web = WebServer.get();
                    int n = web == null ? 0 : web.revokeSessions();
                    ctx.getSource().sendSuccess(() -> Component.literal("Signed out " + n + " browser session(s)."), true);
                    return 1;
                }));
    }
}
