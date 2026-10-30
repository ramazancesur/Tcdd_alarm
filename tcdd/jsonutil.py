"""JSON ağaçlarında ortak okuma yardımcıları."""

from __future__ import annotations

from collections.abc import Callable


def pick(record: dict, *keys: str):
    """İlk dolu alanı döndürür."""
    for key in keys:
        if key in record and record[key] not in (None, ""):
            return record[key]
    return None


def as_int(value) -> int | None:
    """Sayıya benzeyen değerleri tamsayıya çevirir; değilse None."""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str):
        try:
            return int(float(value)) if value.strip() else None
        except ValueError:
            return None
    return None


def walk(obj, visit: Callable[[dict], None]) -> None:
    """İç içe sözlük ve listelerdeki her sözlüğü ziyaret eder."""
    if isinstance(obj, dict):
        visit(obj)
        for value in obj.values():
            walk(value, visit)
    elif isinstance(obj, list):
        for value in obj:
            walk(value, visit)
