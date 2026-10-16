package com.demo.chatbot.evals;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.chatbot.domain.ProductFeedback;
import com.demo.chatbot.domain.StoreRepository;
import com.demo.chatbot.service.LlmSentimentClassifier;
import com.demo.chatbot.service.MarketplaceService;
import com.demo.chatbot.service.Sentiment;
import com.demo.chatbot.service.ToolResult;
import com.demo.chatbot.service.TurnContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Evals for the LLM feedback classifier behind the my_marketplace agent: real gateway, real model.
 *
 * <ul>
 *   <li>Accuracy on a labeled set (clear, neutral, negation, sarcasm, mixed and adversarial
 *       comments) must reach {@link #MIN_ACCURACY}.
 *   <li>Hard gate: no positive/negative flips on the clear cases, whatever the accuracy.
 *   <li>End to end on the seed data, the verdicts the owner sees must be the expected ones.
 * </ul>
 *
 * Traces are named "eval:sentiment" in Langfuse.
 */
@Tag("eval")
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:./target/eval-store.db")
class SentimentEvalTest {

  private static final double MIN_ACCURACY = 0.85;

  @Autowired LlmSentimentClassifier classifier;
  @Autowired MarketplaceService marketplace;
  @Autowired StoreRepository store;

  record Case(String text, String label, String category) {}

  @Test
  void labeledSet() throws Exception {
    List<Case> cases;
    try (InputStream in = getClass().getResourceAsStream("/evals/sentiment.json")) {
      cases = new ObjectMapper().readValue(in, new TypeReference<>() {});
    }
    List<ProductFeedback> batch = new ArrayList<>();
    for (int i = 0; i < cases.size(); i++) {
      batch.add(new ProductFeedback(i + 1, "eval", "P000", "eval", null, cases.get(i).text(), "2026-10-01", null));
    }

    Map<Long, Sentiment> labels = classifier.classify(batch, TurnContext.offline("sentiment"));

    int correct = 0;
    List<String> flips = new ArrayList<>();
    System.out.println("\n=== Sentiment classifier ===");
    for (int i = 0; i < cases.size(); i++) {
      Case c = cases.get(i);
      Sentiment got = labels.get((long) i + 1);
      String gotLabel = got == null ? "unclassified" : got.label();
      boolean ok = c.label().equals(gotLabel);
      correct += ok ? 1 : 0;
      if ("clear".equals(c.category()) && !ok && !"neutral".equals(gotLabel) && !"neutral".equals(c.label())) {
        flips.add(c.text());
      }
      System.out.printf("%-4s %-12s expected %-8s got %-12s %s%n", ok ? "PASS" : "FAIL", c.category(), c.label(), gotLabel, c.text());
    }
    double accuracy = (double) correct / cases.size();
    System.out.printf("accuracy %d/%d (%.0f%%)%n", correct, cases.size(), accuracy * 100);

    assertThat(flips).as("clear positive/negative comments labeled the opposite way").isEmpty();
    assertThat(accuracy).as("accuracy").isGreaterThanOrEqualTo(MIN_ACCURACY);
  }

  @Test
  @SuppressWarnings("unchecked")
  void seedVerdictsEndToEnd() {
    store.resetDemoData();
    TurnContext ctx = TurnContext.offline("sentiment-verdicts");
    Map<String, String> expected = Map.of(
        "wireless earbuds", MarketplaceService.NEEDS_ATTENTION,
        "headlamp", MarketplaceService.DOING_WELL,
        "hammock", MarketplaceService.NOT_ENOUGH);
    expected.forEach((item, verdict) -> {
      Map<String, Object> result = marketplace.feedbackReport(item, ctx);
      assertThat(ToolResult.isOk(result)).as(item + ": " + result).isTrue();
      assertThat((Map<String, Object>) result.get("data")).as(item).containsEntry("verdict", verdict);
    });
  }
}
