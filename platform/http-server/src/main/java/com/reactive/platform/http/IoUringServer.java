package com.reactive.platform.http;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.LongAdder;

/**
 * IoUringServer - High-performance HTTP server using Linux io_uring.
 *
 * io_uring reduces kernel overhead by:
 * - Batching syscalls (1 syscall submits many operations)
 * - Kernel-side polling (no context switches)
 * - Async completion queues
 *
 * Requires: Linux 5.6+, Java 22+, --enable-native-access
 */
public final class IoUringServer {

    // io_uring syscall numbers
    private static final int SYS_io_uring_setup = 425;
    private static final int SYS_io_uring_enter = 426;

    // io_uring opcodes (from include/uapi/linux/io_uring.h)
    private static final byte IORING_OP_ACCEPT = 13;
    private static final byte IORING_OP_SEND = 26;  // Not 9 (that's SENDMSG)
    private static final byte IORING_OP_RECV = 27;

    // io_uring_enter flags
    private static final int IORING_ENTER_GETEVENTS = 1;
    private static final int IORING_ENTER_SQ_WAKEUP = 2;

    // io_uring_setup flags
    private static final int IORING_SETUP_SQPOLL = 2;    // Kernel polls SQ
    private static final int IORING_SETUP_SQ_AFF = 4;    // SQ poll thread affinity

    // Socket constants
    private static final int AF_INET = 2;
    private static final int SOCK_STREAM = 1;
    private static final int SOCK_NONBLOCK = 2048;
    private static final int SOL_SOCKET = 1;
    private static final int SO_REUSEADDR = 2;
    private static final int SO_REUSEPORT = 15;
    private static final int IPPROTO_TCP = 6;
    private static final int TCP_NODELAY = 1;

    // mmap constants
    private static final int PROT_READ = 1;
    private static final int PROT_WRITE = 2;
    private static final int MAP_SHARED = 1;
    private static final int MAP_POPULATE = 0x8000;

    // Ring offsets for mmap
    private static final long IORING_OFF_SQ_RING = 0L;
    private static final long IORING_OFF_CQ_RING = 0x8000000L;
    private static final long IORING_OFF_SQES = 0x10000000L;

    // Minimal HTTP response - 38 bytes instead of 70
    private static final byte[] RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length:0\r\n\r\n".getBytes();

    private static final int QUEUE_DEPTH = 4096;  // Maximum queue for high throughput
    private static final int READ_SIZE = 512;     // Smaller reads for POST with small body

    // Operation types encoded in user_data
    private static final long OP_ACCEPT = 0L;
    private static final long OP_READ = 1L;
    private static final long OP_WRITE = 2L;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = SymbolLookup.loaderLookup()
        .or(Linker.nativeLinker().defaultLookup());

    // Cached MethodHandles for syscalls (creating these is expensive!)
    private static final MethodHandle SYSCALL_IO_URING_ENTER;
    static {
        try {
            SYSCALL_IO_URING_ENTER = LINKER.downcallHandle(
                LIBC.find("syscall").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize syscall handle", e);
        }
    }

    private final int port;
    private final int workers;
    private final boolean busyPoll;
    private final boolean sqpoll;
    private final LongAdder requests = new LongAdder();
    private volatile boolean running = true;

    public IoUringServer(int port, int workers, boolean busyPoll, boolean sqpoll) {
        this.port = port;
        this.workers = workers;
        this.busyPoll = busyPoll;
        this.sqpoll = sqpoll;
    }

    public void start() throws Exception {
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("linux")) {
            throw new UnsupportedOperationException("io_uring requires Linux");
        }

        Thread[] workerThreads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            final int workerId = i;
            workerThreads[i] = Thread.ofPlatform()
                .name("iouring-" + i)
                .start(() -> runWorker(workerId));
        }

        System.out.println("[IoUringServer] Ready");

