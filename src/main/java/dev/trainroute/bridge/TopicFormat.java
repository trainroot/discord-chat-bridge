package dev.trainroute.bridge;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/** Human-readable snapshots from data already present in the Minecraft client. */
public final class TopicFormat {
    private TopicFormat() { }
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern(
            "MMM d, uuuu 'at' h:mm:ss a z", Locale.US);
    public static String timestamp(Instant instant, ZoneId zone) {
        return TIME.withZone(zone).format(instant);
    }

    public static String connected(String server, String username, List<String> players,
                                   OptionalDouble tps, Integer ping, Instant time, ZoneId zone) {
        String prefix = TextFormat.limit(TextFormat.plain(server), 150) + " | Connected as "
                + TextFormat.limit(TextFormat.plain(username), 40) + " | TPS (est.): "
                + (tps.isPresent() ? String.format(Locale.ROOT, "%.1f", tps.getAsDouble()) : "N/A")
                + " | Ping: " + (ping == null ? "N/A" : ping + " ms") + " | Online (tab): ";
        String suffix = " | Updated: " + timestamp(time, zone);
        List<String> names = players.stream().map(TextFormat::plain)
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        StringBuilder list = new StringBuilder();
        int shown = 0;
        for (String name : names) {
            String item = (shown == 0 ? "" : ", ") + name;
            int left = names.size() - shown - 1;
            String overflow = left == 0 ? "" : ", … (+" + left + " more)";
            if (prefix.length() + list.length() + item.length() + overflow.length() + suffix.length() > 1024)
                break;
            list.append(item); shown++;
        }
        if (shown < names.size()) list.append(shown == 0 ? "" : ", ")
                .append("… (+").append(names.size() - shown).append(" more)");
        if (names.isEmpty()) list.append("none");
        return prefix + list + suffix;
    }

    public static String inactive(String server, String username, String state, Instant time, ZoneId zone) {
        return TextFormat.limit(TextFormat.plain(server), 150) + " | " + state + " | Client: "
                + TextFormat.limit(TextFormat.plain(username), 40)
                + " | TPS: unavailable | Online (tab): unavailable | Updated: " + timestamp(time, zone);
    }
}
