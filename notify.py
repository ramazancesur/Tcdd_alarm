"""E-posta ve Telegram bildirimleri. Eksik ayar konsola düşer."""
from __future__ import annotations

import os
import smtplib
import ssl
from email.message import EmailMessage

import requests

CHANNELS = {
    "telegram": "Telegram",
    "email": "E-posta",
    "both": "Hem Telegram hem e-posta",
}

_TELEGRAM = ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID")
_EMAIL = ("SMTP_HOST", "SMTP_USER", "SMTP_PASSWORD")


def missing_env(channel: str) -> list[str]:
    if channel == "console":
        return []
    need: list[str] = []
    if channel in ("telegram", "both"):
        need.extend(k for k in _TELEGRAM if not os.getenv(k, "").strip())
    if channel in ("email", "both"):
        need.extend(k for k in _EMAIL if not os.getenv(k, "").strip())
    return need


def _telegram(text: str) -> None:
    token = os.environ["TELEGRAM_BOT_TOKEN"].strip()
    chat = os.environ["TELEGRAM_CHAT_ID"].strip()
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    r = requests.post(url, json={"chat_id": chat, "text": text}, timeout=30)
    if r.status_code == 401:
        raise RuntimeError("Telegram token reddedildi")
    r.raise_for_status()
    body = r.json()
    if not body.get("ok"):
        raise RuntimeError(body.get("description") or "Telegram iletimi başarısız")


def _email(to: str, subject: str, body: str) -> None:
    host = os.environ["SMTP_HOST"].strip()
    user = os.environ["SMTP_USER"].strip()
    password = os.environ["SMTP_PASSWORD"]
    port = int(os.getenv("SMTP_PORT", "587") or "587")
    sender = os.getenv("SMTP_FROM", "").strip() or user
    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = sender
    msg["To"] = to
    msg.set_content(body)
    if port == 465:
        with smtplib.SMTP_SSL(host, port, timeout=30, context=ssl.create_default_context()) as smtp:
            smtp.login(user, password)
            smtp.send_message(msg)
        return
    with smtplib.SMTP(host, port, timeout=30) as smtp:
        smtp.ehlo()
        smtp.starttls(context=ssl.create_default_context())
        smtp.ehlo()
        smtp.login(user, password)
        smtp.send_message(msg)


def send(settings: dict, subject: str, body: str) -> None:
    """Mesajı terminale yazar ve seçilen kanala iletir. İletim hatası yükseltir."""
    print(f"\n=== {subject} ===\n{body}\n")
    channel = settings.get("channel", "console")
    if channel == "console":
        return
    if channel in ("telegram", "both"):
        _telegram(f"{subject}\n\n{body}")
    if channel in ("email", "both"):
        addr = settings.get("email")
        if not addr:
            raise RuntimeError("E-posta adresi ayarlarda yok")
        _email(addr, subject, body)
