package com.demo.chatbot.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;

/** Data access only. Business rules live in {@link com.demo.chatbot.service.OrderService}. */
@Repository
public class StoreRepository {

  private final JdbcClient jdbc;
  private final DataSource dataSource;

  public StoreRepository(JdbcClient jdbc, DataSource dataSource) {
    this.jdbc = jdbc;
    this.dataSource = dataSource;
  }

  public List<Product> allProducts() {
    return jdbc.sql("SELECT id, name, category, color, price_cents, stock FROM products ORDER BY id")
        .query(
            (rs, n) ->
                new Product(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getString("category"),
                    rs.getString("color"),
                    rs.getLong("price_cents"),
                    rs.getInt("stock")))
        .list();
  }

  /** Scoped by customer: another customer's order is indistinguishable from a missing one. */
  public Optional<Order> findOrder(String customerId, String orderId) {
    return jdbc.sql(
            "SELECT id, customer_id, status, created_at, total_cents FROM orders"
                + " WHERE id = :id AND customer_id = :customerId")
        .param("id", orderId)
        .param("customerId", customerId)
        .query((rs, n) -> toOrder(rs.getString("id"), rs))
        .optional();
  }

  public List<Order> listOrders(String customerId, String status) {
    String sql =
        "SELECT id, customer_id, status, created_at, total_cents FROM orders WHERE customer_id = :customerId"
            + (status == null ? "" : " AND status = :status")
            + " ORDER BY created_at DESC";
    var spec = jdbc.sql(sql).param("customerId", customerId);
    if (status != null) {
      spec = spec.param("status", status);
    }
    return spec.query((rs, n) -> toOrder(rs.getString("id"), rs)).list();
  }

  /** Conditional update: only a PLACED order flips to CANCELLED, so retries are harmless. */
  public boolean cancelIfPlaced(String customerId, String orderId) {
    return jdbc.sql(
                "UPDATE orders SET status = 'CANCELLED'"
                    + " WHERE id = :id AND customer_id = :customerId AND status = 'PLACED'")
            .param("id", orderId)
            .param("customerId", customerId)
            .update()
        == 1;
  }

  public long createHandoff(String customerId, String sessionId, String summary) {
    jdbc.sql(
            "INSERT INTO handoffs (customer_id, session_id, summary, created_at)"
                + " VALUES (:customerId, :sessionId, :summary, :ts)")
        .param("customerId", customerId)
        .param("sessionId", sessionId)
        .param("summary", summary)
        .param("ts", Instant.now().toString())
        .update();
    return jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
  }

  /** Re-runs schema.sql + data.sql. Used by evals and the demo "reset" button. */
  public void resetDemoData() {
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql"), new ClassPathResource("data.sql"))
        .execute(dataSource);
  }

  private Order toOrder(String orderId, java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Order(
        orderId,
        rs.getString("customer_id"),
        rs.getString("status"),
        rs.getString("created_at"),
        rs.getLong("total_cents"),
        items(orderId));
  }

  private List<OrderLine> items(String orderId) {
    return jdbc.sql(
            "SELECT oi.product_id, p.name, p.color, oi.qty, oi.unit_price_cents"
                + " FROM order_items oi JOIN products p ON p.id = oi.product_id WHERE oi.order_id = :id")
        .param("id", orderId)
        .query(
            (rs, n) ->
                new OrderLine(
                    rs.getString("product_id"),
                    rs.getString("name"),
                    rs.getString("color"),
                    rs.getInt("qty"),
                    rs.getLong("unit_price_cents")))
        .list();
  }
}
