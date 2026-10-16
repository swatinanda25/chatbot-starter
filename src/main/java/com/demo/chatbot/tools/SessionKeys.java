package com.demo.chatbot.tools;

/** Session-state keys set by the server. The model never supplies these. */
public final class SessionKeys {

  private SessionKeys() {}

  /** Authenticated customer. Tools read it from state, so it is never a model-filled argument. */
  public static final String CUSTOMER_ID = "customer_id";

  /** Set by the eval harness so its traces can be filtered in Langfuse. */
  public static final String EVAL_CASE = "eval_case";
}
