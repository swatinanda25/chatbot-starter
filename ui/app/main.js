import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { html } from "./html.js";
import { Markdown } from "./Markdown.js";
import { createSession, listAgents, resetData, sendFeedback, sendTurn } from "./api.js";
import { UnderTheHood } from "./UnderTheHood.js";

const MAX_CHARS = 2000; // matches chatbot.max-message-chars
const GREETING = "Hi! How can I help you today?";

let lastId = 0;
const msg = (fields) => ({ id: ++lastId, ...fields });

// Token usage from the server: { input, output, total } for one model call or a whole turn.
const fmtTokens = (t) => `${t.input} in · ${t.output} out · ${t.total} tokens`;
const addTokens = (a, b) => ({ input: a.input + b.input, output: a.output + b.output, total: a.total + b.total });
const NO_TOKENS = { input: 0, output: 0, total: 0 };

// Trace rows for what the agents did: transfers, tool calls and tool results.
function traceRow(ev) {
  if (ev.type === "transfer") return { title: `↪ transfer ${ev.from} → ${ev.to}`, cls: "transfer" };
  if (ev.type === "tool_call") return { title: `🔧 ${ev.author} → ${ev.name} · ${ev.risk}`, json: ev.args, tokens: ev.tokens };
  if (ev.outcome === "awaiting_confirmation") return { title: `⏸ ${ev.name} · waiting for your confirmation`, json: ev.response, cls: "guard" };
  if (ev.outcome === "rejected_by_user") return { title: `↩ ${ev.name} · you declined, nothing changed`, json: ev.response, cls: "guard" };
  const ok = ev.outcome === "ok";
  return { title: `${ok ? "✓" : "✗"} ${ev.name} · ${ev.outcome}`, json: ev.response, cls: ok ? "ok" : "err" };
}

function Trace({ m }) {
  return html`
    <details className=${`msg trace ${m.cls || ""}`}>
      <summary>${m.title}${m.tokens && html` <span className="tokens">· ${fmtTokens(m.tokens)}</span>`}</summary>
      ${m.json && html`<pre>${JSON.stringify(m.json, null, 2)}</pre>`}
    </details>`;
}

// A CONFIRM-tier tool waiting for the customer. The text comes from server data, not the model.
function Confirm({ m, onAnswer, busy }) {
  return html`
    <div className="msg confirm">
      <div className="who">Please confirm</div>
      <div>${m.summary}</div>
      ${m.answer == null
        ? html`<div className="actions">
            <button className="primary" disabled=${busy} onClick=${() => onAnswer(m, true)}>Yes, ${m.tool.replace("_", " ")}</button>
            <button disabled=${busy} onClick=${() => onAnswer(m, false)}>No, keep it</button>
          </div>`
        : html`<div className="note">${m.answer ? "You confirmed." : "You declined."}</div>`}
    </div>`;
}

function Message({ m, onAnswer, onVote, busy }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    await navigator.clipboard.writeText(m.text);
    setCopied(true);
    setTimeout(() => setCopied(false), 1200);
  };

  if (m.role === "user") return html`<div className="msg user">${m.text}</div>`;
  if (m.role === "error") return html`<div className="msg error">${m.text}</div>`;
  if (m.role === "guardrail") {
    return html`
      <div className="msg guardrail" title=${m.detail}>
        🛡 ${m.name} · ${m.outcome}<span className="detail"> — ${m.detail}</span>
      </div>`;
  }
  if (m.role === "trace") return html`<${Trace} m=${m} />`;
  if (m.role === "confirm") return html`<${Confirm} m=${m} onAnswer=${onAnswer} busy=${busy} />`;
  if (m.role === "step") {
    // Intermediate output of a pipeline step (e.g. triage, draft): a trace row, not an answer.
    return html`
      <details className="msg step">
        <summary>step · ${m.author}${m.tokens && html` <span className="tokens">· ${fmtTokens(m.tokens)}</span>`}</summary>
        <${Markdown} text=${m.text} />
      </details>`;
  }
  return html`
    <div className="msg bot">
      <div className="who">${m.author || "assistant"}</div>
      <${Markdown} text=${m.text} />
      <div className="foot">
        ${m.tokens && html`<span title="This reply's model call">${fmtTokens(m.tokens)}</span>`}
        ${m.turnTokens && m.turnTokens.total !== m.tokens?.total &&
          html`<span title="All model calls in this turn">turn: ${m.turnTokens.total} tokens</span>`}
        ${m.latencyMs != null && html`<span>${(m.latencyMs / 1000).toFixed(1)}s</span>`}
        ${m.traceId && (m.vote == null
          ? html`<button className="link" title="Helpful" onClick=${() => onVote(m, 1)}>👍</button>
                 <button className="link" title="Not helpful" onClick=${() => onVote(m, -1)}>👎</button>`
          : html`<span>${m.vote > 0 ? "👍" : "👎"} thanks</span>`)}
        <button className="link" onClick=${copy}>${copied ? "Copied" : "Copy"}</button>
      </div>
    </div>`;
}

function Typing() {
  return html`<div className="msg bot typing" aria-label="Assistant is typing"><i></i><i></i><i></i></div>`;
}

