package com.demo.chatbot.domain;

public record OrderLine(String productId, String productName, String color, int qty, long unitPriceCents) {}
