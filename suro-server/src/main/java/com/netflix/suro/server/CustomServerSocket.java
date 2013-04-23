/*
 * Copyright 2013 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.suro.server;

import org.apache.thrift.transport.TNonblockingServerTransport;
import org.apache.thrift.transport.TNonblockingSocket;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.channels.*;

public class CustomServerSocket extends TNonblockingServerTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(TNonblockingServerTransport.class.getName());

    /**
     * This channel is where all the nonblocking magic happens.
     */
    private ServerSocketChannel serverSocketChannel = null;

    /**
     * Underlying ServerSocket object
     */
    private ServerSocket serverSocket_ = null;

    /**
     * Timeout for client sockets from accept
     */
    private int clientTimeout_ = 0;

    private final ServerConfig config;

    public CustomServerSocket(ServerConfig config) throws TTransportException {
        this.config = config;
        InetSocketAddress bindAddr = new InetSocketAddress(config.getPort());
        try {
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);

            // Make server socket
            serverSocket_ = serverSocketChannel.socket();
            // Prevent 2MSL delay problem on server restarts
            serverSocket_.setReuseAddress(true);
            // Bind to listening port
            serverSocket_.bind(new InetSocketAddress(config.getPort()));
        } catch (IOException ioe) {
            serverSocket_ = null;
            throw new TTransportException("Could not create ServerSocket on address " + bindAddr.toString() + ".");
        }
    }

    public void listen() throws TTransportException {
        // Make sure not to block on accept
        if (serverSocket_ != null) {
            try {
                serverSocket_.setSoTimeout(0);
            } catch (SocketException sx) {
                sx.printStackTrace();
            }
        }
    }

    protected TNonblockingSocket acceptImpl() throws TTransportException {
        if (serverSocket_ == null) {
            throw new TTransportException(TTransportException.NOT_OPEN, "No underlying server socket.");
        }
        try {
            SocketChannel socketChannel = serverSocketChannel.accept();
            if (socketChannel == null) {
                return null;
            }

            TNonblockingSocket tsocket = new TNonblockingSocket(socketChannel);
            tsocket.setTimeout(clientTimeout_);
            tsocket.getSocketChannel().socket().setKeepAlive(true);
            tsocket.getSocketChannel().socket().setSendBufferSize(config.getSocketSendBufferBytes());
            tsocket.getSocketChannel().socket().setReceiveBufferSize(config.getSocketRecvBufferBytes());
            return tsocket;
        } catch (IOException iox) {
            throw new TTransportException(iox);
        }
    }

    public void registerSelector(Selector selector) {
        try {
            // Register the server socket channel, indicating an interest in
            // accepting new connections
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        } catch (ClosedChannelException e) {
            // this shouldn't happen, ideally...
            // TODO: decide what to do with this.
        }
    }

    public void close() {
        if (serverSocket_ != null) {
            try {
                serverSocket_.close();
            } catch (IOException iox) {
                LOGGER.warn("WARNING: Could not close server socket: " + iox.getMessage());
            }
            serverSocket_ = null;
        }
    }

    public void interrupt() {
        // The thread-safeness of this is dubious, but Java documentation suggests
        // that it is safe to do this from a different thread context
        close();
    }
}