        for (Thread t : workerThreads) {
            t.join();
        }
    }

    private void runWorker(int workerId) {
        Arena arena = Arena.ofConfined();
        try {
            // Create io_uring
            Ring ring = createRing(arena, sqpoll);
            if (ring == null) {
                System.err.println("[Worker-" + workerId + "] Failed to create io_uring");
                return;
            }

            // Create server socket
            int serverFd = createServerSocket(arena);

            // Allocate buffers
            MemorySegment clientAddr = arena.allocate(128);
            MemorySegment addrLen = arena.allocate(8);
            addrLen.set(ValueLayout.JAVA_INT, 0, 128);

            // Pre-allocate read buffers for each possible fd (simple approach)
            MemorySegment readBufs = arena.allocate(QUEUE_DEPTH * (long) READ_SIZE);
            MemorySegment writeBuf = arena.allocate(RESPONSE.length);
            writeBuf.copyFrom(MemorySegment.ofArray(RESPONSE));

            // Submit initial ACCEPT
            submitAccept(ring, serverFd, clientAddr, addrLen);
            submit(ring);

            // Event loop - process completions, batch submits
            while (running) {
                int n;
                if (busyPoll) {
                    // Busy-poll: submit and check CQ without blocking
                    submit(ring);
                    n = checkCompletions(ring);
                    if (n <= 0) {
                        Thread.onSpinWait();
                        continue;
                    }
                } else {
                    // Wait for at least 1 completion
                    n = waitForCompletions(ring, 1);
                    if (n <= 0) continue;
                }

                // Process all available completions
                for (int i = 0; i < n; i++) {
                    processCqe(ring, i, serverFd, clientAddr, addrLen, readBufs, writeBuf);
                }

                // Advance CQ head
                advanceCq(ring, n);

                if (!busyPoll) {
                    // Submit pending SQEs
                    submit(ring);
                }
            }

        } catch (Throwable t) {
            if (running) {
                System.err.println("[Worker-" + workerId + "] Error: " + t.getMessage());
                t.printStackTrace();
            }
        } finally {
            arena.close();
        }
    }

    private void processCqe(Ring ring, int index, int serverFd,
                           MemorySegment clientAddr, MemorySegment addrLen,
                           MemorySegment readBufs, MemorySegment writeBuf) throws Throwable {
        // Get CQE (io_uring_cqe: user_data(0), res(8), flags(12))
        int head = ring.cqRing.get(ValueLayout.JAVA_INT, ring.cqHeadOff);
        int cqeIdx = (head + index) & ring.cqMask;
        long cqeOff = ring.cqCqesOff + cqeIdx * 16L;

        long userData = ring.cqRing.get(ValueLayout.JAVA_LONG, cqeOff);
        int res = ring.cqRing.get(ValueLayout.JAVA_INT, cqeOff + 8);

        long opType = userData >> 32;
        int fd = (int) (userData & 0xFFFFFFFFL);

        if (opType == OP_ACCEPT) {
            if (res >= 0) {
                int clientFd = res;
                setTcpNoDelay(clientFd);
                int bufIdx = clientFd % QUEUE_DEPTH;
                submitRecv(ring, clientFd, readBufs.asSlice(bufIdx * (long) READ_SIZE, READ_SIZE));
            }
            // Always resubmit accept
            addrLen.set(ValueLayout.JAVA_INT, 0, 128);
            submitAccept(ring, serverFd, clientAddr, addrLen);

        } else if (opType == OP_READ) {
            if (res > 0) {
                submitSend(ring, fd, writeBuf);
                requests.increment();
            } else {
                close(fd);
            }

        } else if (opType == OP_WRITE) {
            if (res > 0) {
                int bufIdx = fd % QUEUE_DEPTH;
                submitRecv(ring, fd, readBufs.asSlice(bufIdx * (long) READ_SIZE, READ_SIZE));
            } else {
                close(fd);
            }
        }
    }

    // ========== io_uring Ring Management ==========

    // SQ flags (in sqRing at sqFlagsOff)
    private static final int IORING_SQ_NEED_WAKEUP = 1;

    private static class Ring {
        int fd;
        MemorySegment sqRing, cqRing, sqes;
        int sqMask, cqMask;
        long sqHeadOff, sqTailOff, sqArrayOff, sqFlagsOff;
        long cqHeadOff, cqTailOff, cqCqesOff;
        boolean sqpoll;
    }

    private Ring createRing(Arena arena, boolean useSqpoll) throws Throwable {
        // io_uring_params: 120 bytes
        // struct io_uring_params {
        //   u32 sq_entries, cq_entries, flags, sq_thread_cpu, sq_thread_idle, features, wq_fd, resv[3];
        //   struct io_sqring_offsets sq_off;
        //   struct io_cqring_offsets cq_off;
        // }
        MemorySegment params = arena.allocate(120);
        params.fill((byte) 0);

        if (useSqpoll) {
            // Set SQPOLL flag (offset 8 = flags)
            params.set(ValueLayout.JAVA_INT, 8, IORING_SETUP_SQPOLL);
            // Set sq_thread_idle to 10000ms (offset 16)
            params.set(ValueLayout.JAVA_INT, 16, 10000);
        }

        MethodHandle syscall = LINKER.downcallHandle(
            LIBC.find("syscall").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        int ringFd = (int) syscall.invoke((long) SYS_io_uring_setup, QUEUE_DEPTH, params);
        if (ringFd < 0) {
            System.err.println("io_uring_setup failed: " + ringFd + (useSqpoll ? " (SQPOLL requires root)" : ""));
            return null;
        }

        // Parse params
        int sqEntries = params.get(ValueLayout.JAVA_INT, 0);
        int cqEntries = params.get(ValueLayout.JAVA_INT, 4);

        // io_sqring_offsets contains OFFSETS within the SQ ring, not values
        // These offsets tell us where to find head, tail, mask, etc. in the mmaped region
        // struct io_sqring_offsets { head(0), tail(4), ring_mask(8), ring_entries(12), flags(16), dropped(20), array(24), resv1(28), resv2(32) }
        long sqHeadOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 40));      // offset of head in sq ring
        long sqTailOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 44));      // offset of tail in sq ring
        long sqRingMaskOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 48));  // offset of ring_mask in sq ring
        long sqFlagsOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 56));     // offset of flags in sq ring
        long sqArrayOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 64));     // offset of array in sq ring

        // io_cqring_offsets contains OFFSETS within the CQ ring
        // struct io_cqring_offsets { head(0), tail(4), ring_mask(8), ring_entries(12), overflow(16), cqes(20), flags(24), resv1(28), resv2(32) }
        long cqHeadOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 80));      // offset of head in cq ring
        long cqTailOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 84));      // offset of tail in cq ring
        long cqRingMaskOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 88));  // offset of ring_mask in cq ring
        long cqCqesOff = Integer.toUnsignedLong(params.get(ValueLayout.JAVA_INT, 100));     // offset of cqes array in cq ring

        // Calculate sizes - need to accommodate all offsets
        long sqRingSize = sqArrayOff + sqEntries * 4L;
        long cqRingSize = cqCqesOff + cqEntries * 16L;
        long sqesSize = sqEntries * 64L;

        // mmap
        MethodHandle mmap = LINKER.downcallHandle(
            LIBC.find("mmap").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
        );

        MemorySegment sqRingPtr = (MemorySegment) mmap.invoke(
            MemorySegment.NULL, sqRingSize, PROT_READ | PROT_WRITE,
            MAP_SHARED | MAP_POPULATE, ringFd, IORING_OFF_SQ_RING);
        if (sqRingPtr.address() == -1L) return null;

        MemorySegment cqRingPtr = (MemorySegment) mmap.invoke(
            MemorySegment.NULL, cqRingSize, PROT_READ | PROT_WRITE,
            MAP_SHARED | MAP_POPULATE, ringFd, IORING_OFF_CQ_RING);
        if (cqRingPtr.address() == -1L) return null;

        MemorySegment sqesPtr = (MemorySegment) mmap.invoke(
            MemorySegment.NULL, sqesSize, PROT_READ | PROT_WRITE,
            MAP_SHARED | MAP_POPULATE, ringFd, IORING_OFF_SQES);
        if (sqesPtr.address() == -1L) return null;

        Ring ring = new Ring();
        ring.fd = ringFd;
        ring.sqRing = sqRingPtr.reinterpret(sqRingSize);
        ring.cqRing = cqRingPtr.reinterpret(cqRingSize);
        ring.sqes = sqesPtr.reinterpret(sqesSize);
        ring.sqHeadOff = sqHeadOff;
        ring.sqTailOff = sqTailOff;
        ring.sqFlagsOff = sqFlagsOff;
        ring.sqArrayOff = sqArrayOff;
        ring.cqHeadOff = cqHeadOff;
        ring.cqTailOff = cqTailOff;
        ring.cqCqesOff = cqCqesOff;
        ring.sqpoll = useSqpoll;

        // Read the actual mask VALUES from the mapped ring memory (not the offsets!)
        ring.sqMask = ring.sqRing.get(ValueLayout.JAVA_INT, sqRingMaskOff);
        ring.cqMask = ring.cqRing.get(ValueLayout.JAVA_INT, cqRingMaskOff);

        return ring;
    }

    private void submitAccept(Ring ring, int serverFd, MemorySegment addr, MemorySegment addrLen) {
        int tail = ring.sqRing.get(ValueLayout.JAVA_INT, ring.sqTailOff);
        int idx = tail & ring.sqMask;
        long sqeOff = idx * 64L;

        // Clear SQE
        ring.sqes.asSlice(sqeOff, 64).fill((byte) 0);

        // io_uring_sqe for ACCEPT (verified from kernel source):
        // offset 0: opcode (u8), flags (u8), ioprio (u16), fd (s32)
        // offset 8: off/addr2 (u64) - for ACCEPT: pointer to socklen_t
        // offset 16: addr (u64) - pointer to sockaddr
        // offset 24: len (u32), accept_flags (u32)
        // offset 32: user_data (u64)

        ring.sqes.set(ValueLayout.JAVA_BYTE, sqeOff + 0, IORING_OP_ACCEPT);
        ring.sqes.set(ValueLayout.JAVA_INT, sqeOff + 4, serverFd);
        ring.sqes.set(ValueLayout.JAVA_LONG, sqeOff + 8, addrLen.address());  // off/addr2 = addrlen ptr
        ring.sqes.set(ValueLayout.JAVA_LONG, sqeOff + 16, addr.address());    // addr = sockaddr ptr
        ring.sqes.set(ValueLayout.JAVA_LONG, sqeOff + 32, (OP_ACCEPT << 32) | (serverFd & 0xFFFFFFFFL));

        // Update SQ array
        ring.sqRing.set(ValueLayout.JAVA_INT, ring.sqArrayOff + idx * 4L, idx);
        // Update tail
        ring.sqRing.set(ValueLayout.JAVA_INT, ring.sqTailOff, tail + 1);
    }

    private void submitRecv(Ring ring, int fd, MemorySegment buf) {
        int tail = ring.sqRing.get(ValueLayout.JAVA_INT, ring.sqTailOff);
        int idx = tail & ring.sqMask;
        long sqeOff = idx * 64L;

        ring.sqes.asSlice(sqeOff, 64).fill((byte) 0);

        ring.sqes.set(ValueLayout.JAVA_BYTE, sqeOff + 0, IORING_OP_RECV);
        ring.sqes.set(ValueLayout.JAVA_INT, sqeOff + 4, fd);
        ring.sqes.set(ValueLayout.JAVA_LONG, sqeOff + 16, buf.address());
        ring.sqes.set(ValueLayout.JAVA_INT, sqeOff + 24, READ_SIZE);
        ring.sqes.set(ValueLayout.JAVA_LONG, sqeOff + 32, (OP_READ << 32) | (fd & 0xFFFFFFFFL));

        ring.sqRing.set(ValueLayout.JAVA_INT, ring.sqArrayOff + idx * 4L, idx);
        ring.sqRing.set(ValueLayout.JAVA_INT, ring.sqTailOff, tail + 1);
    }

    private void submitSend(Ring ring, int fd, MemorySegment buf) {
        int tail = ring.sqRing.get(ValueLayout.JAVA_INT, ring.sqTailOff);
        int idx = tail & ring.sqMask;
        long sqeOff = idx * 64L;

        ring.sqes.asSlice(sqeOff, 64).fill((byte) 0);

        ring.sqes.set(ValueLayout.JAVA_BYTE, sqeOff + 0, IORING_OP_SEND);
        ring.sqes.set(ValueLayout.JAVA_INT, sqeOff + 4, fd);
        ring.sqes.set(ValueLayout.JAVA_LONG, sqeOff + 16, buf.address());
        ring.sqes.set(ValueLayout.JAVA_INT, sqeOff + 24, RESPONSE.length);
        ring.sqes.set(ValueLayout.JAVA_LONG, sqeOff + 32, (OP_WRITE << 32) | (fd & 0xFFFFFFFFL));

        ring.sqRing.set(ValueLayout.JAVA_INT, ring.sqArrayOff + idx * 4L, idx);
        ring.sqRing.set(ValueLayout.JAVA_INT, ring.sqTailOff, tail + 1);
    }

    private void submit(Ring ring) throws Throwable {
        int head = ring.sqRing.get(ValueLayout.JAVA_INT, ring.sqHeadOff);
        int tail = ring.sqRing.get(ValueLayout.JAVA_INT, ring.sqTailOff);
        int toSubmit = tail - head;
        if (toSubmit <= 0) return;

        if (ring.sqpoll) {
            // In SQPOLL mode, kernel polls SQ automatically
            // Only need to call io_uring_enter if kernel thread needs waking
            int sqFlags = ring.sqRing.get(ValueLayout.JAVA_INT, ring.sqFlagsOff);
            if ((sqFlags & IORING_SQ_NEED_WAKEUP) != 0) {
                int ignored = (int) SYSCALL_IO_URING_ENTER.invokeExact(
                    (long) SYS_io_uring_enter, ring.fd, 0, 0, IORING_ENTER_SQ_WAKEUP, MemorySegment.NULL, 0L);
            }
        } else {
            int ignored = (int) SYSCALL_IO_URING_ENTER.invokeExact(
                (long) SYS_io_uring_enter, ring.fd, toSubmit, 0, 0, MemorySegment.NULL, 0L);
        }
    }

    private int checkCompletions(Ring ring) {
        int head = ring.cqRing.get(ValueLayout.JAVA_INT, ring.cqHeadOff);
        int tail = ring.cqRing.get(ValueLayout.JAVA_INT, ring.cqTailOff);
        return tail - head;
    }

    private int waitForCompletions(Ring ring, int min) throws Throwable {
        int ready = checkCompletions(ring);
        if (ready >= min) return ready;

        // Wait for completions
        int ignored = (int) SYSCALL_IO_URING_ENTER.invokeExact((long) SYS_io_uring_enter, ring.fd, 0, min, IORING_ENTER_GETEVENTS, MemorySegment.NULL, 0L);

        return checkCompletions(ring);
    }

    private void advanceCq(Ring ring, int n) {
        int head = ring.cqRing.get(ValueLayout.JAVA_INT, ring.cqHeadOff);
        ring.cqRing.set(ValueLayout.JAVA_INT, ring.cqHeadOff, head + n);
    }

    // ========== Socket Helpers ==========

    private int createServerSocket(Arena arena) throws Throwable {
        MethodHandle socket = LINKER.downcallHandle(
            LIBC.find("socket").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );

        int fd = (int) socket.invoke(AF_INET, SOCK_STREAM | SOCK_NONBLOCK, 0);
        if (fd < 0) throw new RuntimeException("socket() failed");

        MethodHandle setsockopt = LINKER.downcallHandle(
            LIBC.find("setsockopt").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );

        MemorySegment one = arena.allocate(4);
        one.set(ValueLayout.JAVA_INT, 0, 1);
        setsockopt.invoke(fd, SOL_SOCKET, SO_REUSEADDR, one, 4);
        setsockopt.invoke(fd, SOL_SOCKET, SO_REUSEPORT, one, 4);

        MemorySegment addr = arena.allocate(16);
        addr.set(ValueLayout.JAVA_SHORT, 0, (short) AF_INET);
        addr.set(ValueLayout.JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN), 2, (short) port);
        addr.set(ValueLayout.JAVA_INT, 4, 0);

        MethodHandle bind = LINKER.downcallHandle(
            LIBC.find("bind").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );
        if ((int) bind.invoke(fd, addr, 16) < 0) throw new RuntimeException("bind() failed");

        MethodHandle listen = LINKER.downcallHandle(
            LIBC.find("listen").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );
        if ((int) listen.invoke(fd, 4096) < 0) throw new RuntimeException("listen() failed");

        return fd;
    }

    private void setTcpNoDelay(int fd) throws Throwable {
        Arena arena = Arena.ofAuto();
        MethodHandle setsockopt = LINKER.downcallHandle(
            LIBC.find("setsockopt").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );
        MemorySegment one = arena.allocate(4);
        one.set(ValueLayout.JAVA_INT, 0, 1);
        setsockopt.invoke(fd, IPPROTO_TCP, TCP_NODELAY, one, 4);
    }

    private void close(int fd) throws Throwable {
        MethodHandle close = LINKER.downcallHandle(
            LIBC.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );
        close.invoke(fd);
    }

    public long requestCount() {
        return requests.sum();
    }

    public void stop() {
        running = false;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();
        boolean busyPoll = args.length > 2 && "true".equalsIgnoreCase(args[2]);
        boolean sqpoll = args.length > 3 && "true".equalsIgnoreCase(args[3]);

        System.out.printf("[IoUringServer] port=%d, workers=%d, busyPoll=%s, sqpoll=%s%n",
            port, workers, busyPoll, sqpoll);

        IoUringServer server = new IoUringServer(port, workers, busyPoll, sqpoll);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            System.out.printf("%n[IoUringServer] Total: %,d requests%n", server.requestCount());
        }));

        server.start();
    }
}
