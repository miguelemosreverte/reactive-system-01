package com.reactive.platform.http;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.LongAdder;

/**
 * UltraFastServer - Maximum throughput io_uring HTTP server.
 *
 * Optimizations:
 * - Minimal response (less bytes to send)
 * - Skip full HTTP parsing (just detect request end)
 * - Pre-allocated fixed buffers per connection slot
 * - Cached MethodHandles
 * - Inline everything in hot path
 */
public final class UltraFastServer {

    private static final int SYS_io_uring_setup = 425;
    private static final int SYS_io_uring_enter = 426;

    private static final byte IORING_OP_ACCEPT = 13;
    private static final byte IORING_OP_SEND = 26;
    private static final byte IORING_OP_RECV = 27;

    private static final int IORING_ENTER_GETEVENTS = 1;
    private static final int IORING_SQ_NEED_WAKEUP = 1;

    private static final int AF_INET = 2;
    private static final int SOCK_STREAM = 1;
    private static final int SOCK_NONBLOCK = 2048;
    private static final int SOL_SOCKET = 1;
    private static final int SO_REUSEADDR = 2;
    private static final int SO_REUSEPORT = 15;
    private static final int IPPROTO_TCP = 6;
    private static final int TCP_NODELAY = 1;

    private static final int PROT_READ = 1;
    private static final int PROT_WRITE = 2;
    private static final int MAP_SHARED = 1;
    private static final int MAP_POPULATE = 0x8000;

    private static final long IORING_OFF_SQ_RING = 0L;
    private static final long IORING_OFF_SQES = 0x10000000L;

    // Minimal HTTP response - exactly 38 bytes
    private static final byte[] RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length:0\r\n\r\n".getBytes();

    private static final int QUEUE_DEPTH = 2048;
    private static final int MAX_CONNECTIONS = 4096;
    private static final int READ_SIZE = 512;

    private static final long OP_ACCEPT = 0L;
    private static final long OP_READ = 1L;
    private static final long OP_WRITE = 2L;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = SymbolLookup.loaderLookup()
        .or(Linker.nativeLinker().defaultLookup());

    private static final MethodHandle SYSCALL_SETUP;
    private static final MethodHandle SYSCALL_ENTER;
    private static final MethodHandle SOCKET;
    private static final MethodHandle SETSOCKOPT;
    private static final MethodHandle BIND;
    private static final MethodHandle LISTEN;
    private static final MethodHandle CLOSE;

