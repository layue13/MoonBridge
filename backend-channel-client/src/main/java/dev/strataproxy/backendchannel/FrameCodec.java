package dev.strataproxy.backendchannel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Length-prefixed frame I/O. The length and frame type are both included in the transmitted frame. */
public final class FrameCodec {
    private FrameCodec() { }

    public static byte[] read(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > Wire.MAX_FRAME_BYTES) throw new IOException("frame length out of bounds: " + length);
        byte[] frame = new byte[length];
        input.readFully(frame);
        return frame;
    }

    public static void write(DataOutputStream output, byte[] frame) throws IOException {
        if (frame == null || frame.length < 1 || frame.length > Wire.MAX_FRAME_BYTES) {
            throw new IOException("frame length out of bounds");
        }
        output.writeInt(frame.length);
        output.write(frame);
        output.flush();
    }
}
