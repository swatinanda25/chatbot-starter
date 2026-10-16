package com.demo.chatbot.service;

import com.demo.chatbot.domain.ProductFeedback;
import java.util.List;
import java.util.Map;

/**
 * Labels product feedback as positive, neutral or negative. The production implementation is an
 * LLM ({@link LlmSentimentClassifier}); tests swap in a fake.
 */
public interface SentimentClassifier {

  /**
   * Labels each comment in the batch. Ids missing from the result could not be classified (the
   * model's answer for them was missing or invalid). Throws when the model can't be reached.
   */
  Map<Long, Sentiment> classify(List<ProductFeedback> batch, TurnContext ctx);
}
