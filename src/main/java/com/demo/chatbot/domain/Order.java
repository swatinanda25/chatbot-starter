package com.demo.chatbot.domain;

import java.util.List;

public record Order(
    String id, String customerId, String status, String createdAt, long totalCents, List<OrderLine> items) {

  public boolean isCancellable() {
    return "PLACED".equals(status);
  }

  /** One-line description used in clarifying questions and confirmation dialogs. */
  public String describeItems() {
    return items.stream()
        .map(i -> i.productName() + (i.color() == null ? "" : " (" + i.color() + ")") + " x" + i.qty())
        .reduce((a, b) -> a + ", " + b)
        .orElse("no items");
  }
}
