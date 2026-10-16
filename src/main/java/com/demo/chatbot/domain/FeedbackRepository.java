package com.demo.chatbot.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for product feedback. Aggregation rules live in {@link com.demo.chatbot.service.MarketplaceService}. */
@Repository
public class FeedbackRepository {

  private static final String SELECT =
      "SELECT f.id, f.user_id, f.item, p.name, p.color, f.feedback, f.created_at, f.sentiment"
          + " FROM product_feedback f JOIN products p ON p.id = f.item";

  private final JdbcClient jdbc;

  public FeedbackRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** All feedback on every product, newest first. */
  public List<ProductFeedback> all() {
    return jdbc.sql(SELECT + " ORDER BY f.created_at DESC").query((rs, n) -> toFeedback(rs)).list();
  }

  /** Stores a classifier label once: rows already labeled keep theirs, so reports stay stable. */
  public void saveSentiment(long id, String sentiment) {
    jdbc.sql(
            "UPDATE product_feedback SET sentiment = :sentiment, classified_at = :ts"
                + " WHERE id = :id AND sentiment IS NULL")
        .param("sentiment", sentiment)
        .param("ts", Instant.now().toString())
        .param("id", id)
        .update();
  }

  private static ProductFeedback toFeedback(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ProductFeedback(
        rs.getLong("id"),
        rs.getString("user_id"),
        rs.getString("item"),
        rs.getString("name"),
        rs.getString("color"),
        rs.getString("feedback"),
        rs.getString("created_at"),
        rs.getString("sentiment"));
  }
}
