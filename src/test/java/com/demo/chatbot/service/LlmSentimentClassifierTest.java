package com.demo.chatbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.demo.chatbot.agents.Prompts;
import com.demo.chatbot.domain.AuditRepository;
import com.demo.chatbot.domain.ProductFeedback;
import com.demo.chatbot.guardrails.GuardrailLog;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The classifier's request, parsing and bookkeeping, against a stub model: no gateway. */
class LlmSentimentClassifierTest {

  private static final TurnContext CTX =
      new TurnContext("s1", "inv1", "trace1", "owner", "my_marketplace", null);

  /** Returns a canned reply (or fails) and remembers the requests it got. */
  static class StubLlm extends BaseLlm {
    final List<LlmRequest> requests = new ArrayList<>();
    String reply;
    RuntimeException failure;

    StubLlm(String reply) {
      super("stub");
      this.reply = reply;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
      requests.add(request);
      if (failure != null) {
        return Flowable.error(failure);
      }
      return Flowable.just(LlmResponse.builder()
          .content(Content.builder().role("model").parts(List.of(Part.fromText(reply))).build())
          .usageMetadata(GenerateContentResponseUsageMetadata.builder().promptTokenCount(120).candidatesTokenCount(30).build())
          .build());
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
      throw new UnsupportedOperationException();
    }
  }

  private static ProductFeedback fb(long id, String text) {
    return new ProductFeedback(id, "C001", "P108", "Wireless Earbuds", "white", text, "2026-10-01T00:00:00Z", null);
  }

  @Test
  void parsesValidLabelsAndDropsEverythingElse() {
    String raw = """
        ```json
        [{"id": 1, "sentiment": "Positive"}, {"id": "2", "sentiment": "negative"},
         {"id": 3, "sentiment": "very bad"}, {"id": 99, "sentiment": "neutral"}, {"id": 1, "sentiment": "negative"}]
        ```""";
    Map<Long, Sentiment> labels = LlmSentimentClassifier.parse(raw, Set.of(1L, 2L, 3L));
    assertThat(labels).containsExactlyInAnyOrderEntriesOf(Map.of(1L, Sentiment.POSITIVE, 2L, Sentiment.NEGATIVE));
  }

  @Test
  void junkOutputLabelsNothing() {
    assertThat(LlmSentimentClassifier.parse("I think they are mostly positive!", Set.of(1L))).isEmpty();
    assertThat(LlmSentimentClassifier.parse("[not json]", Set.of(1L))).isEmpty();
    assertThat(LlmSentimentClassifier.parse(null, Set.of(1L))).isEmpty();
  }

  @Test
  void requestCarriesTheRulesTheCommentsAndTraceMetadata() {
    StubLlm llm = new StubLlm("[]");
    LlmSentimentClassifier classifier = new LlmSentimentClassifier(llm, new SimpleMeterRegistry(),
        mock(AuditRepository.class), mock(GuardrailLog.class));

    LlmRequest request = classifier.request(List.of(fb(7, "Battery dies fast")), CTX);

    assertThat(request.getSystemInstructions()).containsExactly(Prompts.SENTIMENT);
    assertThat(request.contents().get(0).text()).contains("\"id\":7").contains("Battery dies fast");
    GenerateContentConfig config = request.config().orElseThrow();
    assertThat(config.temperature()).contains(0f);
    assertThat(config.labels().orElseThrow()).containsEntry("trace_id", "trace1")
        .containsEntry("session_id", "s1")
        .containsEntry("generation_name", LlmSentimentClassifier.NAME)
        .containsEntry("top_level_agent", "my_marketplace")
        .containsEntry("prompt_version", Prompts.VERSION);
  }

  @Test
  void classifiesRecordsMetricsAndAudits() {
    StubLlm llm = new StubLlm("[{\"id\": 1, \"sentiment\": \"negative\"}, {\"id\": 2, \"sentiment\": \"positive\"}]");
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    AuditRepository audit = mock(AuditRepository.class);
    GuardrailLog guardrails = mock(GuardrailLog.class);
    LlmSentimentClassifier classifier = new LlmSentimentClassifier(llm, metrics, audit, guardrails);

    Map<Long, Sentiment> labels = classifier.classify(List.of(fb(1, "Broke in a week"), fb(2, "Love them")), CTX);

    assertThat(labels).containsEntry(1L, Sentiment.NEGATIVE).containsEntry(2L, Sentiment.POSITIVE);
    assertThat(metrics.counter("chatbot.llm.tokens", "type", "input", "agent", LlmSentimentClassifier.NAME).count()).isEqualTo(120);
    assertThat(metrics.counter("chatbot.classifier.comments", "result", "negative").count()).isEqualTo(1);
    assertThat(metrics.find("chatbot.classifier.latency").timer().count()).isEqualTo(1);
    verify(audit).append(any(AuditRepository.Entry.class));
    verify(guardrails, never()).record(any(), any(), anyString(), anyString(), anyString());
  }

  @Test
  void missingLabelsAreUnclassifiedAndRecordedAsAGuardrail() {
    StubLlm llm = new StubLlm("[{\"id\": 1, \"sentiment\": \"negative\"}]");
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    GuardrailLog guardrails = mock(GuardrailLog.class);
    LlmSentimentClassifier classifier = new LlmSentimentClassifier(llm, metrics, mock(AuditRepository.class), guardrails);

    Map<Long, Sentiment> labels = classifier.classify(List.of(fb(1, "Broke"), fb(2, "Hmm")), CTX);

    assertThat(labels).containsOnlyKeys(1L);
    assertThat(metrics.counter("chatbot.classifier.comments", "result", "unclassified").count()).isEqualTo(1);
    verify(guardrails).record(eq("s1"), eq("inv1"), eq("classifier_output_invalid"), eq("unclassified"), anyString());
  }

  @Test
  void largeInputsAreBatched() {
    StubLlm llm = new StubLlm("[]");
    LlmSentimentClassifier classifier = new LlmSentimentClassifier(llm, new SimpleMeterRegistry(),
        mock(AuditRepository.class), mock(GuardrailLog.class));
    List<ProductFeedback> many = new ArrayList<>();
    for (long i = 1; i <= LlmSentimentClassifier.BATCH_SIZE + 5; i++) {
      many.add(fb(i, "ok"));
    }
    classifier.classify(many, CTX);
    assertThat(llm.requests).hasSize(2);
  }

  @Test
  void modelFailureIsCountedAuditedAndRethrown() {
    StubLlm llm = new StubLlm("[]");
    llm.failure = new IllegalStateException("gateway down");
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    AuditRepository audit = mock(AuditRepository.class);
    LlmSentimentClassifier classifier = new LlmSentimentClassifier(llm, metrics, audit, mock(GuardrailLog.class));

    assertThatThrownBy(() -> classifier.classify(List.of(fb(1, "x")), CTX)).hasMessageContaining("gateway down");
    assertThat(metrics.counter("chatbot.llm.errors", "agent", LlmSentimentClassifier.NAME).count()).isEqualTo(1);
    verify(audit).append(any(AuditRepository.Entry.class));
  }
}
