package com.demo.chatbot.api;

import com.demo.chatbot.agents.AgentConfig;
import com.demo.chatbot.agents.AgentRegistry;
import com.demo.chatbot.config.ChatbotProperties;
import com.demo.chatbot.tools.SessionKeys;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Session lifecycle and turn execution, shared by the HTTP API and the eval harness, so evals
 * exercise exactly the production path. Each session is bound to one agent from the registry.
 */
@Service
public class ChatService {

  /** Session-state key holding the name of the agent this session talks to. */
  static final String AGENT_KEY = "agent";

  private final AgentRegistry registry;
  private final ChatbotProperties props;
  /** Confirmation ids the agent actually issued, per session. Single-use, so they can't be forged or replayed. */
  private final Map<String, Set<String>> pendingConfirmations = new ConcurrentHashMap<>();

  public ChatService(AgentRegistry registry, ChatbotProperties props) {
    this.registry = registry;
    this.props = props;
  }

  /** Stub auth: every session belongs to the demo customer. Production: the id comes from the auth token. */
  public String customerId() {
    return props.demoCustomerId();
  }

  /**
   * Creates a session for {@code agent}, which must exist in the registry. The customer id goes
   * into session state, where tools read it; the model never supplies it.
   */
  public String createSession(String agent, Map<String, Object> extraState) {
    Map<String, Object> state = new ConcurrentHashMap<>(extraState);
    state.put(AGENT_KEY, agent);
    state.put(SessionKeys.CUSTOMER_ID, customerId());
    return registry.sessionService()
        .createSession(AgentConfig.APP_NAME, customerId(), state, null)
        .blockingGet()
        .id();
  }

  public boolean sessionExists(String sessionId) {
    return session(sessionId) != null;
  }

  public Flowable<Event> sendMessage(String sessionId, String text) {
    Content content = Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
    return track(sessionId, runner(sessionId).runAsync(customerId(), sessionId, content));
  }

  /**
   * The only way a confirmation reaches the agent: a user-role function response built by the
   * server from a UI click. The model can't produce one, so it can't approve its own actions.
   */
  public Flowable<Event> answerConfirmation(String sessionId, String confirmationId, boolean confirmed) {
    if (!consumeConfirmation(sessionId, confirmationId)) {
      throw new IllegalArgumentException("Unknown or already-answered confirmation: " + confirmationId);
    }
    Part answer = Part.builder()
        .functionResponse(FunctionResponse.builder()
            .id(confirmationId)
            .name(ChatEventMapper.CONFIRMATION_CALL)
            .response(Map.of("confirmed", confirmed))
            .build())
        .build();
    Content content = Content.builder().role("user").parts(List.of(answer)).build();
    return track(sessionId, runner(sessionId).runAsync(customerId(), sessionId, content));
  }

  /**
   * What started each turn of a session, by invocation id: the customer's message (as stored, so
   * already PII-redacted), or their answer to a confirmation card. Used to label the audit view.
   */
  public Map<String, String> turnLabels(String sessionId) {
    Session session = session(sessionId);
    Map<String, String> labels = new LinkedHashMap<>();
    if (session == null) {
      return labels;
    }
    for (Event e : session.events()) {
      if (!"user".equals(e.author()) || labels.containsKey(e.invocationId())) {
        continue;
      }
      for (Part p : e.content().flatMap(Content::parts).orElse(List.of())) {
        if (p.text().isPresent() && !p.text().get().isBlank()) {
          labels.put(e.invocationId(), "“" + p.text().get().strip() + "”");
          break;
        }
        if (p.functionResponse().isPresent()
            && ChatEventMapper.CONFIRMATION_CALL.equals(p.functionResponse().get().name().orElse(""))) {
          boolean yes = Boolean.TRUE.equals(p.functionResponse().get().response().orElse(Map.of()).get("confirmed"));
          labels.put(e.invocationId(), yes ? "✓ Customer confirmed" : "✗ Customer declined");
          break;
        }
      }
    }
    return labels;
  }

  public String traceIdFor(Event event) {
    return registry.observability().traceIdFor(event.invocationId());
  }

  private Runner runner(String sessionId) {
    Object agent = session(sessionId).state().get(AGENT_KEY);
    return registry.runner(String.valueOf(agent))
        .orElseThrow(() -> new IllegalStateException("No agent named " + agent));
  }

  private boolean consumeConfirmation(String sessionId, String confirmationId) {
    Set<String> pending = pendingConfirmations.get(sessionId);
    return pending != null && confirmationId != null && pending.remove(confirmationId);
  }

  private Flowable<Event> track(String sessionId, Flowable<Event> events) {
    return events.doOnNext(e -> e.functionCalls().stream()
        .filter(fc -> ChatEventMapper.CONFIRMATION_CALL.equals(fc.name().orElse("")))
        .forEach(fc -> pendingConfirmations
            .computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
            .add(fc.id().orElseThrow())));
  }

  private Session session(String sessionId) {
    return registry.sessionService()
        .getSession(AgentConfig.APP_NAME, customerId(), sessionId, Optional.empty())
        .blockingGet();
  }
}
