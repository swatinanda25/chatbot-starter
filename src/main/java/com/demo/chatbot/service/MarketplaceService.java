package com.demo.chatbot.service;

import com.demo.chatbot.domain.FeedbackRepository;
import com.demo.chatbot.domain.Product;
import com.demo.chatbot.domain.ProductFeedback;
import com.demo.chatbot.domain.StoreRepository;
import com.demo.chatbot.guardrails.GuardrailLog;
import com.demo.chatbot.guardrails.GuardrailPlugin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Product feedback, aggregated for the store owner. The LLM classifier labels each comment once
 * (labels are stored, so repeated reports agree); the verdict is then plain arithmetic over the
 * labels with fixed thresholds, so the agent reports it and can't invent one.
 *
 * <p>Results never include who wrote a comment: the owner gets counts and anonymous comments.
 * Comments that look like instructions to a model are withheld from the classifier and the agent.
 */
@Service
public class MarketplaceService {

  private static final Logger log = LoggerFactory.getLogger(MarketplaceService.class);

  /** Fewer classified comments than this and there is no verdict. */
  static final int MIN_FEEDBACK = 3;
  static final double DOING_WELL_MIN_POSITIVE = 0.6;
  static final double DOING_WELL_MAX_NEGATIVE = 0.2;
  static final double NEEDS_ATTENTION_MIN_NEGATIVE = 0.4;

  public static final String DOING_WELL = "doing well";
  public static final String MIXED = "mixed";
  public static final String NEEDS_ATTENTION = "needs attention";
  public static final String NOT_ENOUGH = "not enough feedback";
  /** Worst first, for the overview. */
  private static final List<String> SEVERITY = List.of(NEEDS_ATTENTION, MIXED, DOING_WELL, NOT_ENOUGH);

  static final String WITHHELD = "[comment withheld: contained instructions]";
  private static final int MAX_COMMENTS = 10;
  private static final Pattern ITEM_ID = Pattern.compile("(?i)p\\d{3}");

  private final StoreRepository store;
  private final FeedbackRepository feedback;
  private final SentimentClassifier classifier;
  private final GuardrailLog guardrails;

  public MarketplaceService(StoreRepository store, FeedbackRepository feedback, SentimentClassifier classifier,
      GuardrailLog guardrails) {
    this.store = store;
    this.feedback = feedback;
    this.classifier = classifier;
    this.guardrails = guardrails;
  }

  /** Counts per label for one product's feedback. Only classified comments count toward the verdict. */
  record Stats(int positive, int neutral, int negative, int unclassified, int withheld) {
    int classified() {
      return positive + neutral + negative;
    }

    int total() {
      return classified() + unclassified + withheld;
    }
  }

  /** Aggregated feedback and verdict for one product, found by id ("P108") or by name words. */
  public Map<String, Object> feedbackReport(String rawItem, TurnContext ctx) {
    if (rawItem == null || rawItem.isBlank()) {
      return ToolResult.error("INVALID_ITEM", "Name the product, e.g. \"wireless earbuds\" or its id like P108.");
    }
    Map<String, List<ProductFeedback>> byItem = feedback.all().stream()
        .collect(Collectors.groupingBy(ProductFeedback::item));
    List<Product> candidates = candidates(rawItem);
    if (candidates.isEmpty()) {
      return ToolResult.error("ITEM_NOT_FOUND", "No product in the catalog matches \"" + rawItem.strip() + "\".");
    }
    Product product;
    List<Product> withFeedback = candidates.stream().filter(p -> byItem.containsKey(p.id())).toList();
    if (candidates.size() == 1) {
      product = candidates.get(0);
    } else if (withFeedback.size() == 1) {
      product = withFeedback.get(0);
    } else {
      return ambiguous(rawItem, candidates, byItem);
    }

    List<ProductFeedback> rows;
    try {
      rows = classified(byItem.getOrDefault(product.id(), List.of()), ctx);
    } catch (RuntimeException e) {
      return unavailable(e);
    }
    Set<Long> withheld = withheld(rows, ctx);
    Stats stats = stats(rows, withheld);

    Map<String, Object> data = new LinkedHashMap<>(itemView(product));
    data.putAll(statsView(stats));
    data.put("verdict_reason", verdictReason(stats));
    data.put("comments", rows.stream().limit(MAX_COMMENTS).map(f -> commentView(f, withheld)).toList());
    return ToolResult.ok(data);
  }

