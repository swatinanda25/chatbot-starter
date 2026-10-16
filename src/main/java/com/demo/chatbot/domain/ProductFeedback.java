package com.demo.chatbot.domain;

/**
 * One customer's free-text feedback on a product. {@code sentiment} is null until the LLM
 * classifier has labeled it ("positive", "neutral" or "negative").
 */
public record ProductFeedback(
    long id,
    String userId,
    String item,
    String itemName,
    String itemColor,
    String feedback,
    String createdAt,
    String sentiment) {}
