package dev.trainroute.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import java.util.EnumMap;

/** Worker-thread-only Discord REST client. No gateway, external libraries, or user tokens. */
final class DiscordRest implements AutoCloseable {
    enum Route { CHAT, TOPIC }
    enum Result { OK, RETRY, DISABLED }
    private final HttpClient http;
    private final URI base;
    private final String token, channel;
    private final EnumMap<Route, Long> readyAt = new EnumMap<>(Route.class);
    private final EnumMap<Route, Boolean> disabled = new EnumMap<>(Route.class);
    private long globalReadyAt;
    private record Report(String text, Instant at) { }
    private final AtomicReference<Report> chatReport = new AtomicReference<>(new Report("No messages sent", null));
    private final AtomicReference<Report> topicReport = new AtomicReference<>(new Report("Not updated yet", null));

    DiscordRest(String token, String channel) {
        this(token, channel, URI.create("https://discord.com/api/v10/"));
    }

    // Package-private base override is for local HTTP tests; no configurable credential destination.
    DiscordRest(String token, String channel, URI base) {
        this.token = token;
        this.channel = channel;
        this.base = base;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    String status() { return status(ZoneId.systemDefault()); }
    String status(ZoneId zone) {
        return "Chat: " + reportText(chatReport.get(), zone) + "\nTopic: " + reportText(topicReport.get(), zone);
    }
    private String reportText(Report report, ZoneId zone) {
        return report.text + (report.at == null ? "" : " — " + TopicFormat.timestamp(report.at, zone));
    }
    private void report(Route route, String text) {
        (route == Route.CHAT ? chatReport : topicReport).set(new Report(text, Instant.now()));
    }
    // Worker-thread only. Preserve rate-limit deadlines when the user reloads.
    void retryDisabled() {
        for (Route route : Route.values()) {
            if (disabled.getOrDefault(route, false)) report(route, "Retrying after reload");
        }
        disabled.clear();
    }

    static JsonObject messageBody(String content, String nonce) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        body.addProperty("nonce", nonce);
        body.addProperty("enforce_nonce", true);
        body.addProperty("flags", 4); // SUPPRESS_EMBEDS
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);
        return body;
    }

    Result send(Route route, JsonObject body) {
        if (disabled.getOrDefault(route, false)) return Result.DISABLED;
        long now = System.nanoTime();
        if (now < globalReadyAt || now < readyAt.getOrDefault(route, 0L)) return Result.RETRY;
        String suffix = "channels/" + channel + (route == Route.CHAT ? "/messages" : "");
        HttpRequest request = HttpRequest.newBuilder(base.resolve(suffix))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bot " + token)
                .header("Content-Type", "application/json")
                .header("User-Agent", "DiscordBot (https://fabricmc.net/, 1.1.0)")
                .method(route == Route.CHAT ? "POST" : "PATCH", HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long received = System.nanoTime();
            int code = response.statusCode();
            if (response.headers().firstValue("X-RateLimit-Remaining").orElse("1").equals("0")) {
                double seconds = number(response.headers().firstValue("X-RateLimit-Reset-After").orElse("1"), 1);
                readyAt.put(route, received + delay(seconds));
            }
            if (code >= 200 && code < 300) {
                report(route, (route == Route.CHAT ? "Sent" : "Updated") + " (HTTP " + code + ")");
                return Result.OK;
            }
            if (code == 429) {
                JsonObject error;
                try { error = JsonParser.parseString(response.body()).getAsJsonObject(); }
                catch (RuntimeException e) { error = new JsonObject(); }
                double seconds = number(response.headers().firstValue("Retry-After").orElse("5"), 5);
                try { if (error.has("retry_after")) seconds = Math.max(seconds, number(error.get("retry_after").getAsString(), 5)); }
                catch (RuntimeException ignored) { }
                long next = received + delay(seconds);
                readyAt.merge(route, next, Math::max);
                if (response.headers().firstValue("X-RateLimit-Global").orElse("false").equalsIgnoreCase("true")
                        || response.headers().firstValue("X-RateLimit-Scope").orElse("").equals("global"))
                    globalReadyAt = next;
                try { if (error.has("global") && error.get("global").getAsBoolean()) globalReadyAt = next; }
                catch (RuntimeException ignored) { }
                report(route, "Rate limited; retrying in at least " + (long) Math.ceil(seconds) + "s" );
                return Result.RETRY;
            }
            if (code == 401) {
                for (Route r : Route.values()) disabled.put(r, true);
                for (Route r : Route.values()) report(r, "Invalid token (401); fix token and /dbridge reload");
                return Result.DISABLED;
            }
            if (code >= 400 && code < 500) {
                disabled.put(route, true);
                report(route, "HTTP " + code + ": check channel/permissions; /dbridge reload");
                return Result.DISABLED;
            }
            readyAt.put(route, received + delay(10));
            report(route, "Discord error " + code + "; retrying in 10s");
            return Result.RETRY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.RETRY;
        } catch (IOException | RuntimeException e) {
            readyAt.put(route, System.nanoTime() + delay(10));
            // Never log response bodies, request headers, credentials or exception messages.
            report(route, "Connection failed; retrying in 10s");
            return Result.RETRY;
        }
    }

    static double number(String value, double fallback) {
        try { double n = Double.parseDouble(value); return Double.isFinite(n) && n >= 0 ? n : fallback; }
        catch (NumberFormatException e) { return fallback; }
    }

    private static long delay(double seconds) {
        // A corrupt response cannot overflow the monotonic deadline.
        return (long) ((Math.min(86400, Math.max(0, seconds)) + 0.25) * 1_000_000_000L);
    }

    @Override public void close() { http.shutdownNow(); }
}
