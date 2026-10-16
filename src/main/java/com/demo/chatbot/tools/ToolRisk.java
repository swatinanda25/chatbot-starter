package com.demo.chatbot.tools;

import java.util.Map;

/**
 * Risk tier per tool, in one place. Drives whether ADK asks the user to confirm (see
 * AgentConfig) and is logged with every call. Maps directly to MCP's readOnlyHint /
 * destructiveHint if these tools move behind an MCP server later.
 */
public enum ToolRisk {
  /** Runs automatically. */
  READ,
  /** Low-impact write that is safe to run when the user asks (e.g. opening a support ticket). */
  LOW_WRITE,
  /** Changes money or orders: needs explicit user confirmation through the UI. */
  CONFIRM;

  private static final Map<String, ToolRisk> BY_TOOL = Map.of(
      // ADK's built-in hand-off between agents: changes no data.
      "transfer_to_agent", READ,
      "search_products", READ,
      "list_orders", READ,
      "get_order", READ,
      // Owner analytics: reads feedback. Storing classifier labels is a derived cache, not a user-visible change.
      "get_feedback_report", READ,
      "list_feedback_overview", READ,
      "escalate_to_human", LOW_WRITE,
      "cancel_order", CONFIRM);

  public static ToolRisk of(String toolName) {
    // Unknown tools default to the strictest tier.
    return BY_TOOL.getOrDefault(toolName, CONFIRM);
  }
}
