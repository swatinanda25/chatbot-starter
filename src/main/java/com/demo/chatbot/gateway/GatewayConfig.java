package com.demo.chatbot.gateway;

import com.demo.chatbot.config.ChatbotProperties;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.springai.SpringAI;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Model wiring: ADK agent -> SpringAI adapter -> Spring AI OpenAI client -> LiteLLM gateway.
 * The app only knows a logical model alias; the gateway decides the provider, so switching
 * providers is a gateway config change, not a code change.
 */
@Configuration
public class GatewayConfig {

  @Bean
  public BaseLlm chatLlm(ChatbotProperties props) {
    ChatbotProperties.Gateway gw = props.gateway();
    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .baseUrl(gw.baseUrl())
            .apiKey(gw.apiKey())
            .model(gw.model())
            .temperature(0.2)
            .timeout(gw.timeout())
            .maxRetries(1) // the gateway owns retries and provider fallback
            .build();
    OpenAiChatModel openAi = OpenAiChatModel.builder().options(options).build();
    // GatewayChatModel + GatewayTracedLlm carry trace metadata (trace id, session, agent, prompt
    // version) in each request, so LiteLLM can forward it to Langfuse.
    return new GatewayTracedLlm(new SpringAI(new GatewayChatModel(openAi), gw.model()));
  }
}
