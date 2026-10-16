package com.demo.chatbot.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.chatbot.domain.ProductFeedback;
import com.demo.chatbot.domain.StoreRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Aggregation, verdicts, privacy and the injection screen over the seed data. The LLM classifier
 * is replaced by a keyword fake, so this is fast and deterministic; the real classifier's accuracy
 * is measured by SentimentEvalTest.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:./target/test-store.db")
class MarketplaceServiceTest {

  /** Labels the seed comments the way they're written to read. Records what it was sent. */
  static class FakeClassifier implements SentimentClassifier {
    final List<ProductFeedback> seen = new ArrayList<>();
    boolean fail;

    @Override
    public Map<Long, Sentiment> classify(List<ProductFeedback> batch, TurnContext ctx) {
      if (fail) {
        throw new IllegalStateException("model down");
      }
      seen.addAll(batch);
      Map<Long, Sentiment> out = new HashMap<>();
      for (ProductFeedback f : batch) {
        String t = f.feedback().toLowerCase();
        Sentiment s = List.of("dies", "dropping", "stopped", "uncomfortable", "peeling", "broke", "soaked")
            .stream().anyMatch(t::contains) ? Sentiment.NEGATIVE
            : t.contains("plain box") || t.contains("two pockets") ? Sentiment.NEUTRAL
            : Sentiment.POSITIVE;
        out.put(f.id(), s);
      }
      return out;
    }
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    FakeClassifier fakeClassifier() {
      return new FakeClassifier();
    }
  }

  private static final TurnContext CTX = TurnContext.offline("test");

  @Autowired MarketplaceService marketplace;
  @Autowired StoreRepository store;
  @Autowired FakeClassifier classifier;

  @BeforeEach
  void reset() {
    store.resetDemoData();
    classifier.seen.clear();
    classifier.fail = false;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> data(Map<String, Object> result) {
    assertThat(ToolResult.isOk(result)).as(String.valueOf(result)).isTrue();
    return (Map<String, Object>) result.get("data");
  }

  @Test
  void earbudsNeedAttentionAndTheInjectedCommentIsWithheld() {
    Map<String, Object> d = data(marketplace.feedbackReport("wireless earbuds", CTX));
    assertThat(d).containsEntry("item_id", "P108")
        .containsEntry("verdict", MarketplaceService.NEEDS_ATTENTION)
        .containsEntry("negative", 4)
        .containsEntry("positive", 1)
        .containsEntry("withheld", 1)
        .containsEntry("negative_pct", "80%");
    assertThat(d.get("comments").toString()).contains(MarketplaceService.WITHHELD).doesNotContain("Ignore previous");
    assertThat(classifier.seen).noneMatch(f -> f.feedback().contains("Ignore previous"));
  }

  @Test
  void verdictsForTheSeededProducts() {
    assertThat(data(marketplace.feedbackReport("headlamp", CTX))).containsEntry("verdict", MarketplaceService.DOING_WELL);
    assertThat(data(marketplace.feedbackReport("P100", CTX))).containsEntry("verdict", MarketplaceService.DOING_WELL);
    assertThat(data(marketplace.feedbackReport("shell jacket", CTX))).containsEntry("verdict", MarketplaceService.MIXED);
    assertThat(data(marketplace.feedbackReport("rain jacket", CTX))).containsEntry("verdict", MarketplaceService.MIXED);
    assertThat(data(marketplace.feedbackReport("hammock", CTX))).containsEntry("verdict", MarketplaceService.NOT_ENOUGH);
  }

  @Test
  void prefersTheMatchingProductThatHasFeedback() {
    // Blue (P100) has feedback, red (P101) has none.
    assertThat(data(marketplace.feedbackReport("trail running shoe", CTX))).containsEntry("item_id", "P100");
  }

  @Test
  void ambiguousAndUnknownItems() {
    assertThat(marketplace.feedbackReport("jacket", CTX)).containsEntry("error_code", "AMBIGUOUS_ITEM")
        .containsKey("candidates");
    assertThat(marketplace.feedbackReport("kayak", CTX)).containsEntry("error_code", "ITEM_NOT_FOUND");
    assertThat(marketplace.feedbackReport(" ", CTX)).containsEntry("error_code", "INVALID_ITEM");
  }

  @Test
  void labelsAreStoredSoCommentsAreClassifiedOnce() {
    marketplace.feedbackReport("earbuds", CTX);
    int first = classifier.seen.size();
    marketplace.feedbackReport("earbuds", CTX);
    assertThat(first).isEqualTo(5);
    assertThat(classifier.seen).hasSize(first);
  }

  @Test
  void neverRevealsWhoWroteFeedback() throws Exception {
    ObjectMapper json = new ObjectMapper();
    String report = json.writeValueAsString(marketplace.feedbackReport("earbuds", CTX));
    String overview = json.writeValueAsString(marketplace.feedbackOverview(CTX));
    for (String out : List.of(report, overview)) {
      assertThat(out).doesNotContainPattern("C0\\d\\d").doesNotContain("@example.com").doesNotContain("user_id");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void overviewListsWorstFirstAndUnreviewedProducts() {
    Map<String, Object> d = data(marketplace.feedbackOverview(CTX));
    List<Map<String, Object>> items = (List<Map<String, Object>>) d.get("items");
    assertThat(items.get(0)).containsEntry("item_id", "P108").containsEntry("verdict", MarketplaceService.NEEDS_ATTENTION);
    assertThat(items.get(items.size() - 1)).containsEntry("verdict", MarketplaceService.NOT_ENOUGH);
    assertThat((List<String>) d.get("items_without_feedback")).anyMatch(s -> s.startsWith("Daypack 20L"));
  }

  @Test
  void classifierOutageIsAnErrorTheAgentCanExplain() {
    classifier.fail = true;
    assertThat(marketplace.feedbackReport("earbuds", CTX)).containsEntry("error_code", "ANALYSIS_UNAVAILABLE");
    assertThat(marketplace.feedbackOverview(CTX)).containsEntry("error_code", "ANALYSIS_UNAVAILABLE");
  }

  @Test
  void verdictThresholds() {
    assertThat(MarketplaceService.verdict(new MarketplaceService.Stats(2, 0, 0, 0, 0))).isEqualTo(MarketplaceService.NOT_ENOUGH);
    assertThat(MarketplaceService.verdict(new MarketplaceService.Stats(3, 0, 0, 5, 5))).isEqualTo(MarketplaceService.DOING_WELL);
    assertThat(MarketplaceService.verdict(new MarketplaceService.Stats(4, 0, 1, 0, 0))).isEqualTo(MarketplaceService.DOING_WELL);
    assertThat(MarketplaceService.verdict(new MarketplaceService.Stats(3, 0, 1, 0, 0))).isEqualTo(MarketplaceService.MIXED);
    assertThat(MarketplaceService.verdict(new MarketplaceService.Stats(3, 0, 2, 0, 0))).isEqualTo(MarketplaceService.NEEDS_ATTENTION);
    assertThat(MarketplaceService.verdictReason(new MarketplaceService.Stats(1, 0, 4, 0, 0)))
        .isEqualTo("4 of 5 comments are negative (80%), at or above the 40% needs-attention threshold.");
  }
}
