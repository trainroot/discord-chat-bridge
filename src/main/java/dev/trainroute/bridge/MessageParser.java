package dev.trainroute.bridge;

import java.util.List;
import java.util.regex.Pattern;

/** Local text classification based on the supplied server chat formats. */
public final class MessageParser {
    private MessageParser() { }

    public record MessageInfo(String type, String username, String message) { }

    private record Parser(String type, Pattern pattern) { }
    private static Parser parser(String type, String regex) {
        return new Parser(type, Pattern.compile(regex));
    }

    // Specific Bed format precedes generic Java badges so "Bed" is not the sender.
    // The dot is retained on Bedrock names to distinguish them from Java names.
    private static final List<Parser> PARSERS = List.of(
            parser("Public Chat [Bed]", "^<Bed\\s+\\+?(\\w+)(?:\\s+[^>\\r\\n]+)?>\\s*(.*)$"),
            parser("Public Chat [Badge]", "^<(?!Bed\\s)\\+?(\\w+)\\s+[^>\\r\\n]+>\\s*(.*)$"),
            parser("Public Chat", "^<\\+?(\\w+)>\\s*(.*)$"),
            parser("Private Chat", "^From \\+?(\\.?\\w+):\\s*(.*)$"),
            parser("Bedrock Chat [Badge]", "^<(\\.\\w+)\\s+[^>\\r\\n]+>\\s*(.*)$"),
            parser("Bedrock Chat", "^<(\\.\\w+)>\\s*(.*)$"),
            parser("Join", "^(\\w+)\\s+(joined the game)$"),
            parser("Leave", "^(\\w+)\\s+(left the game)$"),
            parser("Bedrock Join", "^(\\.\\w+)\\s+(joined the game)$"),
            parser("Bedrock Leave", "^(\\.\\w+)\\s+(left the game)$")
    );

    /** Returns the first matching category, or null for an unknown format. */
    public static MessageInfo extractMessageInfo(String messageText) {
        if (messageText == null) return null;
        String clean = TextFormat.plain(messageText);
        for (Parser parser : PARSERS) {
            var match = parser.pattern.matcher(clean);
            if (match.matches())
                return new MessageInfo(parser.type, match.group(1), match.group(2).trim());
        }
        return null;
    }
}
