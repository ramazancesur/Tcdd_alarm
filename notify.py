"""Bildirim kanalları: Telegram ve e-posta (SMTP)."""
from __future__ import annotations

import os
import smtplib
import ssl
from email.message import EmailMessage

import requests

CHANNELS = {"email": "E-posta", "telegram": "Telegram", "both": "İkisi de"}


def missing_env(channel: str) -> list[str]:
    need: list[str] = []
    if channel in ("telegram", "both"):
        need += [k for k in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID") if not os.getenv(k)]
    if channel in ("email", "both"):
        need += [k for k in ("SMTP_HOST", "SMTP_USER", "SMTP_PASSWORD") if not os.getenv(k)]
    return need


def send_telegram(text: str) -> None:
    tok, chat = os.environ["TELEGRAM_BOT_TOKEN"], os.environ["TELEGRAM_CHAT_ID"]
    r = requests.post(
        f"https://api.telegram.org/bot{tok}/sendMessage",
        json={"chat_id": chat, "text": text, "disable_web_page_preview": True},
        timeout=15,
    )
    r.raise_for_status()


def send_email(to: str, subject: str, body: str) -> None:
    host = os.environ["SMTP_HOST"]
    port = int(os.getenv("SMTP_PORT", "587"))
    user, pwd = os.environ["SMTP_USER"], os.environ["SMTP_PASSWORD"]
    msg = EmailMessage()
    msg["From"] = os.getenv("SMTP_FROM", user)
    msg["To"] = to
    msg["Subject"] = subject
    msg.set_content(body)
    if port == 465:
        with smtplib.SMTP_SSL(host, port, context=ssl.create_default_context(), timeout=30) as s:
            s.login(user, pwd)
            s.send_message(msg)
    else:
        with smtplib.SMTP(host, port, timeout=30) as s:
            s.starttls(context=ssl.create_default_context())
            s.login(user, pwd)
            s.send_message(msg)


def send(settings: dict, subject: str, body: str) -> None:
    """Seçilen kanaldan gönderir. Her durumda konsola da yazar."""
    print("\a" + subject + "\n" + body + "\n")
    channel = settings.get("channel", "console")
    if channel in ("telegram", "both"):
        try:
            send_telegram(f"{subject}\n\n{body}")
        except Exception as e:  # noqa: BLE001
            print(f"Telegram gönderilemedi: {e}")
    if channel in ("email", "both"):
        try:
            send_email(settings["email"], subject, body)
        except Exception as e:  # noqa: BLE001
            print(f"E-posta gönderilemedi: {e}")
