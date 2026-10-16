package com.demo.chatbot.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.chatbot.domain.StoreRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Business rules, no LLM involved: fast, deterministic, and the real safety net. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:./target/test-store.db")
class OrderServiceTest {

  @Autowired OrderService orders;
  @Autowired StoreRepository store;

  @BeforeEach
  void reset() {
    store.resetDemoData();
  }

  @Test
  void cancelsAPlacedOrderOnce() {
    Map<String, Object> first = orders.cancelOrder("C001", "1004", "ordered by mistake");
    assertThat(ToolResult.isOk(first)).isTrue();

    Map<String, Object> second = orders.cancelOrder("C001", "1004", null);
    assertThat(second).containsEntry("error_code", "ALREADY_CANCELLED");
  }

  @Test
  void refusesToCancelShippedOrDeliveredOrders() {
    assertThat(orders.cancelOrder("C001", "1002", null)).containsEntry("error_code", "NOT_CANCELLABLE");
    assertThat(orders.cancelOrder("C001", "1001", null)).containsEntry("error_code", "NOT_CANCELLABLE");
  }

  @Test
  void anotherCustomersOrderLooksLikeItDoesNotExist() {
    assertThat(orders.getOrder("C001", "1006")).containsEntry("error_code", "NOT_FOUND");
    assertThat(orders.cancelOrder("C001", "1006", null)).containsEntry("error_code", "NOT_FOUND");
    assertThat(ToolResult.isOk(orders.getOrder("C002", "1006"))).isTrue();
  }

  @Test
  void validatesModelSuppliedArguments() {
    assertThat(orders.getOrder("C001", "'; DROP TABLE orders; --")).containsEntry("error_code", "INVALID_ORDER_ID");
    assertThat(orders.getOrder("C001", "#1003")).containsEntry("ok", true);
    assertThat(orders.listOrders("C001", "lost")).containsEntry("error_code", "INVALID_STATUS");
  }

  @Test
  void precheckDoesNotChangeAnything() {
    assertThat(orders.cancelPrecheck("C001", "1003")).isEmpty();
    assertThat(store.findOrder("C001", "1003").orElseThrow().status()).isEqualTo("PLACED");
  }

  @Test
  @SuppressWarnings("unchecked")
  void searchRespectsBudget() {
    Map<String, Object> data = (Map<String, Object>) orders.searchProducts("waterproof jacket", 150.0).get("data");
    List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
    assertThat(products).extracting(p -> p.get("product_id")).contains("P103").doesNotContain("P102");
  }
}
