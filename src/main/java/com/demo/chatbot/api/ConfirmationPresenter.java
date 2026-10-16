package com.demo.chatbot.api;

import com.demo.chatbot.domain.StoreRepository;
import com.demo.chatbot.service.ToolResult;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds the human-readable text for a confirmation dialog from server-side data, not from model
 * text, so the customer sees what will actually happen even if the model describes it wrongly.
 */
@Component
public class ConfirmationPresenter {

  private final StoreRepository store;

  public ConfirmationPresenter(StoreRepository store) {
    this.store = store;
  }

  public String summarize(String customerId, String toolName, Map<String, Object> args) {
    if ("cancel_order".equals(toolName)) {
      String orderId = String.valueOf(args.get("order_id"));
      return store.findOrder(customerId, orderId)
          .map(o -> "Cancel order #" + o.id() + ": " + o.describeItems() + ". Total "
              + ToolResult.money(o.totalCents()) + ", refunded to the original payment method. This can't be undone.")
          .orElse("Cancel order #" + orderId + "?");
    }
    return "Allow the assistant to run " + toolName + " with " + args + "?";
  }
}
