package com.demo.chatbot.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The deterministic hallucination check, against realistic tool results. */
class FactCheckTest {

  private static final String GET_ORDER_1002 = """
      {"ok":true,"data":{"order_id":"1002","status":"SHIPPED","placed_at":"2026-09-25T16:40:00Z",\
      "total":"$189.00","cancellable":false}}""";

  @Test
  void extractsNormalizedFacts() {
    assertThat(FactCheck.facts("Order #1002 has shipped. The total was $189, ticket HO-3."))
        .containsExactlyInAnyOrder("order:1002", "status:shipped", "money:18900", "ticket:HO-3");
  }

  @Test
  void factsFromToolResultsAreSupported() {
    FactCheck.Result r = FactCheck.check(
        "Your order 1002 is shipped. It was placed on September 25, 2026 and the total was $189.00.",
        List.of(GET_ORDER_1002));
    assertThat(r.supported()).isTrue();
    assertThat(r.facts()).contains("order:1002", "money:18900", "status:shipped");
  }

  @Test
  void inventedFactsAreUnsupported() {
    FactCheck.Result r = FactCheck.check("Order 1002 was delivered yesterday; you paid $199.00.", List.of(GET_ORDER_1002));
    assertThat(r.unsupported()).containsExactlyInAnyOrder("status:delivered", "money:19900");
  }

  @Test
  void theCustomersOwnWordsCountAsEvidence() {
    FactCheck.Result r = FactCheck.check("Which order do you mean, 1003 or 1004?", List.of("Cancel 1003 or 1004, not sure"));
    assertThat(r.supported()).isTrue();
  }

  @Test
  void withoutToolsEveryOrderFactIsUnsupported() {
    FactCheck.Result r = FactCheck.check("Your order 1002 has shipped and costs $189.", List.of("Where is my order 1002?"));
    assertThat(r.unsupported()).containsExactlyInAnyOrder("status:shipped", "money:18900");
  }

  @Test
  void verbsAndListsAreReadCorrectly() {
    assertThat(FactCheck.facts("It was placed on September 25.")).doesNotContain("status:placed");
    assertThat(FactCheck.facts("#1003 - Daypack 20L - PLACED")).contains("order:1003", "status:placed");
    assertThat(FactCheck.facts("Order 1004 has been cancelled.")).contains("status:cancelled");
    assertThat(FactCheck.facts("We have 1,250 items and pay $12.34")).doesNotContain("order:1250").contains("money:1234");
  }

  private static final String EARBUDS_REPORT = """
      {"ok":true,"data":{"item_id":"P108","name":"Wireless Earbuds","classified":5,"positive":1,\
      "negative":4,"positive_pct":"20%","negative_pct":"80%","verdict":"needs attention",\
      "verdict_reason":"4 of 5 comments are negative (80%), at or above the 40% needs-attention threshold."}}""";

  @Test
  void feedbackReportFactsFromTheToolAreSupported() {
    FactCheck.Result r = FactCheck.check(
        "**Wireless Earbuds (P108): needs attention**. 80% of 5 comments are negative, 20% positive.",
        List.of(EARBUDS_REPORT));
    assertThat(r.facts()).contains("item:P108", "verdict:needs attention", "pct:80", "pct:20");
    assertThat(r.supported()).isTrue();
  }

  @Test
  void inventedVerdictsAndPercentagesAreUnsupported() {
    FactCheck.Result r = FactCheck.check("**Wireless Earbuds: doing well** with 90% positive feedback.",
        List.of(EARBUDS_REPORT));
    assertThat(r.unsupported()).containsExactlyInAnyOrder("verdict:doing well", "pct:90");
  }

  @Test
  void smallTalkIsNotAVerdict() {
    assertThat(FactCheck.facts("Glad you're doing well! It's a mixed bag out there.")).isEmpty();
  }

  @Test
  void repliesWithoutFactsAreNotJudged() {
    FactCheck.Result r = FactCheck.check("Happy to help! What can I do for you?", List.of());
    assertThat(r.hasFacts()).isFalse();
  }
}
