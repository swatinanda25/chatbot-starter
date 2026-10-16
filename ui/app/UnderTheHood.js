// "Under the hood" panel: latency percentiles per agent, the fact-consistency (hallucination)
// check, feedback, tokens and guardrails across the app, plus the current session's audit trail.
import { useEffect, useState } from "react";
import { html } from "./html.js";
import { getAudit, getSummary } from "./api.js";

const secs = (ms) => (ms == null ? "–" : (ms / 1000).toFixed(1) + "s");
const pct = (x) => (x == null ? "–" : Math.round(x * 100) + "%");

function Section({ title, hint, children }) {
  return html`
    <section>
      <h3>${title}${hint && html` <span className="hint" title=${hint}>ⓘ</span>`}</h3>
      ${children}
    </section>`;
}

function Empty({ children }) {
  return html`<p className="empty">${children}</p>`;
}

function Latency({ rows, window }) {
  if (!rows.length) return html`<${Empty}>No turns yet.<//>`;
  return html`
    <table>
      <thead><tr><th>Agent</th><th>Turns</th><th>P50</th><th>P90</th><th>P99</th><th>Max</th></tr></thead>
      <tbody>${rows.map((r) => html`
        <tr key=${r.agent}>
          <td>${r.agent}</td><td>${r.turns}</td><td>${secs(r.p50_ms)}</td>
          <td><b>${secs(r.p90_ms)}</b></td><td>${secs(r.p99_ms)}</td><td>${secs(r.max_ms)}</td>
        </tr>`)}
      </tbody>
    </table>
    <p className="note">Percentiles over the last ${window} minutes; turns since start.</p>`;
}

function Facts({ rows }) {
  if (!rows.length) return html`<${Empty}>No replies checked yet.<//>`;
  return html`
    <table>
      <thead><tr><th>Agent</th><th>Replies with facts</th><th>Unsupported</th><th>Rate</th></tr></thead>
      <tbody>${rows.map((r) => html`
        <tr key=${r.agent} className=${r.unsupported ? "bad" : ""}>
          <td>${r.agent}</td><td>${r.replies_with_facts}</td><td>${r.unsupported}</td><td><b>${pct(r.unsupported_rate)}</b></td>
        </tr>`)}
      </tbody>
    </table>`;
}

function Feedback({ f }) {
  return html`
    <div className="tiles">
      <div className="tile"><b>${pct(f.satisfaction)}</b><span>👍 rate (${f.up} 👍 · ${f.down} 👎)</span></div>
      <div className="tile"><b>${pct(f.response_rate)}</b><span>of ${f.turns} turns got a vote</span></div>
    </div>`;
}

function Context({ c }) {
  if (!c.agents.length) return html`<${Empty}>No model calls yet.<//>`;
  return html`
    <table>
      <thead><tr><th>Agent step</th><th>Calls</th><th>Prompt P50</th><th>P90</th><th>Max</th><th>Window</th><th>Trimmed</th></tr></thead>
      <tbody>${c.agents.map((r) => html`
        <tr key=${r.agent} className=${r.max_window_used > 0.8 ? "bad" : ""}>
          <td>${r.agent}</td><td>${r.calls}</td><td>${r.p50_prompt_tokens ?? "–"}</td><td>${r.p90_prompt_tokens ?? "–"}</td>
          <td>${r.max_prompt_tokens}</td>
          <td><div className="meter" title=${pct(r.max_window_used) + " of the model's context at the largest call"}>
            <i style=${{ width: Math.min(100, (r.max_window_used || 0) * 100) + "%" }}></i></div></td>
          <td>${r.trimmed_calls ? `${r.trimmed_calls} (−${r.dropped_messages} msgs)` : "–"}</td>
        </tr>`)}
      </tbody>
    </table>
    <p className="note">Model context ${c.model_context_tokens.toLocaleString()} tokens · each call keeps the last ${c.max_history_turns || "∞"} customer turns.</p>`;
}

function Tokens({ byAgent }) {
  const rows = Object.entries(byAgent);
  if (!rows.length) return html`<${Empty}>No model calls yet.<//>`;
  return html`
    <table>
      <thead><tr><th>Agent step</th><th>Input</th><th>Output</th></tr></thead>
      <tbody>${rows.map(([agent, t]) => html`
        <tr key=${agent}><td>${agent}</td><td>${t.input ?? 0}</td><td>${t.output ?? 0}</td></tr>`)}
      </tbody>
    </table>`;
}

function Guardrails({ counts }) {
  const rows = Object.entries(counts);
  if (!rows.length) return html`<${Empty}>None fired yet.<//>`;
  return html`<div className="chips">${rows.map(([name, n]) => html`<span key=${name} className="chip">🛡 ${name} · ${n}</span>`)}</div>`;
}

