package com.demo.chatbot.observability;

import com.demo.chatbot.config.ChatbotProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Reads the chatbot's Micrometer meters and turns them into the headline numbers for the UI's
 * "Under the hood" panel. Counts are since the app started; percentiles are over the rolling
 * window ({@link ObservabilityPlugin#PERCENTILE_WINDOW}). In production these meters go to
 * Prometheus and Grafana instead, where percentiles are computed across instances.
 */
@Component
public class ObservabilitySummary {

  private final MeterRegistry metrics;
  private final ChatbotProperties props;

  public ObservabilitySummary(MeterRegistry metrics, ChatbotProperties props) {
    this.metrics = metrics;
    this.props = props;
  }

  public Map<String, Object> summary() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("percentile_window_minutes", ObservabilityPlugin.PERCENTILE_WINDOW.toMinutes());
    out.put("latency", latency());
    out.put("facts", facts());
    out.put("feedback", feedback());
    out.put("context", context());
    out.put("tokens", tokens());
    out.put("guardrails", countsByTag("chatbot.guardrail.triggered", "guardrail"));
    out.put("marketplace", marketplace());
    return out;
  }

  /**
   * The owner's my_marketplace agent: verdicts shown, and the sentiment classifier behind them
   * (calls, latency, labels, and how often feedback was withheld as instruction-like).
   */
  private Map<String, Object> marketplace() {
    Map<String, Object> classifier = new LinkedHashMap<>();
    Timer t = metrics.find("chatbot.classifier.latency").timer();
    classifier.put("calls", t == null ? 0 : t.count());
    if (t != null) {
      for (ValueAtPercentile p : t.takeSnapshot().percentileValues()) {
        classifier.put("p" + Math.round(p.percentile() * 100) + "_ms", Math.round(p.value(TimeUnit.MILLISECONDS)));
      }
    }
    classifier.put("errors", (long) counter("chatbot.llm.errors", "agent", "feedback_classifier"));
    classifier.put("labels", countsByTag("chatbot.classifier.comments", "result"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("reports", countsByTag("chatbot.marketplace.reports", "verdict"));
    out.put("classifier", classifier);
    out.put("withheld_turns", (long) counter("chatbot.guardrail.triggered", "guardrail", "indirect_injection"));
    return out;
  }

  /** Turn latency per top-level agent: count, mean, P50/P90/P99 and max, in milliseconds. */
  private List<Map<String, Object>> latency() {
    return metrics.find("chatbot.turn.latency").timers().stream()
        .sorted((a, b) -> String.valueOf(a.getId().getTag("agent")).compareTo(String.valueOf(b.getId().getTag("agent"))))
        .map(t -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("agent", t.getId().getTag("agent"));
          row.put("turns", t.count());
          row.put("mean_ms", Math.round(t.mean(TimeUnit.MILLISECONDS)));
          for (ValueAtPercentile p : t.takeSnapshot().percentileValues()) {
            row.put("p" + Math.round(p.percentile() * 100) + "_ms", Math.round(p.value(TimeUnit.MILLISECONDS)));
          }
          row.put("max_ms", Math.round(t.max(TimeUnit.MILLISECONDS)));
          return row;
        })
        .toList();
  }

  /** Fact-consistency results per agent, and the unsupported rate among replies that state facts. */
  private List<Map<String, Object>> facts() {
    Map<String, Map<String, Double>> byAgent = new TreeMap<>();
    for (Counter c : metrics.find("chatbot.facts.replies").counters()) {
      byAgent.computeIfAbsent(c.getId().getTag("agent"), k -> new TreeMap<>())
          .merge(c.getId().getTag("result"), c.count(), Double::sum);
    }
    return byAgent.entrySet().stream().map(e -> {
      double supported = e.getValue().getOrDefault("supported", 0.0);
      double unsupported = e.getValue().getOrDefault("unsupported", 0.0);
      double checked = supported + unsupported;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("agent", e.getKey());
      row.put("replies_with_facts", (long) checked);
      row.put("unsupported", (long) unsupported);
      row.put("replies_without_facts", e.getValue().getOrDefault("no_facts", 0.0).longValue());
      row.put("unsupported_rate", checked == 0 ? null : unsupported / checked);
      return row;
    }).toList();
  }

  /** 👍 rate among votes, and how many turns got a vote at all. */
  private Map<String, Object> feedback() {
    double up = counter("chatbot.feedback", "score", "up");
    double down = counter("chatbot.feedback", "score", "down");
    long turns = metrics.find("chatbot.turn.latency").timers().stream().mapToLong(Timer::count).sum();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("up", (long) up);
    out.put("down", (long) down);
    out.put("satisfaction", up + down == 0 ? null : up / (up + down));
    out.put("turns", turns);
    out.put("response_rate", turns == 0 ? null : (up + down) / turns);
    return out;
  }

  /**
   * Context window per agent step: prompt tokens per model call (mean, P90, max) against the
   * model's context size, and how often the history limit trimmed a call.
   */
  private Map<String, Object> context() {
    int window = props.modelContextTokens();
    List<Map<String, Object>> rows = metrics.find("chatbot.llm.prompt_tokens").summaries().stream()
        .sorted((a, b) -> String.valueOf(a.getId().getTag("agent")).compareTo(String.valueOf(b.getId().getTag("agent"))))
        .map(s -> {
          String agent = s.getId().getTag("agent");
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("agent", agent);
          row.put("calls", s.count());
          row.put("mean_prompt_tokens", Math.round(s.mean()));
          for (ValueAtPercentile p : s.takeSnapshot().percentileValues()) {
            row.put("p" + Math.round(p.percentile() * 100) + "_prompt_tokens", Math.round(p.value()));
          }
          row.put("max_prompt_tokens", Math.round(s.max()));
          row.put("max_window_used", window <= 0 ? null : s.max() / window);
          row.put("trimmed_calls", (long) counter("chatbot.context.trimmed", "agent", agent));
          row.put("dropped_messages", (long) counter("chatbot.context.dropped_messages", "agent", agent));
          return row;
        })
        .toList();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("model_context_tokens", window);
    out.put("max_history_turns", props.maxHistoryTurns());
    out.put("agents", rows);
    return out;
  }

  /** Input and output tokens per agent step (sub-agents are counted separately). */
  private Map<String, Map<String, Long>> tokens() {
    Map<String, Map<String, Long>> out = new TreeMap<>();
    for (Counter c : metrics.find("chatbot.llm.tokens").counters()) {
      out.computeIfAbsent(c.getId().getTag("agent"), k -> new TreeMap<>())
          .merge(c.getId().getTag("type"), (long) c.count(), Long::sum);
    }
    return out;
  }

  private Map<String, Long> countsByTag(String name, String tag) {
    Map<String, Long> out = new TreeMap<>();
    for (Counter c : metrics.find(name).counters()) {
      out.merge(c.getId().getTag(tag), (long) c.count(), Long::sum);
    }
    return out;
  }

  private double counter(String name, String tag, String value) {
    Counter c = metrics.find(name).tag(tag, value).counter();
    return c == null ? 0 : c.count();
  }
}
