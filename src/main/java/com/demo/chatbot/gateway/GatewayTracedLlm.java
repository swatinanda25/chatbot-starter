package com.demo.chatbot.gateway;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.GenerateContentConfig;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;

/**
 * Decorates the ADK model so gateway metadata (trace id, session id, agent name) rides along with
 * each request. The observability plugin puts that metadata on the request as config labels; the
 * Spring AI adapter ignores labels, so this decorator hands them to {@link GatewayChatModel}.
 *
 * <p>Always calls the delegate non-streaming, so the model call happens on this thread while the
 * context is set.
 */
public final class GatewayTracedLlm extends BaseLlm {

  private final BaseLlm delegate;

  public GatewayTracedLlm(BaseLlm delegate) {
    super(delegate.model());
    this.delegate = delegate;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    Map<String, String> metadata =
        llmRequest.config().flatMap(GenerateContentConfig::labels).orElse(Map.of());
    return Flowable.defer(
        () -> {
          GatewayRequestContext.set(metadata);
          try {
            return Flowable.fromIterable(
                delegate.generateContent(llmRequest, false).toList().blockingGet());
          } finally {
            GatewayRequestContext.clear();
          }
        });
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    return delegate.connect(llmRequest);
  }
}
