package com.demo.chatbot.agents;

import com.demo.chatbot.tools.MarketplaceTools;
import com.demo.chatbot.tools.OrderTools;
import com.demo.chatbot.tools.ProductTools;
import com.demo.chatbot.tools.ToolRisk;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.GenerateContentConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The chatbot's top-level agents. Every {@link BaseAgent} bean declared here gets its own Runner
 * in {@link AgentRegistry} and can be picked per chat session. To add an agent, add a bean.
 *
 * <p>Sub-agents are built inline, not as beans: an ADK agent can have only one parent.
 */
@Configuration
public class AgentConfig {

  public static final String APP_NAME = "chatbot";

  /** Hard cap on model steps per agent per turn, so a confused model can't loop on tools. */
  private static final int MAX_STEPS = 8;

  /** One agent, no tools: the user's question goes to the LLM and the answer comes back. */
  @Bean
  public BaseAgent assistantAgent(BaseLlm chatLlm) {
    return LlmAgent.builder()
        .name("assistant")
        .description("One model call, no tools: can talk but can't look anything up.")
        .model(chatLlm)
        .instruction(Prompts.ASSISTANT)
        .generateContentConfig(config())
        .build();
  }

  /**
   * Router plus two specialists, each seeing only the tools it needs.
   *
   * <p>Tradeoff: a transfer costs an extra model call (latency and tokens). At this size one agent
   * with all five tools would also work. Splitting buys least-privilege tool access (the product
   * agent can't cancel anything) and shorter, focused prompts, and it scales as more domains arrive.
   */
  @Bean
  public BaseAgent supportRouterAgent(BaseLlm chatLlm, OrderTools orderTools, ProductTools productTools) {
    LlmAgent orderAgent = LlmAgent.builder()
        .name("order_agent")
        .description("Handles the signed-in customer's orders: status, contents, cancellation.")
        .model(chatLlm)
        .instruction(Prompts.ORDERS)
        .generateContentConfig(config())
        .maxSteps(MAX_STEPS)
        .tools(
            tool(orderTools, "listOrders", "list_orders"),
            tool(orderTools, "getOrder", "get_order"),
            tool(orderTools, "cancelOrder", "cancel_order"),
            tool(orderTools, "escalateToHuman", "escalate_to_human"))
        .build();

    LlmAgent productAgent = LlmAgent.builder()
        .name("product_agent")
        .description("Answers product questions: availability, price, color, recommendations.")
        .model(chatLlm)
        .instruction(Prompts.PRODUCTS)
        .generateContentConfig(config())
        .maxSteps(MAX_STEPS)
        .tools(tool(productTools, "searchProducts", "search_products"))
        .build();

    return LlmAgent.builder()
        .name("support_router")
        .description("Front desk that transfers to order and product specialists; can cancel orders with confirmation.")
        .model(chatLlm)
        .instruction(Prompts.ROOT)
        .generateContentConfig(config())
        .maxSteps(MAX_STEPS)
        .tools(tool(orderTools, "escalateToHuman", "escalate_to_human"))
        .subAgents(orderAgent, productAgent)
        .build();
  }

  /**
   * Sequential pipeline: triage, then draft, then review. Each step runs once per turn, in order,
   * and hands its output to the next through session state (outputKey -> {key} in the next
   * instruction). The draft step looks facts up with read-only tools (plus opening a hand-off
   * ticket); it can't cancel anything. Only the reviewer's reply is shown as the answer.
   *
   * <p>Tradeoff: at least three model calls per turn instead of one, for a classified, reviewed reply.
   */
  @Bean
  public BaseAgent supportPipelineAgent(BaseLlm chatLlm, OrderTools orderTools, ProductTools productTools) {
    LlmAgent triage = step(chatLlm, "triage_agent", "Classifies intent, sentiment and hand-off need.", Prompts.TRIAGE)
        .outputKey("triage")
        .build();
    LlmAgent draft = step(chatLlm, "draft_agent", "Looks up facts and drafts a reply using the triage.", Prompts.DRAFT)
        .outputKey("draft")
        .maxSteps(MAX_STEPS)
        .tools(
            tool(orderTools, "listOrders", "list_orders"),
            tool(orderTools, "getOrder", "get_order"),
            tool(productTools, "searchProducts", "search_products"),
            tool(orderTools, "escalateToHuman", "escalate_to_human"))
        .build();
    LlmAgent review = step(chatLlm, "review_agent", "Reviews the draft and writes the final reply.", Prompts.REVIEW)
        .build();

    return SequentialAgent.builder()
        .name("support_pipeline")
        .description("Triage, draft (with order and product lookups), then review: three agents in order on every message.")
        .subAgents(triage, draft, review)
        .build();
  }

  /**
   * The store owner's agent, not the customer's: aggregated product feedback and a verdict per
   * product. One agent with two read-only tools; it has no order tools at all. Sentiment comes
   * from an LLM classifier inside the tools, and the verdict is computed in code from those labels,
   * so the agent explains a verdict rather than inventing one.
   *
   * <p>Stub: auth is shared with the customer agents, so anyone can pick it in the demo. Production
   * gates it behind an owner role.
   */
  @Bean
  public BaseAgent myMarketplaceAgent(BaseLlm chatLlm, MarketplaceTools marketplaceTools) {
    return LlmAgent.builder()
        .name("my_marketplace")
        .description("For the store owner: aggregated product feedback and whether each item is doing well.")
        .model(chatLlm)
        .instruction(Prompts.MARKETPLACE)
        .generateContentConfig(config())
        .maxSteps(MAX_STEPS)
        .tools(
            tool(marketplaceTools, "getFeedbackReport", "get_feedback_report"),
            tool(marketplaceTools, "listFeedbackOverview", "list_feedback_overview"))
        .build();
  }

  private static LlmAgent.Builder step(BaseLlm llm, String name, String description, String instruction) {
    return LlmAgent.builder()
        .name(name)
        .description(description)
        .model(llm)
        .instruction(instruction)
        .generateContentConfig(config())
        // The pipeline decides the order; steps never hand control to each other.
        .disallowTransferToParent(true)
        .disallowTransferToPeers(true);
  }

  /** Risk tier decides confirmation: CONFIRM-tier tools pause for the user's explicit approval. */
  private static FunctionTool tool(Object instance, String method, String toolName) {
    return FunctionTool.create(instance, method, ToolRisk.of(toolName) == ToolRisk.CONFIRM);
  }

  private static GenerateContentConfig config() {
    return GenerateContentConfig.builder().temperature(0.2f).maxOutputTokens(800).build();
  }
}
