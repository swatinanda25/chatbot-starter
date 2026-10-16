package com.demo.chatbot.dev;

import com.demo.chatbot.ChatbotApplication;
import com.demo.chatbot.agents.AgentRegistry;
import com.demo.chatbot.config.ChatbotProperties;
import com.google.adk.agents.BaseAgent;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.web.AdkWebServer;
import com.google.adk.web.AgentStaticLoader;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Serves the app's real agents (every one in the registry) in the ADK dev UI, for debugging:
 * events, tool calls, state, traces. The agents come from the app's own Spring context, so they
 * use the same instructions and the same LiteLLM gateway wiring as the chatbot. Dev only; run it
 * with scripts/adk-web.sh.
 *
 * <p>Unlike {@code AdkWebServer.start(agents)}, this also gives the dev UI's runners the app's
 * plugins (observability, guardrails, history limit) plus {@link DevSessionDefaultsPlugin}, which
 * signs every dev UI session in as the demo customer, as the chat API does.
 */
public final class AdkWebLauncher {

  public static void main(String[] args) {
    ConfigurableApplicationContext app =
        new SpringApplicationBuilder(ChatbotApplication.class)
            .web(WebApplicationType.NONE) // just the beans; the dev UI brings its own web server
            .run(args);
    AgentRegistry registry = app.getBean(AgentRegistry.class);
    BaseAgent[] agents = registry.topLevelAgents().toArray(BaseAgent[]::new);

    // Session defaults first, so customer_id is in state before any other plugin or tool runs.
    List<BasePlugin> plugins = new ArrayList<>();
    plugins.add(new DevSessionDefaultsPlugin(app.getBean(ChatbotProperties.class).demoCustomerId()));
    plugins.addAll(registry.plugins());

    // System property beats application.yml's server.port, so the dev UI doesn't collide with the app.
    System.setProperty("server.port", System.getenv().getOrDefault("ADK_WEB_PORT", "8000"));
    // Same setup as AdkWebServer.start(agents), plus the "extraPlugins" bean that ADK web's
    // RunnerService attaches to every runner it builds.
    System.setProperty("adk.agents.loader", "static");
    System.setProperty("org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE", String.valueOf(10 * 1024 * 1024));
    SpringApplication web = new SpringApplication(AdkWebServer.class);
    web.addInitializers(ctx -> {
      ctx.getBeanFactory().registerSingleton("agentLoader", new AgentStaticLoader(agents));
      ctx.getBeanFactory().registerSingleton("extraPlugins", plugins);
    });
    web.run();
  }
}
