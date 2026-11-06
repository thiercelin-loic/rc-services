package com.rollercoaster.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configures STOMP over WebSocket endpoints for:
 * 1. Real-time Monaco Editor RAX syntax & schema diagnostics (/topic/diagnostics/{workspaceId})
 * 2. Sub-50ms Visual Data Batch streaming (/topic/visuals/{tenantId}/{reportId})
 * 3. Staging Write-Back and workspace telemetry push notifications (/topic/workspace/{workspaceId})
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketStompConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("https://app.rollercoaster.com", "https://rollercoaster.com", "http://localhost", "http://localhost:*", "http://angular:*");
    }
}
