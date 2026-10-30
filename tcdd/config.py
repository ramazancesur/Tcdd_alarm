"""Kaydedilen alarm ayarı."""

from __future__ import annotations

from dataclasses import dataclass

from tcdd.models import Kind, Prefs, kind_of
from tcdd.stations import Station

_REQUIRED = {
    "genders",
    "notify",
    "side_by_side",
    "facing",
    "direction",
    "strict",
    "kinds",
}


def _kinds(names: list[str] | None) -> set[Kind]:
    found = set()
    for name in names or []:
        try:
            found.add(Kind[name])
        except KeyError:
            found.add(kind_of(str(name)))
    return found


@dataclass
class AlarmConfig:
    """Kullanıcının seçtiği güzergah, yolcu ve bildirim ayarı."""

    origin: Station
    destination: Station
    dates: list[str]
    time_from: str
    time_to: str
    genders: list[str]
    side_by_side: bool
    facing: bool
    direction: str
    strict: bool
    kinds: list[str]
    forward_rotation: int | None
    notify: dict
    interval: int

    def prefs(self) -> Prefs:
        return Prefs(
            genders=self.genders,
            side_by_side=self.side_by_side,
            facing=self.facing,
            direction=self.direction,
            strict=self.strict,
            kinds=_kinds(self.kinds),
        )

    def to_dict(self) -> dict:
        return {
            "from": self.origin.to_dict(),
            "to": self.destination.to_dict(),
            "dates": self.dates,
            "time_from": self.time_from,
            "time_to": self.time_to,
            "genders": self.genders,
            "side_by_side": self.side_by_side,
            "facing": self.facing,
            "direction": self.direction,
            "strict": self.strict,
            "kinds": self.kinds,
            "forward_rotation": self.forward_rotation,
            "notify": self.notify,
            "interval": self.interval,
        }

    @classmethod
    def from_dict(cls, raw: dict) -> AlarmConfig | None:
        """Eski kayıtlarda zorunlu alan yoksa None döner."""
        if not _REQUIRED <= raw.keys():
            return None
        return cls(
            origin=_station(raw["from"]),
            destination=_station(raw["to"]),
            dates=list(raw["dates"]),
            time_from=raw["time_from"],
            time_to=raw["time_to"],
            genders=list(raw["genders"]),
            side_by_side=bool(raw["side_by_side"]),
            facing=bool(raw["facing"]),
            direction=raw["direction"],
            strict=bool(raw["strict"]),
            kinds=list(raw["kinds"]),
            forward_rotation=raw.get("forward_rotation"),
            notify=dict(raw["notify"]),
            interval=int(raw["interval"]),
        )


def _station(raw: dict) -> Station:
    return Station(
        int(raw["id"]),
        str(raw["name"]),
        list(raw.get("pairs") or []),
    )
