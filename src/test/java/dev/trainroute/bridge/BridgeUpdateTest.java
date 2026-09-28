package dev.trainroute.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class BridgeUpdateTest {
    @Test void boldsHeaderAndItalicizesJoinLeaveWithoutTrustingUserMarkdown() {
        assertEquals("**Bed +AGoodDog MSA**: hello", TextFormat.relayLine("<Bed +AGoodDog MSA> hello", true));
        assertEquals("**.Player \\[VIP\\]**: hello", TextFormat.relayLine("<.Player [VIP]> hello", true));
        assertEquals("***Player*** *joined the game*", TextFormat.relayLine("Player joined the game", true));
        assertEquals("***.Player*** *left the game*", TextFormat.relayLine(".Player left the game", true));
        String result = TextFormat.relayLine("<Name **badge**> @everyone **fake bold**", true);
        assertEquals("**Name \\*\\*badge\\*\\***: @\u200beveryone \\*\\*fake bold\\*\\*", result);
        String longHeader = TextFormat.relayLine("<Name " + "*".repeat(1000) + "> " + "😀".repeat(2000), true);
        assertTrue(longHeader.length() <= 1800);
        assertFalse(longHeader.contains("\uD83D…"));
        assertFalse(longHeader.contains("\\…"));
    }

    @Test void excludesBedTokensWithoutExcludingOtherCommandsOrWords() {
        for (String line : List.of("/bed", "/bed home", "<Name> /bed", "Command: '/bed'", "/BED", "From Name: /bed"))
            assertTrue(TextFormat.excludedCommand(line), line);
        for (String line : List.of("/bedrock", "/bedtime", "bed", "https://example.net/bed", "go to bed", "/bed-test"))
            assertFalse(TextFormat.excludedCommand(line), line);
    }

    @Test void topicListsEveryNameWhenItFitsAndUsesReadableZonedTime() {
        List<String> names = new ArrayList<>();
        for (int i = 44; i >= 0; i--) names.add(String.format("P%02d", i));
        String topic = TopicFormat.connected("server", "BridgeBot", names, OptionalDouble.of(19.9), 42,
                Instant.parse("2026-09-27T18:24:54Z"), ZoneId.of("America/Chicago"));
        assertTrue(topic.contains("Online (tab): P00, P01"));
        for (String name : names) assertTrue(topic.contains(name));
        assertTrue(topic.contains("Sep 27, 2026 at 1:24:54 PM CDT"));
        assertFalse(topic.contains("more)"));
        assertTrue(topic.length() <= 1024);
    }

    @Test void topicOverflowCountsUnlistedNamesAndNeverCutsOffTimestamp() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 300; i++) names.add(String.format("Player%010d", i));
        String topic = TopicFormat.connected("server", "BridgeBot", names, OptionalDouble.empty(), null,
                Instant.EPOCH, ZoneId.of("UTC"));
        assertTrue(topic.length() <= 1024);
        long shown = names.stream().filter(topic::contains).count();
        assertTrue(topic.contains("(+" + (300 - shown) + " more)"));
        assertTrue(topic.endsWith("Jan 1, 1970 at 12:00:00 AM UTC"));
        String offline = TopicFormat.inactive("server", "BridgeBot", "Disconnected", Instant.EPOCH, ZoneId.of("UTC"));
        assertTrue(offline.contains("Online (tab): unavailable"));
        assertTrue(offline.contains("Disconnected"));
    }

    @Test void disconnectIsOncePerObservedSessionAndRemembersLastServer() {
        BridgeSession session = new BridgeSession();
        assertNull(session.disconnect());
        session.observe("BridgeBot", "first.example:25565");
        assertEquals(new BridgeSession.Disconnection("BridgeBot", "first.example:25565"), session.disconnect());
        assertNull(session.disconnect());
        assertEquals("first.example:25565", session.server());
        session.observe("BridgeBot", "second.example");
        assertEquals("second.example", session.disconnect().server());
    }

    @Test void disconnectNoticeSurvivesReconnectAndTopicCanChangeBeforePeriodicDeadline() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = recordingServer(requests, false);
        BridgeConfig config = new BridgeConfig();
        try (DiscordWorker worker = new DiscordWorker(config, new DiscordRest("fake", "123", base(server)), false)) {
            worker.topic("Connected", true); worker.pump();
            worker.enqueue("old chat"); worker.clearChat();
            worker.notice(TextFormat.disconnectNotice("BridgeBot", "first.example"));
            worker.topic("Disconnected", true);
            worker.clearChat(); // A fast subsequent JOIN must not discard the warning.
            worker.pump();
            assertEquals(3, requests.size());
            assertTrue(requests.get(1).contains("has disconnected from"));
            assertTrue(requests.get(2).contains("Disconnected"));
            assertFalse(String.join("", requests).contains("old chat"));
            worker.topic("Reconnected", true); worker.pump();
            assertEquals(4, requests.size());
            worker.topic("ordinary snapshot"); worker.pump();
            assertEquals(4, requests.size()); // Periodic updates still wait.
            assertTrue(worker.status().contains("Chat: Sent (HTTP 200)"));
            assertTrue(worker.status().contains("Topic: Updated (HTTP 200)"));
            assertTrue(worker.status().contains("Sent: 1"));
        } finally { server.stop(0); }
    }

    @Test void urgentTopicAndReloadStillRespectDiscordRateLimit() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = recordingServer(requests, true);
        BridgeConfig config = new BridgeConfig();
        try (DiscordWorker worker = new DiscordWorker(config, new DiscordRest("fake", "123", base(server)), false)) {
            worker.topic("Connected", true); worker.pump();
            worker.topic("Disconnected", true); worker.notice("warning"); worker.pump();
            assertEquals(2, requests.size()); // Initial PATCH plus POST; second PATCH waits.
            worker.reconfigure(config); worker.pump();
            assertEquals(2, requests.size()); // Reload does not reset rate-limit deadlines.
            assertTrue(worker.status().contains("Topic: Rate limited"));
            assertTrue(worker.status().contains("Chat: Sent"));
        } finally { server.stop(0); }
    }

    private static HttpServer recordingServer(List<String> requests, boolean limitTopic) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            String data = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonParser.parseString(data);
            requests.add(x.getRequestMethod() + " " + data);
            boolean limited = limitTopic && x.getRequestMethod().equals("PATCH");
            byte[] response = (limited ? "{\"retry_after\":60}" : "{}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(limited ? 429 : 200, response.length);
            x.getResponseBody().write(response); x.close();
        });
        server.start(); return server;
    }
    private static URI base(HttpServer server) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v10/"); }
}
