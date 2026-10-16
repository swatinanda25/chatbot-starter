package com.demo.chatbot.service;

import java.util.UUID;

/**
 * Who and which turn a tool runs for, so model calls made inside a tool (the sentiment classifier)
 * join the turn's Langfuse trace, and their guardrail triggers and audit rows land in its session.
 *
 * @param agent the top-level agent the session talks to
 * @param evalCase the eval case id when the eval harness runs the turn, else null
 */
public record TurnContext(
    String sessionId, String invocationId, String traceId, String userId, String agent, String evalCase) {

  /** For calls outside a chat turn (evals, tests): a fresh invocation and trace of their own. */
  public static TurnContext offline(String name) {
    String id = UUID.randomUUID().toString().replace("-", "");
    return new TurnContext(null, "offline-" + id, id, "system", name, name);
  }
}
