package moe.phrolova.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Server-sent event fan-out. Each connected browser has a bounded queue drained by its own virtual thread;
 * a browser that falls too far behind is disconnected and resynchronises from a fresh snapshot on reconnect.
 */
final class Events {
    private static final int QUEUE = 2048;
    private static final long HEARTBEAT_S = 15;

    final class Client {
        private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE);
        private volatile boolean dropped;

        void send(String type, Object data) {
            if (!queue.offer(format(type, data))) dropped = true;
        }
    }

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private static String format(String type, Object data) {
        return "event: " + type + "\ndata: " + Http.GSON.toJson(data) + "\n\n";
    }

    void broadcast(String type, Object data) {
        if (clients.isEmpty()) return;
        String msg = format(type, data);
        for (Client c : clients)
            if (!c.queue.offer(msg)) c.dropped = true;
    }

    boolean hasClients() {
        return !clients.isEmpty();
    }

    void close() {
        closed = true;
    }

    /** Streams events until the browser disconnects; {@code onOpen} queues the initial snapshot. */
    void serve(HttpExchange ex, java.util.function.Consumer<Client> onOpen, java.util.function.BooleanSupplier authorized) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        Client client = new Client();
        clients.add(client);
        try (OutputStream out = ex.getResponseBody()) {
            onOpen.accept(client);
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (!closed && !client.dropped && authorized.getAsBoolean()) {
                String msg = client.queue.poll(HEARTBEAT_S, TimeUnit.SECONDS);
                if (msg == null) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    StringBuilder batch = new StringBuilder(msg);
                    while (batch.length() < 1 << 20 && (msg = client.queue.poll()) != null) batch.append(msg);
                    out.write(batch.toString().getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Browser went away.
        } finally {
            clients.remove(client);
            ex.close();
        }
    }
}
