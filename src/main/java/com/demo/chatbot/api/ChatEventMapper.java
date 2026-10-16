package com.demo.chatbot.api;

import com.demo.chatbot.agents.AgentRegistry;
import com.demo.chatbot.tools.ToolRisk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns ADK events into small UI events:
 *
 * <ul>
 *   <li>"message": an agent's reply to the customer;
 *   <li>"step": intermediate output of a pipeline step, shown as a trace row rather than an answer;
 *   <li>"transfer", "tool_call", "tool_result": what the agents did, for the trace;
 *   <li>"confirmation": a CONFIRM-tier tool waiting for the customer's approval.
 * </ul>
 *
 * Model output carries the token usage of the call that produced it, when the model reports it.
 */
@Component
public class ChatEventMapper {

  static final String CONFIRMATION_CALL = "adk_request_confirmation";
  private static final String TRANSFER_CALL = "transfer_to_agent";
  private static final ObjectMapper JSON = JsonBaseModel.getMapper();

  private final AgentRegistry registry;
  private final ConfirmationPresenter presenter;

  public ChatEventMapper(AgentRegistry registry, ConfirmationPresenter presenter) {
    this.registry = registry;
    this.presenter = presenter;
  }

  public List<Map<String, Object>> map(Event event, String customerId) {
    List<Map<String, Object>> out = new ArrayList<>();
    String author = event.author();
    Optional<Tokens> tokens = tokens(event);
    boolean tokensShown = false;
    List<Part> parts = event.content().flatMap(Content::parts).orElse(List.of());
    for (Part part : parts) {
      Map<String, Object> ui = null;
      if (part.text().isPresent() && !part.thought().orElse(false) && !"user".equals(author)
          && !event.partial().orElse(false) && !part.text().get().isBlank()) {
        String type = registry.isInternal(author) ? "step" : "message";
        ui = ui(type, Map.of("author", author, "text", part.text().get()));
      } else if (part.functionCall().isPresent()) {
        ui = functionCall(author, part.functionCall().get(), customerId);
      } else if (part.functionResponse().isPresent()) {
        String name = part.functionResponse().get().name().orElse("");
        if (!CONFIRMATION_CALL.equals(name) && !TRANSFER_CALL.equals(name)) {
          ui = functionResponse(author, part.functionResponse().get());
        }
      }
      if (ui == null) {
        continue;
      }
      // A model response's usage goes on the first thing it produced (a reply, step or tool call).
      if (tokens.isPresent() && !tokensShown && !"tool_result".equals(ui.get("type"))) {
        ui.put("tokens", tokens.get().toMap());
        tokensShown = true;
      }
      out.add(ui);
    }
    return out;
  }

  /** Token usage of one model call: prompt (input), response (output) and total. */
  public record Tokens(int input, int output, int total) {
    public Tokens plus(Tokens o) {
      return new Tokens(input + o.input, output + o.output, total + o.total);
    }

    public Map<String, Object> toMap() {
      return Map.of("input", input, "output", output, "total", total);
    }
  }

  /** Usage reported for a completed model response; partial (streamed) chunks are skipped. */
  public Optional<Tokens> tokens(Event event) {
    if (event.partial().orElse(false)) {
      return Optional.empty();
    }
    return event.usageMetadata().map(u -> {
      int in = u.promptTokenCount().orElse(0);
      int out = u.candidatesTokenCount().orElse(0);
      return new Tokens(in, out, u.totalTokenCount().orElse(in + out));
    });
  }

  private Map<String, Object> functionCall(String author, FunctionCall fc, String customerId) {
    String name = fc.name().orElse("");
    Map<String, Object> args = fc.args().orElse(Map.of());
    if (TRANSFER_CALL.equals(name)) {
      return ui("transfer", Map.of("from", author, "to", String.valueOf(args.get("agent_name"))));
    }
    if (CONFIRMATION_CALL.equals(name)) {
      FunctionCall original = toFunctionCall(args.get("originalFunctionCall"));
      String tool = original.name().orElse("");
      Map<String, Object> toolArgs = original.args().orElse(Map.of());
      return ui("confirmation", Map.of(
          "id", fc.id().orElse(""),
          "tool", tool,
          "args", toolArgs,
          "summary", presenter.summarize(customerId, tool, toolArgs)));
    }
    return ui("tool_call", Map.of("author", author, "name", name, "args", args, "risk", ToolRisk.of(name).name()));
  }

  private Map<String, Object> functionResponse(String author, FunctionResponse fr) {
    Map<String, Object> response = fr.response().orElse(Map.of());
    String error = String.valueOf(response.get("error"));
    // ADK's own placeholders for the confirmation flow are steps, not failures.
    Object outcome = Boolean.TRUE.equals(response.get("ok")) ? "ok"
        : error.contains("requires confirmation") ? "awaiting_confirmation"
        : error.contains("rejected") ? "rejected_by_user"
        : response.getOrDefault("error_code", response.getOrDefault("error", "error"));
    return ui("tool_result", Map.of(
        "author", author, "name", fr.name().orElse(""), "outcome", String.valueOf(outcome), "response", response));
  }

  private static FunctionCall toFunctionCall(Object value) {
    return value instanceof FunctionCall fc ? fc : JSON.convertValue(value, FunctionCall.class);
  }

  private static Map<String, Object> ui(String type, Map<String, Object> fields) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    m.putAll(fields);
    return m;
  }
}
