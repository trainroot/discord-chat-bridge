package dev.trainroute.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Local configuration. Credentials are deliberately kept outside this JSON. */
public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public boolean enabled = false;
    public String channelId = "";
    public List<String> allowedServers = List.of("play.example.net:25565");
    public String serverLabel = "Minecraft";
    public boolean forwardSystemMessages = true;
    public boolean formatParsedMessages = true;
    public boolean updateChannelTopic = true;
    public boolean announceDisconnects = true;
    public String timestampZone = "system";
    public int topicUpdateSeconds = 300;
    public int maxQueuedMessages = 500;
    public int messageMaxAgeSeconds = 120;
    public List<String> excludeMessageRegex = List.of();

    public static BridgeConfig load(Path directory) throws IOException {
        Path path = directory.resolve("discord-client-bridge.json");
        if (!Files.exists(path)) {
            Files.createDirectories(directory);
            Files.writeString(path, GSON.toJson(new BridgeConfig()) + "\n", StandardCharsets.UTF_8);
        }
        BridgeConfig c = GSON.fromJson(Files.readString(path), BridgeConfig.class);
        if (c == null) throw new IllegalArgumentException("Empty JSON configuration");
        if (c.channelId == null || c.serverLabel == null || c.allowedServers == null
                || c.excludeMessageRegex == null) throw new IllegalArgumentException("Null configuration field");
        c.zone(); // Validate before replacing a running configuration.
        c.channelId = c.channelId.trim();
        if (c.enabled && !c.channelId.matches("[0-9]{17,20}"))
            throw new IllegalArgumentException("channelId must be a Discord channel ID");
        if (c.allowedServers.stream().anyMatch(s -> s == null || s.isBlank()))
            throw new IllegalArgumentException("allowedServers contains an empty entry");
        if (c.excludeMessageRegex.size() > 30 || c.excludeMessageRegex.stream().anyMatch(s -> s == null || s.length() > 500))
            throw new IllegalArgumentException("Too many or oversized message filters");
        c.topicUpdateSeconds = Math.max(300, c.topicUpdateSeconds);
        c.maxQueuedMessages = Math.clamp(c.maxQueuedMessages, 10, 2000);
        c.messageMaxAgeSeconds = Math.clamp(c.messageMaxAgeSeconds, 10, 600);
        c.serverLabel = TextFormat.limit(TextFormat.plain(c.serverLabel), 100);
        return c;
    }

    public java.time.ZoneId zone() {
        return "system".equals(timestampZone) ? java.time.ZoneId.systemDefault()
                : java.time.ZoneId.of(timestampZone);
    }

    public boolean allows(String address) {
        return allowedServers.stream().anyMatch(s -> s.equals("*") || normalize(s).equals(normalize(address)));
    }

    static String normalize(String address) {
        String s = address.trim().toLowerCase(Locale.ROOT);
        return s.endsWith(":25565") ? s.substring(0, s.length() - 6) : s;
    }

    public static String token(Path directory) throws IOException {
        String token = System.getenv("DISCORD_BRIDGE_BOT_TOKEN");
        if (token == null || token.isBlank()) {
            Path p = directory.resolve("discord-client-bridge-token.txt");
            token = Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8).trim() : "";
        }
        token = token.trim();
        if (token.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Bot token must not contain whitespace");
        return token;
    }
}
