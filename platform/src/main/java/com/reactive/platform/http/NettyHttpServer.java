package com.reactive.platform.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance HTTP server using Netty.
 *
 * Netty provides:
 * - Zero-copy buffer management
 * - Efficient event loop (epoll on Linux)
 * - Connection pooling
 * - Pipelining support
 *
 * This is the fastest option for HTTP serving on the JVM.
 */
public final class NettyHttpServer implements HttpServer {

    private final Config config;
    private final Map<RouteKey, Handler> routes = new ConcurrentHashMap<>();

    public NettyHttpServer() {
        this(Config.defaults());
    }

    public NettyHttpServer(Config config) {
        this.config = config;
    }

    @Override
    public HttpServer route(Method method, String path, Handler handler) {
        routes.put(new RouteKey(method, path), handler);
        return this;
    }

    @Override
    public Handle start(int port) {
        return new NettyHandle(port, config, routes);
    }

    private record RouteKey(Method method, String path) {}

    // ========================================================================
    // Netty Server Handle
    // ========================================================================

    private static final class NettyHandle implements Handle {
        private final EventLoopGroup bossGroup;
        private final EventLoopGroup workerGroup;
        private final Channel serverChannel;

        NettyHandle(int port, Config config, Map<RouteKey, Handler> routes) {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup(config.workerThreads());

            try {
                ServerBootstrap b = new ServerBootstrap();
                b.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .option(ChannelOption.SO_BACKLOG, config.backlog())
                        .option(ChannelOption.SO_REUSEADDR, true)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline()
                                        .addLast(new HttpServerCodec())
                                        .addLast(new HttpObjectAggregator(config.maxRequestSize()))
                                        .addLast(new RequestHandler(routes));
                            }
                        });

                serverChannel = b.bind(port).sync().channel();
                System.out.println("[NettyHttpServer] Started on port " + port);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Failed to start server", e);
            }
        }

        @Override
        public void awaitTermination() throws InterruptedException {
            serverChannel.closeFuture().sync();
        }

        @Override
        public void close() {
            serverChannel.close();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
            System.out.println("[NettyHttpServer] Stopped");
        }
    }

    // ========================================================================
    // Request Handler
    // ========================================================================

    private static final class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final Map<RouteKey, Handler> routes;

        RequestHandler(Map<RouteKey, Handler> routes) {
            this.routes = routes;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest nettyReq) {
            // Convert Netty request to our Request
            Request request = toRequest(nettyReq);

            // Find handler
            Handler handler = routes.get(new RouteKey(request.method(), request.path()));

            if (handler == null) {
                sendResponse(ctx, Response.notFound(), nettyReq);
                return;
            }

            // Handle request
            try {
                CompletableFuture<Response> future = handler.handle(request);
                future.whenComplete((response, error) -> {
                    if (error != null) {
                        sendResponse(ctx, Response.serverError(error.getMessage()), nettyReq);
                    } else {
                        sendResponse(ctx, response, nettyReq);
                    }
                });
            } catch (Exception e) {
                sendResponse(ctx, Response.serverError(e.getMessage()), nettyReq);
            }
        }

        private Request toRequest(FullHttpRequest nettyReq) {
            // Parse method
            Method method = Method.valueOf(nettyReq.method().name());

            // Parse path and query
            QueryStringDecoder decoder = new QueryStringDecoder(nettyReq.uri());
            String path = decoder.path();
            Map<String, String> queryParams = new HashMap<>();
            decoder.parameters().forEach((k, v) -> {
                if (!v.isEmpty()) queryParams.put(k, v.get(0));
            });

            // Parse headers
            Map<String, String> headers = new HashMap<>();
            nettyReq.headers().forEach(e ->
                    headers.put(e.getKey().toLowerCase(), e.getValue()));

            // Get body
            ByteBuf content = nettyReq.content();
            byte[] body = new byte[content.readableBytes()];
            content.readBytes(body);

            return new Request(method, path, headers, queryParams, body);
        }

        private void sendResponse(ChannelHandlerContext ctx, Response response, FullHttpRequest request) {
            ByteBuf content = Unpooled.wrappedBuffer(response.body());

            FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(response.status()),
                    content
            );

            // Set headers
            response.headers().forEach((k, v) ->
                    nettyResponse.headers().set(k, v));
            nettyResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());

            // Keep-alive handling
            boolean keepAlive = HttpUtil.isKeepAlive(request);
            if (keepAlive) {
                nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }

            ChannelFuture f = ctx.writeAndFlush(nettyResponse);
            if (!keepAlive) {
                f.addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    // ========================================================================
    // Factory method
    // ========================================================================

    /**
     * Create a Netty-based HTTP server.
     */
    public static HttpServer createNetty() {
        return new NettyHttpServer();
    }

    /**
     * Create with configuration.
     */
    public static HttpServer createNetty(Config config) {
        return new NettyHttpServer(config);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        HttpServer server = new NettyHttpServer(Config.defaults().withWorkerThreads(workers))
            .get("/health", Handler.sync(req -> Response.ok("{\"status\":\"UP\"}")))
            .post("/events", Handler.sync(req -> Response.accepted("{\"ok\":true}")));

        try (Handle handle = server.start(port)) {
            System.out.println("Server running. Press Ctrl+C to stop.");
            handle.awaitTermination();
        }
    }
}
