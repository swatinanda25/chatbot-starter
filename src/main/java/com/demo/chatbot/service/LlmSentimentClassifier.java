package com.demo.chatbot.service;

import com.demo.chatbot.agents.Prompts;
import com.demo.chatbot.domain.AuditRepository;
import com.demo.chatbot.domain.ProductFeedback;
import com.demo.chatbot.guardrails.GuardrailLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sentiment per comment from the chat model, through the same gateway as every agent call (so
 * LiteLLM's guard and Langfuse see it). Each call carries the turn's trace metadata, so it shows
 * up as a "feedback_classifier" generation inside the owner's trace.
 *
 * <p>Observability mirrors {@link com.demo.chatbot.observability.ObservabilityPlugin}, which never
 * sees this call because it runs inside a tool, not as an agent step: tokens and prompt size
 * under {@code agent=feedback_classifier}, latency, label counts, an audit row per call, and model
 * errors. The model's answer is validated strictly; anything that isn't one of the three labels
 * for an id in the batch is left unclassified, never guessed.
 */
@Component
public class LlmSentimentClassifier implements SentimentClassifier {

  public static final String NAME = "feedback_classifier";
  static final int BATCH_SIZE = 20;

  private static final Logger log = LoggerFactory.getLogger(LlmSentimentClassifier.class);
  private static final ObjectMapper JSON = JsonBaseModel.getMapper();
  private static final Duration PERCENTILE_WINDOW = Duration.ofMinutes(10);

  private final BaseLlm llm;
  private final MeterRegistry metrics;
  private final AuditRepository audit;
  private final GuardrailLog guardrails;

  public LlmSentimentClassifier(BaseLlm chatLlm, MeterRegistry metrics, AuditRepository audit, GuardrailLog guardrails) {
    this.llm = chatLlm;
    this.metrics = metrics;
    this.audit = audit;
    this.guardrails = guardrails;
  }

  @Override
  public Map<Long, Sentiment> classify(List<ProductFeedback> batch, TurnContext ctx) {
    Map<Long, Sentiment> out = new HashMap<>();
    for (int i = 0; i < batch.size(); i += BATCH_SIZE) {
      out.putAll(classifyChunk(batch.subList(i, Math.min(batch.size(), i + BATCH_SIZE)), ctx));
    }
    return out;
  }

  private Map<Long, Sentiment> classifyChunk(List<ProductFeedback> chunk, TurnContext ctx) {
    List<Long> ids = chunk.stream().map(ProductFeedback::id).toList();
    long start = System.nanoTime();
    String raw;
    try {
      List<LlmResponse> responses = llm.generateContent(request(chunk, ctx), false).toList().blockingGet();
      raw = responses.stream().map(LlmSentimentClassifier::text).collect(Collectors.joining());
      responses.forEach(this::recordTokens);
    } catch (RuntimeException e) {
      metrics.counter("chatbot.llm.errors", "agent", NAME).increment();
      audit(ctx, ids, String.valueOf(e.getMessage()), latencyMs(start), "error");
      throw e;
    }
    long ms = latencyMs(start);
    Timer.builder("chatbot.classifier.latency")
        .description("One sentiment-classifier model call")
        .publishPercentiles(0.5, 0.9)
        .distributionStatisticExpiry(PERCENTILE_WINDOW)
        .register(metrics)
        .record(Duration.ofMillis(ms));

    Map<Long, Sentiment> labels = parse(raw, Set.copyOf(ids));
    labels.values().forEach(s -> metrics.counter("chatbot.classifier.comments", "result", s.label()).increment());
    int unclassified = ids.size() - labels.size();
    if (unclassified > 0) {
      metrics.counter("chatbot.classifier.comments", "result", "unclassified").increment(unclassified);
      guardrails.record(ctx.sessionId(), ctx.invocationId(), "classifier_output_invalid", "unclassified",
          unclassified + " of " + ids.size() + " comments got no valid label");
    }
    Map<String, String> result = new LinkedHashMap<>();
    labels.forEach((id, s) -> result.put(String.valueOf(id), s.label()));
    audit(ctx, ids, result, ms, unclassified == 0 ? "ok" : "partial");
    return labels;
  }

  /** One user message holding the comments as JSON; the rules are the system instruction. */
  LlmRequest request(List<ProductFeedback> chunk, TurnContext ctx) {
    List<Map<String, Object>> comments = chunk.stream()
        .map(f -> Map.<String, Object>of("id", f.id(), "text", f.feedback()))
        .toList();
    String payload;
    try {
      payload = JSON.writeValueAsString(comments);
    } catch (Exception e) {
      throw new IllegalStateException("could not serialize comments", e);
    }
    GenerateContentConfig config = GenerateContentConfig.builder()
        .temperature(0f)
        .maxOutputTokens(60 + 25 * chunk.size())
        .labels(labels(ctx))
        .build();
    return LlmRequest.builder()
        .model(llm.model())
        .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText(payload))).build()))
        .config(config)
        .appendInstructions(List.of(Prompts.SENTIMENT))
        .build();
  }

  /** The same trace metadata ObservabilityPlugin puts on agent calls, so LiteLLM files this call in the turn's trace. */
  static Map<String, String> labels(TurnContext ctx) {
    Map<String, String> labels = new HashMap<>();
    labels.put("trace_id", ctx.traceId());
    if (ctx.sessionId() != null) {
      labels.put("session_id", ctx.sessionId());
    }
    labels.put("trace_user_id", ctx.userId());
    labels.put("trace_name", ctx.evalCase() == null ? "support-chat" : "eval:" + ctx.evalCase());
    labels.put("generation_name", NAME);
    labels.put("prompt_version", Prompts.VERSION);
    labels.put("top_level_agent", ctx.agent());
    return labels;
  }

  /**
   * Strict parse of {@code [{"id": 12, "sentiment": "negative"}, ...]}, tolerating code fences
   * and text around the array. Unknown ids, duplicates and non-labels are dropped.
   */
  static Map<Long, Sentiment> parse(String raw, Set<Long> expectedIds) {
    Map<Long, Sentiment> out = new HashMap<>();
    if (raw == null) {
      return out;
    }
    int from = raw.indexOf('[');
    int to = raw.lastIndexOf(']');
    if (from < 0 || to <= from) {
      return out;
    }
    List<Map<String, Object>> rows;
    try {
      rows = JSON.readValue(raw.substring(from, to + 1), new TypeReference<>() {});
    } catch (Exception e) {
      log.debug("classifier output is not JSON: {}", e.toString());
      return out;
    }
    for (Map<String, Object> row : rows) {
      if (row == null) {
        continue;
      }
      Long id = toId(row.get("id"));
      if (id == null || !expectedIds.contains(id) || out.containsKey(id)) {
        continue;
      }
      Sentiment.parse(String.valueOf(row.get("sentiment"))).ifPresent(s -> out.put(id, s));
    }
    return out;
  }

  private static Long toId(Object raw) {
    if (raw instanceof Number n) {
      return n.longValue();
    }
    try {
      return raw == null ? null : Long.parseLong(String.valueOf(raw).strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void recordTokens(LlmResponse response) {
    response.usageMetadata().ifPresent(u -> {
      u.promptTokenCount().ifPresent(n -> DistributionSummary.builder("chatbot.llm.prompt_tokens")
          .description("Prompt size of one model call: how full the context window is")
          .tag("agent", NAME)
          .publishPercentiles(0.5, 0.9)
          .distributionStatisticExpiry(PERCENTILE_WINDOW)
          .register(metrics)
          .record(n));
      u.promptTokenCount().ifPresent(n -> metrics.counter("chatbot.llm.tokens", "type", "input", "agent", NAME).increment(n));
      u.candidatesTokenCount().ifPresent(n -> metrics.counter("chatbot.llm.tokens", "type", "output", "agent", NAME).increment(n));
    });
  }

  private static String text(LlmResponse response) {
    List<String> texts = new ArrayList<>();
    response.content().flatMap(Content::parts).orElse(List.of()).stream()
        .filter(p -> !p.thought().orElse(false))
        .forEach(p -> p.text().ifPresent(texts::add));
    return String.join("", texts);
  }

  private static long latencyMs(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
  }

  private void audit(TurnContext ctx, List<Long> ids, Object result, long ms, String outcome) {
    try {
      audit.append(new AuditRepository.Entry(ctx.sessionId(), ctx.invocationId(), NAME, "classifier",
          "feedback_sentiment", JSON.writeValueAsString(Map.of("comment_ids", ids)),
          result instanceof String s ? s : JSON.writeValueAsString(result), ms, outcome));
    } catch (Exception e) {
      // Observability must never break the conversation.
      log.warn("audit write failed: {}", e.toString());
    }
  }
}
