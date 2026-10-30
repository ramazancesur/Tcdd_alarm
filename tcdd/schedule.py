"""Sefer cevabının okunması ve saat aralığı."""

from __future__ import annotations

import datetime as dt

from tcdd.jsonutil import as_int, walk
from tcdd.models import Kind, kind_of
from tcdd.paths import TZ, date_format


def date_param(day: str) -> str:
    """Yerel gece yarısının UTC karşılığı: önceki gün 21:00."""
    date = dt.date.fromisoformat(day) - dt.timedelta(days=1)
    midnight = dt.datetime.combine(date, dt.time(21, 0))
    return midnight.strftime(date_format())


def parse_time(value) -> dt.datetime | None:
    """Epoch, ISO veya bilinen metin biçimlerinden saat okur."""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        seconds = value / 1000 if value > 1e11 else value
        return dt.datetime.fromtimestamp(seconds, TZ)
    if isinstance(value, str):
        return _parse_time_text(value)
    return None


def _parse_time_text(value: str) -> dt.datetime | None:
    numeric = value.strip().replace(".", "", 1)
    if numeric.isdigit() and not any(char.isalpha() for char in value):
        number = as_int(value)
        if number is not None:
            raw = float(value) if "." in value else number
            return parse_time(raw)
    formats = (
        "%Y-%m-%dT%H:%M:%S%z",
        "%Y-%m-%dT%H:%M:%S",
        "%d-%m-%Y %H:%M:%S",
        "%b %d, %Y %I:%M:%S %p",
    )
    for fmt in formats:
        try:
            parsed = dt.datetime.strptime(value, fmt)
        except ValueError:
            continue
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=TZ)
    try:
        parsed = dt.datetime.fromisoformat(value)
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=TZ)


def in_range(moment: dt.datetime | None, start: str, end: str) -> bool:
    """Saat aralığı. Saat okunamadıysa seferi kaçırmamak için dahil eder."""
    if moment is None:
        return True
    if moment.tzinfo:
        local = moment.astimezone(TZ)
    else:
        local = moment.replace(tzinfo=TZ)
    clock = local.strftime("%H:%M")
    return start <= clock <= end


def extract_trains(data, kinds: set[Kind]) -> list[dict]:
    """Cevaptan tren listesi çıkarır.

    Kalkış saati, biniş istasyonunun segmentinden alınır.
    """
    lists: list[list] = []
    walk(
        data,
        lambda obj: lists.append(obj["trains"])
        if isinstance(obj.get("trains"), list)
        else None,
    )
    trains = []
    for items in lists:
        for train in items:
            if isinstance(train, dict):
                trains.append(_train(train, kinds))
    return trains


def _train(train: dict, kinds: set[Kind]) -> dict:
    origin_id = as_int(train.get("departureStationId"))
    destination_id = as_int(train.get("arrivalStationId"))
    departure = _boarding_time(train, origin_id)
    cabins = _cabins(train, kinds)
    return {
        "id": as_int(train.get("id")) or as_int(train.get("trainId")),
        "name": _commercial_name(train),
        "departure": departure,
        "seats": sum(cabins.values()),
        "cabins": cabins,
        "from_id": origin_id,
        "to_id": destination_id,
    }


def _boarding_time(train: dict, origin_id: int | None) -> dt.datetime | None:
    exact = None
    first = None

    def visit(obj: dict) -> None:
        nonlocal exact, first
        if "departureTime" not in obj:
            return
        moment = parse_time(obj.get("departureTime"))
        if moment is None:
            return
        if first is None:
            first = moment
        segment = obj.get("segment")
        segment = segment if isinstance(segment, dict) else {}
        station = segment.get("departureStation")
        station = station if isinstance(station, dict) else {}
        station_id = as_int(station.get("id"))
        matches = (
            exact is None
            and station_id is not None
            and origin_id is not None
            and station_id == origin_id
        )
        if matches:
            exact = moment

    walk(train, visit)
    return exact or first


def _cabins(train: dict, kinds: set[Kind]) -> dict[str, int]:
    cabins: dict[str, int] = {}

    def visit(obj: dict) -> None:
        availabilities = obj.get("cabinClassAvailabilities")
        if not isinstance(availabilities, list):
            return
        for cabin in availabilities:
            if not isinstance(cabin, dict):
                continue
            details = cabin.get("cabinClass")
            details = details if isinstance(details, dict) else {}
            name = str(details.get("name") or cabin.get("name") or "?")
            count = as_int(cabin.get("availabilityCount")) or 0
            if kind_of(name) in kinds:
                cabins[name] = cabins.get(name, 0) + count

    walk(train, visit)
    return cabins


def _commercial_name(train: dict) -> str:
    nested = None

    def visit(obj: dict) -> None:
        nonlocal nested
        if nested is None and obj.get("commercialName"):
            nested = obj.get("commercialName")

    walk(train, visit)
    name = train.get("commercialName") or train.get("name") or nested or "Tren"
    return str(name)
