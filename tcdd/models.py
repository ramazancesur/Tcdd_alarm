"""Koltuk, yolcu tercihi ve öneri türleri."""

from __future__ import annotations

import enum
import unicodedata
from dataclasses import dataclass, field


class Kind(enum.Enum):
    """Seçilebilir koltuk türü."""

    ECONOMY = "Ekonomi"
    BUSINESS = "Business"
    WHEELCHAIR = "Tekerlekli sandalye"


def tr_norm(text: str) -> str:
    """Türkçe harfleri ve büyük/küçük farkını yok sayarak karşılaştırır."""
    lowered = text.replace("İ", "i").replace("I", "ı").lower().replace("ı", "i")
    decomposed = unicodedata.normalize("NFKD", lowered)
    return "".join(
        char for char in decomposed if unicodedata.category(char) != "Mn"
    )


def kind_of(*texts: str | None) -> Kind:
    """Vagon veya kabin adından koltuk türünü çıkarır."""
    blob = " ".join(tr_norm(text) for text in texts if text)
    if "tekerlek" in blob or "wheelchair" in blob:
        return Kind.WHEELCHAIR
    if "busines" in blob:
        return Kind.BUSINESS
    return Kind.ECONOMY


def norm_gender(value) -> str | None:
    """Cinsiyet metnini ``K`` veya ``E`` yapar."""
    if value is None:
        return None
    token = str(value).strip().upper().replace("İ", "I")
    if token in ("K", "F", "FEMALE", "WOMAN", "KADIN"):
        return "K"
    if token in ("E", "M", "MALE", "MAN", "ERKEK"):
        return "E"
    return None


@dataclass
class Prefs:
    """Planlayıcının uyguladığı yolcu tercihleri."""

    genders: list[str]
    side_by_side: bool = False
    facing: bool = False
    direction: str = "A"
    strict: bool = False
    kinds: set[Kind] = field(
        default_factory=lambda: {Kind.ECONOMY, Kind.BUSINESS}
    )


@dataclass
class Seat:
    """Haritadan okunan tek koltuk."""

    wagon: str
    no: str
    row: int | None
    pos: int | None
    free: bool
    gender: str | None
    direction: str | None
    group: str | None
    kind: Kind
    rot: int | None = None
    blk: str | None = None
    restrict: str | None = None

    @property
    def sid(self) -> str:
        return f"{self.wagon}-{self.no}"

    @property
    def num(self) -> int | None:
        digits = "".join(char for char in self.no if char.isdigit())
        return int(digits) if digits else None


@dataclass
class Plan:
    """Seçilen koltuklar ve karşılanamayan tercihler."""

    seats: list[Seat]
    genders: list[str]
    unmet: list[str]
    notes: list[str]

    @property
    def ids(self) -> list[str]:
        return [seat.sid for seat in self.seats]
