package dev.strataproxy.core.protocol;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Validates and serializes Adventure components for Minecraft 1.7.10 JSON chat. */
public final class MinecraftText {
    public static final int MAX_JSON_BYTES = 32_767;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 256;
    private static final GsonComponentSerializer SERIALIZER = GsonComponentSerializer.colorDownsamplingGson().toBuilder()
            .editOptions(options -> options.value(
                    net.kyori.adventure.text.serializer.json.JSONOptions.EMIT_COMPACT_TEXT_COMPONENT, false))
            .build();

    private MinecraftText() { }

    /** Serializes a supported component tree, failing before a caller schedules a network write. */
    public static String encode(Component component) {
        Objects.requireNonNull(component, "component");
        validateAdventureNodes(component, 1, new ValidationState());
        return encodeValidated(component);
    }

    /** Serializes a disconnect component and requires at least one non-whitespace visible character. */
    public static String encodeReason(Component component) {
        Objects.requireNonNull(component, "component");
        validateAdventureNodes(component, 1, new ValidationState());
        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        if (plain.isBlank()) throw new IllegalArgumentException("disconnect reason must not be blank");
        return encodeValidated(component);
    }

    /** Applies the shared wire-size bound to a pre-encoded component before packet allocation. */
    public static void validateEncoded(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length() > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("encoded component exceeds " + MAX_JSON_BYTES + " bytes");
        }
        int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("encoded component exceeds " + MAX_JSON_BYTES + " bytes");
        }
    }

    private static String encodeValidated(Component component) {
        String encoded = SERIALIZER.serialize(component);
        validateEncoded(encoded);
        return encoded;
    }

    private static void validateAdventureNodes(Component component, int depth, ValidationState state) {
        if (depth > MAX_DEPTH || ++state.count > MAX_NODES) {
            throw new IllegalArgumentException("component tree exceeds supported depth or node count");
        }
        if (!(component instanceof TextComponent) && !(component instanceof TranslatableComponent)) {
            throw new IllegalArgumentException("Minecraft 1.7.10 supports only text and translatable components");
        }
        if (component instanceof TextComponent text) {
            state.addText(text.content());
        } else if (component instanceof TranslatableComponent translatable) {
            state.addText(translatable.key());
            if (translatable.fallback() != null) {
                throw new IllegalArgumentException("translatable fallback text is unsupported by Minecraft 1.7.10");
            }
            for (TranslationArgument argument : translatable.arguments()) {
                Object value = argument.value();
                if (value instanceof ComponentLike child) validateAdventureNodes(child.asComponent(), depth + 1, state);
                else if (++state.count > MAX_NODES) {
                    throw new IllegalArgumentException("too many translation arguments");
                }
                else if (value instanceof String string) state.addText(string);
                else if (!(value instanceof Number) && !(value instanceof Boolean)) {
                    throw new IllegalArgumentException("unsupported translatable argument type: "
                            + (value == null ? "null" : value.getClass().getName()));
                }
            }
        }
        var style = component.style();
        if (style.insertion() != null || style.font() != null || style.shadowColor() != null) {
            throw new IllegalArgumentException("component style contains a field unsupported by Minecraft 1.7.10");
        }
        var click = style.clickEvent();
        if (click != null && click.action() != net.kyori.adventure.text.event.ClickEvent.Action.OPEN_URL
                && click.action() != net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND
                && click.action() != net.kyori.adventure.text.event.ClickEvent.Action.SUGGEST_COMMAND) {
            throw new IllegalArgumentException("unsupported Minecraft 1.7.10 click action: " + click.action());
        }
        if (click != null) {
            if (!(click.payload() instanceof net.kyori.adventure.text.event.ClickEvent.Payload.Text payload)) {
                throw new IllegalArgumentException("supported Minecraft 1.7.10 click actions require a text payload");
            }
            state.addText(payload.value());
        }
        var hover = style.hoverEvent();
        if (hover != null && hover.action() != net.kyori.adventure.text.event.HoverEvent.Action.SHOW_TEXT) {
            throw new IllegalArgumentException("unsupported Minecraft 1.7.10 hover action: " + hover.action());
        }
        if (hover != null) {
            if (!(hover.value() instanceof Component hoverText)) {
                throw new IllegalArgumentException("show_text hover value must be a text component");
            }
            validateAdventureNodes(hoverText, depth + 1, state);
        }
        for (Component child : component.children()) validateAdventureNodes(child, depth + 1, state);
    }

    private static final class ValidationState {
        private int count;
        private int inputBytes;

        private void addText(String value) {
            if (value.length() > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("component text exceeds " + MAX_JSON_BYTES + " bytes");
            }
            int bytes = value.getBytes(StandardCharsets.UTF_8).length;
            inputBytes += bytes;
            if (inputBytes > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("component text exceeds " + MAX_JSON_BYTES + " bytes");
            }
        }
    }
}
