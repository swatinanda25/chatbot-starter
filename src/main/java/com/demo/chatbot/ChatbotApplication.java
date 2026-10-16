package com.demo.chatbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChatbotApplication {

  public static void main(String[] args) {
    SpringApplication.run(ChatbotApplication.class, args);
  }
}
