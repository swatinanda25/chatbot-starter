package com.demo.chatbot.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only log of every tool call, guardrail decision and model error, per session. */
@Repository
public class AuditRepository {

  private final JdbcClient jdbc;

  public AuditRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Entry(
      String sessionId,
      String invocationId,
      String agent,
      String kind,
      String name,
      String argsJson,
      String resultJson,
      Long latencyMs,
      String outcome) {}

  public void append(Entry e) {
    jdbc.sql(
            "INSERT INTO audit_log (ts, session_id, invocation_id, agent, kind, name, args_json,"
                + " result_json, latency_ms, outcome) VALUES (:ts, :sessionId, :invocationId, :agent,"
                + " :kind, :name, :args, :result, :latency, :outcome)")
        .param("ts", Instant.now().toString())
        .param("sessionId", e.sessionId())
        .param("invocationId", e.invocationId())
        .param("agent", e.agent())
        .param("kind", e.kind())
        .param("name", e.name())
        .param("args", e.argsJson())
        .param("result", e.resultJson())
        .param("latency", e.latencyMs())
        .param("outcome", e.outcome())
        .update();
  }

  public List<Map<String, Object>> forSession(String sessionId) {
    return jdbc.sql(
            "SELECT ts, invocation_id, agent, kind, name, args_json, result_json, latency_ms, outcome"
                + " FROM audit_log WHERE session_id = :sessionId ORDER BY id")
        .param("sessionId", sessionId)
        .query()
        .listOfRows();
  }
}
