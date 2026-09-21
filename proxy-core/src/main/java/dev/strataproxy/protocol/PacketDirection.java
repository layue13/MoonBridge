package dev.strataproxy.protocol;

/**
 * Direction of a packet relative to the client connection.
 */
public enum PacketDirection {
    /** Sent from backend server to client. */
    CLIENTBOUND,
    /** Sent from client to backend server. */
    SERVERBOUND
}
