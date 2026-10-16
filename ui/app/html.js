// JSX-like templates without a build step: html`<div className="x">${value}</div>`
import { createElement } from "react";
import htm from "htm";

export const html = htm.bind(createElement);
