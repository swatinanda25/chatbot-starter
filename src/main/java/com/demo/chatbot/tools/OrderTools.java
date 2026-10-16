package com.demo.chatbot.tools;

import com.demo.chatbot.service.OrderService;
import com.demo.chatbot.service.ToolResult;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Thin ADK adapters over {@link OrderService}. The {@code toolContext} parameter is injected by
 * ADK and hidden from the model, which is how the customer id stays out of the model's hands.
 */
@Component
public class OrderTools {

  private final OrderService orders;

  public OrderTools(OrderService orders) {
    this.orders = orders;
  }

  @Schema(name = "list_orders",
      description = "List the signed-in customer's orders, newest first, with status, items and totals."
          + " Use it to find which order the customer means when they describe it instead of giving an id.")
  public Map<String, Object> listOrders(
      @Schema(name = "status", optional = true,
          description = "Optional filter: PLACED, SHIPPED, DELIVERED or CANCELLED. Omit for all orders.")
          String status,
      ToolContext toolContext) {
    return withCustomer(toolContext, customerId -> orders.listOrders(customerId, status));
  }

  @Schema(name = "get_order",
      description = "Get one of the signed-in customer's orders by id: status, items, total and whether it can be cancelled.")
  public Map<String, Object> getOrder(
      @Schema(name = "order_id", description = "The order id, e.g. \"1003\".") String orderId,
      ToolContext toolContext) {
    return withCustomer(toolContext, customerId -> orders.getOrder(customerId, orderId));
  }

  @Schema(name = "cancel_order",
      description = "Cancel one PLACED order. The customer is shown a confirmation dialog before this runs."
          + " Only call it once you know the exact order id and the customer has asked to cancel it.")
  public Map<String, Object> cancelOrder(
      @Schema(name = "order_id", description = "The exact order id to cancel, e.g. \"1003\".") String orderId,
      @Schema(name = "reason", optional = true, description = "The customer's reason, in their words, if given.")
          String reason,
      ToolContext toolContext) {
    return withCustomer(toolContext, customerId -> orders.cancelOrder(customerId, orderId, reason));
  }

  @Schema(name = "escalate_to_human",
      description = "Open a ticket for a human support specialist. Use for refunds, returns, damaged items,"
          + " billing disputes, or when the customer asks for a person.")
  public Map<String, Object> escalateToHuman(
      @Schema(name = "summary", description = "One or two sentences a specialist can act on, including order ids.")
          String summary,
      ToolContext toolContext) {
    return withCustomer(toolContext, customerId -> orders.escalate(customerId, toolContext.sessionId(), summary));
  }

  static Map<String, Object> withCustomer(
      ToolContext toolContext, java.util.function.Function<String, Map<String, Object>> action) {
    Object customerId = toolContext.state().get(SessionKeys.CUSTOMER_ID);
    if (!(customerId instanceof String id) || id.isBlank()) {
      return ToolResult.error("UNAUTHENTICATED", "No signed-in customer on this session.");
    }
    return action.apply(id);
  }
}
