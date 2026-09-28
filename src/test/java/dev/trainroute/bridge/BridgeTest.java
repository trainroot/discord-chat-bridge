package dev.trainroute.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BridgeTest {
    @Test void estimatesTwentyAndTenTpsAndExpires() {
        TpsEstimator t = new TpsEstimator();
        assertTrue(t.estimate(0).isEmpty());
        t.record(100, 1_000_000_000L);
        t.record(120, 2_000_000_000L);
        assertEquals(20, t.estimate(2_000_000_000L).orElseThrow(), 0.001);
        t.reset(); t.record(0, 1_000_000_000L); t.record(20, 3_000_000_000L);
        assertEquals(10, t.estimate(3_000_000_000L).orElseThrow(), 0.001);
        assertTrue(t.estimate(34_000_000_000L).isEmpty());
    }

    @Test void tpsWeightsElapsedTimeAndResetsOnWorldChange() {
        TpsEstimator t = new TpsEstimator();
        t.record(0, 1_000_000_000L); t.record(20, 2_000_000_000L); t.record(40, 5_000_000_000L);
        assertEquals(10, t.estimate(5_000_000_000L).orElseThrow(), 0.001);
        t.record(0, 6_000_000_000L);
        assertTrue(t.estimate(6_000_000_000L).isEmpty());
    }

    @Test void tpsCapsCatchupAndHandlesFrozenTime() {
        TpsEstimator t = new TpsEstimator();
        t.record(0, 1_000_000_000L); t.record(20, 1_100_000_000L);
        assertEquals(20, t.estimate(1_100_000_000L).orElseThrow());
        t.reset(); t.record(5, 1_000_000_000L); t.record(5, 2_000_000_000L);
        assertEquals(0, t.estimate(2_000_000_000L).orElseThrow());
    }

    @Test void protectsMentionsMarkupAndUnicode() {
        String line = TextFormat.discordLine("§c<Sam> @everyone **hello**\nsecond");
        assertFalse(line.contains("§c")); assertFalse(line.contains("@everyone"));
        assertFalse(line.contains("\n")); assertTrue(line.contains("\\*"));
        String shortened = TextFormat.limit("😀".repeat(2000), 1800);
        assertTrue(shortened.length() <= 1800);
        assertFalse(Character.isHighSurrogate(shortened.charAt(shortened.length() - 2)));
        assertTrue(TextFormat.discordLine("*".repeat(4000)).length() <= 1800);
    }

    @Test void configIsDisabledByDefaultAndServerMatchingIsExact(@TempDir Path p) throws Exception {
        BridgeConfig c = BridgeConfig.load(p);
        assertFalse(c.enabled);
        c.allowedServers = java.util.List.of("play.example.net");
        assertTrue(c.allows("PLAY.EXAMPLE.NET:25565"));
        assertFalse(c.allows("play.example.net.evil.org"));
        assertFalse(c.allows("play.example.net:25566"));
        Files.writeString(p.resolve("discord-client-bridge.json"), "{\"topicUpdateSeconds\":1}");
        assertEquals(300, BridgeConfig.load(p).topicUpdateSeconds);
    }

    @Test void payloadSuppressesPingsAndHasIdempotencyNonce() {
        JsonObject body = DiscordRest.messageBody("@everyone", "123");
        assertEquals(0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        assertTrue(body.get("enforce_nonce").getAsBoolean());
        assertEquals("123", body.get("nonce").getAsString());
        assertEquals(4, body.get("flags").getAsInt());
    }

    @Test void restUsesBotAuthAndCorrectRoutes() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            request.set(x.getRequestMethod() + " " + x.getRequestURI());
            auth.set(x.getRequestHeaders().getFirst("Authorization"));
            JsonParser.parseString(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            x.sendResponseHeaders(200, 2); x.getResponseBody().write("{}".getBytes()); x.close();
        });
        server.start();
        try (DiscordRest rest = new DiscordRest("test-only", "123", base(server))) {
            assertEquals(DiscordRest.Result.OK, rest.send(DiscordRest.Route.CHAT, DiscordRest.messageBody("test", "1")));
            assertEquals("POST /api/v10/channels/123/messages", request.get());
            assertEquals("Bot test-only", auth.get());
            assertEquals(DiscordRest.Result.OK, rest.send(DiscordRest.Route.TOPIC, new JsonObject()));
            assertEquals("PATCH /api/v10/channels/123", request.get());
        } finally { server.stop(0); }
    }

    @Test void global429BlocksBothRoutesWithoutSleeping() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = errorServer(429, "{\"retry_after\":60,\"global\":true}", calls);
        try (DiscordRest rest = new DiscordRest("test-only", "123", base(server))) {
            assertEquals(DiscordRest.Result.RETRY, rest.send(DiscordRest.Route.CHAT, new JsonObject()));
            assertEquals(DiscordRest.Result.RETRY, rest.send(DiscordRest.Route.TOPIC, new JsonObject()));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test void topic403DoesNotDisableChat() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = errorServer(403, "{}", calls);
        try (DiscordRest rest = new DiscordRest("test-only", "123", base(server))) {
            assertEquals(DiscordRest.Result.DISABLED, rest.send(DiscordRest.Route.TOPIC, new JsonObject()));
            rest.send(DiscordRest.Route.TOPIC, new JsonObject());
            assertEquals(1, calls.get());
            rest.send(DiscordRest.Route.CHAT, new JsonObject());
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    @Test void invalidTokenDisablesBothRoutes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = errorServer(401, "{}", calls);
        try (DiscordRest rest = new DiscordRest("test-only", "123", base(server))) {
            rest.send(DiscordRest.Route.CHAT, new JsonObject());
            assertEquals(DiscordRest.Result.DISABLED, rest.send(DiscordRest.Route.TOPIC, new JsonObject()));
            assertEquals(1, calls.get());
            assertFalse(rest.status().contains("test-only"));
        } finally { server.stop(0); }
    }

    private static URI base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v10/");
    }

    private static HttpServer errorServer(int code, String response, AtomicInteger calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            calls.incrementAndGet(); x.getRequestBody().readAllBytes();
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(code, body.length); x.getResponseBody().write(body); x.close();
        });
        server.start(); return server;
    }
}
