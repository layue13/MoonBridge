package dev.moonbridge.core.session;

import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Same-JVM screening of the current online cipher copy path and direct ByteBuffer processing. */
public final class CipherBenchmark {
    private static final byte[] SECRET = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    private static final PooledByteBufAllocator ALLOCATOR = PooledByteBufAllocator.DEFAULT;
    private enum Path { ARRAYS, NIO }

    public static void main(String[] args) throws Exception {
        int bytes = args.length > 0 ? Integer.parseInt(args[0]) : 4096;
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 20_000;
        int warmup = args.length > 2 ? Integer.parseInt(args[2]) : 3_000;
        int rounds = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        if (bytes < 1 || bytes > 1_048_576 || iterations < 1 || warmup < 0 || rounds < 1 || rounds > 10) {
            throw new IllegalArgumentException("bytes 1..1048576, iterations positive, warmup nonnegative, rounds 1..10");
        }
        ByteBuf input = ALLOCATOR.directBuffer(bytes, bytes);
        try {
            for (int i = 0; i < bytes; i++) input.writeByte(i * 31 + 17);
            long expected = run(Path.ARRAYS, input, 1, false).checksum();
            long actual = run(Path.NIO, input, 1, false).checksum();
            if (expected != actual) throw new IllegalStateException("cipher paths disagree");
            run(Path.ARRAYS, input, warmup, false);
            run(Path.NIO, input, warmup, false);
            System.out.printf("AES/CFB8, pooled direct input/output, bytes=%d, iterations=%d, warmup=%d, rounds=%d%n",
                    bytes, iterations, warmup, rounds);
            for (int round = 1; round <= rounds; round++) {
                Path first = (round & 1) == 1 ? Path.ARRAYS : Path.NIO;
                Path second = first == Path.ARRAYS ? Path.NIO : Path.ARRAYS;
                Result firstResult = run(first, input, iterations, true);
                Result secondResult = run(second, input, iterations, true);
                if (firstResult.checksum() != secondResult.checksum()) {
                    throw new IllegalStateException("cipher paths disagree in round " + round);
                }
                print(round, first, firstResult, bytes, iterations);
                print(round, second, secondResult, bytes, iterations);
            }
        } finally {
            input.release();
        }
    }

    private static Result run(Path path, ByteBuf input, int iterations, boolean measure) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(SECRET, "AES"), new IvParameterSpec(SECRET));
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = measure && threads.isThreadAllocatedMemorySupported()
                ? threads.getThreadAllocatedBytes(threadId) : 0;
        long start = System.nanoTime();
        long checksum = 0;
        for (int i = 0; i < iterations; i++) {
            ByteBuf output = ALLOCATOR.directBuffer(input.readableBytes(), input.readableBytes());
            try {
                if (path == Path.ARRAYS) {
                    byte[] source = new byte[input.readableBytes()];
                    input.getBytes(input.readerIndex(), source);
                    byte[] encrypted = cipher.update(source);
                    output.writeBytes(encrypted);
                    Arrays.fill(source, (byte) 0);
                    Arrays.fill(encrypted, (byte) 0);
                } else {
                    ByteBuffer source = input.internalNioBuffer(input.readerIndex(), input.readableBytes());
                    ByteBuffer target = output.internalNioBuffer(0, input.readableBytes());
                    int written = cipher.update(source, target);
                    if (written != input.readableBytes()) throw new IllegalStateException("cipher output length changed");
                    output.writerIndex(written);
                }
                checksum = checksum * 31 + output.getUnsignedByte(0)
                        + output.getUnsignedByte(output.writerIndex() - 1);
            } finally {
                output.release();
            }
        }
        long elapsed = System.nanoTime() - start;
        long allocated = measure && threads.isThreadAllocatedMemorySupported()
                ? threads.getThreadAllocatedBytes(threadId) - allocatedBefore : -1;
        return new Result(elapsed, allocated, checksum);
    }

    private static void print(int round, Path path, Result result, int bytes, int iterations) {
        double seconds = result.nanos() / 1e9;
        double mibPerSecond = (double) bytes * iterations / seconds / (1024 * 1024);
        System.out.printf("round=%d path=%s MiB/s=%.1f ns/op=%.0f allocatedBytes/op=%.0f checksum=%d%n",
                round, path, mibPerSecond, (double) result.nanos() / iterations,
                (double) result.allocatedBytes() / iterations, result.checksum());
    }

    private record Result(long nanos, long allocatedBytes, long checksum) { }
}
