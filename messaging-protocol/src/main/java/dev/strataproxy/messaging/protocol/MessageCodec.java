package dev.strataproxy.messaging.protocol;

import dev.strataproxy.messaging.Endpoint;
import dev.strataproxy.messaging.Message;
import dev.strataproxy.messaging.MessageKind;
import dev.strataproxy.messaging.MessagingException;
import dev.strataproxy.messaging.PublishResult;
import dev.strataproxy.messaging.SendResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Binary message frames shared by proxy-core and Java 8 backend clients. */
public final class MessageCodec {
    public static final int MESSAGE = 9;
    public static final int RESPONSE = 10;
    public static final int MAX_FRAME_BYTES = 66 * 1024;
    public static final int MAX_NODE_BYTES = 128;
    public static final int MAX_PUBLISH_RESULTS = 256;
    public static final long MIN_TIMEOUT_MILLIS = 1L;
    public static final long MAX_TIMEOUT_MILLIS = 60_000L;

    private static final int EVENT = 1;
    private static final int REQUEST = 2;
    private static final int REPLY = 3;
    private static final int SEND_RESULT = 1;
    private static final int REPLY_RESULT = 2;
    private static final int PUBLISH_RESULT = 3;
    private static final int ERROR_RESULT = 4;
    private static final int MAX_DETAIL_BYTES = 1024;

    private MessageCodec() { }

    /** Returns a message frame body (the transport adds the outer 4-byte length prefix). */
    public static byte[] message(long operationId, long timeoutMillis, Message message) throws IOException {
        requireOperationId(operationId);
        requireTimeout(timeoutMillis);
        if (message == null) throw new IllegalArgumentException("message is required");
        if (message.kind() == MessageKind.REPLY) throw new IllegalArgumentException("reply cannot be sent as a message request");
        return encode(MESSAGE, out -> {
            out.writeLong(operationId);
            out.writeLong(timeoutMillis);
            writeMessage(out, message);
        });
    }

    public static IncomingMessage decodeMessage(byte[] frame) throws IOException {
        DataInputStream in = body(frame, MESSAGE);
        long operationId = in.readLong();
        requireWireOperationId(operationId);
        long timeoutMillis = in.readLong();
        requireWireTimeout(timeoutMillis);
        Message message = readMessage(in);
        if (message.kind() == MessageKind.REPLY) throw new IOException("reply cannot be sent as a message request");
        requireEnd(in);
        return new IncomingMessage(operationId, timeoutMillis, message);
    }

    public static byte[] sendResult(long operationId, SendResult result) throws IOException {
        requireOperationId(operationId);
        if (result == null) throw new IllegalArgumentException("send result is required");
        return encode(RESPONSE, out -> {
            out.writeLong(operationId);
            out.writeByte(SEND_RESULT);
            out.writeByte(sendResultCode(result));
        });
    }

    public static byte[] reply(long operationId, Message message) throws IOException {
        requireOperationId(operationId);
        if (message == null || message.kind() != MessageKind.REPLY) {
            throw new IllegalArgumentException("a reply message is required");
        }
        return encode(RESPONSE, out -> {
            out.writeLong(operationId);
            out.writeByte(REPLY_RESULT);
            writeMessage(out, message);
        });
    }

    public static byte[] published(long operationId, PublishResult result) throws IOException {
        requireOperationId(operationId);
        if (result == null) throw new IllegalArgumentException("publish result is required");
        Map<Endpoint, SendResult> results = result.results();
        if (results.size() > MAX_PUBLISH_RESULTS) throw new IllegalArgumentException("too many publish results");
        return encode(RESPONSE, out -> {
            out.writeLong(operationId);
            out.writeByte(PUBLISH_RESULT);
            writeUuid(out, result.messageId());
            out.writeShort(results.size());
            for (Map.Entry<Endpoint, SendResult> entry : results.entrySet()) {
                writeEndpoint(out, entry.getKey());
                out.writeByte(sendResultCode(entry.getValue()));
            }
        });
    }

