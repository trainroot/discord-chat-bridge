package dev.trainroute.bridge;

/** Remembers the last allowed session even after Minecraft clears its connection. */
final class BridgeSession {
    record Disconnection(String username, String server) { }
    private String username = "Minecraft client", server = "Minecraft";
    private boolean observed;
    void observe(String username, String server) {
        this.username = username; this.server = server; observed = true;
    }
    Disconnection disconnect() {
        if (!observed) return null;
        observed = false;
        return new Disconnection(username, server);
    }
    String username() { return username; }
    String server() { return server; }
    boolean observed() { return observed; }
}
