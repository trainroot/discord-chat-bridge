package dev.trainroute.bridge;

/** Plain-text formatting, length limits and Discord markup neutralization. */
public final class TextFormat {
    private TextFormat() { }
    private static final java.util.regex.Pattern BED_COMMAND = java.util.regex.Pattern.compile(
            "(?i)(?<![\\w/])/bed(?![\\w/-])");

    /** Hide received /bed echoes, including arguments, without matching /bedrock. */
    public static boolean excludedCommand(String text) { return BED_COMMAND.matcher(text).find(); }

    public static String plain(String text) {
        return text.replaceAll("§[0-9A-FK-ORa-fk-or]", "")
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll(" +", " ").trim();
    }

    public static String discordLine(String text) {
        return escape(text, 1800);
    }

    private static String escape(String text, int max) {
        StringBuilder out = new StringBuilder();
        for (char ch : plain(text).toCharArray()) {
            String piece = ("\\`*_~|<>[]()#!-".indexOf(ch) >= 0 ? "\\" : "") + ch
                    + (ch == '@' ? "\u200b" : "");
            if (out.length() + piece.length() > max - 1) {
                if (!out.isEmpty() && Character.isHighSurrogate(out.charAt(out.length() - 1)))
                    out.setLength(out.length() - 1);
                out.append('…');
                break;
            }
            out.append(piece);
        }
        return out.toString();
    }

    /** Classifies known messages, keeps unknown text, then applies Discord escaping. */
    public static String relayLine(String clean, boolean formatParsedMessages) {
        var info = formatParsedMessages ? MessageParser.extractMessageInfo(clean) : null;
        if (info == null) return discordLine(clean);
        String name = escape(info.username(), 160);
        if (info.type().endsWith("Join") || info.type().endsWith("Leave"))
            return "***" + name + "*** *" + escape(info.message(), 1500) + "*";
        if (info.type().equals("Private Chat"))
            return "From **" + name + "**: " + escape(info.message(), 1600);
        // Preserve every prefix/suffix inside <...>, including +, Bed and badges.
        int end = clean.indexOf('>');
        String header = escape(clean.substring(1, end), 300);
        return "**" + header + "**: " + escape(info.message(), 1794 - header.length());
    }

    public static String disconnectNotice(String username, String server) {
        return "**" + escape(username, 160) + "** has disconnected from **"
                + escape(server, 300) + "**.";
    }

    /** Limit UTF-16 length without splitting a Unicode surrogate pair. */
    public static String limit(String text, int max) {
        if (text.length() <= max) return text;
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }
}