    public static byte[] error(long operationId, MessagingException.Code code, String detail) throws IOException {
        requireOperationId(operationId);
        if (code == null) throw new IllegalArgumentException("error code is required");
        byte[] encodedDetail = detail == null || detail.isEmpty()
                ? new byte[0] : encodeUtf8(detail, MAX_DETAIL_BYTES, "error detail");
        return encode(RESPONSE, out -> {
            out.writeLong(operationId);
            out.writeByte(ERROR_RESULT);
            out.writeByte(errorCode(code));
            out.writeShort(encodedDetail.length);
            out.write(encodedDetail);
        });
    }

    public static Response decodeResponse(byte[] frame) throws IOException {
        DataInputStream in = body(frame, RESPONSE);
        long operationId = in.readLong();
        requireWireOperationId(operationId);
        int typeCode = in.readUnsignedByte();
        Response response;
        switch (typeCode) {
            case SEND_RESULT:
                response = Response.send(operationId, readSendResult(in));
                break;
            case REPLY_RESULT:
                Message reply = readMessage(in);
                if (reply.kind() != MessageKind.REPLY) throw new IOException("response message is not a reply");
                response = Response.reply(operationId, reply);
                break;
            case PUBLISH_RESULT:
                UUID messageId = readUuid(in);
                int count = in.readUnsignedShort();
                if (count > MAX_PUBLISH_RESULTS) throw new IOException("too many publish results");
                Map<Endpoint, SendResult> results = new LinkedHashMap<Endpoint, SendResult>();
                for (int i = 0; i < count; i++) {
                    Endpoint endpoint = readEndpoint(in);
                    SendResult result = readSendResult(in);
                    if (results.put(endpoint, result) != null) throw new IOException("duplicate publish endpoint");
                }
                response = Response.published(operationId, new PublishResult(messageId, results));
                break;
            case ERROR_RESULT:
                MessagingException.Code errorCode = readErrorCode(in);
                String detail = readOptionalString(in, MAX_DETAIL_BYTES, "error detail");
                response = Response.error(operationId, errorCode, detail);
                break;
            default:
                throw new IOException("unknown messaging response type: " + typeCode);
        }
        requireEnd(in);
        return response;
    }

    private static void writeMessage(DataOutputStream out, Message message) throws IOException {
        writeUuid(out, message.id());
        out.writeByte(messageKindCode(message.kind()));
        writeString(out, message.channel(), Message.MAX_CHANNEL_BYTES, "channel");
        writeEndpoint(out, message.source());
        writeNullableEndpoint(out, message.target());
        writeNullableUuid(out, message.replyTo());
        byte[] payload = message.payload();
        if (payload.length > Message.MAX_PAYLOAD_BYTES) throw new IOException("message payload exceeds limit");
        out.writeInt(payload.length);
        out.write(payload);
    }

    private static Message readMessage(DataInputStream in) throws IOException {
        UUID id = readUuid(in);
        MessageKind kind = readMessageKind(in.readUnsignedByte());
        String channel = readString(in, Message.MAX_CHANNEL_BYTES, "channel");
        try {
            Message.validateChannel(channel);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid message channel", invalid);
        }
        Endpoint source = readEndpoint(in);
        Endpoint target = readNullableEndpoint(in);
        UUID replyTo = readNullableUuid(in);
        int length = in.readInt();
        if (length < 0 || length > Message.MAX_PAYLOAD_BYTES) throw new IOException("message payload length out of bounds");
        byte[] payload = new byte[length];
        in.readFully(payload);
        try {
            return new Message(id, kind, channel, source, target, replyTo, payload);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid message envelope", invalid);
        }
    }

    private static void writeEndpoint(DataOutputStream out, Endpoint endpoint) throws IOException {
        if (endpoint == null) throw new IllegalArgumentException("endpoint is required");
        out.writeBoolean(endpoint.isProxy());
        if (!endpoint.isProxy()) writeString(out, endpoint.backendName(), MAX_NODE_BYTES, "backend name");
    }

