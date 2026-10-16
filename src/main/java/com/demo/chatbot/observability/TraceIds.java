package com.demo.chatbot.observability;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One Langfuse trace id per invocation (user turn). Shared by {@link ObservabilityPlugin}, which
 * tags agent model calls with it, and by tools that make model calls of their own (the feedback
 * sentiment classifier), so those land in the same trace.
 */
@Component
public class TraceIds {

  private final Map<String, String> byInvocation = new ConcurrentHashMap<>();

  public String traceIdFor(String invocationId) {
    return byInvocation.computeIfAbsent(invocationId, k -> UUID.randomUUID().toString().replace("-", ""));
  }

  public void remove(String invocationId) {
    byInvocation.remove(invocationId);
  }
}
