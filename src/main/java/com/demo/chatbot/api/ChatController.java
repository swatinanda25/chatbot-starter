package com.demo.chatbot.api;

import com.demo.chatbot.agents.AgentRegistry;
import com.demo.chatbot.domain.AuditRepository;
import com.demo.chatbot.domain.StoreRepository;
import com.demo.chatbot.guardrails.GuardrailLog;
import com.demo.chatbot.observability.ObservabilitySummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {

  private static final Logger log = LoggerFactory.getLogger(ChatController.class);
  private static final ObjectMapper JSON = JsonBaseModel.getMapper();

  private final ChatService chat;
  private final ChatEventMapper mapper;
  private final AgentRegistry registry;
  private final GuardrailLog guardrails;
  private final AuditRepository audit;
  private final StoreRepository store;
  private final MeterRegistry metrics;
  private final ObservabilitySummary summary;
  private final AuditPresenter auditPresenter;

  public ChatController(ChatService chat, ChatEventMapper mapper, AgentRegistry registry, GuardrailLog guardrails,
      AuditRepository audit, StoreRepository store, MeterRegistry metrics, ObservabilitySummary summary,
      AuditPresenter auditPresenter) {
    this.chat = chat;
    this.mapper = mapper;
    this.registry = registry;
    this.guardrails = guardrails;
    this.audit = audit;
    this.store = store;
    this.metrics = metrics;
    this.summary = summary;
    this.auditPresenter = auditPresenter;
  }

  public record ChatRequest(String sessionId, String message, Confirmation confirmation) {}

  public record Confirmation(String id, boolean confirmed) {}

  public record SessionRequest(String agent) {}

  public record Feedback(String sessionId, String traceId, int score, String comment) {}

  /** The agents a session can talk to, and the one used when a session doesn't pick. */
  @GetMapping("/agents")
  public Map<String, Object> agents() {
    return Map.of("agents", registry.agents(), "default", registry.defaultAgent());
  }

  /** Starts a session with the agent named in the body, or the default agent. */
  @PostMapping("/sessions")
  public Map<String, Object> createSession(@RequestBody(required = false) SessionRequest req) {
    String agent = req == null || req.agent() == null ? registry.defaultAgent() : req.agent();
    if (!registry.exists(agent)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown agent: " + agent);
    }
    return Map.of(
        "sessionId", chat.createSession(agent, Map.of()),
        "customerId", chat.customerId(),
        "agent", agent);
  }

  /** One user turn (a message or a confirmation answer), streamed back as server-sent events. */
  @PostMapping("/chat")
  public SseEmitter chat(@RequestBody ChatRequest req) {
    if (req.sessionId() == null || !chat.sessionExists(req.sessionId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown session");
    }
    Flowable<Event> events;
    if (req.confirmation() != null) {
      try {
        events = chat.answerConfirmation(req.sessionId(), req.confirmation().id(), req.confirmation().confirmed());
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
      }
    } else if (req.message() != null && !req.message().isBlank()) {
      events = chat.sendMessage(req.sessionId(), req.message());
    } else {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send a message or a confirmation");
    }

    SseEmitter emitter = new SseEmitter(300_000L); // room for a 3-step pipeline on a slow local model
    long start = System.currentTimeMillis();
    // Token usage summed over every model call in the turn (one per step in a pipeline).
    AtomicReference<ChatEventMapper.Tokens> turnTokens = new AtomicReference<>(new ChatEventMapper.Tokens(0, 0, 0));
    AtomicReference<String> invocationId = new AtomicReference<>();
    events.subscribeOn(Schedulers.io()).subscribe(
        event -> {
          if (invocationId.compareAndSet(null, event.invocationId())) {
            send(emitter, Map.of("type", "turn", "trace_id", chat.traceIdFor(event)));
          }
          mapper.tokens(event).ifPresent(t -> turnTokens.updateAndGet(t::plus));
          for (Map<String, Object> ui : mapper.map(event, chat.customerId())) {
            send(emitter, ui);
          }
        },
        error -> {
          log.warn("turn failed: {}", error.toString());
          sendGuardrails(emitter, invocationId.get());
          send(emitter, Map.of("type", "error", "message", "Something went wrong. Please try again."));
          emitter.complete();
        },
        () -> {
          sendGuardrails(emitter, invocationId.get());
          send(emitter, Map.of(
              "type", "done",
              "latency_ms", System.currentTimeMillis() - start,
              "tokens", turnTokens.get().toMap()));
          emitter.complete();
        });
    return emitter;
  }

  /** Every tool call, guardrail decision and model error in a session, oldest first. */
  @GetMapping("/sessions/{sessionId}/audit")
  public List<Map<String, Object>> audit(@PathVariable String sessionId) {
    return audit.forSession(sessionId);
  }

  /** The same audit trail, presented: grouped by turn, each row summarized in one line. */
  @GetMapping("/sessions/{sessionId}/audit/view")
  public List<AuditPresenter.Turn> auditView(@PathVariable String sessionId) {
    return auditPresenter.present(audit.forSession(sessionId), chat.turnLabels(sessionId));
  }

  /** Thumbs up/down. Stub: counted and audited here; production also posts it as a Langfuse score on the trace. */
  @PostMapping("/feedback")
  public Map<String, Object> feedback(@RequestBody Feedback fb) throws IOException {
    String vote = fb.score() > 0 ? "up" : "down";
    metrics.counter("chatbot.feedback", "score", vote).increment();
    String detail = JSON.writeValueAsString(Map.of(
        "trace_id", String.valueOf(fb.traceId()), "score", fb.score(), "comment", String.valueOf(fb.comment())));
    audit.append(new AuditRepository.Entry(fb.sessionId(), null, "user", "feedback", "thumbs", null, detail, null, vote));
    return Map.of("ok", true);
  }

  /** Headline numbers for the UI's "Under the hood" panel: latency percentiles, facts, feedback, tokens, guardrails. */
  @GetMapping("/observability/summary")
  public Map<String, Object> observabilitySummary() {
    return summary.summary();
  }

  /** Demo only: restore the seed data between runs. */
  @PostMapping("/admin/reset")
  public Map<String, Object> reset() {
    store.resetDemoData();
    return Map.of("ok", true);
  }

  /** One "guardrail" event per check that fired during the turn. */
  private void sendGuardrails(SseEmitter emitter, String invocationId) {
    for (GuardrailLog.Trigger t : guardrails.drain(invocationId)) {
      send(emitter, t.toMap());
    }
  }

  private static void send(SseEmitter emitter, Map<String, Object> payload) {
    try {
      emitter.send(SseEmitter.event().name("chat").data(JSON.writeValueAsString(payload)));
    } catch (IOException | IllegalStateException e) {
      // Client went away; the turn still completes server-side and is fully audited.
      log.debug("SSE send failed: {}", e.toString());
    }
  }
}
