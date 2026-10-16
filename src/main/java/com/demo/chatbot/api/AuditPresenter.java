package com.demo.chatbot.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns raw audit_log rows into something a person can read: grouped by turn (one ADK
 * invocation), each turn labelled with what the customer said, and each row summarized in one
 * line ("get_order 1002 → SHIPPED, $189.00") with a severity. The raw JSON stays on every row for
 * drilling down.
 *
 * <p>Like {@link ConfirmationPresenter}, the wording lives on the server, so every view (the UI
 * panel, a support-staff tool, curl) reads the same thing.
 */
@Component
public class AuditPresenter {

  private static final ObjectMapper JSON = JsonBaseModel.getMapper();
  private static final String FEEDBACK_GROUP = "feedback";

  /** Severity: ok (normal), guard (a safety check acted or the flow paused for the customer), bad (an error or an unsupported fact). */
  public enum Severity { ok, guard, bad }

  public record Row(String time, String agent, String kind, String name, String outcome, Long latencyMs,
      String icon, String summary, Severity severity, String argsJson, String resultJson) {}

  public record Turn(String invocationId, String startedAt, String label, List<Row> rows) {}

  private static final Map<String, String> GUARDRAIL_LABELS = Map.ofEntries(
      Map.entry("pii_redaction", "Personal data removed from the message"),
      Map.entry("output_pii", "Personal data removed from the reply"),
      Map.entry("message_too_long", "Message over the size limit"),
      Map.entry("gateway_block", "Blocked by the gateway"),
      Map.entry("model_unavailable", "Model unavailable, friendly fallback sent"),
      Map.entry("cancel_precheck", "Cancel refused before asking the customer"),
      Map.entry("one_destructive_action_per_turn", "Second destructive action in one turn blocked"),
      Map.entry("triage_normalized", "Triage output repaired"),
      Map.entry("grounding", "Invented order fact replaced"),
      Map.entry("prompt_leak", "Reply quoting the instructions replaced"),
      Map.entry("ticket_reference", "Missing ticket id added to the reply"),
      Map.entry("tool_error", "Tool failure turned into an error result"),
      Map.entry("indirect_injection", "Instruction-like feedback withheld from the models"),
      Map.entry("classifier_output_invalid", "Sentiment classifier gave no valid label for some comments"));

  /**
   * @param rows audit_log rows for one session, oldest first (AuditRepository.forSession)
   * @param turnLabels what started each turn, by invocation id: the customer's message, or their
   *     answer to a confirmation card. Turns without a label are named by their first row.
   */
  public List<Turn> present(List<Map<String, Object>> rows, Map<String, String> turnLabels) {
    Map<String, List<Map<String, Object>>> byTurn = new LinkedHashMap<>();
    for (Map<String, Object> r : rows) {
      Object inv = r.get("invocation_id");
      byTurn.computeIfAbsent(inv == null ? FEEDBACK_GROUP : inv.toString(), k -> new ArrayList<>()).add(r);
    }
    List<Turn> turns = new ArrayList<>();
    byTurn.forEach((inv, group) -> {
      List<Row> presented = group.stream().map(this::row).toList();
      String label = FEEDBACK_GROUP.equals(inv) ? "Feedback"
          : turnLabels.getOrDefault(inv, "Turn (" + presented.get(0).summary() + ")");
      turns.add(new Turn(FEEDBACK_GROUP.equals(inv) ? null : inv, str(group.get(0).get("ts")), label, presented));
    });
    // Feedback last; turns in the order they started.
    turns.sort((a, b) -> a.invocationId() == null ? 1 : b.invocationId() == null ? -1 : a.startedAt().compareTo(b.startedAt()));
    return turns;
  }

