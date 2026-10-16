package com.demo.chatbot.gateway;

import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * Wraps the OpenAI-compatible client that talks to LiteLLM. Adds the request's trace metadata to
 * the OpenAI {@code metadata} field and the customer to {@code user}. LiteLLM forwards both to
 * Langfuse (trace_id, session_id, trace_user_id, generation_name, ...).
 */
public final class GatewayChatModel implements ChatModel {

  private final OpenAiChatModel delegate;

  public GatewayChatModel(OpenAiChatModel delegate) {
    this.delegate = delegate;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    Map<String, String> metadata = GatewayRequestContext.get();
    if (metadata == null || metadata.isEmpty()) {
      return delegate.call(prompt);
    }
    OpenAiChatOptions base =
        prompt.getOptions() instanceof OpenAiChatOptions o ? o : (OpenAiChatOptions) delegate.getOptions();
    var tagged = base.mutate().metadata(metadata);
    if (metadata.containsKey("trace_user_id")) {
      tagged.user(metadata.get("trace_user_id"));
    }
    return delegate.call(new Prompt(prompt.getInstructions(), tagged.build()));
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return delegate.stream(prompt);
  }

  @Override
  public ChatOptions getOptions() {
    return delegate.getOptions();
  }
}
