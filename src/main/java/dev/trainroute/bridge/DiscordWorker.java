package dev.trainroute.bridge;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One background HTTP worker; lifecycle notices survive clearing ordinary chat. */
final class DiscordWorker implements AutoCloseable {
    private record Line(String text, long created) { }
    private record Batch(JsonObject body, long created, long generation, int count) { }
    private record Topic(String text, long urgency) { }
    private volatile BridgeConfig config;
    private final DiscordRest rest;
    private final ArrayDeque<Line> queue = new ArrayDeque<>(), notices = new ArrayDeque<>();
    private final ScheduledExecutorService executor;
    private final AtomicLong nonce = new AtomicLong(System.currentTimeMillis() * 1000);
    private final AtomicLong dropped = new AtomicLong(), sent = new AtomicLong();
    private volatile Topic topic;
    private volatile boolean closed, resetRoutes;
    private long generation, urgency;
    private Batch pending, pendingNotice;
    private volatile long nextTopic;
    private long sentUrgency;
    private volatile String sentTopic;
    private volatile boolean topicDisabled, chatDisabled;

    DiscordWorker(BridgeConfig config, String token) {
        this(config, new DiscordRest(token, config.channelId), true);
    }

    // Tests use a local HTTP server and deterministic manual pump calls.
    DiscordWorker(BridgeConfig config, DiscordRest rest, boolean schedule) {
        this.config = config; this.rest = rest;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "discord-client-bridge"); t.setDaemon(true); return t;
        });
        if (schedule) executor.scheduleWithFixedDelay(this::pumpSafely, 500, 500, TimeUnit.MILLISECONDS);
    }

    void reconfigure(BridgeConfig value) { config = value; clearChat(); resetRoutes = true; }

    synchronized void enqueue(String text) {
        if (closed || chatDisabled || text.isBlank()) return;
        if (queue.size() >= config.maxQueuedMessages) { queue.removeFirst(); dropped.incrementAndGet(); }
        queue.addLast(new Line(text, System.nanoTime()));
    }

    synchronized void notice(String text) {
        if (closed || chatDisabled) return;
        if (notices.size() >= 20) { notices.removeFirst(); dropped.incrementAndGet(); }
        notices.addLast(new Line(text, System.nanoTime()));
    }

    void topic(String value) { topic(value, false); }
    synchronized void topic(String value, boolean urgent) {
        if (urgent) urgency++;
        topic = new Topic(TextFormat.limit(value, 1024), urgency);
    }

    synchronized void clearChat() { generation++; queue.clear(); pending = null; }

    synchronized String status() {
        return rest.status(config.zone()) + (config.updateChannelTopic ? "" : " (updates off)")
                + "\nSent: " + sent.get() + " | Waiting: "
                + (queue.size() + notices.size() + (pending == null ? 0 : pending.count)
                + (pendingNotice == null ? 0 : 1)) + " | Dropped: " + dropped.get();
    }

    private void pumpSafely() {
        if (closed) return;
        try { pump(); }
        catch (RuntimeException e) {
            DiscordBridgeClient.LOGGER.warn("Bridge worker failed ({}); retrying", e.getClass().getSimpleName());
        }
    }

    void pump() {
        if (closed) return;
        if (resetRoutes) {
            resetRoutes = false; rest.retryDisabled(); topicDisabled = false; chatDisabled = false;
        }
        pumpMessages(); // Notices and chat are not held behind a slow topic request.
        if (closed) return;
        Topic desired = topic;
        long now = System.nanoTime();
        if (config.updateChannelTopic && !topicDisabled && desired != null
                && !desired.text.equals(sentTopic)
                && (now >= nextTopic || desired.urgency > sentUrgency)) {
            JsonObject body = new JsonObject(); body.addProperty("topic", desired.text);
            DiscordRest.Result result = rest.send(DiscordRest.Route.TOPIC, body);
            if (result == DiscordRest.Result.OK) {
                sentTopic = desired.text; sentUrgency = desired.urgency;
                nextTopic = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.topicUpdateSeconds);
            }
            if (result == DiscordRest.Result.DISABLED) topicDisabled = true;
        }
    }

    private void pumpMessages() {
        if (chatDisabled) return;
        Batch batch;
        boolean notification;
        synchronized (this) {
            long now = System.nanoTime();
            long oldest = now - TimeUnit.SECONDS.toNanos(config.messageMaxAgeSeconds);
            long noticeOldest = now - TimeUnit.MINUTES.toNanos(5);
            while (!queue.isEmpty() && queue.peekFirst().created < oldest) {
                queue.removeFirst(); dropped.incrementAndGet();
            }
            while (!notices.isEmpty() && notices.peekFirst().created < noticeOldest) {
                notices.removeFirst(); dropped.incrementAndGet();
            }
            if (pending != null && pending.created < oldest) {
                dropped.addAndGet(pending.count); pending = null;
            }
            if (pendingNotice != null && pendingNotice.created < noticeOldest) {
                dropped.incrementAndGet(); pendingNotice = null;
            }
            if (pendingNotice == null && !notices.isEmpty()) {
                Line line = notices.removeFirst();
                pendingNotice = new Batch(DiscordRest.messageBody(line.text, Long.toString(nonce.incrementAndGet())),
                        line.created, -1, 1);
            }
            notification = pendingNotice != null;
            if (!notification && pending == null && !queue.isEmpty()) {
                StringBuilder content = new StringBuilder();
                long created = queue.peekFirst().created;
                int count = 0;
                while (!queue.isEmpty() && content.length() + queue.peekFirst().text.length() + 1 <= 1900) {
                    if (!content.isEmpty()) content.append('\n');
                    content.append(queue.removeFirst().text); count++;
                }
                pending = new Batch(DiscordRest.messageBody(content.toString(), Long.toString(nonce.incrementAndGet())),
                        created, generation, count);
            }
            batch = notification ? pendingNotice : pending;
        }
        if (batch == null) return;
        synchronized (this) { if ((!notification && batch.generation != generation) || closed) return; }
        DiscordRest.Result result = rest.send(DiscordRest.Route.CHAT, batch.body);
        synchronized (this) {
            if (result == DiscordRest.Result.OK) sent.addAndGet(batch.count);
            if (result != DiscordRest.Result.RETRY) {
                if (notification && pendingNotice == batch) pendingNotice = null;
                if (!notification && pending == batch) pending = null;
            }
            if (result == DiscordRest.Result.DISABLED) {
                chatDisabled = true; queue.clear(); notices.clear(); pending = null; pendingNotice = null;
            }
        }
    }

    private synchronized boolean drained() {
        Topic desired = topic;
        return (chatDisabled || (queue.isEmpty() && notices.isEmpty() && pending == null && pendingNotice == null))
                && (!config.updateChannelTopic || topicDisabled || desired == null || desired.text.equals(sentTopic));
    }

    /** A bounded best-effort flush on a normal game exit; never waits for a long rate limit. */
    void closeGracefully() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!closed && !drained() && System.nanoTime() < deadline) {
            try { Thread.sleep(25); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        close();
    }

    @Override public void close() {
        closed = true;
        clearChat();
        executor.shutdownNow();
        rest.close();
    }
}
