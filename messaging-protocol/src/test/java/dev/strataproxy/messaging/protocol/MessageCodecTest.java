package dev.strataproxy.messaging.protocol;

import dev.strataproxy.messaging.Endpoint;
import dev.strataproxy.messaging.Message;
import dev.strataproxy.messaging.MessageKind;
import dev.strataproxy.messaging.PublishResult;
import dev.strataproxy.messaging.SendResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageCodecTest {
    private static final Endpoint PROXY = Endpoint.proxy();
    private static final Endpoint BACKEND = Endpoint.backend("island-a");

    @Test
    void messageRoundTripsEnvelopeIdAndPayloadAtMaximumSize() throws Exception {
        byte[] payload = new byte[Message.MAX_PAYLOAD_BYTES];
        Arrays.fill(payload, (byte) 0x5a);
        UUID id = UUID.fromString("a4e57b18-09b0-4ef0-a7c6-4b8e2d70a029");
        Message event = new Message(id, MessageKind.EVENT, "islands:bulk", BACKEND, PROXY, null, payload);

        MessageCodec.IncomingMessage decoded = MessageCodec.decodeMessage(
                MessageCodec.message(41, 60000, event));

        assertEquals(41, decoded.operationId);
        assertEquals(60000, decoded.timeoutMillis);
        assertEquals(id, decoded.message.id());
        assertEquals(MessageKind.EVENT, decoded.message.kind());
        assertEquals("islands:bulk", decoded.message.channel());
        assertEquals(BACKEND, decoded.message.source());
        assertEquals(PROXY, decoded.message.target());
        assertArrayEquals(payload, decoded.message.payload());
    }

    @Test
    void replyRoundTripsMessageIdAndReplyTo() throws Exception {
        UUID requestId = UUID.fromString("b5366a10-3a44-4b8d-8c31-83e91ae7af1c");
        UUID replyId = UUID.fromString("6e1bf5ca-3ce1-422b-92e3-c49e1ce3e198");
        Message request = new Message(requestId, MessageKind.REQUEST, "islands:query", PROXY, BACKEND,
                null, new byte[] {1, 2});
        Message reply = new Message(replyId, MessageKind.REPLY, request.channel(), BACKEND, PROXY,
                request.id(), new byte[] {3, 4});

        MessageCodec.Response decoded = MessageCodec.decodeResponse(MessageCodec.reply(9, reply));

        assertEquals(MessageCodec.Response.Type.REPLY, decoded.type);
        assertEquals(9, decoded.operationId);
        assertEquals(replyId, decoded.message.id());
        assertEquals(requestId, decoded.message.replyTo());
        assertEquals(BACKEND, decoded.message.source());
        assertEquals(PROXY, decoded.message.target());
        assertArrayEquals(new byte[] {3, 4}, decoded.message.payload());
    }

    @Test
    void timeoutAndFailureUseStableWireCodesSixAndSeven() throws Exception {
        byte[] timeout = MessageCodec.sendResult(1, SendResult.TIMED_OUT);
        byte[] failed = MessageCodec.sendResult(2, SendResult.FAILED);

        assertEquals(6, timeout[10] & 0xff);
        assertEquals(7, failed[10] & 0xff);
        assertEquals(SendResult.TIMED_OUT, MessageCodec.decodeResponse(timeout).sendResult);
        assertEquals(SendResult.FAILED, MessageCodec.decodeResponse(failed).sendResult);
    }

    @Test
    void rejectsPayloadLargerThanThePublicLimit() {
        assertThrows(IllegalArgumentException.class, () -> Message.event("islands:bulk", PROXY, BACKEND,
                new byte[Message.MAX_PAYLOAD_BYTES + 1]));
    }

    @Test
    void rejectsMalformedUtf8BooleanAndMessageKind() throws Exception {
        byte[] valid = MessageCodec.message(3, 1000,
                Message.event("islands:wire", BACKEND, PROXY, new byte[0]));

        byte[] invalidUtf8 = valid.clone();
        int channelOffset = 1 + 8 + 8 + 16 + 1 + 2;
        invalidUtf8[channelOffset] = (byte) 0xc3;
        invalidUtf8[channelOffset + 1] = 0x28;
        assertThrows(IOException.class, () -> MessageCodec.decodeMessage(invalidUtf8));

        byte[] invalidBoolean = valid.clone();
        int sourceBooleanOffset = channelOffset + "islands:wire".getBytes(StandardCharsets.US_ASCII).length;
        invalidBoolean[sourceBooleanOffset] = 2;
        assertThrows(IOException.class, () -> MessageCodec.decodeMessage(invalidBoolean));

        byte[] invalidKind = valid.clone();
        invalidKind[1 + 8 + 8 + 16] = 0x7f;
        assertThrows(IOException.class, () -> MessageCodec.decodeMessage(invalidKind));
    }

    @Test
    void rejectsTrailingBytesAndOversizedFrames() throws Exception {
        byte[] message = MessageCodec.message(5, 1000,
                Message.event("islands:wire", BACKEND, PROXY, new byte[0]));
        byte[] trailing = Arrays.copyOf(message, message.length + 1);
        assertThrows(IOException.class, () -> MessageCodec.decodeMessage(trailing));

        byte[] response = MessageCodec.sendResult(6, SendResult.ACCEPTED);
        byte[] responseTrailing = Arrays.copyOf(response, response.length + 1);
        assertThrows(IOException.class, () -> MessageCodec.decodeResponse(responseTrailing));

        byte[] oversized = new byte[MessageCodec.MAX_FRAME_BYTES + 1];
        oversized[0] = (byte) MessageCodec.RESPONSE;
        assertThrows(IOException.class, () -> MessageCodec.decodeResponse(oversized));
    }

    @Test
    void rejectsPublishResultsAboveTheFanoutLimit() throws Exception {
        Map<Endpoint, SendResult> tooMany = new LinkedHashMap<>();
        for (int index = 0; index <= MessageCodec.MAX_PUBLISH_RESULTS; index++) {
            tooMany.put(Endpoint.backend("node-" + index), SendResult.ACCEPTED);
        }
        PublishResult result = new PublishResult(UUID.randomUUID(), tooMany);
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.published(12, result));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(MessageCodec.RESPONSE);
        out.writeLong(13);
        out.writeByte(3); // PUBLISH_RESULT
        out.writeLong(1L);
        out.writeLong(2L);
        out.writeShort(MessageCodec.MAX_PUBLISH_RESULTS + 1);
        assertThrows(IOException.class, () -> MessageCodec.decodeResponse(bytes.toByteArray()));
    }
}
