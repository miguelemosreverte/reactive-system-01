package com.reactive.platform.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import io.netty.util.AsciiString;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP/2 Server for maximum throughput.
 *
 * HTTP/2 advantages over HTTP/1.1:
 * - Multiplexing: multiple streams over single TCP connection
 * - Header compression (HPACK)
 * - No head-of-line blocking at HTTP level
 * - Binary framing (more efficient parsing)
 *
 * This enables 1M+ req/s by allowing many concurrent requests
 * without the round-trip overhead of HTTP/1.1.
 */
public final class Http2Server {

    // Pre-computed responses
    private static final byte[] RESPONSE_BODY_202 = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESPONSE_BODY_200 = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);

    private final int workers;
    private final AtomicLong requestCount = new AtomicLong(0);

    private Http2Server(int workers) {
        this.workers = workers;
    }

    public static Http2Server create() {
        return new Http2Server(Runtime.getRuntime().availableProcessors());
    }

    public Http2Server workers(int count) {
        return new Http2Server(count);
    }

    public Handle start(int port) {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(workers);

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 4096)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        configureClearTextHttp2(ch.pipeline());
                    }
                });

            ChannelFuture f = b.bind(port).sync();
            System.out.printf("[Http2Server] Started on port %d with %d workers (h2c - HTTP/2 cleartext)%n", port, workers);

            return new ServerHandle(f.channel(), bossGroup, workerGroup, requestCount);

        } catch (Exception e) {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            throw new RuntimeException("Failed to start HTTP/2 server", e);
        }
    }

    /**
     * Configure HTTP/2 over cleartext (h2c) with upgrade from HTTP/1.1.
     * This allows clients to upgrade from HTTP/1.1 to HTTP/2.
     */
    private void configureClearTextHttp2(ChannelPipeline pipeline) {
        HttpServerCodec http1Codec = new HttpServerCodec();
        HttpServerUpgradeHandler upgradeHandler = new HttpServerUpgradeHandler(
            http1Codec,
            protocol -> {
                if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                    return new Http2ServerUpgradeCodec(
                        Http2FrameCodecBuilder.forServer().build(),
                        new Http2Handler(requestCount)
                    );
                }
                return null;
            },
            65536
        );

        // For prior knowledge (client sends HTTP/2 preface directly)
        CleartextHttp2ServerUpgradeHandler cleartextHandler = new CleartextHttp2ServerUpgradeHandler(
            http1Codec,
            upgradeHandler,
            new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(
                        Http2FrameCodecBuilder.forServer().build(),
                        new Http2Handler(requestCount)
                    );
                }
            }
        );

        pipeline.addLast(cleartextHandler);
        pipeline.addLast(new Http1FallbackHandler(requestCount));
    }

    // ========================================================================
    // HTTP/2 Handler
    // ========================================================================

    private static class Http2Handler extends ChannelDuplexHandler {
        private final AtomicLong requestCount;

        Http2Handler(AtomicLong requestCount) {
            this.requestCount = requestCount;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                handleHeaders(ctx, headersFrame);
            } else if (msg instanceof Http2DataFrame dataFrame) {
                handleData(ctx, dataFrame);
            }
        }

        private void handleHeaders(ChannelHandlerContext ctx, Http2HeadersFrame frame) {
            Http2Headers headers = frame.headers();
            CharSequence method = headers.method();
            CharSequence path = headers.path();

            // For GET requests or POST without body
            if (frame.isEndStream()) {
                sendResponse(ctx, frame.stream(), isGet(method));
            }
            // POST with body - wait for DATA frame
        }

        private void handleData(ChannelHandlerContext ctx, Http2DataFrame frame) {
            // Body received - just count and respond
            requestCount.incrementAndGet();

            if (frame.isEndStream()) {
                sendResponse(ctx, frame.stream(), false);
            }

            // Release the data
            frame.release();
        }

        private void sendResponse(ChannelHandlerContext ctx, Http2FrameStream stream, boolean isGet) {
            requestCount.incrementAndGet();

            byte[] body = isGet ? RESPONSE_BODY_200 : RESPONSE_BODY_202;
            int status = isGet ? 200 : 202;

            Http2Headers responseHeaders = new DefaultHttp2Headers()
                .status(String.valueOf(status))
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);

            ctx.write(new DefaultHttp2HeadersFrame(responseHeaders).stream(stream));

            ByteBuf content = Unpooled.wrappedBuffer(body);
            ctx.writeAndFlush(new DefaultHttp2DataFrame(content, true).stream(stream));
        }

        private boolean isGet(CharSequence method) {
            return method != null && method.length() == 3 &&
                   method.charAt(0) == 'G' && method.charAt(1) == 'E' && method.charAt(2) == 'T';
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    // ========================================================================
    // HTTP/1.1 Fallback Handler (for clients that don't upgrade)
    // ========================================================================

    private static class Http1FallbackHandler extends SimpleChannelInboundHandler<HttpRequest> {
        private final AtomicLong requestCount;

        Http1FallbackHandler(AtomicLong requestCount) {
            this.requestCount = requestCount;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpRequest req) {
            requestCount.incrementAndGet();

            boolean isGet = req.method() == HttpMethod.GET;
            byte[] body = isGet ? RESPONSE_BODY_200 : RESPONSE_BODY_202;
            HttpResponseStatus status = isGet ? HttpResponseStatus.OK : HttpResponseStatus.ACCEPTED;

            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body)
            );

            response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .setInt(HttpHeaderNames.CONTENT_LENGTH, body.length)
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

            ctx.writeAndFlush(response);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    // ========================================================================
    // Handle
    // ========================================================================

    public interface Handle extends AutoCloseable {
        long requestCount();
        void awaitTermination() throws InterruptedException;
        @Override void close();
    }

    private static class ServerHandle implements Handle {
        private final Channel channel;
        private final EventLoopGroup bossGroup;
        private final EventLoopGroup workerGroup;
        private final AtomicLong requestCount;

        ServerHandle(Channel channel, EventLoopGroup bossGroup, EventLoopGroup workerGroup, AtomicLong requestCount) {
            this.channel = channel;
            this.bossGroup = bossGroup;
            this.workerGroup = workerGroup;
            this.requestCount = requestCount;
        }

        @Override
        public long requestCount() {
            return requestCount.get();
        }

        @Override
        public void awaitTermination() throws InterruptedException {
            channel.closeFuture().sync();
        }

        @Override
        public void close() {
            channel.close();
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            System.out.printf("[Http2Server] Stopped. Requests: %,d%n", requestCount.get());
        }
    }

    // ========================================================================
    // Main
    // ========================================================================

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  HTTP/2 Server - High Throughput with Multiplexing");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.printf("  Port:    %d%n", port);
        System.out.printf("  Workers: %d%n", workers);
        System.out.println("  Mode:    h2c (HTTP/2 cleartext, no TLS)");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("Test with:");
        System.out.printf("  curl --http2-prior-knowledge -X POST -d '{\"test\":1}' http://localhost:%d/events%n", port);
        System.out.println();

        try (Handle handle = Http2Server.create().workers(workers).start(port)) {
            System.out.println("Server running. Press Ctrl+C to stop.");
            handle.awaitTermination();
        }
    }
}