  Row row(Map<String, Object> r) {
    String kind = str(r.get("kind"));
    String name = str(r.get("name"));
    String outcome = str(r.get("outcome"));
    Map<String, Object> args = parseMap(r.get("args_json"));
    Map<String, Object> result = parseMap(r.get("result_json"));
    String summary;
    String icon;
    Severity severity;
    switch (kind) {
      case "tool" -> {
        icon = "transfer_to_agent".equals(name) ? "↪" : "🔧";
        summary = toolSummary(name, outcome, args, result);
        severity = switch (outcome) {
          case "ok" -> Severity.ok;
          case "awaiting_confirmation", "rejected_by_user", "one_at_a_time", "not_cancellable",
              "already_cancelled", "not_found", "ambiguous_item", "item_not_found" -> Severity.guard;
          default -> Severity.bad;
        };
      }
      case "guardrail" -> {
        icon = "🛡";
        String detail = str(r.get("result_json"));
        summary = GUARDRAIL_LABELS.getOrDefault(name, name) + (detail.isBlank() ? "" : ": " + detail);
        severity = Severity.guard;
      }
      case "fact_check" -> {
        icon = "🔎";
        summary = factSummary(r, outcome);
        severity = "unsupported".equals(outcome) ? Severity.bad : Severity.ok;
      }
      case "classifier" -> {
        icon = "🏷";
        summary = classifierSummary(args, result, outcome, str(r.get("result_json")));
        severity = "ok".equals(outcome) ? Severity.ok : "partial".equals(outcome) ? Severity.guard : Severity.bad;
      }
      case "model_error" -> {
        icon = "⚠";
        summary = "Model call failed: " + abbreviate(str(r.get("result_json")), 120);
        severity = Severity.bad;
      }
      case "feedback" -> {
        icon = "up".equals(outcome) ? "👍" : "👎";
        summary = "up".equals(outcome) ? "Customer marked a reply helpful" : "Customer marked a reply not helpful";
        severity = "up".equals(outcome) ? Severity.ok : Severity.guard;
      }
      default -> {
        icon = "•";
        summary = name + " · " + outcome;
        severity = Severity.ok;
      }
    }
    String ts = str(r.get("ts"));
    Long latency = r.get("latency_ms") instanceof Number n ? n.longValue() : null;
    return new Row(ts.length() >= 19 ? ts.substring(11, 19) : ts, str(r.get("agent")), kind, name, outcome, latency,
        icon, summary, severity, (String) r.get("args_json"), (String) r.get("result_json"));
  }

  @SuppressWarnings("unchecked")
  private static String toolSummary(String name, String outcome, Map<String, Object> args, Map<String, Object> result) {
    Map<String, Object> data = result.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    String orderId = str(args.getOrDefault("order_id", data.get("order_id")));
    if (!"ok".equals(outcome)) {
      return switch (outcome) {
        case "awaiting_confirmation" -> name + " " + orderId + " → waiting for the customer to confirm";
        case "rejected_by_user" -> name + " " + orderId + " → the customer declined; nothing changed";
        default -> name + (orderId.isBlank() ? "" : " " + orderId) + " → " + outcome.toUpperCase()
            + (result.get("message") == null ? "" : " (" + abbreviate(str(result.get("message")), 90) + ")");
      };
    }
    return switch (name) {
      case "transfer_to_agent" -> "Handed over to " + str(args.get("agent_name"));
      case "get_order" -> "get_order " + orderId + " → " + str(data.get("status")) + ", " + str(data.get("total"));
      case "list_orders" -> {
        List<Map<String, Object>> orders = data.get("orders") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        String filter = str(args.get("status")).isBlank() ? "" : " (" + args.get("status") + ")";
        yield "list_orders" + filter + " → " + orders.size() + " orders"
            + (orders.isEmpty() ? "" : ": " + orders.stream().limit(4)
                .map(o -> "#" + o.get("order_id") + " " + o.get("status")).collect(Collectors.joining(", ")))
            + (orders.size() > 4 ? ", …" : "");
      }
      case "search_products" -> {
        List<Map<String, Object>> products = data.get("products") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        String budget = args.get("max_price") == null ? "" : " under $" + args.get("max_price");
        yield "search_products \"" + str(args.get("query")) + "\"" + budget + " → " + products.size() + " results"
            + (products.isEmpty() ? "" : ": " + products.stream().limit(3)
                .map(p -> p.get("name") + " " + p.get("price") + (Boolean.TRUE.equals(p.get("in_stock")) ? "" : " (out of stock)"))
                .collect(Collectors.joining(", ")))
            + (products.size() > 3 ? ", …" : "");
      }
      case "cancel_order" -> "Cancelled order " + orderId + "; refund " + str(data.get("total"));
      case "escalate_to_human" -> "Opened ticket " + str(data.get("ticket_id")) + ": " + abbreviate(str(args.get("summary")), 90);
      case "get_feedback_report" -> "get_feedback_report \"" + str(args.get("item")) + "\" → " + str(data.get("name"))
          + ": " + str(data.get("verdict")) + " (" + str(data.get("classified")) + " comments, " + str(data.get("positive_pct"))
          + " positive, " + str(data.get("negative_pct")) + " negative)";
      case "list_feedback_overview" -> {
        List<Map<String, Object>> items = data.get("items") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        yield "list_feedback_overview → " + items.size() + " products"
            + (items.isEmpty() ? "" : ": " + items.stream().limit(4)
                .map(i -> i.get("name") + " " + i.get("verdict")).collect(Collectors.joining(", ")))
            + (items.size() > 4 ? ", …" : "");
      }
      default -> name + " → ok";
    };
  }

