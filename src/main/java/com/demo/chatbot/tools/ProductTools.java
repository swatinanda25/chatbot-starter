package com.demo.chatbot.tools;

import com.demo.chatbot.service.OrderService;
import com.google.adk.tools.Annotations.Schema;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ProductTools {

  private final OrderService catalog;

  public ProductTools(OrderService catalog) {
    this.catalog = catalog;
  }

  @Schema(name = "search_products",
      description = "Search the store catalog. Returns matching products with price, color and stock."
          + " Pass the product words from the customer's request (e.g. \"waterproof jacket\").")
  public Map<String, Object> searchProducts(
      @Schema(name = "query", description = "Product words: type, material, color, use.") String query,
      @Schema(name = "max_price", optional = true, description = "Optional maximum price in US dollars.")
          Double maxPrice) {
    return catalog.searchProducts(query, maxPrice);
  }
}
