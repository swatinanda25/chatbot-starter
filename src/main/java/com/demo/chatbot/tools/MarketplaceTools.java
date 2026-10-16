package com.demo.chatbot.tools;

import com.demo.chatbot.observability.TraceIds;
import com.demo.chatbot.service.MarketplaceService;
import com.demo.chatbot.service.ToolResult;
import com.demo.chatbot.service.TurnContext;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Thin ADK adapters over {@link MarketplaceService}, for the store owner's my_marketplace agent.
 * The {@code toolContext} (hidden from the model) supplies the turn's ids, so the classifier's
 * model calls join this turn's trace, audit trail and guardrail events.
 */
@Component
public class MarketplaceTools {

  private final MarketplaceService marketplace;
  private final TraceIds traceIds;
  private final MeterRegistry metrics;

  public MarketplaceTools(MarketplaceService marketplace, TraceIds traceIds, MeterRegistry metrics) {
    this.marketplace = marketplace;
    this.traceIds = traceIds;
    this.metrics = metrics;
  }

  @Schema(name = "get_feedback_report",
      description = "Aggregated customer feedback for one product: comment counts, positive and negative"
          + " percentages, a verdict (doing well, mixed, needs attention, not enough feedback) with the"
          + " reason, and recent anonymous comments.")
  public Map<String, Object> getFeedbackReport(
      @Schema(name = "item", description = "Product words the owner used (e.g. \"wireless earbuds\") or a product id like P108.")
          String item,
      ToolContext toolContext) {
    Map<String, Object> result = marketplace.feedbackReport(item, turn(toolContext));
    countReport(result);
    return result;
  }

  @Schema(name = "list_feedback_overview",
      description = "Every product's feedback verdict and positive/negative percentages, worst first, plus"
          + " the products with no feedback yet.")
  public Map<String, Object> listFeedbackOverview(ToolContext toolContext) {
    return marketplace.feedbackOverview(turn(toolContext));
  }

  private TurnContext turn(ToolContext ctx) {
    Object agent = ctx.state().get("agent");
    Object evalCase = ctx.state().get(SessionKeys.EVAL_CASE);
    return new TurnContext(ctx.sessionId(), ctx.invocationId(), traceIds.traceIdFor(ctx.invocationId()),
        ctx.userId(), agent == null ? ctx.agentName() : String.valueOf(agent),
        evalCase == null ? null : String.valueOf(evalCase));
  }

  /** Business metric: which verdicts the owner is being shown. */
  private void countReport(Map<String, Object> result) {
    String verdict = ToolResult.isOk(result) && result.get("data") instanceof Map<?, ?> data
        ? String.valueOf(data.get("verdict"))
        : "error";
    metrics.counter("chatbot.marketplace.reports", "verdict", verdict).increment();
  }
}
