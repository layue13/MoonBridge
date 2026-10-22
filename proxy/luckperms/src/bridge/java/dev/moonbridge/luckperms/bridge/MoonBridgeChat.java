package dev.moonbridge.luckperms.bridge;

import dev.moonbridge.api.CommandSource;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.PlayerView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Unshaded host-side boundary. The LuckPerms engine sends JSON, never its private Adventure objects.
 * This class is compiled against MoonBridge's Adventure version and merged after engine relocation.
 */
public final class MoonBridgeChat {
    private static final Pattern ARGUMENT = Pattern.compile("%%|%(?:(\\d+)\\$)?s");

    private MoonBridgeChat() { }

    /** Creates host command sources without exposing host Adventure types to the shaded engine. */
    public static CommandSource source(PluginContext context, java.util.Optional<PlayerView> player) {
        if (player.isPresent()) {
            PlayerView view = player.get();
            return CommandSource.player(view, component -> context.players().sendMessage(view.identity(), component));
        }
        return CommandSource.console(component -> context.logger().info("{}",
                PlainTextComponentSerializer.plainText().serialize(component)));
    }

    public static void send(CommandSource source, String json) {
        source.reply(decode(json));
    }

    public static void send(Players players, PlayerIdentity identity, String json) {
        players.sendMessage(identity, decode(json));
    }

    /** Converts LuckPerms output into the rich-text capabilities supported by Minecraft 1.7.10. */
    public static Component decode(String json) {
        Objects.requireNonNull(json, "json");
        if (json.length() > 131_072) throw new IllegalArgumentException("LuckPerms message is too large");
        return normalize(GsonComponentSerializer.gson().deserialize(json), 1, new Budget());
    }

    private static Component normalize(Component input, int depth, Budget budget) {
        if (depth > 24 || ++budget.nodes > 200) throw new IllegalArgumentException("LuckPerms message tree is too large");
        Component result;
        if (input instanceof TextComponent text) {
            result = Component.text(text.content());
        } else if (input instanceof TranslatableComponent translated) {
            var arguments = new ArrayList<TranslationArgument>();
            for (TranslationArgument argument : translated.arguments()) {
                if (argument.value() instanceof ComponentLike component) {
                    arguments.add(TranslationArgument.component(normalize(component.asComponent(), depth + 1, budget)));
                } else {
                    arguments.add(argument);
                }
            }
            result = translated.fallback() == null
                    ? Component.translatable(translated.key()).arguments(arguments)
                    : renderFallback(translated.fallback(), arguments);
        } else {
            // Keybinds, selectors and newer component kinds have no representation on protocol 5.
            result = Component.text(PlainTextComponentSerializer.plainText().serialize(input.children(java.util.List.of())));
        }

        var style = input.style().toBuilder().font(null).insertion(null).shadowColor(null);
        ClickEvent click = input.clickEvent();
        if (click != null) {
            if (click.action() == ClickEvent.Action.COPY_TO_CLIPBOARD
                    && click.payload() instanceof ClickEvent.Payload.Text text) {
                style.clickEvent(ClickEvent.suggestCommand(text.value()));
            } else if (click.action() != ClickEvent.Action.OPEN_URL
                    && click.action() != ClickEvent.Action.RUN_COMMAND
                    && click.action() != ClickEvent.Action.SUGGEST_COMMAND) {
                style.clickEvent(null);
            }
        }
        var hover = input.hoverEvent();
        if (hover != null) {
            style.hoverEvent(hover.action() == HoverEvent.Action.SHOW_TEXT && hover.value() instanceof Component text
                    ? HoverEvent.showText(normalize(text, depth + 1, budget)) : null);
        }
        result = result.style(style.build());
        for (Component child : input.children()) result = result.append(normalize(child, depth + 1, budget));
        return result;
    }

    private static Component renderFallback(String format, java.util.List<TranslationArgument> arguments) {
        var matcher = ARGUMENT.matcher(format);
        Component result = Component.empty();
        int end = 0;
        int sequential = 0;
        while (matcher.find()) {
            result = result.append(Component.text(format.substring(end, matcher.start())));
            if (matcher.group().equals("%%")) result = result.append(Component.text("%"));
            else {
                int index;
                try { index = matcher.group(1) == null ? sequential++ : Integer.parseInt(matcher.group(1)) - 1; }
                catch (NumberFormatException invalid) { index = -1; }
                if (index < 0 || index >= arguments.size()) result = result.append(Component.text(matcher.group()));
                else {
                    Object value = arguments.get(index).value();
                    result = result.append(value instanceof ComponentLike component ? component.asComponent()
                            : Component.text(String.valueOf(value)));
                }
            }
            end = matcher.end();
        }
        return result.append(Component.text(format.substring(end)));
    }

    private static final class Budget { private int nodes; }
}
