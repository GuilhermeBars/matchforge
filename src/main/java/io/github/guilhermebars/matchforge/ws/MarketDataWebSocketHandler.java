package io.github.guilhermebars.matchforge.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.guilhermebars.matchforge.api.ApiMapper;
import io.github.guilhermebars.matchforge.engine.DomainEvent;
import io.github.guilhermebars.matchforge.events.InProcessEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Each client has a bounded FIFO and a virtual sender. The engine never performs socket I/O. */
@Component
public class MarketDataWebSocketHandler extends TextWebSocketHandler {
    private static final int CAPACITY = 256;
    private final ObjectMapper json;
    private final ApiMapper mapper;
    private final ConcurrentMap<String, Client> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-data-coalescer"));
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final AutoCloseable registration;
    private volatile boolean closed;
    private final class Client {
        final WebSocketSession session;
        final BlockingQueue<String> queue = new ArrayBlockingQueue<>(CAPACITY);
        final Set<String> subscriptions = new HashSet<>();
        final Map<String, Long> sequences = new HashMap<>();
        final Map<String, Object> books = new HashMap<>();
        volatile long sendingSince;
        volatile boolean stopped;
        Future<?> sender;
        Client(WebSocketSession session) { this.session = new ConcurrentWebSocketSessionDecorator(session, 2000, 65536); }
        synchronized void offer(String channel, String symbol, String type, Object data) {
            if (stopped) return;
            try {
                var message = json.writeValueAsString(Map.of("channel", channel, "symbol", symbol, "type", type,
                        "sequence", sequences.merge(symbol, 1L, Long::sum), "data", data));
                if (message.length() > 65536 || !queue.offer(message)) disconnect(this, CloseStatus.SESSION_NOT_RELIABLE);
            } catch (Exception e) { disconnect(this, CloseStatus.SERVER_ERROR); }
        }
        void send() {
            try {
                while (!stopped) {
                    String message = queue.take(); sendingSince = System.nanoTime();
                    session.sendMessage(new TextMessage(message)); sendingSince = 0;
                }
            } catch (Exception e) { disconnect(this, CloseStatus.SESSION_NOT_RELIABLE); }
        }
    }
    public MarketDataWebSocketHandler(ObjectMapper json, ApiMapper mapper, InProcessEventPublisher publisher) {
        this.json = json; this.mapper = mapper;
        registration = publisher.register(this::events);
        ticker.scheduleWithFixedDelay(this::tick, 75, 75, TimeUnit.MILLISECONDS);
    }
    @Override public void afterConnectionEstablished(WebSocketSession session) {
        if (closed) { try { session.close(CloseStatus.GOING_AWAY); } catch (Exception ignored) {} return; }
        var client = new Client(session); clients.put(session.getId(), client);
        client.sender = senders.submit(client::send);
    }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        var client = clients.get(session.getId()); if (client == null) return;
        try {
            var request = json.readTree(message.getPayload());
            String op = request.path("op").asText(), channel = request.path("channel").asText(), symbol = request.path("symbol").asText();
            if (!Set.of("subscribe", "unsubscribe").contains(op) || !Set.of("book", "trades").contains(channel))
                throw new IllegalArgumentException("invalid subscription");
            mapper.instrument(symbol);
            synchronized (client) {
                String key = channel + ":" + symbol;
                if (op.equals("unsubscribe")) { client.subscriptions.remove(key); client.books.remove(symbol); }
                else if (client.subscriptions.add(key)) {
                    var book = mapper.book(symbol, 10);
                    client.offer("book", symbol, "snapshot", book);
                    client.books.put(symbol, List.of(book.bids(), book.asks()));
                }
            }
        } catch (Exception e) { disconnect(client, CloseStatus.BAD_DATA); }
    }
    private void events(List<DomainEvent> events) {
        for (var event : events) if (event instanceof DomainEvent.TradeExecuted trade) {
            String symbol = trade.symbol().value(); var fill = mapper.fill(trade);
            for (var client : clients.values()) synchronized (client) {
                if (client.subscriptions.contains("trades:" + symbol)) client.offer("trades", symbol, "trade", fill);
            }
        }
    }
    private void tick() {
        for (var client : clients.values()) {
            if (client.sendingSince != 0 && System.nanoTime() - client.sendingSince > TimeUnit.SECONDS.toNanos(2)) {
                disconnect(client, CloseStatus.SESSION_NOT_RELIABLE); continue;
            }
            synchronized (client) {
                for (String key : client.subscriptions) if (key.startsWith("book:")) {
                    String symbol = key.substring(5); var book = mapper.book(symbol, 10);
                    Object levels = List.of(book.bids(), book.asks());
                    if (!levels.equals(client.books.put(symbol, levels))) client.offer("book", symbol, "update", book);
                }
            }
        }
    }
    private void disconnect(Client client, CloseStatus status) {
        if (!clients.remove(client.session.getId(), client)) return;
        client.stopped = true; client.queue.clear();
        if (client.sender != null) client.sender.cancel(true);
        // Closing may perform I/O: never close a transport on the engine thread.
        try { senders.submit(() -> { try { client.session.close(status); } catch (Exception ignored) {} }); }
        catch (RejectedExecutionException ignored) { /* shutdown owns remaining sockets */ }
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var client = clients.get(session.getId()); if (client != null) disconnect(client, status);
    }
    @Override public void handleTransportError(WebSocketSession session, Throwable exception) {
        var client = clients.get(session.getId()); if (client != null) disconnect(client, CloseStatus.SERVER_ERROR);
    }
    @PreDestroy public void shutdown() throws Exception {
        closed = true; registration.close(); ticker.shutdownNow();
        clients.values().forEach(c -> disconnect(c, CloseStatus.GOING_AWAY));
        senders.shutdown();
        if (!senders.awaitTermination(5, TimeUnit.SECONDS)) senders.shutdownNow();
    }
}