function App() {
  const [agents, setAgents] = useState([]);
  const [agent, setAgent] = useState(null);
  const [session, setSession] = useState(null);
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [chatTokens, setChatTokens] = useState(NO_TOKENS);
  const [hoodOpen, setHoodOpen] = useState(false);
  const [turnCount, setTurnCount] = useState(0); // bumps after every turn, so the panel reloads
  const logRef = useRef(null);
  const inputRef = useRef(null);

  const add = (m) => setMessages((ms) => [...ms, msg(m)]);
  const patch = (id, fields) => setMessages((ms) => ms.map((m) => (m.id === id ? { ...m, ...fields } : m)));

  // Sessions are bound to one agent, so switching agent starts a new chat.
  async function newChat(name = agent) {
    setSession(null);
    setMessages([]);
    setChatTokens(NO_TOKENS);
    try {
      setSession(await createSession(name));
      add({ role: "bot", author: "assistant", text: GREETING });
    } catch (e) {
      add({ role: "error", text: e.message });
    }
    inputRef.current?.focus();
  }

  useEffect(() => {
    listAgents()
      .then((r) => {
        setAgents(r.agents);
        setAgent(r.default);
        newChat(r.default);
      })
      .catch((e) => add({ role: "error", text: e.message }));
  }, []);

  function pickAgent(name) {
    setAgent(name);
    newChat(name);
  }

  useEffect(() => {
    const el = logRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, busy]);

  // Runs one turn: a typed message, or the customer's answer to a confirmation card.
  async function runTurn(body) {
    setBusy(true);
    let traceId = null;
    try {
      for await (const ev of sendTurn(session.sessionId, body)) {
        if (ev.type === "turn") traceId = ev.trace_id;
        else if (ev.type === "message") add({ role: "bot", author: ev.author, text: ev.text, tokens: ev.tokens });
        else if (ev.type === "step") add({ role: "step", author: ev.author, text: ev.text, tokens: ev.tokens });
        else if (ev.type === "transfer" || ev.type === "tool_call" || ev.type === "tool_result") add({ role: "trace", ...traceRow(ev) });
        else if (ev.type === "confirmation") add({ role: "confirm", confirmId: ev.id, tool: ev.tool, summary: ev.summary, answer: null });
        else if (ev.type === "guardrail") add({ role: "guardrail", name: ev.name, outcome: ev.outcome, detail: ev.detail });
        else if (ev.type === "error") add({ role: "error", text: ev.message });
        else if (ev.type === "done") {
          // Attach turn latency, token totals and the trace id (for feedback) to the last reply.
          setMessages((ms) => {
            const i = ms.findLastIndex((m) => m.role === "bot");
            if (i < 0) return ms;
            const copy = ms.slice();
            copy[i] = { ...copy[i], latencyMs: ev.latency_ms, turnTokens: ev.tokens, traceId };
            return copy;
          });
          if (ev.tokens) setChatTokens((t) => addTokens(t, ev.tokens));
        }
      }
    } catch (err) {
      add({ role: "error", text: err.message || "Connection problem." });
    } finally {
      setBusy(false);
      setTurnCount((n) => n + 1);
      inputRef.current?.focus();
    }
  }

  async function submit(e) {
    e?.preventDefault();
    const text = input.trim();
    if (!text || busy || !session) return;
    setInput("");
    add({ role: "user", text });
    await runTurn({ message: text });
  }

  async function answer(m, confirmed) {
    patch(m.id, { answer: confirmed });
    await runTurn({ confirmation: { id: m.confirmId, confirmed } });
  }

  function vote(m, score) {
    patch(m.id, { vote: score });
    sendFeedback(session.sessionId, m.traceId, score).then(() => setTurnCount((n) => n + 1)).catch(() => {});
  }

  async function reset() {
    try {
      await resetData();
      add({ role: "guardrail", name: "demo data", outcome: "reset", detail: "orders restored to the seed data" });
    } catch (e) {
      add({ role: "error", text: e.message });
    }
  }

  function onKeyDown(e) {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) submit(e);
  }

  const rows = Math.min(6, input.split("\n").length);

  return html`
    <header>
      <h1>Trailhead Outfitters · Support</h1>
      <span className="meta">
        ${session ? `signed in as ${session.customerId} · session ${session.sessionId.slice(0, 8)}` : "connecting…"}
      </span>
      ${chatTokens.total > 0 && html`
        <span className="meta" title="Token usage of this chat so far">${fmtTokens(chatTokens)}</span>`}
      <span className="spacer"></span>
      <select
        value=${agent || ""}
        disabled=${busy || !agents.length}
        title=${agents.find((a) => a.name === agent)?.description || "Agent"}
        onChange=${(e) => pickAgent(e.target.value)}
      >
        ${agents.map((a) => html`<option key=${a.name} value=${a.name}>${a.label || a.name}</option>`)}
      </select>
      <button onClick=${() => setHoodOpen((o) => !o)} aria-pressed=${hoodOpen} title="Latency, hallucination check, feedback, tokens, audit trail">Under the hood</button>
      <button onClick=${reset} disabled=${busy} title="Restore the demo orders">Reset data</button>
      <button onClick=${() => newChat()} disabled=${busy} title="Start a new conversation">New chat</button>
    </header>
    <main>
      <div className="log" ref=${logRef}>
        ${messages.map((m) => html`<${Message} key=${m.id} m=${m} onAnswer=${answer} onVote=${vote} busy=${busy} />`)}
        ${busy && html`<${Typing} />`}
      </div>
      <form onSubmit=${submit}>
        <textarea
          ref=${inputRef}
          rows=${rows}
          value=${input}
          maxLength=${MAX_CHARS}
          placeholder="Ask a question… (Shift+Enter for a new line)"
          onInput=${(e) => setInput(e.target.value)}
          onKeyDown=${onKeyDown}
        />
        <button className="primary" disabled=${busy || !session || !input.trim()}>Send</button>
      </form>
    </main>
    ${hoodOpen && html`
      <${UnderTheHood} sessionId=${session?.sessionId} refreshKey=${turnCount} onClose=${() => setHoodOpen(false)} />`}`;
}

createRoot(document.getElementById("root")).render(html`<${App} />`);
