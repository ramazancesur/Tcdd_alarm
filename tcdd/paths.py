"""Dosya yolları ve TCDD uç noktalarının varsayılanları."""

from __future__ import annotations

import os
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent
TZ = ZoneInfo("Europe/Istanbul")

STATIONS_FILE = ROOT / "stations.json"
CONFIG_FILE = ROOT / "alarm.json"
STATE_FILE = ROOT / "state.json"
DUMP_DIR = ROOT / "dumps"
ENV_FILE = ROOT / ".env"

DEFAULT_BASE = "https://web-api-prod-ytp.tcddtasimacilik.gov.tr/tms"
DEFAULT_CDN = "https://cdn-api-prod-ytp.tcddtasimacilik.gov.tr"
SITE = "https://ebilet.tcddtasimacilik.gov.tr/"
MIN_INTERVAL = 60
_DEFAULT_UA = (
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)


def load_dotenv(path: Path | None = None) -> None:
    """``.env`` içindeki eksik ortam değişkenlerini yükler."""
    env_path = path or ENV_FILE
    if not env_path.exists():
        return
    for raw in env_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        cleaned = value.strip().strip('"').strip("'")
        os.environ.setdefault(key.strip(), cleaned)


def base_url() -> str:
    return os.getenv("TCDD_BASE", DEFAULT_BASE)


def cdn_url() -> str:
    return os.getenv("TCDD_CDN", DEFAULT_CDN)


def stations_url() -> str:
    default = f"{cdn_url()}/datas/station-pairs-MOBIL.json"
    return os.getenv("TCDD_STATIONS_URL", default)


def availability_url() -> str:
    default = f"{base_url()}/train/train-availability"
    return os.getenv("TCDD_AVAIL_URL", default)


def seatmap_url() -> str:
    default = f"{base_url()}/seat-maps/load-by-train-id"
    return os.getenv("TCDD_SEATMAP_URL", default)


def date_format() -> str:
    return os.getenv("TCDD_DATE_FMT", "%d-%m-%Y %H:%M:%S")


def unit_id() -> str:
    return os.getenv("TCDD_UNIT_ID", "3895")


def user_agent() -> str:
    return os.getenv("TCDD_UA", _DEFAULT_UA)


def headers() -> dict[str, str]:
    """e-bilet istek başlıkları. ``TCDD_TOKEN`` varsa Authorization eklenir."""
    result = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "tr",
        "Origin": "https://ebilet.tcddtasimacilik.gov.tr",
        "Referer": SITE,
        "User-Agent": user_agent(),
        "unit-id": unit_id(),
    }
    token = os.getenv("TCDD_TOKEN")
    if token:
        result["Authorization"] = token
    return result


class BlockedError(RuntimeError):
    """Sunucu isteği reddetti (401, 403 veya 429)."""
