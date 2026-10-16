package com.demo.chatbot.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HistoryLimitPluginTest {

  private static Content user(String text) {
    return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
  }

  private static Content model(String text) {
    return Content.builder().role("model").parts(List.of(Part.fromText(text))).build();
  }

  private static Content toolCall() {
    return Content.builder().role("model").parts(List.of(Part.builder()
        .functionCall(FunctionCall.builder().name("get_order").args(Map.of("order_id", "1002")).build()).build())).build();
  }

  private static Content toolResult() {
    return Content.builder().role("user").parts(List.of(Part.builder()
        .functionResponse(FunctionResponse.builder().name("get_order").response(Map.of("ok", true)).build()).build())).build();
  }

  @Test
  void keepsTheLastNCustomerTurnsAndTheirToolCalls() {
    List<Content> history = List.of(
        user("turn 1"), model("reply 1"),
        user("turn 2"), toolCall(), toolResult(), model("reply 2"),
        user("turn 3"), toolCall(), toolResult(), model("reply 3"),
        user("turn 4"));

    List<Content> kept = HistoryLimitPlugin.trim(history, 2);

    assertThat(kept).hasSize(5);
    assertThat(kept.get(0).parts().get().get(0).text()).contains("turn 3");
    assertThat(HistoryLimitPlugin.customerTurns(kept)).isEqualTo(2);
  }

  @Test
  void relayedAgentOutputAndToolResultsAreNotTurnBoundaries() {
    List<Content> history = List.of(
        user("turn 1"),
        user("For context: below is a transcript of what another agent did ... [triage_agent] said: intent: other"),
        toolResult(),
        user("turn 2"));

    assertThat(HistoryLimitPlugin.customerTurns(history)).isEqualTo(2);
    assertThat(HistoryLimitPlugin.trim(history, 1)).containsExactly(history.get(3));
  }

  @Test
  void shortConversationsAreUntouched() {
    List<Content> history = List.of(user("turn 1"), model("reply"), user("turn 2"));
    assertThat(HistoryLimitPlugin.trim(history, 6)).isSameAs(history);
  }
}
