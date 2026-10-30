"""İstasyon listesinin okunması ve önbelleğe alınması."""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass, field

import requests

from tcdd.jsonutil import as_int, pick
from tcdd.models import tr_norm
from tcdd.paths import STATIONS_FILE, BlockedError, headers, stations_url


@dataclass
class Station:
    """Satışa açık bir istasyon ve gidilebilen eşleri."""

    id: int
    name: str
    pairs: list[int] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {"id": self.id, "name": self.name, "pairs": list(self.pairs)}


DEMO_STATIONS = [
    Station(98, "ANKARA GAR"),
    Station(48, "İSTANBUL(PENDİK)"),
    Station(49, "İSTANBUL(SÖĞÜTLÜÇEŞME)"),
    Station(50, "İSTANBUL(HALKALI)"),
    Station(60, "ESKİŞEHİR"),
    Station(70, "KONYA"),
    Station(80, "İZMİR(BASMANE)"),
    Station(90, "SİVAS"),
]


def _pair_ids(value) -> list[int]:
    found = []
    for pair in value if isinstance(value, list) else []:
        raw = pair.get("id") if isinstance(pair, dict) else pair
        number = as_int(raw)
        if number is not None:
            found.append(number)
    return found


def parse_stations(data) -> list[Station]:
    """Farklı JSON şekillerinden istasyon listesi çıkarır."""
    rows = _station_rows(data)
    stations = []
    for row in rows:
        if not isinstance(row, dict) or row.get("ticketSaleActive") is False:
            continue
        station_id = as_int(pick(row, "id", "stationId", "station_id"))
        name = pick(row, "name", "stationName", "station_name")
        if station_id is not None and name:
            stations.append(
                Station(station_id, str(name), _pair_ids(row.get("pairs")))
            )
    return sorted(stations, key=lambda station: tr_norm(station.name))


def _station_rows(data):
    if isinstance(data, dict):
        for key in ("stations", "data", "result", "content", "items"):
            if isinstance(data.get(key), list):
                return data[key]
    return data if isinstance(data, list) else []


def _from_cache() -> list[Station]:
    raw = json.loads(STATIONS_FILE.read_text(encoding="utf-8"))
    return [
        Station(item["id"], item["name"], list(item.get("pairs") or []))
        for item in raw
    ]


def get_stations(refresh: bool = False) -> list[Station]:
    """Önbellekteki listeyi, yoksa e-bilet listesini döndürür."""
    if STATIONS_FILE.exists() and not refresh:
        return _from_cache()
    print("İstasyon listesi e-bilet'ten alınıyor...")
    try:
        stations = _download()
    except Exception as error:  # CLI sınırı: kullanıcıya seçenekleri göster.
        _exit_station_error(error)
    _write(stations)
    print(f"{len(stations)} istasyon kaydedildi.")
    return stations


def _download() -> list[Station]:
    public = {
        key: value
        for key, value in headers().items()
        if key != "Authorization"
    }
    response = requests.get(stations_url(), headers=public, timeout=30)
    if response.status_code in (401, 403):
        response = requests.get(stations_url(), headers=headers(), timeout=30)
    if response.status_code in (401, 403, 429):
        raise BlockedError(f"HTTP {response.status_code}")
    response.raise_for_status()
    stations = parse_stations(response.json())
    if not stations:
        raise ValueError("Listede istasyon bulunamadı")
    return stations


def _write(stations: list[Station]) -> None:
    payload = [station.to_dict() for station in stations]
    text = json.dumps(payload, ensure_ascii=False, indent=1)
    STATIONS_FILE.write_text(text, encoding="utf-8")


def _exit_station_error(error: Exception) -> None:
    sys.exit(
        f"İstasyon listesi alınamadı ({error}).\n"
        "Seçenekler:\n"
        "  1) .env dosyasına TCDD_TOKEN=... ekleyin "
        "(e-bilet'te F12 > Network > bir isteğin 'Authorization' başlığı)\n"
        "  2) Tarayıcıda e-bilet'i açıp Network sekmesinde istasyon listesi "
        "isteğinin JSON cevabını\n"
        f"     {STATIONS_FILE.name} olarak bu klasöre kaydedin, "
        "ya da TCDD_STATIONS_URL ile doğru adresi verin."
    )
