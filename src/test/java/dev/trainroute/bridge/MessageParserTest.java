package dev.trainroute.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MessageParserTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "<+AGoodDog MSA> hello|Public Chat [Badge]|AGoodDog|hello",
            "<AGoodDog MSA> hello|Public Chat [Badge]|AGoodDog|hello",
            "<+AGoodDog> hello|Public Chat|AGoodDog|hello",
            "<AGoodDog> hello|Public Chat|AGoodDog|hello",
            "From AGoodDog: hello|Private Chat|AGoodDog|hello",
            "From .AGoodDog: hello|Private Chat|.AGoodDog|hello",
            "<.AGoodDog MSA>hello|Bedrock Chat [Badge]|.AGoodDog|hello",
            "<.AGoodDog> hello|Bedrock Chat|.AGoodDog|hello",
            "AGoodDog joined the game|Join|AGoodDog|joined the game",
            "AGoodDog left the game|Leave|AGoodDog|left the game",
            ".AGoodDog joined the game|Bedrock Join|.AGoodDog|joined the game",
            ".AGoodDog left the game|Bedrock Leave|.AGoodDog|left the game",
            "<Bed +AGoodDog MSA> hello|Public Chat [Bed]|AGoodDog|hello",
            "<Bed AGoodDog MSA> hello|Public Chat [Bed]|AGoodDog|hello",
            "<Bed +AGoodDog> hello|Public Chat [Bed]|AGoodDog|hello",
            "<Bed AGoodDog> hello|Public Chat [Bed]|AGoodDog|hello",
            "<Bed> hello|Public Chat|Bed|hello",
            "<Sam_123> hello|Public Chat|Sam_123|hello"
    })
    void recognizesSuppliedFormats(String raw, String type, String username, String message) {
        assertEquals(new MessageParser.MessageInfo(type, username, message),
                MessageParser.extractMessageInfo(raw));
    }

    @Test void keepsMessageContentsAndHandlesEmptyMessages() {
        var result = MessageParser.extractMessageInfo("§c<+Sam MSA>  hi > there: 😀  ");
        assertEquals("Sam", result.username());
        assertEquals("hi > there: 😀", result.message());
        assertEquals("", MessageParser.extractMessageInfo("<Sam> ").message());
    }

    @Test void unknownFormatsAreNotMisidentifiedOrDropped() {
        for (String text : new String[]{"Server restarting", "prefix <Sam> hello",
                "<Sam missing terminator", "Sam joined the game yesterday", ""}) {
            assertNull(MessageParser.extractMessageInfo(text));
            assertEquals(TextFormat.discordLine(text), TextFormat.relayLine(text, true));
        }
        assertNull(MessageParser.extractMessageInfo(null));
    }

    @Test void relayFormatsOnceAndStillEscapesMentionsAndMarkup() {
        String raw = "<+Sam MSA> @everyone **hello**";
        assertEquals("**+Sam MSA**: " + TextFormat.discordLine("@everyone **hello**"),
                TextFormat.relayLine(raw, true));
        assertEquals(TextFormat.discordLine(raw), TextFormat.relayLine(raw, false));
        assertFalse(TextFormat.relayLine(raw, true).contains("@everyone"));
        assertEquals("From **.Sam**: hello",
                TextFormat.relayLine("From .Sam: hello", true));
    }

    @Test void existingConfigsEnableParsingAndCanOptOut(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("discord-client-bridge.json");
        Files.writeString(config, "{\"enabled\":false}");
        assertTrue(BridgeConfig.load(dir).formatParsedMessages);
        Files.writeString(config, "{\"formatParsedMessages\":false}");
        assertFalse(BridgeConfig.load(dir).formatParsedMessages);
    }
}