  @SuppressWarnings("unchecked")
  private static String classifierSummary(Map<String, Object> args, Map<String, Object> labels, String outcome, String raw) {
    int sent = args.get("comment_ids") instanceof List<?> l ? l.size() : 0;
    if ("error".equals(outcome)) {
      return "Sentiment classifier failed on " + sent + " comments: " + abbreviate(raw, 100);
    }
    Map<Object, Long> counts = labels.values().stream().collect(Collectors.groupingBy(v -> v, Collectors.counting()));
    String byLabel = counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
    return "Sentiment classifier labeled " + labels.size() + " of " + sent + " comments" + (byLabel.isEmpty() ? "" : ": " + byLabel);
  }

  private static String factSummary(Map<String, Object> r, String outcome) {
    List<String> facts;
    try {
      facts = JSON.readValue(str(r.get("args_json")), new TypeReference<List<String>>() {});
    } catch (Exception e) {
      facts = List.of();
    }
    String checked = facts.stream().map(AuditPresenter::readableFact).collect(Collectors.joining(", "));
    if ("unsupported".equals(outcome)) {
      String missing = str(r.get("result_json")).replaceFirst("^not found: ", "");
      String readable = List.of(missing.split(",\\s*")).stream().map(AuditPresenter::readableFact).collect(Collectors.joining(", "));
      return "Reply stated facts no tool returned: " + readable + " (checked: " + checked + ")";
    }
    return "Reply's facts all found in tool results or the customer's words: " + checked;
  }

  /** "order:1002" → "order 1002", "money:18900" → "$189.00", "status:shipped" → "status shipped". */
  static String readableFact(String fact) {
    int colon = fact.indexOf(':');
    if (colon < 0) {
      return fact;
    }
    String type = fact.substring(0, colon);
    String value = fact.substring(colon + 1);
    if ("money".equals(type)) {
      try {
        long cents = Long.parseLong(value);
        return String.format("$%d.%02d", cents / 100, cents % 100);
      } catch (NumberFormatException e) {
        return value;
      }
    }
    if ("pct".equals(type)) {
      return value + "%";
    }
    return type + " " + value;
  }

  private static Map<String, Object> parseMap(Object json) {
    if (!(json instanceof String s) || !s.startsWith("{")) {
      return Map.of();
    }
    try {
      return JSON.readValue(s, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private static String str(Object o) {
    return Objects.toString(o, "");
  }

  private static String abbreviate(String s, int max) {
    String one = s.replaceAll("\\s+", " ").strip();
    return one.length() > max ? one.substring(0, max) + "…" : one;
  }
}
