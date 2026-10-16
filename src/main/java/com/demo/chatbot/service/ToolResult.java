package com.demo.chatbot.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uniform tool response envelope. Errors are data, not exceptions, so the model can read the code
 * and recover (ask a question, explain, or offer a hand-off) instead of the turn crashing.
 */
public final class ToolResult {

  private ToolResult() {}

  public static Map<String, Object> ok(Object data) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ok", true);
    m.put("data", data);
    return m;
  }

  public static Map<String, Object> error(String code, String message) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ok", false);
    m.put("error_code", code);
    m.put("message", message);
    return m;
  }

  public static boolean isOk(Map<String, Object> result) {
    return Boolean.TRUE.equals(result.get("ok"));
  }

  public static String money(long cents) {
    return String.format("$%d.%02d", cents / 100, cents % 100);
  }
}
