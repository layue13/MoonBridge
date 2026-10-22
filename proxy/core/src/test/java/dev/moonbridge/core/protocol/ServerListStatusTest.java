package dev.moonbridge.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerListStatusTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void defaultStatusEncodesProtocolFiveAndLiveOnlineCount() throws Exception {
        JsonNode response = decode(ServerListStatus.defaultStatus(), 23);
        assertEquals(5, response.path("version").path("protocol").intValue());
        assertEquals("1.7.10", response.path("version").path("name").textValue());
        assertEquals(100, response.path("players").path("max").intValue());
        assertEquals(23, response.path("players").path("online").intValue());
        assertEquals("MoonBridge", response.path("description").path("text").textValue());
        assertEquals(0, response.path("players").path("sample").size());
    }

    @Test
    void escapesJsonAndPreservesUnicodeAndNewlines() throws Exception {
        String motd = "岛\"屿\\\n第二行\t\u0001";
        JsonNode response = decode(new ServerListStatus(motd, 42, null), 0);
        assertEquals(motd, response.path("description").path("text").textValue());
        assertEquals(0, response.path("players").path("online").intValue());
        assertEquals(42, response.path("players").path("max").intValue());
    }

    @Test
    void accepts1024NonBmpCodePoints() throws Exception {
        String motd = "😀".repeat(1024);
        JsonNode response = decode(new ServerListStatus(motd, 100, null), 3);
        assertEquals(motd, response.path("description").path("text").textValue());
    }

    @Test
    void includesFaviconInTheStatusJson() throws Exception {
        String favicon = "data:image/png;base64," + Base64.getEncoder().encodeToString("png bytes".getBytes(StandardCharsets.UTF_8));
        JsonNode response = decode(new ServerListStatus("Icon", 17, favicon), 2);
        assertEquals(favicon, response.path("favicon").textValue());
        assertEquals(17, response.path("players").path("max").intValue());
    }

    @Test
    void rejectsMotdAndFaviconThatExceedProtocolStringLimit() {
        String largeFavicon = "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[24_000]);
        assertThrows(IllegalArgumentException.class,
                () -> new ServerListStatus("m".repeat(1024), 100, largeFavicon));
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new ServerListStatus("x".repeat(1025), 10, null));
        assertThrows(IllegalArgumentException.class, () -> new ServerListStatus("ok", 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ServerListStatus("ok", 1_000_001, null));
        assertThrows(IllegalArgumentException.class, () -> ServerListStatus.defaultStatus().encode(
                UnpooledByteBufAllocator.DEFAULT, -1));
    }

    private JsonNode decode(ServerListStatus status, int online) throws Exception {
        ByteBuf body = status.encode(UnpooledByteBufAllocator.DEFAULT, online);
        try {
            assertEquals(0, ProtocolVarInt.read(body));
            int length = ProtocolVarInt.read(body);
            assertTrue(length <= 32_767);
            byte[] bytes = new byte[length];
            body.readBytes(bytes);
            assertEquals(0, body.readableBytes());
            return json.readTree(new String(bytes, StandardCharsets.UTF_8));
        } finally {
            body.release();
        }
    }
}
