"""TCDD e-bilet HTTP istemcisi."""

from __future__ import annotations

import json
import os

import requests

from tcdd.config import AlarmConfig
from tcdd.models import Seat
from tcdd.paths import (
    DUMP_DIR,
    BlockedError,
    availability_url,
    headers,
    seatmap_url,
)
from tcdd.schedule import date_param, extract_trains
from tcdd.seatmap import parse_seatmap


class TcddClient:
    """Sefer ve koltuk haritası sorguları."""

    def trains(self, config: AlarmConfig, day: str) -> list[dict]:
        payload = {
            "searchRoutes": [
                {
                    "departureStationId": config.origin.id,
                    "departureStationName": config.origin.name,
                    "arrivalStationId": config.destination.id,
                    "arrivalStationName": config.destination.name,
                    "departureDate": date_param(day),
                }
            ],
            "passengerTypeCounts": [
                {"id": 0, "count": len(config.genders)}
            ],
            "searchReservation": False,
            "searchType": "DOMESTIC",
            "blTrainTypes": ["TURISTIK_TREN"],
        }
        body = _post(availability_url(), payload)
        return extract_trains(body, config.prefs().kinds)

    def seats(self, config: AlarmConfig, day: str, train: dict) -> list[Seat]:
        """Seçilen trenin koltuk haritası. Kimlik yoksa boş liste."""
        if train.get("id") is None:
            return []
        payload = {
            "fromStationId": train.get("from_id") or config.origin.id,
            "toStationId": train.get("to_id") or config.destination.id,
            "trainId": train["id"],
        }
        data = _post(seatmap_url(), payload)
        if os.getenv("TCDD_DUMP"):
            _dump(day, train["id"], data)
        return parse_seatmap(data, config.forward_rotation)


def _post(url: str, payload: dict):
    response = requests.post(url, headers=headers(), json=payload, timeout=40)
    if response.status_code in (401, 403, 429):
        raise BlockedError(f"HTTP {response.status_code}")
    response.raise_for_status()
    return response.json()


def _dump(day: str, train_id, data) -> None:
    DUMP_DIR.mkdir(exist_ok=True)
    path = DUMP_DIR / f"seatmap_{day}_{train_id}.json"
    text = json.dumps(data, ensure_ascii=False, indent=1)
    path.write_text(text, encoding="utf-8")
