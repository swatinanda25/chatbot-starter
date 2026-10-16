package com.demo.chatbot.observability;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic hallucination check for structured facts. Extracts the facts a reply asserts
 * (order ids, money amounts, ticket ids, order statuses; for the owner's feedback reports also
 * percentages, product ids and verdicts) and reports any that don't appear in the session's
 * evidence: tool results, plus what the customer said.
 *
 * <p>It catches invented hard facts, not wrong reasoning or wrong prose: a cheap, explainable
 * lower bound to pair with evals and an LLM-as-judge on sampled traces.
 */
public final class FactCheck {

  private FactCheck() {}

  private static final Pattern MONEY = Pattern.compile("\\$\\s?(\\d{1,6}(?:,\\d{3})*)(?:\\.(\\d{2}))?");
  /** Four-digit ids; not part of a longer number, an amount, or "1,234" / "12.34". */
  private static final Pattern ORDER_ID = Pattern.compile("(?<![\\d$.,-])\\d{4}(?!\\d|[.,]\\d)");
  private static final Pattern TICKET = Pattern.compile("\\bHO-\\d+\\b", Pattern.CASE_INSENSITIVE);
  /**
   * An order status asserted about an order: after "is/was/has (been)…" ("has shipped"), or the
   * uppercase form tools return ("SHIPPED"). Not the verb in "it was placed on September 25".
   */
  private static final Pattern STATUS = Pattern.compile(
      "(?i:\\b(?:is|was|has|have|are|were|been|status(?:\\s+is)?:?)\\s+(?:already\\s+|now\\s+|been\\s+)*"
          + "(placed(?!\\s+(?:on|in|at|by)\\b)|shipped|delivered|cancell?ed)\\b)"
          + "|\\b(PLACED|SHIPPED|DELIVERED|CANCELL?ED)\\b");

  private static final Pattern PERCENT = Pattern.compile("(?<![\\d.])(\\d{1,3})\\s?%");
  private static final Pattern ITEM_ID = Pattern.compile("\\bP\\d{3}\\b");
  /**
   * A feedback verdict stated as "<product>: verdict" (the reply format) or "verdict": "..." (the
   * tool result). Requiring the colon keeps small talk ("glad you're doing well") out.
   */
  private static final Pattern VERDICT = Pattern.compile(
      ":\\W{0,3}(doing well|mixed|needs attention|not enough feedback)\\b", Pattern.CASE_INSENSITIVE);

  public record Result(Set<String> facts, Set<String> unsupported) {
    public boolean hasFacts() {
      return !facts.isEmpty();
    }

    public boolean supported() {
      return unsupported.isEmpty();
    }
  }

  /** Normalized facts asserted by a piece of text, e.g. "order:1002", "money:18900", "status:shipped". */
  public static Set<String> facts(String text) {
    Set<String> out = new LinkedHashSet<>();
    Matcher m = MONEY.matcher(text);
    while (m.find()) {
      long dollars = Long.parseLong(m.group(1).replace(",", ""));
      long cents = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
      out.add("money:" + (dollars * 100 + cents));
    }
    // Ids are matched on text with money removed, so "$1299" isn't also read as order 1299.
    String noMoney = MONEY.matcher(text).replaceAll(" ");
    m = ORDER_ID.matcher(noMoney);
    while (m.find()) {
      out.add("order:" + m.group());
    }
    m = TICKET.matcher(text);
    while (m.find()) {
      out.add("ticket:" + m.group().toUpperCase(Locale.ROOT));
    }
    m = STATUS.matcher(text);
    while (m.find()) {
      String s = (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(Locale.ROOT);
      out.add("status:" + ("canceled".equals(s) ? "cancelled" : s));
    }
    m = PERCENT.matcher(text);
    while (m.find()) {
      out.add("pct:" + Integer.parseInt(m.group(1)));
    }
    m = ITEM_ID.matcher(text);
    while (m.find()) {
      out.add("item:" + m.group());
    }
    m = VERDICT.matcher(text);
    while (m.find()) {
      out.add("verdict:" + m.group(1).toLowerCase(Locale.ROOT));
    }
    return out;
  }

  /** Checks a reply against evidence texts (tool results as JSON, customer messages). */
  public static Result check(String reply, List<String> evidence) {
    Set<String> claimed = facts(reply);
    Set<String> known = new LinkedHashSet<>();
    evidence.forEach(e -> known.addAll(facts(e)));
    Set<String> unsupported = new LinkedHashSet<>(claimed);
    unsupported.removeAll(known);
    // Years in dates ("September 25, 2026") read as 4-digit ids; they're supported when any tool
    // returned a date in that year.
    unsupported.removeIf(f -> f.startsWith("order:20") && evidence.stream().anyMatch(e -> e.contains(f.substring(6) + "-")));
    return new Result(claimed, unsupported);
  }
}
