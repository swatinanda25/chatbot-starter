package com.demo.chatbot.dev;

import com.demo.chatbot.tools.SessionKeys;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;

/**
 * Dev UI only (test scope, never in the app jar). The chat API's ChatService puts the signed-in
 * customer and the chosen agent into session state when it creates a session; ADK web creates its
 * own sessions, so this fills the same keys on the first agent call. Without it, every tool
 * returns UNAUTHENTICATED in the dev UI.
 *
 * <p>Written through CallbackContext.state(), so it's a real state change: it shows up in the dev
 * UI's State tab and persists for the rest of the session.
 */
final class DevSessionDefaultsPlugin extends BasePlugin {

  /** Same key ChatService uses for the session's top-level agent. */
  private static final String AGENT_KEY = "agent";

  private final String demoCustomerId;

  DevSessionDefaultsPlugin(String demoCustomerId) {
    super("dev_session_defaults");
    this.demoCustomerId = demoCustomerId;
  }

  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext ctx) {
    if (ctx.state().get(SessionKeys.CUSTOMER_ID) == null) {
      ctx.state().put(SessionKeys.CUSTOMER_ID, demoCustomerId);
    }
    if (ctx.state().get(AGENT_KEY) == null) {
      // The first agent called in a new session is the top-level one the dev UI picked.
      ctx.state().put(AGENT_KEY, agent.name());
    }
    return Maybe.empty();
  }
}
