package dev.moonbridge.luckperms.bridge;

import dev.moonbridge.core.protocol.MinecraftText;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoonBridgeChatTest {
    @Test void copyActionsBecomeUsableSuggestionsAndUnsupportedStylesAreRemoved() {
        var result = MoonBridgeChat.decode("""
                {"text":"example.permission","color":"#e242f5","insertion":"secret","font":"minecraft:uniform",
                "clickEvent":{"action":"copy_to_clipboard","value":"example.permission"},
                "hoverEvent":{"action":"show_text","contents":{"text":"Copy this node","insertion":"ignored"}}}
                """);
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, result.clickEvent().action());
        assertNull(result.style().font());
        assertNull(result.style().insertion());
        assertDoesNotThrow(() -> MinecraftText.encode(result));
    }

    @Test void editorLinksAndCommandActionsRemainInteractive() {
        var result = MoonBridgeChat.decode("""
                {"text":"Editor","clickEvent":{"action":"open_url","value":"https://luckperms.net/editor/example"},
                "extra":[{"text":" Apply","clickEvent":{"action":"run_command","value":"/lp applyedits example"}}]}
                """);
        assertEquals(ClickEvent.Action.OPEN_URL, result.clickEvent().action());
        assertEquals(ClickEvent.Action.RUN_COMMAND, result.children().getFirst().clickEvent().action());
        assertDoesNotThrow(() -> MinecraftText.encode(result));
    }

    @Test void translationFallbackIsRenderedForOldClients() {
        var result = MoonBridgeChat.decode("""
                {"translate":"plugin.new.key","fallback":"Hello %1$s, %s! 100%%","with":[{"text":"Alice"}]}
                """);
        assertEquals("Hello Alice, Alice! 100%", PlainTextComponentSerializer.plainText().serialize(result));
        assertDoesNotThrow(() -> MinecraftText.encode(result));
    }
}
