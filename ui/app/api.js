// Backend calls. /api/chat streams server-sent events:
//   turn (trace_id) · message · step · transfer · tool_call · tool_result · confirmation · guardrail · error · done
// Model output carries tokens: {input, output, total} for its model call; "done" carries the turn total.

const API = (window.CHATBOT_API_URL || "").replace(/\/$/, ""); // set in config.js

export async function listAgents() {
  const res = await fetch(API + "/api/agents");
  if (!res.ok) throw new Error("Could not load agents (" + res.status + ")");
  return res.json(); // { agents: [{ name, label, description }], default }
}

export async function createSession(agent) {
  const res = await fetch(API + "/api/sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ agent }),
  });
  if (!res.ok) throw new Error("Could not start a session (" + res.status + ")");
  return res.json(); // { sessionId, customerId, agent }
}

// One turn: either { message } or { confirmation: { id, confirmed } }.
export async function* sendTurn(sessionId, body) {
  const res = await fetch(API + "/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionId, ...body }),
  });
  if (res.status === 404) throw new Error("Session expired. Start a new chat.");
  if (res.status === 400 && body.confirmation) throw new Error("That confirmation was already answered.");
  if (!res.ok) throw new Error("Request failed (" + res.status + ")");

  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true }).replace(/\r\n/g, "\n");
    let i;
    while ((i = buf.indexOf("\n\n")) >= 0) {
      const chunk = buf.slice(0, i);
      buf = buf.slice(i + 2);
      const data = chunk
        .split("\n")
        .filter((l) => l.startsWith("data:"))
        .map((l) => l.slice(5))
        .join("\n");
      if (data) yield JSON.parse(data);
    }
  }
}

export async function sendFeedback(sessionId, traceId, score) {
  await fetch(API + "/api/feedback", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionId, traceId, score, comment: "" }),
  });
}

// Demo only: restore the seed orders (e.g. after cancelling some).
export async function resetData() {
  const res = await fetch(API + "/api/admin/reset", { method: "POST" });
  if (!res.ok) throw new Error("Reset failed (" + res.status + ")");
}

// "Under the hood" panel: headline metrics, and one session's audit trail.
export async function getSummary() {
  const res = await fetch(API + "/api/observability/summary");
  if (!res.ok) throw new Error("Could not load metrics (" + res.status + ")");
  return res.json();
}

// Presented by the server (AuditPresenter): [{ invocationId, startedAt, label, rows: [{ time, icon, summary, severity, ... }] }]
export async function getAudit(sessionId) {
  const res = await fetch(API + "/api/sessions/" + encodeURIComponent(sessionId) + "/audit/view");
  if (!res.ok) throw new Error("Could not load the audit log (" + res.status + ")");
  return res.json();
}
