package com.demo.chatbot.agents;

import com.demo.chatbot.config.ChatbotProperties;
import com.demo.chatbot.domain.AuditRepository;
import com.demo.chatbot.guardrails.GuardrailLog;
import com.demo.chatbot.guardrails.GuardrailPlugin;
import com.demo.chatbot.observability.ObservabilityPlugin;
import com.demo.chatbot.observability.TraceIds;
import com.demo.chatbot.service.OrderService;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * All top-level agents, each with its own Runner. The runners share one session service, so a
 * session id is valid everywhere; each session records which agent it talks to. Every runner
 * carries the same {@link ObservabilityPlugin}, {@link GuardrailPlugin} and {@link HistoryLimitPlugin}.
 */
@Component
public class AgentRegistry {

  private final Map<String, BaseAgent> agents = new LinkedHashMap<>();
  private final Map<String, Runner> runners = new LinkedHashMap<>();
  private final Set<String> internalAuthors = new HashSet<>();
  // Stub: in-memory sessions are lost on restart. Production: a persistent session service.
  private final BaseSessionService sessions = new InMemorySessionService();
  private final String defaultAgent;
  private final ObservabilityPlugin observability;
  private final HistoryLimitPlugin historyLimit;
  private final GuardrailPlugin guardrails;

  public AgentRegistry(List<BaseAgent> topLevelAgents, ChatbotProperties props, GuardrailLog guardrailLog,
      OrderService orders, AuditRepository audit, MeterRegistry metrics, TraceIds traceIds) {
    topLevelAgents.forEach(this::collectInternalAuthors);
    // The same plugins on every agent. Order matters: observability is passive and must see every
    // event before guardrails short-circuit. Guardrails skip the output guard for intermediate steps.
    this.observability = new ObservabilityPlugin(audit, metrics, traceIds, this::isInternal);
    this.guardrails = new GuardrailPlugin(guardrailLog, orders, props.maxMessageChars(), this::isInternal);
    this.historyLimit = new HistoryLimitPlugin(props.maxHistoryTurns(), metrics);
    InMemoryArtifactService artifacts = new InMemoryArtifactService();
    for (BaseAgent agent : topLevelAgents) {
      agents.put(agent.name(), agent);
      runners.put(agent.name(), Runner.builder()
          .appName(AgentConfig.APP_NAME)
          .agent(agent)
          .sessionService(sessions)
          .artifactService(artifacts)
          .plugins(observability, guardrails, historyLimit)
          .build());
    }
    String configured = props.defaultAgent();
    this.defaultAgent = configured != null && agents.containsKey(configured)
        ? configured
        : agents.keySet().iterator().next();
  }

  /** Display names for agents whose identifier isn't how people should see them; others show their name. */
  private static final Map<String, String> LABELS = Map.of("my_marketplace", "My Marketplace");

  public record AgentInfo(String name, String label, String description) {}

  public List<AgentInfo> agents() {
    return agents.values().stream()
        .map(a -> new AgentInfo(a.name(), LABELS.getOrDefault(a.name(), a.name()), a.description()))
        .toList();
  }

  public List<BaseAgent> topLevelAgents() {
    return List.copyOf(agents.values());
  }

  public boolean exists(String name) {
    return agents.containsKey(name);
  }

  public String defaultAgent() {
    return defaultAgent;
  }

  public Optional<Runner> runner(String name) {
    return Optional.ofNullable(runners.get(name));
  }

  /** The plugins every runner carries, in order. Also attached to the ADK dev UI's runners. */
  public List<BasePlugin> plugins() {
    return List.of(observability, guardrails, historyLimit);
  }

  public HistoryLimitPlugin historyLimit() {
    return historyLimit;
  }

  public ObservabilityPlugin observability() {
    return observability;
  }

  public BaseSessionService sessionService() {
    return sessions;
  }

  /** Authors whose text is an intermediate pipeline step, not a reply to the customer. */
  public boolean isInternal(String author) {
    return internalAuthors.contains(author);
  }

  /** In a SequentialAgent, every step but the last produces intermediate output. */
  private void collectInternalAuthors(BaseAgent agent) {
    List<? extends BaseAgent> subs = agent.subAgents();
    if (agent instanceof SequentialAgent) {
      for (int i = 0; i < subs.size() - 1; i++) {
        internalAuthors.add(subs.get(i).name());
      }
    }
    subs.forEach(this::collectInternalAuthors);
  }
}
