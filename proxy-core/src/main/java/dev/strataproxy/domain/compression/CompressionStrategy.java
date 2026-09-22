package dev.strataproxy.domain.compression;

/**
 * Chooses how the proxy should compress a packet or frame.
 */
public interface CompressionStrategy {
    /**
     * Selects compression behavior for the supplied context.
     *
     * @param context runtime compression inputs
     * @return selected compression action
     */
    CompressionAction choose(CompressionContext context);
}
