package com.demo.chatbot.service;

import java.util.Locale;
import java.util.Optional;

/** Label the LLM classifier gives one piece of product feedback. Stored lowercase in product_feedback.sentiment. */
public enum Sentiment {
  POSITIVE,
  NEUTRAL,
  NEGATIVE;

  public String label() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Strict: only the three labels, case-insensitive. Anything else is not a label. */
  public static Optional<Sentiment> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    return switch (raw.strip().toLowerCase(Locale.ROOT)) {
      case "positive" -> Optional.of(POSITIVE);
      case "neutral" -> Optional.of(NEUTRAL);
      case "negative" -> Optional.of(NEGATIVE);
      default -> Optional.empty();
    };
  }
}
