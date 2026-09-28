package dev.trainroute.bridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** All Minecraft state and commands stay on the client; only Discord receives added requests. */
public final class DiscordBridgeClient implements ClientModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("discord-client-bridge");
    private static final TpsEstimator TPS = new TpsEstimator();
    private final BridgeSession session = new BridgeSession();
    private BridgeConfig config = new BridgeConfig();
    private DiscordWorker worker;
    private List<Pattern> excludes = List.of();
    private boolean paused, connected, publishedActive;
    private long joinedAt, nextSnapshot;
    private String state = "Disabled", workerToken = "";

    @Override public void onInitializeClient() {
        String loaded = load();
        if (worker == null) state = loaded;
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) -> relay(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (config.forwardSystemMessages && !overlay) relay(message.getString());
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            connected = true; TPS.reset(); nextSnapshot = 0; joinedAt = System.nanoTime(); publishedActive = false;
            if (worker != null) worker.clearChat();
            observe(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> disconnected());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            disconnected();
            if (worker != null) worker.closeGracefully();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                literal("dbridge")
                    .executes(ctx -> feedback(status()))
                    .then(literal("status").executes(ctx -> feedback(status())))
                    .then(literal("help").executes(ctx -> feedback("/dbridge — status\n/dbridge on | off — resume or pause\n/dbridge reload — reload settings")))
                    .then(literal("reload").executes(ctx -> feedback(load())))
                    .then(literal("off").executes(ctx -> {
                        paused = true; publishedActive = false;
                        if (worker != null) {
                            worker.clearChat();
                            if (session.observed()) worker.topic(inactiveTopic("Bridge paused"), true);
                        }
                        return feedback("Paused.");
                    }))
                    .then(literal("on").executes(ctx -> {
                        paused = false; nextSnapshot = 0; publishedActive = false;
                        return feedback(worker == null ? "Set enabled=true in config, then /dbridge reload."
                                : active(MinecraftClient.getInstance()) ? "Bridge on." : "Bridge on; waiting for an allowed server.");
                    }))));
    }

    public static void timeUpdate(long worldAge) { TPS.record(worldAge, System.nanoTime()); }
    public static void resetTps() { TPS.reset(); }

    private String load() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir();
            BridgeConfig replacement = BridgeConfig.load(dir);
            List<Pattern> patterns = replacement.excludeMessageRegex.stream().map(Pattern::compile).toList();
            String token = replacement.enabled ? BridgeConfig.token(dir) : "";
            if (replacement.enabled && token.isBlank()) return "Missing bot token. Check the token file.";
            boolean reuse = worker != null && replacement.enabled && replacement.channelId.equals(config.channelId)
                    && token.equals(workerToken);
            if (worker != null && !reuse) {
                worker.clearChat();
                if (session.observed()) worker.topic(inactiveTopic("Bridge stopped"), true);
                worker.closeGracefully(); worker = null;
            }
            config = replacement; excludes = patterns; workerToken = token;
            paused = false; nextSnapshot = 0;
            if (config.enabled) {
                if (reuse) worker.reconfigure(config);
                else { worker = new DiscordWorker(config, token); publishedActive = false; }
                state = "Ready";
            } else { state = "Disabled in config"; session.disconnect(); }
            if (worker != null && session.observed() && !config.allows(session.server())) {
                worker.clearChat(); worker.topic(inactiveTopic("Server no longer allowed"), true);
                session.disconnect(); publishedActive = false;
            }
            return config.enabled ? "Settings reloaded." : "Disabled in config.";
        } catch (Exception e) {
            LOGGER.warn("Bridge config rejected ({})", e.getClass().getSimpleName());
            return "Settings invalid; previous settings kept. Check JSON, filters and timestampZone.";
        }
    }

    private boolean active(MinecraftClient client) {
        return worker != null && !paused && connected && client.getNetworkHandler() != null
                && client.world != null && client.getCurrentServerEntry() != null
                && config.allows(client.getCurrentServerEntry().address);
    }

    private void observe(MinecraftClient client) {
        if (active(client)) session.observe(client.getSession().getUsername(), client.getCurrentServerEntry().address);
    }

    private void disconnected() {
        connected = false; publishedActive = false; TPS.reset();
        var previous = session.disconnect();
        if (worker == null) return;
        worker.clearChat();
        if (previous == null) return; // DISCONNECT and CLIENT_STOPPING may both fire.
        if (config.announceDisconnects && !paused)
            worker.notice(TextFormat.disconnectNotice(previous.username(), previous.server()));
        worker.topic(inactiveTopic("Disconnected"), true);
    }

    private void relay(String raw) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!active(client)) return;
        observe(client);
        String clean = TextFormat.plain(raw);
        if (TextFormat.excludedCommand(clean)) return;
        clean = TextFormat.limit(clean, 4096);
        for (Pattern pattern : excludes) if (pattern.matcher(clean).find()) return;
        worker.enqueue(TextFormat.relayLine(clean, config.formatParsedMessages));
    }

    private void tick(MinecraftClient client) {
        if (worker == null || System.nanoTime() < nextSnapshot) return;
        nextSnapshot = System.nanoTime() + 1_000_000_000L;
        if (!active(client)) return;
        observe(client);
        if (System.nanoTime() - joinedAt < 5_000_000_000L) return;
        var handler = client.getNetworkHandler();
        var names = handler.getListedPlayerListEntries().stream().map(e -> e.getProfile().name()).toList();
        var own = client.player == null ? null : handler.getPlayerListEntry(client.player.getUuid());
        String topic = TopicFormat.connected(serverDisplay(), session.username(), names,
                TPS.estimate(System.nanoTime()), own == null ? null : own.getLatency(), Instant.now(), config.zone());
        worker.topic(topic, !publishedActive);
        publishedActive = true;
    }

    private String serverDisplay() {
        return config.serverLabel.isBlank() || config.serverLabel.equals("Minecraft") ? session.server()
                : config.serverLabel + " (" + session.server() + ")";
    }

    private String inactiveTopic(String reason) {
        return TopicFormat.inactive(serverDisplay(), session.username(), reason, Instant.now(), config.zone());
    }

    private String status() {
        MinecraftClient client = MinecraftClient.getInstance();
        String activity = worker == null ? state : paused ? "Paused" : active(client) ? "Forwarding"
                : connected ? "Server not allowed" : "Not connected";
        return activity + (active(client) ? " — " + session.server() : "")
                + (worker == null ? "" : "\n" + worker.status());
    }

    private int feedback(String text) {
        for (String line : text.split("\n"))
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("[Bridge] " + line));
        return 1;
    }
}
