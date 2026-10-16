package com.demo.chatbot.observability;

import com.demo.chatbot.agents.Prompts;
import com.demo.chatbot.domain.AuditRepository;
import com.demo.chatbot.tools.SessionKeys;
import com.demo.chatbot.tools.ToolRisk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Passive, app-side observability. Never changes behavior (every hook returns empty).
 *
 * <ul>
 *   <li>Tags each model call with trace metadata, which LiteLLM forwards to Langfuse. One trace
 *       per user turn, with a generation per agent step.
 *   <li>Writes every tool call to audit_log, with args, result, latency and outcome. (Guardrail
 *       decisions are audited by GuardrailLog.)
 *   <li>Emits Micrometer metrics: turn latency per agent and tool latency, both with P50/P90/P99
 *       over a rolling window; tokens; model errors.
 *   <li>Fact-consistency check on every customer-facing reply: order ids, money, ticket ids and
 *       statuses must appear in the session's tool results or the customer's own words. Measured
 *       on the model's raw reply, before guardrails rewrite it, so it is the model's
 *       hallucination rate on structured facts ({@code chatbot.facts.replies}).
 * </ul>
 */
public class ObservabilityPlugin extends BasePlugin {

  private static final Logger log = LoggerFactory.getLogger(ObservabilityPlugin.class);
  private static final ObjectMapper JSON = JsonBaseModel.getMapper();

  private final AuditRepository audit;
  private final MeterRegistry metrics;
  private final TraceIds traceIds;
  private final Map<String, Long> runStarts = new ConcurrentHashMap<>();
  private final Map<String, Long> toolStarts = new ConcurrentHashMap<>();
  /** The top-level agent the session talks to, per running invocation, for per-agent metrics. */
  private final Map<String, String> turnAgents = new ConcurrentHashMap<>();
  /** Per session: tool results and customer messages, the evidence replies are checked against. */
  private final Map<String, List<String>> evidence = new ConcurrentHashMap<>();
  private final Predicate<String> isIntermediateStep;

  /** Percentiles are computed in-process over this rolling window. */
  static final Duration PERCENTILE_WINDOW = Duration.ofMinutes(10);

  public ObservabilityPlugin(AuditRepository audit, MeterRegistry metrics, TraceIds traceIds,
      Predicate<String> isIntermediateStep) {
    super("observability");
    this.audit = audit;
    this.metrics = metrics;
    this.traceIds = traceIds;
    this.isIntermediateStep = isIntermediateStep;
  }

  /** Trace id for an invocation (one user turn). Exposed so the API can return it to the UI. */
  public String traceIdFor(String invocationId) {
    return traceIds.traceIdFor(invocationId);
  }

