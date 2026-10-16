package com.demo.chatbot.service;

import com.demo.chatbot.domain.Order;
import com.demo.chatbot.domain.Product;
import com.demo.chatbot.domain.StoreRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Business rules. This layer never trusts the model: it validates every argument, scopes every
 * query to the authenticated customer, and re-checks state at execution time.
 */
@Service
public class OrderService {

  private static final Pattern ORDER_ID = Pattern.compile("\\d{4,10}");
  private static final Set<String> STATUSES = Set.of("PLACED", "SHIPPED", "DELIVERED", "CANCELLED");

  private final StoreRepository store;

  public OrderService(StoreRepository store) {
    this.store = store;
  }

  public Map<String, Object> getOrder(String customerId, String rawOrderId) {
    Optional<String> orderId = normalizeOrderId(rawOrderId);
    if (orderId.isEmpty()) {
      return ToolResult.error("INVALID_ORDER_ID", "Order ids are numbers like 1003.");
    }
    return store
        .findOrder(customerId, orderId.get())
        .map(o -> ToolResult.ok(view(o)))
        .orElseGet(() -> notFound(orderId.get()));
  }

  public Map<String, Object> listOrders(String customerId, String rawStatus) {
    String status = rawStatus == null || rawStatus.isBlank() ? null : rawStatus.trim().toUpperCase(Locale.ROOT);
    if (status != null && !STATUSES.contains(status)) {
      return ToolResult.error("INVALID_STATUS", "Status must be one of " + STATUSES + ".");
    }
    List<Map<String, Object>> orders = store.listOrders(customerId, status).stream().map(this::view).toList();
    return ToolResult.ok(Map.of("orders", orders, "count", orders.size()));
  }

  /**
   * Checks whether a cancel can succeed, without changing anything. Run before asking the user to
   * confirm, so we never ask "cancel order 1002?" for an order that has already shipped.
   */
  public Optional<Map<String, Object>> cancelPrecheck(String customerId, String rawOrderId) {
    Optional<String> orderId = normalizeOrderId(rawOrderId);
    if (orderId.isEmpty()) {
      return Optional.of(ToolResult.error("INVALID_ORDER_ID", "Order ids are numbers like 1003."));
    }
    Optional<Order> order = store.findOrder(customerId, orderId.get());
    if (order.isEmpty()) {
      return Optional.of(notFound(orderId.get()));
    }
    return cancelBlocker(order.get());
  }

  /** Executes the cancel. Re-validates, because state may have changed since confirmation. */
  public Map<String, Object> cancelOrder(String customerId, String rawOrderId, String reason) {
    Optional<Map<String, Object>> blocked = cancelPrecheck(customerId, rawOrderId);
    if (blocked.isPresent()) {
      return blocked.get();
    }
    String orderId = normalizeOrderId(rawOrderId).orElseThrow();
    if (!store.cancelIfPlaced(customerId, orderId)) {
      // Lost a race with fulfilment (or a duplicate request): report the current state truthfully.
      return store
          .findOrder(customerId, orderId)
          .flatMap(this::cancelBlocker)
          .orElseGet(() -> ToolResult.error("CANCEL_FAILED", "The order could not be cancelled."));
    }
    Order updated = store.findOrder(customerId, orderId).orElseThrow();
    Map<String, Object> data = new LinkedHashMap<>(view(updated));
    data.put("refund_note", "The full amount of " + ToolResult.money(updated.totalCents())
        + " will be refunded to the original payment method in 3-5 business days.");
    data.put("reason_recorded", reason == null ? "" : reason);
    return ToolResult.ok(data);
  }

  /**
   * Stub: token match over a 12-row catalog. In production this is full-text or vector search
   * (or a search service behind a tool); the agent contract stays the same.
   */
  public Map<String, Object> searchProducts(String query, Double maxPriceDollars) {
    List<String> terms = ProductMatch.terms(query);
    Long maxCents = maxPriceDollars == null ? null : Math.round(maxPriceDollars * 100);
    List<Map<String, Object>> matches = store.allProducts().stream()
        .filter(p -> maxCents == null || p.priceCents() <= maxCents)
        .map(p -> Map.entry(p, ProductMatch.score(p, terms)))
        .filter(e -> terms.isEmpty() || e.getValue() > 0)
        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
        .limit(8)
        .map(e -> productView(e.getKey()))
        .toList();
    return ToolResult.ok(Map.of("products", matches, "count", matches.size()));
  }

  public Map<String, Object> escalate(String customerId, String sessionId, String summary) {
    if (summary == null || summary.isBlank()) {
      return ToolResult.error("MISSING_SUMMARY", "Include a one-sentence summary for the support agent.");
    }
    long ticket = store.createHandoff(customerId, sessionId, summary.trim());
    return ToolResult.ok(Map.of(
        "ticket_id", "HO-" + ticket,
        "expected_response", "A support specialist will email the customer within 1 business day."));
  }

  private Optional<Map<String, Object>> cancelBlocker(Order o) {
    return switch (o.status()) {
      case "PLACED" -> Optional.empty();
      case "CANCELLED" -> Optional.of(ToolResult.error("ALREADY_CANCELLED", "Order " + o.id() + " is already cancelled."));
      default -> Optional.of(ToolResult.error("NOT_CANCELLABLE",
          "Order " + o.id() + " is " + o.status() + " and can no longer be cancelled. A return can be arranged by a support specialist."));
    };
  }

  private Map<String, Object> notFound(String orderId) {
    return ToolResult.error("NOT_FOUND", "No order " + orderId + " on this customer's account.");
  }

  private Optional<String> normalizeOrderId(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String id = raw.trim().replaceFirst("^#", "");
    return ORDER_ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
  }

  private Map<String, Object> view(Order o) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("order_id", o.id());
    m.put("status", o.status());
    m.put("placed_at", o.createdAt());
    m.put("total", ToolResult.money(o.totalCents()));
    m.put("cancellable", o.isCancellable());
    m.put("items", o.items().stream()
        .map(i -> Map.of("product", i.productName(), "color", String.valueOf(i.color()), "qty", i.qty()))
        .toList());
    return m;
  }

  private Map<String, Object> productView(Product p) {
    return Map.of(
        "product_id", p.id(),
        "name", p.name(),
        "category", p.category(),
        "color", String.valueOf(p.color()),
        "price", ToolResult.money(p.priceCents()),
        "in_stock", p.stock() > 0);
  }
}
