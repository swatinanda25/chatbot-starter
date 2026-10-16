package com.demo.chatbot.service;

import com.demo.chatbot.domain.Product;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Stub product matching: token overlap over a 12-row catalog. Shared by product search and the
 * feedback report. In production this is full-text or vector search; callers stay the same.
 */
final class ProductMatch {

  private ProductMatch() {}

  /** Lowercase words longer than two letters, singularized ("jackets" -> "jacket"). */
  static List<String> terms(String query) {
    return query == null ? List.of() : Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
        .filter(t -> t.length() > 2)
        .map(t -> t.endsWith("s") ? t.substring(0, t.length() - 1) : t)
        .toList();
  }

  /** How many terms appear in the product's name, category or color. */
  static int score(Product p, List<String> terms) {
    String haystack = (p.name() + " " + p.category() + " " + p.color()).toLowerCase(Locale.ROOT);
    return (int) terms.stream().filter(haystack::contains).count();
  }
}