  @Override
  public Maybe<Content> beforeRunCallback(InvocationContext ctx) {
    runStarts.put(ctx.invocationId(), System.nanoTime());
    // The agent the customer picked (session state), not ctx.agent(): ADK starts later turns at
    // whichever sub-agent last took over (e.g. order_agent), which would split the router's numbers.
    Object picked = ctx.session().state().get("agent");
    turnAgents.put(ctx.invocationId(), picked instanceof String name ? name : ctx.agent().name());
    traceIdFor(ctx.invocationId());
    // What the customer said is evidence too: echoing their order id back isn't a hallucination.
    ctx.userContent().flatMap(Content::parts).orElse(List.of()).stream()
        .flatMap(p -> p.text().stream())
        .forEach(t -> evidence(ctx.session().id()).add(t));
    return Maybe.empty();
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(CallbackContext ctx, LlmRequest.Builder request) {
    Object evalCase = ctx.state().get(SessionKeys.EVAL_CASE);
    Map<String, String> labels = new HashMap<>();
    labels.put("trace_id", traceIdFor(ctx.invocationId()));
    labels.put("session_id", ctx.sessionId());
    labels.put("trace_user_id", ctx.userId());
    labels.put("trace_name", evalCase == null ? "support-chat" : "eval:" + evalCase);
    labels.put("generation_name", ctx.agentName());
    labels.put("prompt_version", Prompts.VERSION);
    // The agent the customer (or owner) picked, so Langfuse can filter one agent's traces, sub-steps included.
    String topLevel = turnAgents.getOrDefault(ctx.invocationId(), ctx.agentName());
    labels.put("top_level_agent", topLevel);
    GenerateContentConfig existing = request.config().orElse(GenerateContentConfig.builder().build());
    existing.labels().ifPresent(l -> l.forEach(labels::putIfAbsent));
    request.config(existing.toBuilder().labels(labels).build());
    return Maybe.empty();
  }

  @Override
  public Maybe<LlmResponse> afterModelCallback(CallbackContext ctx, LlmResponse response) {
    response.usageMetadata().ifPresent(u -> {
      u.promptTokenCount().ifPresent(n -> DistributionSummary.builder("chatbot.llm.prompt_tokens")
          .description("Prompt size of one model call: how full the context window is")
          .tag("agent", ctx.agentName())
          .publishPercentiles(0.5, 0.9)
          .distributionStatisticExpiry(PERCENTILE_WINDOW)
          .register(metrics)
          .record(n));
      u.promptTokenCount().ifPresent(n -> metrics.counter("chatbot.llm.tokens", "type", "input", "agent", ctx.agentName()).increment(n));
      u.candidatesTokenCount().ifPresent(n -> metrics.counter("chatbot.llm.tokens", "type", "output", "agent", ctx.agentName()).increment(n));
    });
    if (!response.partial().orElse(false) && !isIntermediateStep.test(ctx.agentName())) {
      text(response).ifPresent(reply -> factCheck(ctx, reply));
    }
    return Maybe.empty();
  }

  private void factCheck(CallbackContext ctx, String reply) {
    String agent = turnAgents.getOrDefault(ctx.invocationId(), ctx.agentName());
    FactCheck.Result r = FactCheck.check(reply, List.copyOf(evidence(ctx.sessionId())));
    String result = !r.hasFacts() ? "no_facts" : r.supported() ? "supported" : "unsupported";
    metrics.counter("chatbot.facts.replies", "agent", agent, "result", result).increment();
    if (r.hasFacts()) {
      record(ctx.sessionId(), ctx.invocationId(), ctx.agentName(), "fact_check", "reply",
          r.facts(), r.supported() ? "all facts found in tool results or the customer's message"
              : "not found: " + String.join(", ", r.unsupported()), null, result);
    }
  }

  @Override
  public Maybe<LlmResponse> onModelErrorCallback(CallbackContext ctx, LlmRequest.Builder request, Throwable error) {
    metrics.counter("chatbot.llm.errors", "agent", ctx.agentName()).increment();
    record(ctx.sessionId(), ctx.invocationId(), ctx.agentName(), "model_error", "llm", null,
        String.valueOf(error.getMessage()), null, "error");
    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(BaseTool tool, Map<String, Object> args, ToolContext ctx) {
    toolStarts.put(callKey(ctx), System.nanoTime());
    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool, Map<String, Object> args, ToolContext ctx, Map<String, Object> result) {
    Long start = toolStarts.remove(callKey(ctx));
    long ms = start == null ? 0 : Duration.ofNanos(System.nanoTime() - start).toMillis();
    String outcome = outcome(result);
    Timer.builder("chatbot.tool.latency")
        .tag("tool", tool.name())
        .tag("risk", ToolRisk.of(tool.name()).name())
        .tag("outcome", outcome)
        .publishPercentiles(0.5, 0.9)
        .distributionStatisticExpiry(PERCENTILE_WINDOW)
        .register(metrics)
        .record(Duration.ofMillis(ms));
    try {
      evidence(ctx.sessionId()).add(JSON.writeValueAsString(result));
    } catch (Exception e) {
      log.debug("could not keep tool result as evidence: {}", e.toString());
    }
    record(ctx.sessionId(), ctx.invocationId(), ctx.agentName(), "tool", tool.name(), args, result, ms, outcome);
    log.info("tool={} risk={} outcome={} latency_ms={} session={}",
        tool.name(), ToolRisk.of(tool.name()), outcome, ms, ctx.sessionId());
    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> args, ToolContext ctx, Throwable error) {
    toolStarts.remove(callKey(ctx));
    record(ctx.sessionId(), ctx.invocationId(), ctx.agentName(), "tool", tool.name(), args,
        String.valueOf(error.getMessage()), null, "exception");
    return Maybe.empty();
  }

  @Override
  public Completable afterRunCallback(InvocationContext ctx) {
    traceIds.remove(ctx.invocationId());
    String agent = turnAgents.remove(ctx.invocationId());
    Long start = runStarts.remove(ctx.invocationId());
    if (start != null) {
      Timer.builder("chatbot.turn.latency")
          .tag("agent", agent == null ? ctx.agent().name() : agent)
          .publishPercentiles(0.5, 0.9, 0.99)
          .distributionStatisticExpiry(PERCENTILE_WINDOW)
          .register(metrics)
          .record(Duration.ofNanos(System.nanoTime() - start));
    }
    return Completable.complete();
  }

  private static String outcome(Map<String, Object> result) {
    if (Boolean.TRUE.equals(result.get("ok"))) {
      return "ok";
    }
    // Framework tools outside the ToolResult envelope (e.g. transfer_to_agent) return no status.
    if (!result.containsKey("ok") && !result.containsKey("error")) {
      return "ok";
    }
    if (result.get("error_code") instanceof String code) {
      return code.toLowerCase();
    }
    // ADK's own placeholders when a tool needs confirmation or was rejected by the user.
    String error = String.valueOf(result.get("error"));
    if (error.contains("requires confirmation")) {
      return "awaiting_confirmation";
    }
    if (error.contains("rejected")) {
      return "rejected_by_user";
    }
    return "error";
  }

  private List<String> evidence(String sessionId) {
    return evidence.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
  }

  private static Optional<String> text(LlmResponse response) {
    String joined = response.content().flatMap(Content::parts).orElse(List.of()).stream()
        .filter(p -> !p.thought().orElse(false))
        .flatMap(p -> p.text().stream())
        .reduce("", String::concat);
    return joined.isBlank() ? Optional.empty() : Optional.of(joined);
  }

  private static String callKey(ToolContext ctx) {
    return ctx.invocationId() + ":" + ctx.functionCallId().orElse("?");
  }

  private void record(String sessionId, String invocationId, String agent, String kind, String name,
      Object args, Object result, Long latencyMs, String outcome) {
    try {
      audit.append(new AuditRepository.Entry(sessionId, invocationId, agent, kind, name,
          args == null ? null : JSON.writeValueAsString(args),
          result == null ? null : (result instanceof String s ? s : JSON.writeValueAsString(result)),
          latencyMs, outcome));
    } catch (Exception e) {
      // Observability must never break the conversation.
      log.warn("audit write failed: {}", e.toString());
    }
  }
}
