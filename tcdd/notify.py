"""E-posta ve Telegram bildirimleri.

Her kanal bir stratejidir. ``both`` iki stratejiyi sırayla çalıştırır.
Eksik ayar konsol kanalına düşmez; çağıran karar verir.
"""

from __future__ import annotations

import os
import smtplib
import ssl
from email.message import EmailMessage
from typing import Protocol

import requests

CHANNELS = {
    "telegram": "Telegram",
    "email": "E-posta",
    "both": "Hem Telegram hem e-posta",
}

_TELEGRAM = ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID")
_EMAIL = ("SMTP_HOST", "SMTP_USER", "SMTP_PASSWORD")


def _blank(name: str) -> bool:
    return not os.getenv(name, "").strip()


class Channel(Protocol):
    """Tek bir iletim yolu."""

    def missing(self) -> list[str]:
        """Eksik ortam değişkenlerinin adları."""

    def deliver(self, settings: dict, subject: str, body: str) -> None:
        """Mesajı iletir. Hata yükseltir."""


class TelegramChannel:
    """Telegram Bot API üzerinden metin gönderir."""

    def missing(self) -> list[str]:
        return [name for name in _TELEGRAM if _blank(name)]

    def deliver(self, settings: dict, subject: str, body: str) -> None:
        del settings
        _send_telegram(f"{subject}\n\n{body}")


class EmailChannel:
    """SMTP ile e-posta gönderir. 465 SSL, diğer portlar STARTTLS."""

    def missing(self) -> list[str]:
        return [name for name in _EMAIL if _blank(name)]

    def deliver(self, settings: dict, subject: str, body: str) -> None:
        address = settings.get("email")
        if not address:
            raise RuntimeError("E-posta adresi ayarlarda yok")
        _send_email(address, subject, body)


_BY_NAME: dict[str, tuple[Channel, ...]] = {
    "telegram": (TelegramChannel(),),
    "email": (EmailChannel(),),
    "both": (TelegramChannel(), EmailChannel()),
    "console": (),
}


def missing_env(channel: str) -> list[str]:
    """Seçilen kanalın eksik ortam değişkenlerini döndürür."""
    missing: list[str] = []
    for channel_impl in _BY_NAME.get(channel, ()):
        missing.extend(channel_impl.missing())
    return missing


def send(settings: dict, subject: str, body: str) -> None:
    """Mesajı terminale yazar ve seçilen kanallara iletir."""
    print(f"\n=== {subject} ===\n{body}\n")
    channel = settings.get("channel", "console")
    for channel_impl in _BY_NAME.get(channel, ()):
        channel_impl.deliver(settings, subject, body)


def _send_telegram(text: str) -> None:
    token = os.environ["TELEGRAM_BOT_TOKEN"].strip()
    chat = os.environ["TELEGRAM_CHAT_ID"].strip()
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    response = requests.post(
        url, json={"chat_id": chat, "text": text}, timeout=30
    )
    if response.status_code == 401:
        raise RuntimeError("Telegram token reddedildi")
    response.raise_for_status()
    body = response.json()
    if not body.get("ok"):
        detail = body.get("description") or "Telegram iletimi başarısız"
        raise RuntimeError(detail)


def _send_email(to: str, subject: str, body: str) -> None:
    host = os.environ["SMTP_HOST"].strip()
    user = os.environ["SMTP_USER"].strip()
    password = os.environ["SMTP_PASSWORD"]
    port = int(os.getenv("SMTP_PORT", "587") or "587")
    sender = os.getenv("SMTP_FROM", "").strip() or user
    message = EmailMessage()
    message["Subject"] = subject
    message["From"] = sender
    message["To"] = to
    message.set_content(body)
    if port == 465:
        _send_ssl(host, port, user, password, message)
        return
    _send_starttls(host, port, user, password, message)


def _send_ssl(host, port, user, password, message: EmailMessage) -> None:
    context = ssl.create_default_context()
    with smtplib.SMTP_SSL(host, port, timeout=30, context=context) as smtp:
        smtp.login(user, password)
        smtp.send_message(message)


def _send_starttls(host, port, user, password, message: EmailMessage) -> None:
    context = ssl.create_default_context()
    with smtplib.SMTP(host, port, timeout=30) as smtp:
        smtp.ehlo()
        smtp.starttls(context=context)
        smtp.ehlo()
        smtp.login(user, password)
        smtp.send_message(message)