    static {
        try {
            SYSCALL_SETUP = LINKER.downcallHandle(
                LIBC.find("syscall").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            );
            SYSCALL_ENTER = LINKER.downcallHandle(
                LIBC.find("syscall").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            );
            SOCKET = LINKER.downcallHandle(
                LIBC.find("socket").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            SETSOCKOPT = LINKER.downcallHandle(
                LIBC.find("setsockopt").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            BIND = LINKER.downcallHandle(
                LIBC.find("bind").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            LISTEN = LINKER.downcallHandle(
                LIBC.find("listen").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            CLOSE = LINKER.downcallHandle(
                LIBC.find("close").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final int port;
    private final int workers;
    private final LongAdder requests = new LongAdder();
    private volatile boolean running = true;

    public UltraFastServer(int port, int workers) {
        this.port = port;
        this.workers = workers;
    }

    public void start() throws Exception {
        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            final int id = i;
            threads[i] = Thread.ofPlatform().name("worker-" + i).start(() -> runWorker(id));
        }
        System.out.printf("[UltraFast] port=%d workers=%d ready%n", port, workers);
        for (Thread t : threads) t.join();
    }

    private void runWorker(int id) {
        try (Arena arena = Arena.ofConfined()) {
            // Create ring
            MemorySegment params = arena.allocate(120);
            params.fill((byte) 0);

            int ringFd = (int) SYSCALL_SETUP.invokeExact((long) SYS_io_uring_setup, QUEUE_DEPTH, params);
            if (ringFd < 0) { System.err.println("ring failed"); return; }

            int sqEntries = params.get(ValueLayout.JAVA_INT, 0);
            int cqEntries = params.get(ValueLayout.JAVA_INT, 4);

            long sqHeadOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 40));
            long sqTailOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 44));
            int sqMask = params.get(ValueLayout.JAVA_INT, 48);
            long sqArrayOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 64));

            long cqHeadOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 80));
            long cqTailOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 84));
            int cqMask = params.get(ValueLayout.JAVA_INT, 88);
            long cqCqesOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 100));

            long sqRingSize = sqArrayOff + sqEntries * 4L;
            long sqesSize = sqEntries * 64L;
            long cqRingSize = cqCqesOff + cqEntries * 16L;

            MethodHandle mmap = LINKER.downcallHandle(
                LIBC.find("mmap").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
            );

            MemorySegment sqRing = ((MemorySegment) mmap.invokeExact(
                MemorySegment.NULL, sqRingSize, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_POPULATE, ringFd, IORING_OFF_SQ_RING
            )).reinterpret(sqRingSize);

            MemorySegment sqes = ((MemorySegment) mmap.invokeExact(
                MemorySegment.NULL, sqesSize, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_POPULATE, ringFd, IORING_OFF_SQES
            )).reinterpret(sqesSize);

            MemorySegment cqRing = sqRing; // CQ ring shares mapping with SQ ring

            // Read actual masks from mapped memory
            long sqMaskOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 48));
            long cqMaskOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 88));
            sqMask = sqRing.get(ValueLayout.JAVA_INT, sqMaskOff);
            cqMask = sqRing.get(ValueLayout.JAVA_INT, cqMaskOff);

            // Server socket
            int serverFd = (int) SOCKET.invokeExact(AF_INET, SOCK_STREAM | SOCK_NONBLOCK, 0);
            MemorySegment one = arena.allocate(4);
            one.set(ValueLayout.JAVA_INT, 0, 1);
            SETSOCKOPT.invokeExact(serverFd, SOL_SOCKET, SO_REUSEADDR, one, 4);
            SETSOCKOPT.invokeExact(serverFd, SOL_SOCKET, SO_REUSEPORT, one, 4);
            SETSOCKOPT.invokeExact(serverFd, IPPROTO_TCP, TCP_NODELAY, one, 4);

            MemorySegment addr = arena.allocate(16);
            addr.set(ValueLayout.JAVA_SHORT, 0, (short) AF_INET);
            addr.set(ValueLayout.JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN), 2, (short) port);
            BIND.invokeExact(serverFd, addr, 16);
            LISTEN.invokeExact(serverFd, 8192);

            // Buffers
            MemorySegment clientAddr = arena.allocate(128);
            MemorySegment addrLen = arena.allocate(8);
            MemorySegment readBufs = arena.allocate((long) MAX_CONNECTIONS * READ_SIZE);
            MemorySegment writeResp = arena.allocate(RESPONSE.length);
            writeResp.copyFrom(MemorySegment.ofArray(RESPONSE));

            // Submit initial accept
            addrLen.set(ValueLayout.JAVA_INT, 0, 128);
            int tail = sqRing.get(ValueLayout.JAVA_INT, sqTailOff);
            int idx = tail & sqMask;
            long sqeOff = idx * 64L;
            sqes.asSlice(sqeOff, 64).fill((byte) 0);
            sqes.set(ValueLayout.JAVA_BYTE, sqeOff, IORING_OP_ACCEPT);
            sqes.set(ValueLayout.JAVA_INT, sqeOff + 4, serverFd);
            sqes.set(ValueLayout.JAVA_LONG, sqeOff + 8, addrLen.address());
            sqes.set(ValueLayout.JAVA_LONG, sqeOff + 16, clientAddr.address());
            sqes.set(ValueLayout.JAVA_LONG, sqeOff + 32, OP_ACCEPT << 32);
            sqRing.set(ValueLayout.JAVA_INT, sqArrayOff + idx * 4L, idx);
            sqRing.set(ValueLayout.JAVA_INT, sqTailOff, tail + 1);

            SYSCALL_ENTER.invokeExact((long) SYS_io_uring_enter, ringFd, 1, 0, 0, MemorySegment.NULL, 0L);

            // Event loop
            while (running) {
                // Wait for completions
                int cqHead = sqRing.get(ValueLayout.JAVA_INT, cqHeadOff);
                int cqTail = sqRing.get(ValueLayout.JAVA_INT, cqTailOff);
                int ready = cqTail - cqHead;

                if (ready == 0) {
                    SYSCALL_ENTER.invokeExact((long) SYS_io_uring_enter, ringFd, 0, 1, IORING_ENTER_GETEVENTS, MemorySegment.NULL, 0L);
                    cqHead = sqRing.get(ValueLayout.JAVA_INT, cqHeadOff);
                    cqTail = sqRing.get(ValueLayout.JAVA_INT, cqTailOff);
                    ready = cqTail - cqHead;
                }

                // Process all completions
                for (int i = 0; i < ready; i++) {
                    int cqeIdx = (cqHead + i) & cqMask;
                    long cqeOff = cqCqesOff + cqeIdx * 16L;
                    long userData = sqRing.get(ValueLayout.JAVA_LONG, cqeOff);
                    int res = sqRing.get(ValueLayout.JAVA_INT, cqeOff + 8);

                    long op = userData >> 32;
                    int fd = (int) userData;

                    tail = sqRing.get(ValueLayout.JAVA_INT, sqTailOff);

                    if (op == OP_ACCEPT) {
                        if (res >= 0) {
                            // New connection - submit recv
                            int clientFd = res;
                            SETSOCKOPT.invokeExact(clientFd, IPPROTO_TCP, TCP_NODELAY, one, 4);

                            idx = tail & sqMask;
                            sqeOff = idx * 64L;
                            sqes.asSlice(sqeOff, 64).fill((byte) 0);
                            sqes.set(ValueLayout.JAVA_BYTE, sqeOff, IORING_OP_RECV);
                            sqes.set(ValueLayout.JAVA_INT, sqeOff + 4, clientFd);
                            sqes.set(ValueLayout.JAVA_LONG, sqeOff + 16, readBufs.address() + (clientFd % MAX_CONNECTIONS) * READ_SIZE);
                            sqes.set(ValueLayout.JAVA_INT, sqeOff + 24, READ_SIZE);
                            sqes.set(ValueLayout.JAVA_LONG, sqeOff + 32, (OP_READ << 32) | clientFd);
                            sqRing.set(ValueLayout.JAVA_INT, sqArrayOff + idx * 4L, idx);
                            tail++;
                        }
                        // Resubmit accept
                        addrLen.set(ValueLayout.JAVA_INT, 0, 128);
                        idx = tail & sqMask;
                        sqeOff = idx * 64L;
                        sqes.asSlice(sqeOff, 64).fill((byte) 0);
                        sqes.set(ValueLayout.JAVA_BYTE, sqeOff, IORING_OP_ACCEPT);
                        sqes.set(ValueLayout.JAVA_INT, sqeOff + 4, serverFd);
                        sqes.set(ValueLayout.JAVA_LONG, sqeOff + 8, addrLen.address());
                        sqes.set(ValueLayout.JAVA_LONG, sqeOff + 16, clientAddr.address());
                        sqes.set(ValueLayout.JAVA_LONG, sqeOff + 32, OP_ACCEPT << 32);
                        sqRing.set(ValueLayout.JAVA_INT, sqArrayOff + idx * 4L, idx);
                        tail++;
                        sqRing.set(ValueLayout.JAVA_INT, sqTailOff, tail);

                    } else if (op == OP_READ) {
                        if (res > 0) {
                            // Got data - send response immediately
                            requests.increment();
                            idx = tail & sqMask;
                            sqeOff = idx * 64L;
                            sqes.asSlice(sqeOff, 64).fill((byte) 0);
                            sqes.set(ValueLayout.JAVA_BYTE, sqeOff, IORING_OP_SEND);
                            sqes.set(ValueLayout.JAVA_INT, sqeOff + 4, fd);
                            sqes.set(ValueLayout.JAVA_LONG, sqeOff + 16, writeResp.address());
                            sqes.set(ValueLayout.JAVA_INT, sqeOff + 24, RESPONSE.length);
                            sqes.set(ValueLayout.JAVA_LONG, sqeOff + 32, (OP_WRITE << 32) | fd);
                            sqRing.set(ValueLayout.JAVA_INT, sqArrayOff + idx * 4L, idx);
                            tail++;
                            sqRing.set(ValueLayout.JAVA_INT, sqTailOff, tail);
                        } else {
                            CLOSE.invokeExact(fd);
                        }

                    } else if (op == OP_WRITE) {
                        if (res > 0) {
                            // Sent response - read next request
                            idx = tail & sqMask;
                            sqeOff = idx * 64L;
                            sqes.asSlice(sqeOff, 64).fill((byte) 0);
                            sqes.set(ValueLayout.JAVA_BYTE, sqeOff, IORING_OP_RECV);
                            sqes.set(ValueLayout.JAVA_INT, sqeOff + 4, fd);
                            sqes.set(ValueLayout.JAVA_LONG, sqeOff + 16, readBufs.address() + (fd % MAX_CONNECTIONS) * READ_SIZE);
                            sqes.set(ValueLayout.JAVA_INT, sqeOff + 24, READ_SIZE);
                            sqes.set(ValueLayout.JAVA_LONG, sqeOff + 32, (OP_READ << 32) | fd);
                            sqRing.set(ValueLayout.JAVA_INT, sqArrayOff + idx * 4L, idx);
                            tail++;
                            sqRing.set(ValueLayout.JAVA_INT, sqTailOff, tail);
                        } else {
                            CLOSE.invokeExact(fd);
                        }
                    }
                }

                // Advance CQ head
                sqRing.set(ValueLayout.JAVA_INT, cqHeadOff, cqHead + ready);

                // Submit pending SQEs
                int sqHead = sqRing.get(ValueLayout.JAVA_INT, sqHeadOff);
                tail = sqRing.get(ValueLayout.JAVA_INT, sqTailOff);
                int toSubmit = tail - sqHead;
                if (toSubmit > 0) {
                    SYSCALL_ENTER.invokeExact((long) SYS_io_uring_enter, ringFd, toSubmit, 0, 0, MemorySegment.NULL, 0L);
                }
            }
        } catch (Throwable t) {
            if (running) t.printStackTrace();
        }
    }

    public long requestCount() { return requests.sum(); }
    public void stop() { running = false; }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        UltraFastServer server = new UltraFastServer(port, workers);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            System.out.printf("%n[UltraFast] Total: %,d requests%n", server.requestCount());
        }));
        server.start();
    }
}