  /** Every product with feedback, worst verdict first, plus the products nobody has reviewed. */
  public Map<String, Object> feedbackOverview(TurnContext ctx) {
    List<ProductFeedback> rows;
    try {
      rows = classified(feedback.all(), ctx);
    } catch (RuntimeException e) {
      return unavailable(e);
    }
    Set<Long> withheld = withheld(rows, ctx);
    Map<String, List<ProductFeedback>> byItem = rows.stream().collect(Collectors.groupingBy(ProductFeedback::item));

    List<Map<String, Object>> items = new ArrayList<>();
    List<String> noFeedback = new ArrayList<>();
    for (Product p : store.allProducts()) {
      List<ProductFeedback> mine = byItem.get(p.id());
      if (mine == null) {
        noFeedback.add(p.name() + " (" + p.color() + ", " + p.id() + ")");
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>(itemView(p));
      row.putAll(statsView(stats(mine, withheld)));
      items.add(row);
    }
    items.sort(Comparator
        .comparingInt((Map<String, Object> r) -> SEVERITY.indexOf((String) r.get("verdict")))
        .thenComparing(r -> -(int) r.get("negative")));
    return ToolResult.ok(Map.of("items", items, "items_without_feedback", noFeedback));
  }

  // ---- Verdict (pure, unit-tested) ----------------------------------------------------------

  static String verdict(Stats s) {
    int n = s.classified();
    if (n < MIN_FEEDBACK) {
      return NOT_ENOUGH;
    }
    double positive = (double) s.positive() / n;
    double negative = (double) s.negative() / n;
    if (negative >= NEEDS_ATTENTION_MIN_NEGATIVE) {
      return NEEDS_ATTENTION;
    }
    if (positive >= DOING_WELL_MIN_POSITIVE && negative <= DOING_WELL_MAX_NEGATIVE) {
      return DOING_WELL;
    }
    return MIXED;
  }

  static String verdictReason(Stats s) {
    int n = s.classified();
    return switch (verdict(s)) {
      case NOT_ENOUGH -> "Only " + n + " classified comment" + (n == 1 ? "" : "s") + "; at least " + MIN_FEEDBACK
          + " are needed for a verdict.";
      case NEEDS_ATTENTION -> s.negative() + " of " + n + " comments are negative (" + pct(s.negative(), n)
          + "), at or above the " + pct(NEEDS_ATTENTION_MIN_NEGATIVE) + " needs-attention threshold.";
      case DOING_WELL -> s.positive() + " of " + n + " comments are positive (" + pct(s.positive(), n)
          + ") and " + s.negative() + " negative (" + pct(s.negative(), n) + ").";
      default -> "Positive " + pct(s.positive(), n) + " and negative " + pct(s.negative(), n)
          + ": not clearly good or bad.";
    };
  }

  static String pct(int part, int whole) {
    return whole == 0 ? "n/a" : Math.round(100.0 * part / whole) + "%";
  }

  private static String pct(double share) {
    return Math.round(share * 100) + "%";
  }

  // ---- Helpers ------------------------------------------------------------------------------

  /** Products matching the request: an exact id, else the best name/category/color matches. */
  private List<Product> candidates(String rawItem) {
    List<Product> all = store.allProducts();
    String trimmed = rawItem.strip();
    if (ITEM_ID.matcher(trimmed).matches()) {
      String id = trimmed.toUpperCase(Locale.ROOT);
      return all.stream().filter(p -> p.id().equals(id)).toList();
    }
    List<String> terms = ProductMatch.terms(trimmed);
    int best = all.stream().mapToInt(p -> ProductMatch.score(p, terms)).max().orElse(0);
    return best == 0 ? List.of() : all.stream().filter(p -> ProductMatch.score(p, terms) == best).toList();
  }

  /**
   * Labels the rows that have none yet (skipping withheld ones), stores the labels, and returns
   * the rows with labels filled in. Throws if the classifier can't be reached.
   */
  private List<ProductFeedback> classified(List<ProductFeedback> rows, TurnContext ctx) {
    List<ProductFeedback> pending = rows.stream()
        .filter(f -> f.sentiment() == null && !GuardrailPlugin.looksLikeInjection(f.feedback()))
        .toList();
    if (pending.isEmpty()) {
      return rows;
    }
    Map<Long, Sentiment> labels = classifier.classify(pending, ctx);
    labels.forEach((id, s) -> feedback.saveSentiment(id, s.label()));
    return rows.stream()
        .map(f -> f.sentiment() == null && labels.containsKey(f.id())
            ? new ProductFeedback(f.id(), f.userId(), f.item(), f.itemName(), f.itemColor(), f.feedback(),
                f.createdAt(), labels.get(f.id()).label())
            : f)
        .toList();
  }

  /** Ids of comments that read like instructions to a model. Recorded as a guardrail trigger for the turn. */
  private Set<Long> withheld(List<ProductFeedback> rows, TurnContext ctx) {
    Set<Long> ids = rows.stream()
        .filter(f -> GuardrailPlugin.looksLikeInjection(f.feedback()))
        .map(ProductFeedback::id)
        .collect(Collectors.toSet());
    if (!ids.isEmpty()) {
      guardrails.record(ctx.sessionId(), ctx.invocationId(), "indirect_injection", "withheld",
          ids.size() + " feedback comment(s) contained instructions; withheld from the classifier and the agent");
    }
    return ids;
  }

  private static Stats stats(List<ProductFeedback> rows, Set<Long> withheld) {
    int positive = 0;
    int neutral = 0;
    int negative = 0;
    int unclassified = 0;
    int held = 0;
    for (ProductFeedback f : rows) {
      if (withheld.contains(f.id())) {
        held++;
        continue;
      }
      Optional<Sentiment> s = Sentiment.parse(f.sentiment());
      if (s.isEmpty()) {
        unclassified++;
        continue;
      }
      switch (s.get()) {
        case POSITIVE -> positive++;
        case NEUTRAL -> neutral++;
        case NEGATIVE -> negative++;
      }
    }
    return new Stats(positive, neutral, negative, unclassified, held);
  }

  private static Map<String, Object> itemView(Product p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("item_id", p.id());
    m.put("name", p.name());
    m.put("color", String.valueOf(p.color()));
    return m;
  }

  private static Map<String, Object> statsView(Stats s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("feedback_count", s.total());
    m.put("classified", s.classified());
    m.put("positive", s.positive());
    m.put("neutral", s.neutral());
    m.put("negative", s.negative());
    m.put("unclassified", s.unclassified());
    m.put("withheld", s.withheld());
    m.put("positive_pct", pct(s.positive(), s.classified()));
    m.put("negative_pct", pct(s.negative(), s.classified()));
    m.put("verdict", verdict(s));
    return m;
  }

  /** No user id: the owner sees what was said and when, not who said it. */
  private static Map<String, Object> commentView(ProductFeedback f, Set<Long> withheld) {
    boolean held = withheld.contains(f.id());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("date", f.createdAt().length() >= 10 ? f.createdAt().substring(0, 10) : f.createdAt());
    m.put("sentiment", held ? "withheld" : f.sentiment() == null ? "unclassified" : f.sentiment());
    m.put("text", held ? WITHHELD : f.feedback());
    return m;
  }

  private static Map<String, Object> ambiguous(String rawItem, List<Product> candidates,
      Map<String, List<ProductFeedback>> byItem) {
    List<Map<String, Object>> options = candidates.stream().map(p -> {
      Map<String, Object> m = new LinkedHashMap<>(itemView(p));
      m.put("feedback_count", byItem.getOrDefault(p.id(), List.of()).size());
      return m;
    }).toList();
    Map<String, Object> err = ToolResult.error("AMBIGUOUS_ITEM",
        "Several products match \"" + rawItem.strip() + "\". Ask the owner which one they mean.");
    err.put("candidates", options);
    return err;
  }

  private static Map<String, Object> unavailable(RuntimeException e) {
    log.warn("feedback classification failed: {}", e.toString());
    return ToolResult.error("ANALYSIS_UNAVAILABLE",
        "Feedback analysis is unavailable right now. Tell the owner to try again in a moment.");
  }
}
