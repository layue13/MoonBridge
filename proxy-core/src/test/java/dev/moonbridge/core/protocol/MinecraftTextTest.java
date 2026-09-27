package dev.moonbridge.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftTextTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void templateVariablesRemainLiteralAndPrimitiveArgumentsAreBounded() throws Exception {
        var message = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                "<green>Hello <player></green>",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", "<red>Ada"));
        var encoded = JSON.readTree(MinecraftText.encode(message));
        assertTrue(encoded.toString().contains("<red>Ada"));
        var arguments = new java.util.ArrayList<net.kyori.adventure.text.TranslationArgument>();
        for (int i = 0; i < 300; i++) arguments.add(net.kyori.adventure.text.TranslationArgument.numeric(i));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftText.encode(Component.translatable("key").arguments(arguments)));
    }

    @Test
    void writesLegacyTextTranslationStyleClickAndHoverJson() throws Exception {
        Component component = Component.translatable("chat.type.text", Component.text("Ada"),
                        Component.text("hello"))
                .color(NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/hello"))
                .hoverEvent(HoverEvent.showText(Component.text("Show details")));

        JsonNode encoded = JSON.readTree(MinecraftText.encode(component));

        assertEquals("chat.type.text", encoded.path("translate").textValue());
        assertEquals(2, encoded.path("with").size());
        assertEquals("red", encoded.path("color").textValue());
        assertTrue(encoded.path("bold").booleanValue());
        assertEquals("run_command", encoded.path("clickEvent").path("action").textValue());
        assertEquals("/hello", encoded.path("clickEvent").path("value").textValue());
        assertEquals("show_text", encoded.path("hoverEvent").path("action").textValue());
        JsonNode hoverValue = encoded.path("hoverEvent").path("value");
        assertEquals("Show details", hoverValue.isTextual()
                ? hoverValue.textValue() : hoverValue.path("text").textValue());
        assertTrue(encoded.path("hoverEvent").has("value"));
        assertTrue(!encoded.path("hoverEvent").has("contents"));
    }

    @Test
    void supportsTextExtraAndAllThreeMinecraftClickActions() throws Exception {
        for (ClickEvent<?> click : new ClickEvent<?>[]{
                ClickEvent.openUrl("https://example.test"),
                ClickEvent.runCommand("/home"),
                ClickEvent.suggestCommand("/spawn")}) {
            Component component = Component.text("go").clickEvent(click)
                    .append(Component.text(" now", NamedTextColor.GREEN));
            JsonNode encoded = JSON.readTree(MinecraftText.encode(component));
            assertEquals(click.action().toString().toLowerCase(), encoded.path("clickEvent").path("action").textValue());
            assertEquals(" now", encoded.path("extra").get(0).path("text").textValue());
        }
    }

    @Test
    void rejectsComponentKindsAndEventsWithoutMinecraft1710Support() {
        assertThrows(IllegalArgumentException.class, () -> MinecraftText.encode(Component.keybind("key.jump")));
        assertThrows(IllegalArgumentException.class, () -> MinecraftText.encode(Component.selector("@a")));
        assertThrows(IllegalArgumentException.class, () -> MinecraftText.encode(Component.text("x")
                .clickEvent(ClickEvent.copyToClipboard("x"))));
        assertThrows(IllegalArgumentException.class, () -> MinecraftText.encode(Component.text("x")
                .hoverEvent(HoverEvent.showItem(Key.key("minecraft:stone"), 1))));
        assertThrows(IllegalArgumentException.class, () -> MinecraftText.encode(Component.text("x")
                .insertion("insert")));
        assertThrows(IllegalArgumentException.class, () -> MinecraftText.encode(
                Component.translatable("hello").fallback("Hello")));
    }

    @Test
    void rejectsBlankReasonsAndBoundsTheTreeBeforeSerialization() {
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftText.encodeReason(Component.text(" \n\t")));

        Component tooDeep = Component.text("leaf");
        for (int i = 0; i < 32; i++) tooDeep = Component.text("x").hoverEvent(HoverEvent.showText(tooDeep));
        Component deep = tooDeep;
        assertThrows(IllegalArgumentException.class, () -> MinecraftText.encode(deep));

        Component[] children = new Component[256];
        for (int i = 0; i < children.length; i++) children[i] = Component.text("x");
        Component wide = Component.textOfChildren(children);
        assertThrows(IllegalArgumentException.class, () -> MinecraftText.encode(wide));

        assertThrows(IllegalArgumentException.class,
                () -> MinecraftText.encode(Component.text("x".repeat(MinecraftText.MAX_JSON_BYTES + 1))));
    }

    @Test
    void packetEncodersRejectOversizedPreencodedJsonBeforeAllocation() {
        String tooLarge = "\"" + "x".repeat(MinecraftText.MAX_JSON_BYTES) + "\"";
        assertThrows(IllegalArgumentException.class, () -> Minecraft1710PlayPackets.chatReplyEncoded(
                io.netty.buffer.UnpooledByteBufAllocator.DEFAULT, tooLarge));
        assertThrows(IllegalArgumentException.class, () -> Minecraft1710PlayPackets.disconnectEncoded(
                io.netty.buffer.UnpooledByteBufAllocator.DEFAULT, tooLarge));
        assertThrows(IllegalArgumentException.class, () -> MinecraftLoginDisconnect.encodeJson(
                io.netty.buffer.UnpooledByteBufAllocator.DEFAULT, tooLarge));
        assertNotNull(MinecraftText.encode(Component.text("valid")));
    }
}
