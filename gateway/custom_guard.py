"""Gateway guardrail for the support bot: generic checks every app behind this gateway gets.

pre_call (before any provider sees the request):
  1. Mask card and SSN numbers in every message (defense in depth; the app also redacts).
  2. Block obvious prompt-injection / jailbreak attempts in the latest user message.

The heuristics here are a cheap first layer. In production you'd chain a model-based classifier
(e.g. Llama Guard, Lakera, Model Armor, all LiteLLM guardrail integrations) through config,
with no app changes.
"""

import re
from typing import Any

from fastapi import HTTPException
from litellm.integrations.custom_guardrail import CustomGuardrail

CARD = re.compile(r"\b(?:\d[ -]?){12,18}\d\b")
SSN = re.compile(r"\b\d{3}-\d{2}-\d{4}\b")

INJECTION = [
    re.compile(p, re.IGNORECASE)
    for p in (
        r"\bignore\s+(all\s+|any\s+|the\s+)?(previous|prior|above|earlier|your)\s+(instructions|rules|prompts?)\b",
        r"\bdisregard\s+(all\s+|your\s+)?(instructions|rules|guidelines)\b",
        r"\b(reveal|print|show|repeat)\s+(me\s+)?(your|the)\s+(system\s+prompt|instructions|hidden\s+rules)\b",
        r"\byou\s+are\s+now\s+(in\s+)?(developer|dan|jailbreak|god)\s*mode\b",
        r"\bact\s+as\s+(an?\s+)?(admin|administrator|system|root)\b",
    )
]


def _luhn(digits: str) -> bool:
    if not 13 <= len(digits) <= 19:
        return False
    total, double = 0, False
    for ch in reversed(digits):
        d = int(ch)
        if double:
            d = d * 2 - 9 if d * 2 > 9 else d * 2
        total += d
        double = not double
    return total % 10 == 0


def _mask(text: str) -> str:
    text = SSN.sub("[REDACTED_ID_NUMBER]", text)
    return CARD.sub(
        lambda m: "[REDACTED_CARD_NUMBER]" if _luhn(re.sub(r"[ -]", "", m.group())) else m.group(), text
    )


def _text_of(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return " ".join(p.get("text", "") for p in content if isinstance(p, dict))
    return ""


class SupportBotGuardrail(CustomGuardrail):
    async def async_pre_call_hook(self, user_api_key_dict, cache, data: dict, call_type):
        messages = data.get("messages") or []

        for msg in messages:
            content = msg.get("content")
            if isinstance(content, str):
                msg["content"] = _mask(content)
            elif isinstance(content, list):
                for part in content:
                    if isinstance(part, dict) and isinstance(part.get("text"), str):
                        part["text"] = _mask(part["text"])

        last_user = next((m for m in reversed(messages) if m.get("role") == "user"), None)
        if last_user is not None:
            text = _text_of(last_user.get("content"))
            for pattern in INJECTION:
                if pattern.search(text):
                    raise HTTPException(
                        status_code=400,
                        detail={"error": "guardrail_blocked: prompt_injection", "pattern": pattern.pattern},
                    )
        return data