    private static Endpoint readEndpoint(DataInputStream in) throws IOException {
        if (readBoolean(in)) return Endpoint.proxy();
        String name = readString(in, MAX_NODE_BYTES, "backend name");
        try {
            return Endpoint.backend(name);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid backend endpoint", invalid);
        }
    }

    private static void writeNullableEndpoint(DataOutputStream out, Endpoint endpoint) throws IOException {
        out.writeBoolean(endpoint != null);
        if (endpoint != null) writeEndpoint(out, endpoint);
    }

    private static Endpoint readNullableEndpoint(DataInputStream in) throws IOException {
        return readBoolean(in) ? readEndpoint(in) : null;
    }

    private static void writeNullableUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) writeUuid(out, value);
    }

    private static UUID readNullableUuid(DataInputStream in) throws IOException {
        return readBoolean(in) ? readUuid(in) : null;
    }

    private static boolean readBoolean(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value != 0 && value != 1) throw new IOException("invalid boolean value");
        return value == 1;
    }

    private static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
        if (uuid == null) throw new IllegalArgumentException("UUID is required");
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static int messageKindCode(MessageKind kind) {
        if (kind == MessageKind.EVENT) return EVENT;
        if (kind == MessageKind.REQUEST) return REQUEST;
        if (kind == MessageKind.REPLY) return REPLY;
        throw new IllegalArgumentException("unknown message kind");
    }

    private static MessageKind readMessageKind(int code) throws IOException {
        switch (code) {
            case EVENT: return MessageKind.EVENT;
            case REQUEST: return MessageKind.REQUEST;
            case REPLY: return MessageKind.REPLY;
            default: throw new IOException("unknown message kind: " + code);
        }
    }

    private static int sendResultCode(SendResult result) {
        switch (result) {
            case ACCEPTED: return 1;
            case NO_SUBSCRIBER: return 2;
            case NOT_CONNECTED: return 3;
            case BACKPRESSURED: return 4;
            case REJECTED: return 5;
            case TIMED_OUT: return 6;
            case FAILED: return 7;
            default: throw new IllegalArgumentException("unknown send result");
        }
    }

    private static SendResult readSendResult(DataInputStream in) throws IOException {
        switch (in.readUnsignedByte()) {
            case 1: return SendResult.ACCEPTED;
            case 2: return SendResult.NO_SUBSCRIBER;
            case 3: return SendResult.NOT_CONNECTED;
            case 4: return SendResult.BACKPRESSURED;
            case 5: return SendResult.REJECTED;
            case 6: return SendResult.TIMED_OUT;
            case 7: return SendResult.FAILED;
            default: throw new IOException("unknown send result");
        }
    }

    private static int errorCode(MessagingException.Code code) {
        switch (code) {
            case NOT_CONNECTED: return 1;
            case NO_HANDLER: return 2;
            case BACKPRESSURED: return 3;
            case TIMED_OUT: return 4;
            case CLOSED: return 5;
            case REJECTED: return 6;
            case HANDLER_FAILED: return 7;
            case PROTOCOL_ERROR: return 8;
            default: throw new IllegalArgumentException("unknown messaging error code");
        }
    }

    private static MessagingException.Code readErrorCode(DataInputStream in) throws IOException {
        switch (in.readUnsignedByte()) {
            case 1: return MessagingException.Code.NOT_CONNECTED;
            case 2: return MessagingException.Code.NO_HANDLER;
            case 3: return MessagingException.Code.BACKPRESSURED;
            case 4: return MessagingException.Code.TIMED_OUT;
            case 5: return MessagingException.Code.CLOSED;
            case 6: return MessagingException.Code.REJECTED;
            case 7: return MessagingException.Code.HANDLER_FAILED;
            case 8: return MessagingException.Code.PROTOCOL_ERROR;
            default: throw new IOException("unknown messaging error code");
        }
    }

    private static void writeString(DataOutputStream out, String value, int maxBytes, String label) throws IOException {
        byte[] bytes = encodeUtf8(value, maxBytes, label);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxBytes, String label) throws IOException {
        int length = in.readUnsignedShort();
        if (length == 0 || length > maxBytes) throw new IOException(label + " length out of bounds");
        return readUtf8(in, length, label);
    }

    private static String readOptionalString(DataInputStream in, int maxBytes, String label) throws IOException {
        int length = in.readUnsignedShort();
        if (length > maxBytes) throw new IOException(label + " length out of bounds");
        if (length == 0) return "";
        return readUtf8(in, length, label);
    }

    private static String readUtf8(DataInputStream in, int length, String label) throws IOException {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("invalid UTF-8 in " + label, invalid);
        }
    }

    private static byte[] encodeUtf8(String value, int maxBytes, String label) throws IOException {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(label + " is required");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maxBytes) throw new IllegalArgumentException(label + " length out of bounds");
        return bytes;
    }

    private static DataInputStream body(byte[] frame, int type) throws IOException {
        if (frame == null || frame.length < 1 || frame.length > MAX_FRAME_BYTES || (frame[0] & 0xff) != type) {
            throw new IOException("invalid message frame type or size");
        }
        return new DataInputStream(new ByteArrayInputStream(frame, 1, frame.length - 1));
    }

    private static byte[] encode(int type, Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(type);
        writer.write(out);
        out.flush();
        byte[] frame = bytes.toByteArray();
        if (frame.length > MAX_FRAME_BYTES) throw new IOException("message frame exceeds maximum size");
        return frame;
    }

    private static void requireEnd(DataInputStream in) throws IOException {
        if (in.read() != -1) throw new IOException("trailing message frame data");
    }

    private static void requireOperationId(long operationId) {
        if (operationId <= 0) throw new IllegalArgumentException("operationId must be positive");
    }

    private static void requireWireOperationId(long operationId) throws IOException {
        if (operationId <= 0) throw new IOException("operationId must be positive");
    }

    private static void requireTimeout(long timeoutMillis) {
        if (timeoutMillis < MIN_TIMEOUT_MILLIS || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("timeoutMillis out of bounds");
        }
    }

    private static void requireWireTimeout(long timeoutMillis) throws IOException {
        if (timeoutMillis < MIN_TIMEOUT_MILLIS || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new IOException("timeoutMillis out of bounds");
        }
    }

    @FunctionalInterface
    private interface Writer { void write(DataOutputStream out) throws IOException; }

    /** Decoded inbound message with its hop-local acknowledgement operation and deadline. */
    public static final class IncomingMessage {
        public final long operationId;
        public final long timeoutMillis;
        public final Message message;

        private IncomingMessage(long operationId, long timeoutMillis, Message message) {
            this.operationId = operationId;
            this.timeoutMillis = timeoutMillis;
            this.message = message;
        }
    }

    /** Decoded response. Unused value fields are null. */
    public static final class Response {
        public enum Type { SEND, REPLY, PUBLISH, ERROR }

        public final long operationId;
        public final Type type;
        public final SendResult sendResult;
        public final Message message;
        public final PublishResult publishResult;
        public final MessagingException.Code errorCode;
        public final String detail;

        private Response(long operationId, Type type, SendResult sendResult, Message message,
                         PublishResult publishResult, MessagingException.Code errorCode, String detail) {
            this.operationId = operationId;
            this.type = type;
            this.sendResult = sendResult;
            this.message = message;
            this.publishResult = publishResult;
            this.errorCode = errorCode;
            this.detail = detail;
        }

        private static Response send(long id, SendResult result) {
            return new Response(id, Type.SEND, result, null, null, null, null);
        }
        private static Response reply(long id, Message message) {
            return new Response(id, Type.REPLY, null, message, null, null, null);
        }
        private static Response published(long id, PublishResult result) {
            return new Response(id, Type.PUBLISH, null, null, result, null, null);
        }
        private static Response error(long id, MessagingException.Code code, String detail) {
            return new Response(id, Type.ERROR, null, null, null, code, detail);
        }
    }
}
