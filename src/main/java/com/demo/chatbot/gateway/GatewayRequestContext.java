package com.demo.chatbot.gateway;

import java.util.Map;

/**
 * Carries per-call trace metadata from {@link GatewayTracedLlm} to {@link GatewayChatModel}. Both
 * run on the same thread inside one synchronous model call, so a ThreadLocal is enough here.
 */
final class GatewayRequestContext {

  private static final ThreadLocal<Map<String, String>> CURRENT = new ThreadLocal<>();

  private GatewayRequestContext() {}

  static void set(Map<String, String> metadata) {
    CURRENT.set(metadata);
  }

  static Map<String, String> get() {
    return CURRENT.get();
  }

  static void clear() {
    CURRENT.remove();
  }
}
