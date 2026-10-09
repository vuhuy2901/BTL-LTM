package com.nhom8.client.ws;

import com.nhom8.common.message.Envelope;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Lớp quản lý kết nối WebSocket phía Client.
 * Sử dụng java.net.http.WebSocket (có sẵn từ Java 11).
 */
public class GameWebSocketClient implements WebSocket.Listener {

    private final String url;
    private final Consumer<Envelope> messageHandler;
    private WebSocket webSocket;
    private StringBuilder messageBuffer = new StringBuilder();

    public GameWebSocketClient(String url, Consumer<Envelope> messageHandler) {
        this.url = url;
        this.messageHandler = messageHandler;
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<Envelope> messageQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private java.util.concurrent.CompletableFuture<WebSocket> connectFuture;

    public java.util.concurrent.CompletableFuture<WebSocket> connect() {
        if (webSocket != null) {
            return java.util.concurrent.CompletableFuture.completedFuture(webSocket);
        }
        if (connectFuture != null && !connectFuture.isDone()) {
            return connectFuture;
        }

        HttpClient client = HttpClient.newHttpClient();
        connectFuture = client.newWebSocketBuilder()
                .buildAsync(URI.create(url), this)
                .thenApply(ws -> {
                    this.webSocket = ws;
                    System.out.println("[CLIENT] Connected to WebSocket at " + url);
                    flushQueue();
                    return ws;
                })
                .exceptionally(ex -> {
                    System.err.println("[CLIENT] Lỗi kết nối WebSocket: " + ex.getMessage());
                    return null;
                });
        return connectFuture;
    }

    public void send(Envelope envelope) {
        if (webSocket != null) {
            String json = envelope.toJson();
            webSocket.sendText(json, true);
        } else {
            messageQueue.add(envelope);
            if (connectFuture == null || connectFuture.isDone()) {
                connect();
            }
        }
    }

    private void flushQueue() {
        while (webSocket != null && !messageQueue.isEmpty()) {
            Envelope env = messageQueue.poll();
            if (env != null) {
                webSocket.sendText(env.toJson(), true);
            }
        }
    }

    public void close() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client đóng kết nối");
        }
    }

    public boolean isConnected() {
        return webSocket != null;
    }

    // === WebSocket.Listener implementation ===

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);
        if (last) {
            String fullMessage = messageBuffer.toString();
            messageBuffer.setLength(0); // Clear buffer
            try {
                Envelope envelope = Envelope.fromJson(fullMessage);
                messageHandler.accept(envelope);
            } catch (Exception e) {
                System.err.println("[CLIENT] JSON Parse Error: " + e.getMessage());
            }
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        System.out.println("[CLIENT] WebSocket Closed: " + reason);
        this.webSocket = null;
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("[CLIENT] WebSocket Error: " + error.getMessage());
        WebSocket.Listener.super.onError(webSocket, error);
    }
}
