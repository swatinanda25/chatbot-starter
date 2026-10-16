package com.demo.chatbot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatbot")
public record ChatbotProperties(Gateway gateway, String demoCustomerId, int maxMessageChars, String defaultAgent,
    int maxHistoryTurns, int modelContextTokens) {

  public record Gateway(String baseUrl, String apiKey, String model, Duration timeout) {}
}
