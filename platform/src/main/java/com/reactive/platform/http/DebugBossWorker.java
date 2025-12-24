package com.reactive.platform.http;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class DebugBossWorker {
    static final byte[] RESPONSE = "HTTP/1.1 202\r\n\r\n".getBytes();
    static final Queue<SocketChannel> pending = new ConcurrentLinkedQueue<>();
    static final AtomicBoolean running = new AtomicBoolean(true);
    static volatile Selector workerSelector;
    
    public static void main(String[] args) throws Exception {
        // Worker thread
        Thread worker = new Thread(() -> {
            try {
                workerSelector = Selector.open();
                ByteBuffer readBuf = ByteBuffer.allocateDirect(4096);
                ByteBuffer writeBuf = ByteBuffer.allocateDirect(RESPONSE.length);
                writeBuf.put(RESPONSE);
                
                System.out.println("Worker started");
                
                while (running.get()) {
                    // Register pending
                    SocketChannel ch;
                    while ((ch = pending.poll()) != null) {
                        System.out.println("Worker: registering channel");
                        ch.register(workerSelector, SelectionKey.OP_READ);
                    }
                    
                    int ready = workerSelector.select(100);
                    if (ready > 0) {
                        System.out.println("Worker: " + ready + " ready");
                        Iterator<SelectionKey> keys = workerSelector.selectedKeys().iterator();
                        while (keys.hasNext()) {
                            SelectionKey key = keys.next();
                            keys.remove();
                            
                            if (key.isReadable()) {
                                SocketChannel channel = (SocketChannel) key.channel();
                                readBuf.clear();
                                int n = channel.read(readBuf);
                                System.out.println("Worker: read " + n + " bytes");
                                
                                if (n > 0) {
                                    writeBuf.rewind();
                                    channel.write(writeBuf);
                                    System.out.println("Worker: wrote response");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        worker.start();
        
        // Wait for worker to start
        while (workerSelector == null) Thread.sleep(10);
        
        // Boss thread
        ServerSocketChannel server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        server.bind(new InetSocketAddress(9999), 1024);
        Selector bossSelector = Selector.open();
        server.register(bossSelector, SelectionKey.OP_ACCEPT);
        
        System.out.println("Boss started on port 9999");
        
        while (running.get()) {
            if (bossSelector.select(100) == 0) continue;
            
            Iterator<SelectionKey> keys = bossSelector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();
                
                if (key.isAcceptable()) {
                    SocketChannel client = server.accept();
                    if (client != null) {
                        System.out.println("Boss: accepted connection");
                        client.configureBlocking(false);
                        client.setOption(StandardSocketOptions.TCP_NODELAY, true);
                        pending.add(client);
                        workerSelector.wakeup();
                    }
                }
            }
        }
    }
}
