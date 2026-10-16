package com.demo.chatbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The UI is a separate static site (ui/), so the API allows its origin cross-origin. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

  private final String[] allowedOrigins;

  public CorsConfig(@Value("${chatbot.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String[] allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
        .allowedOrigins(allowedOrigins)
        .allowedMethods("GET", "POST")
        .allowedHeaders("Content-Type");
  }
}
