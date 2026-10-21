package dev.moonbridge.messaging;

/** A failure with a stable messaging-layer category. */
public class MessagingException extends RuntimeException {
    public enum Code {
        NOT_CONNECTED,
        NO_HANDLER,
        BACKPRESSURED,
        TIMED_OUT,
        CLOSED,
        REJECTED,
        HANDLER_FAILED,
        PROTOCOL_ERROR
    }

    private final Code code;

    public MessagingException(Code code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    public MessagingException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    public Code code() { return code; }

    private static Code requireCode(Code code) {
        if (code == null) throw new NullPointerException("code");
        return code;
    }
}
