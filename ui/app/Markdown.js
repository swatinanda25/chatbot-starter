// Renders LLM output as Markdown (GFM: lists, tables, code blocks), sanitized before it hits the DOM.
import { useMemo } from "react";
import { marked } from "marked";
import DOMPurify from "dompurify";
import { html } from "./html.js";

DOMPurify.addHook("afterSanitizeAttributes", (node) => {
  if (node.tagName === "A") {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener noreferrer");
  }
});

export function Markdown({ text }) {
  const __html = useMemo(
    () => DOMPurify.sanitize(marked.parse(text, { gfm: true, breaks: true })),
    [text],
  );
  return html`<div className="md" dangerouslySetInnerHTML=${{ __html }} />`;
}
