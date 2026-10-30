"""Koltuk haritası JSON ayrıştırıcısı.

Alan adları e-bilet mobil uygulamasının APK'sından okundu. Android
sürümündeki SeatMapParser ile aynı kuralları uygular.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from tcdd.models import Kind, Seat, kind_of, norm_gender


def _text(record: dict, key: str) -> str | None:
    if key not in record or record[key] is None:
        return None
    text = str(record[key])
    return text or None


def _number(record: dict, key: str) -> float | None:
    if key not in record or record[key] is None:
        return None
    value = record[key]
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return None
    return None


def _genders(value) -> list[str]:
    if not isinstance(value, list):
        return []
    found = []
    for item in value:
        if isinstance(item, dict):
            raw = (
                item.get("gender")
                or item.get("name")
                or item.get("code")
                or item.get("type")
            )
        else:
            raw = item
        gender = norm_gender(raw)
        if gender:
            found.append(gender)
    return found


def _step(values: list[float]) -> float:
    """Koordinatlar arasındaki en küçük pozitif adım."""
    ordered = sorted(set(values))
    gaps = [
        right - left
        for left, right in zip(ordered, ordered[1:])
        if right - left > 0
    ]
    return min(gaps) if gaps else 1.0


def _label(number: float) -> str:
    if number == math.floor(number) and abs(number) < 1e15:
        return str(int(number))
    return str(number)


def _rotation(number: float | None) -> int | None:
    if number is None:
        return None
    return (int(number) % 360 + 360) % 360


def _find_wagons(node):
    """``seatMapTemplate`` taşıyan ilk vagon listesini bulur."""
    if isinstance(node, dict):
        wagons = node.get("seatMaps")
        if (
            isinstance(wagons, list)
            and wagons
            and isinstance(wagons[0], dict)
            and "seatMapTemplate" in wagons[0]
        ):
            return wagons
        for value in node.values():
            found = _find_wagons(value)
            if found is not None:
                return found
    elif isinstance(node, list):
        for value in node:
            found = _find_wagons(value)
            if found is not None:
                return found
    return None


def _kind_for(car_name: str, item: dict, template: dict) -> Kind:
    names = []
    for prop in item.get("itemProperties") or []:
        if isinstance(prop, dict) and prop.get("name"):
            names.append(str(prop["name"]))
    item_type = item.get("itemType")
    if isinstance(item_type, dict):
        type_name = _text(item_type, "name")
    else:
        type_name = None
    return kind_of(
        car_name,
        _text(item, "name"),
        type_name,
        _text(template, "name"),
        " ".join(names),
    )


@dataclass
class _RawSeat:
    number: str
    x: float | None
    y: float | None
    template: dict
    item: dict


@dataclass
class _Layout:
    """Vagon koordinatlarından sıra, blok ve yön okuması."""

    have_position: bool
    along_cross: dict[str, tuple[float, float]]
    unit_along: float
    unit_cross: float
    min_along: float
    min_cross: float
    blocks: dict[str, str]
    rotation: dict[str, int | None]
    seat_at: dict[tuple[float, float], str]
    forward: int | None

    def row_and_pos(self, number: str) -> tuple[int | None, int | None]:
        point = self.along_cross.get(number)
        if not self.have_position or point is None:
            return None, None
        row = int(round((point[0] - self.min_along) / self.unit_along))
        pos = int(round((point[1] - self.min_cross) / self.unit_cross))
        return row, pos

    def direction(self, rotation: int | None) -> str | None:
        if self.forward is None or rotation is None:
            return None
        if rotation == self.forward:
            return "F"
        if rotation == (self.forward + 180) % 360:
            return "B"
        return None

    def facing_group(self, wagon: str, number: str) -> str | None:
        if not self.have_position:
            return None
        own_rotation = self.rotation.get(number)
        point = self.along_cross.get(number)
        if own_rotation is None or point is None:
            return None
        for delta in (-self.unit_along, self.unit_along):
            other = self.seat_at.get((point[0] + delta, point[1]))
            if other is None:
                continue
            other_rotation = self.rotation.get(other)
            if other_rotation is None:
                continue
            if (other_rotation - own_rotation) % 360 == 180:
                shared = _label(min(point[0], point[0] + delta))
                return f"{wagon}-{shared}-{self.blocks.get(number)}"
        return None


def _saleable(template: dict) -> list[_RawSeat]:
    raw = []
    for item_template in template.get("seatMaps") or []:
        if not isinstance(item_template, dict):
            continue
        number = _text(item_template, "seatNumber")
        if number is None:
            continue
        item = item_template.get("item")
        item = item if isinstance(item, dict) else {}
        if item.get("saleable") is False:
            continue
        raw.append(
            _RawSeat(
                number,
                _number(item_template, "positionX"),
                _number(item_template, "positionY"),
                item_template,
                item,
            )
        )
    return raw


def _layout(raw: list[_RawSeat], forward_rot: int | None) -> _Layout:
    have_position = all(
        seat.x is not None and seat.y is not None for seat in raw
    )
    along_cross: dict[str, tuple[float, float]] = {}
    unit_along = unit_cross = 1.0
    min_along = min_cross = 0.0
    blocks: dict[str, str] = {}
    if have_position:
        xs = [seat.x for seat in raw if seat.x is not None]
        ys = [seat.y for seat in raw if seat.y is not None]
        cross_is_y = (max(ys) - min(ys)) <= (max(xs) - min(xs))
        step_x, step_y = _step(xs), _step(ys)
        for seat in raw:
            assert seat.x is not None and seat.y is not None
            along_cross[seat.number] = (
                (seat.x, seat.y) if cross_is_y else (seat.y, seat.x)
            )
        if cross_is_y:
            unit_along, unit_cross = step_x, step_y
        else:
            unit_along, unit_cross = step_y, step_x
        min_along = min(point[0] for point in along_cross.values())
        min_cross = min(point[1] for point in along_cross.values())
        blocks = _blocks(along_cross, unit_cross)
    rotation = {
        seat.number: _rotation(_number(seat.template, "rotation"))
        for seat in raw
    }
    forward = None if forward_rot is None else _rotation(float(forward_rot))
    return _Layout(
        have_position,
        along_cross,
        unit_along,
        unit_cross,
        min_along,
        min_cross,
        blocks,
        rotation,
        {point: number for number, point in along_cross.items()},
        forward,
    )


def _blocks(
    along_cross: dict[str, tuple[float, float]], unit_cross: float
) -> dict[str, str]:
    """Koridor boşluğu bloğu böler; bitişik koltuklar aynı bloğu paylaşır."""
    rows: dict[float, list[tuple[float, str]]] = {}
    for number, point in along_cross.items():
        rows.setdefault(point[0], []).append((point[1], number))
    blocks: dict[str, str] = {}
    for seats in rows.values():
        seats.sort(key=lambda item: item[0])
        start = 0.0
        previous = None
        for cross, number in seats:
            if previous is None or cross - previous > unit_cross * 1.01:
                start = cross
            blocks[number] = _label(start)
            previous = cross
    return blocks


def _occupied(wagon: dict) -> dict[str, list[str]]:
    occupied: dict[str, list[str]] = {}
    for allocation in wagon.get("allocationSeats") or []:
        if not isinstance(allocation, dict):
            continue
        number = _text(allocation, "seatNumber")
        if number is None:
            continue
        occupied[number] = _genders(allocation.get("genders"))
    return occupied


def _wagon_name(wagon: dict, car: dict) -> str:
    return (
        _text(wagon, "trainCarIndex")
        or _text(wagon, "trainCarId")
        or _text(car, "name")
        or _text(car, "id")
        or "?"
    )


def _parse_wagon(wagon: dict, forward_rot: int | None) -> list[Seat]:
    template = wagon.get("seatMapTemplate")
    template = template if isinstance(template, dict) else {}
    car = template.get("car")
    car = car if isinstance(car, dict) else {}
    name = _wagon_name(wagon, car)
    car_name = _text(car, "name") or ""
    on_sale = wagon.get("trainCarOnSale") is not False
    occupied = _occupied(wagon)
    raw = _saleable(template)
    if not raw:
        return []
    layout = _layout(raw, forward_rot)
    seats = []
    for seat in raw:
        genders = occupied.get(seat.number)
        taken = bool(seat.template.get("isAllocated"))
        allocated = taken or seat.number in occupied
        rotation = layout.rotation.get(seat.number)
        row, pos = layout.row_and_pos(seat.number)
        seats.append(
            Seat(
                wagon=name,
                no=seat.number,
                row=row,
                pos=pos,
                free=on_sale and not allocated,
                gender=None if not genders else genders[0],
                direction=layout.direction(rotation),
                group=layout.facing_group(name, seat.number),
                kind=_kind_for(car_name, seat.item, seat.template),
                rot=rotation,
                blk=layout.blocks.get(seat.number),
                restrict=norm_gender(_text(seat.template, "genderRestriction")),
            )
        )
    return seats


def parse_seatmap(data, forward_rot: int | None = None) -> list[Seat]:
    """Koltuk haritası cevabını Seat listesine çevirir.

    Şema tanınmazsa boş liste döner.
    """
    wagons = _find_wagons(data)
    if not wagons:
        return []
    seats: list[Seat] = []
    for wagon in wagons:
        if isinstance(wagon, dict):
            seats.extend(_parse_wagon(wagon, forward_rot))
    return seats
