package io.github.guilhermebars.matchforge.config;

import io.github.guilhermebars.matchforge.ws.MarketDataWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final MarketDataWebSocketHandler handler;

    public WebSocketConfiguration(MarketDataWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/market-data");
    }
}
