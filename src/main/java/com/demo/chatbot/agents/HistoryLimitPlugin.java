package com.demo.chatbot.agents;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple context-window management: each model call sees at most the last N customer turns.
 * ADK otherwise resends the whole session history on every call, so tokens and latency grow with
 * conversation length, and a long chat can overflow the model's context (Ollama then silently
 * drops the oldest tokens, system prompt included).
 *
 * <p>Cuts happen only at a customer message, so a tool call is never separated from its result.
 * Messages relayed from other agents (ADK sends them as user-role text starting "For context:")
 * belong to the turn they're in. Structured memory that must survive trimming, like the customer
 * id, lives in session state, not in the history.
 */
public class HistoryLimitPlugin extends BasePlugin {

  private static final Logger log = LoggerFactory.getLogger(HistoryLimitPlugin.class);
  static final String OTHER_AGENT_PREFIX = "For context:";

  private final int maxTurns;
  private final MeterRegistry metrics;

  public HistoryLimitPlugin(int maxTurns, MeterRegistry metrics) {
    super("history_limit");
    this.maxTurns = maxTurns;
    this.metrics = metrics;
  }

  public int maxTurns() {
    return maxTurns;
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(CallbackContext ctx, LlmRequest.Builder request) {
    if (maxTurns <= 0) {
      return Maybe.empty();
    }
    try {
      List<Content> contents = request.build().contents();
      List<Content> kept = trim(contents, maxTurns);
      DistributionSummary.builder("chatbot.context.turns_sent")
          .tag("agent", ctx.agentName())
          .register(metrics)
          .record(customerTurns(kept));
      if (kept.size() < contents.size()) {
        request.contents(kept);
        metrics.counter("chatbot.context.trimmed", "agent", ctx.agentName()).increment();
        metrics.counter("chatbot.context.dropped_messages", "agent", ctx.agentName())
            .increment(contents.size() - kept.size());
      }
    } catch (RuntimeException e) {
      // Trimming is an optimization; on any surprise, send the request unchanged.
      log.warn("history trim skipped: {}", e.toString());
    }
    return Maybe.empty();
  }

  /** Keeps everything from the start of the last {@code maxTurns} customer turns. */
  static List<Content> trim(List<Content> contents, int maxTurns) {
    List<Integer> turnStarts = new ArrayList<>();
    for (int i = 0; i < contents.size(); i++) {
      if (isCustomerMessage(contents.get(i))) {
        turnStarts.add(i);
      }
    }
    if (turnStarts.size() <= maxTurns) {
      return contents;
    }
    int from = turnStarts.get(turnStarts.size() - maxTurns);
    return List.copyOf(contents.subList(from, contents.size()));
  }

  static int customerTurns(List<Content> contents) {
    return (int) contents.stream().filter(HistoryLimitPlugin::isCustomerMessage).count();
  }

  /** A message the customer typed: user role, with text that isn't relayed from another agent. */
  static boolean isCustomerMessage(Content c) {
    if (!"user".equals(c.role().orElse(""))) {
      return false;
    }
    List<Part> parts = c.parts().orElse(List.of());
    if (parts.isEmpty() || parts.stream().anyMatch(p -> p.functionResponse().isPresent())) {
      return false;
    }
    String first = parts.get(0).text().orElse("");
    return !first.isBlank() && !first.startsWith(OTHER_AGENT_PREFIX);
  }
}
