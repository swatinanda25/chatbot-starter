package com.demo.chatbot.guardrails;

import com.demo.chatbot.domain.AuditRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Guardrail triggers per turn (ADK invocation). Each trigger is logged, counted in the
 * {@code chatbot.guardrail.triggered} metric, written to audit_log, and held until the chat API
 * drains it into the turn's event stream.
 */
@Component
public class GuardrailLog {

  private static final Logger log = LoggerFactory.getLogger(GuardrailLog.class);

  public record Trigger(String name, String outcome, String detail) {
    public Map<String, Object> toMap() {
      return Map.of("type", "guardrail", "name", name, "outcome", outcome, "detail", detail);
    }
  }

  private final Map<String, List<Trigger>> byInvocation = new ConcurrentHashMap<>();
  private final MeterRegistry metrics;
  private final AuditRepository audit;

  public GuardrailLog(MeterRegistry metrics, AuditRepository audit) {
    this.metrics = metrics;
    this.audit = audit;
  }

  /** Records a trigger once per turn: a check that fires on every pipeline step counts once. */
  public void record(String sessionId, String invocationId, String name, String outcome, String detail) {
    List<Trigger> list = byInvocation.computeIfAbsent(invocationId, k -> new ArrayList<>());
    synchronized (list) {
      if (list.stream().anyMatch(t -> t.name().equals(name))) {
        return;
      }
      list.add(new Trigger(name, outcome, detail));
    }
    log.info("guardrail {} {} (invocation {}): {}", name, outcome, invocationId, detail);
    metrics.counter("chatbot.guardrail.triggered", "guardrail", name, "outcome", outcome).increment();
    try {
      audit.append(new AuditRepository.Entry(sessionId, invocationId, "guardrail", "guardrail", name, null, detail, null, outcome));
    } catch (Exception e) {
      // Auditing must never break the conversation.
      log.warn("audit write failed: {}", e.toString());
    }
  }

  public boolean fired(String invocationId, String name) {
    List<Trigger> list = byInvocation.get(invocationId);
    if (list == null) {
      return false;
    }
    synchronized (list) {
      return list.stream().anyMatch(t -> t.name().equals(name));
    }
  }

  /** Returns and forgets this turn's triggers. */
  public List<Trigger> drain(String invocationId) {
    List<Trigger> list = invocationId == null ? null : byInvocation.remove(invocationId);
    return list == null ? List.of() : List.copyOf(list);
  }
}