// The owner's my_marketplace agent: verdicts shown, and the LLM sentiment classifier behind them.
function Marketplace({ m }) {
  const reports = Object.entries(m.reports);
  const labels = Object.entries(m.classifier.labels);
  if (!reports.length && !m.classifier.calls) return html`<${Empty}>No feedback reports yet.<//>`;
  return html`
    <table>
      <tbody>
        <tr><td>Reports by verdict</td><td>${reports.map(([v, n]) => `${v} · ${n}`).join(", ") || "–"}</td></tr>
        <tr><td>Classifier calls</td><td>${m.classifier.calls} (P50 ${secs(m.classifier.p50_ms)}, P90 ${secs(m.classifier.p90_ms)}, errors ${m.classifier.errors})</td></tr>
        <tr><td>Comments labeled</td><td>${labels.map(([l, n]) => `${l} · ${n}`).join(", ") || "–"}</td></tr>
        <tr className=${m.withheld_turns ? "bad" : ""}><td>Turns with withheld comments</td><td>${m.withheld_turns}</td></tr>
      </tbody>
    </table>`;
}

// Turns and one-line summaries come from the server (AuditPresenter); raw JSON is one click away.
function Audit({ turns }) {
  if (!turns.length) return html`<${Empty}>Nothing recorded for this session yet.<//>`;
  return html`
    <div className="audit">${turns.map((t, ti) => html`
      <div key=${t.invocationId || "feedback-" + ti} className="turn">
        <div className="turn-label">${t.label}</div>
        ${t.rows.map((r, i) => html`
          <details key=${i} className=${`row ${r.severity}`}>
            <summary>
              <span className="time">${r.time}</span>
              <span className="icon">${r.icon}</span>
              <span className="what" title=${r.summary}>${r.summary}</span>
              ${r.latencyMs != null && r.kind === "tool" && html`<span className="ms">${r.latencyMs} ms</span>`}
            </summary>
            <div className="detail">
              <div><b>${r.kind}</b> ${r.name} · <b>outcome</b> ${r.outcome} · <b>agent</b> ${r.agent}</div>
              ${r.argsJson && html`<div><b>${r.kind === "fact_check" ? "facts" : "args"}</b> <code>${r.argsJson}</code></div>`}
              ${r.resultJson && html`<div><b>result</b> <code>${r.resultJson.length > 600 ? r.resultJson.slice(0, 600) + "…" : r.resultJson}</code></div>`}
            </div>
          </details>`)}
      </div>`)}
    </div>`;
}

export function UnderTheHood({ sessionId, refreshKey, onClose }) {
  const [summary, setSummary] = useState(null);
  const [audit, setAudit] = useState([]);
  const [error, setError] = useState(null);

  async function load() {
    try {
      const [s, a] = await Promise.all([getSummary(), sessionId ? getAudit(sessionId) : []]);
      setSummary(s);
      setAudit(a);
      setError(null);
    } catch (e) {
      setError(e.message);
    }
  }

  // Reload when opened, when the session changes, and after every turn.
  useEffect(() => { load(); }, [sessionId, refreshKey]);

  return html`
    <aside className="hood" aria-label="Under the hood">
      <div className="hood-head">
        <h2>Under the hood</h2>
        <span className="spacer"></span>
        <button onClick=${load}>Refresh</button>
        <button onClick=${onClose} aria-label="Close">✕</button>
      </div>
      ${error && html`<p className="msg error">${error}</p>`}
      ${summary && html`
        <${Section} title="Turn latency" hint="P50: half of turns are faster than this (the typical experience). P90: 1 in 10 turns is slower (the tail customers notice).">
          <${Latency} rows=${summary.latency} window=${summary.percentile_window_minutes} />
        <//>
        <${Section} title="Hallucination check" hint="Order ids, prices, ticket ids and statuses in each customer-facing reply must appear in a tool result or the customer's own message. Measured on the model's raw reply, before guardrails rewrite it. A lower bound: it doesn't judge reasoning or prose.">
          <${Facts} rows=${summary.facts} />
        <//>
        <${Section} title="Feedback" hint="👍 rate among votes. The response rate shows how few turns get a vote, so read the rate with care.">
          <${Feedback} f=${summary.feedback} />
        <//>
        <${Section} title="Context window" hint="Prompt tokens per model call: the whole history is resent each call, so this grows with the conversation. Window = largest call as a share of the model's context. Trimmed = calls where the history limit dropped older turns.">
          <${Context} c=${summary.context} />
        <//>
        <${Section} title="Tokens" hint="Per agent step since start: sub-agents (order_agent, triage_agent…) are counted separately.">
          <${Tokens} byAgent=${summary.tokens} />
        <//>
        <${Section} title="Guardrails fired" hint="Since start, by guardrail.">
          <${Guardrails} counts=${summary.guardrails} />
        <//>
        <${Section} title="My Marketplace" hint="The owner's agent. Verdicts are computed from per-comment sentiment labels; an LLM classifier labels each comment once (stored afterwards). Withheld = comments that read like instructions to a model, never shown to the classifier or the agent.">
          <${Marketplace} m=${summary.marketplace} />
        <//>`}
      <${Section} title="This session's audit trail" hint="From audit_log, grouped by turn: every tool call, guardrail decision, fact check and vote in this session. Expand a row for the raw JSON.">
        <${Audit} turns=${audit} />
      <//>
    </aside>`;
}
