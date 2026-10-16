package com.demo.chatbot.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditPresenterTest {

  private final AuditPresenter presenter = new AuditPresenter();

  private static Map<String, Object> row(String inv, String ts, String agent, String kind, String name,
      String args, String result, Long ms, String outcome) {
    Map<String, Object> r = new HashMap<>();
    r.put("invocation_id", inv);
    r.put("ts", ts);
    r.put("agent", agent);
    r.put("kind", kind);
    r.put("name", name);
    r.put("args_json", args);
    r.put("result_json", result);
    r.put("latency_ms", ms);
    r.put("outcome", outcome);
    return r;
  }

  @Test
  void groupsByTurnAndSummarizesEachRow() {
    List<Map<String, Object>> rows = List.of(
        row("inv-1", "2026-10-12T16:16:22Z", "support_router", "tool", "transfer_to_agent",
            "{\"agent_name\":\"order_agent\"}", "{}", 0L, "ok"),
        row("inv-1", "2026-10-12T16:16:23Z", "order_agent", "tool", "get_order", "{\"order_id\":\"1002\"}",
            "{\"ok\":true,\"data\":{\"order_id\":\"1002\",\"status\":\"SHIPPED\",\"total\":\"$189.00\"}}", 5L, "ok"),
        row("inv-1", "2026-10-12T16:16:24Z", "order_agent", "fact_check", "reply",
            "[\"order:1002\",\"status:shipped\"]", "all facts found", null, "supported"),
        row("inv-2", "2026-10-12T16:17:00Z", "order_agent", "guardrail", "cancel_precheck", null,
            "order 1002: NOT_CANCELLABLE", null, "blocked"),
        row(null, "2026-10-12T16:18:00Z", "user", "feedback", "thumbs", null, "{}", null, "up"));

    List<AuditPresenter.Turn> turns = presenter.present(rows, Map.of("inv-1", "“Where is my order 1002?”"));

    assertThat(turns).extracting(AuditPresenter.Turn::label)
        .containsExactly("“Where is my order 1002?”", "Turn (Cancel refused before asking the customer: order 1002: NOT_CANCELLABLE)", "Feedback");
    assertThat(turns.get(0).rows()).extracting(AuditPresenter.Row::summary).containsExactly(
        "Handed over to order_agent",
        "get_order 1002 → SHIPPED, $189.00",
        "Reply's facts all found in tool results or the customer's words: order 1002, status shipped");
    assertThat(turns.get(1).rows().get(0).severity()).isEqualTo(AuditPresenter.Severity.guard);
    assertThat(turns.get(2).rows().get(0).summary()).isEqualTo("Customer marked a reply helpful");
  }

  @Test
  void flagsUnsupportedFactsAndErrors() {
    AuditPresenter.Row fact = presenter.row(row("i", "2026-10-12T10:00:00Z", "assistant", "fact_check", "reply",
        "[\"order:1002\",\"status:shipped\",\"money:18900\"]", "not found: status:shipped, money:18900", null, "unsupported"));
    assertThat(fact.severity()).isEqualTo(AuditPresenter.Severity.bad);
    assertThat(fact.summary()).startsWith("Reply stated facts no tool returned: status shipped, $189.00");

    AuditPresenter.Row notFound = presenter.row(row("i", "2026-10-12T10:00:00Z", "order_agent", "tool", "get_order",
        "{\"order_id\":\"1006\"}", "{\"ok\":false,\"error_code\":\"NOT_FOUND\",\"message\":\"No order 1006 on this customer's account.\"}",
        3L, "not_found"));
    assertThat(notFound.summary()).isEqualTo("get_order 1006 → NOT_FOUND (No order 1006 on this customer's account.)");
  }

  @Test
  void describesTheConfirmationFlow() {
    AuditPresenter.Row waiting = presenter.row(row("i", "2026-10-12T10:00:00Z", "order_agent", "tool", "cancel_order",
        "{\"order_id\":\"1004\"}", "{\"error\":\"This tool call requires confirmation, please approve or reject.\"}", 1L,
        "awaiting_confirmation"));
    assertThat(waiting.summary()).isEqualTo("cancel_order 1004 → waiting for the customer to confirm");
    assertThat(waiting.severity()).isEqualTo(AuditPresenter.Severity.guard);
  }
}
