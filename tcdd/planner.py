"""Koltuk önerisi.

Her tercih ayrı bir kural fonksiyonudur. Kurallar sırayla uygulanır;
katı modda karşılanmayan tercih öneriyi eleler.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from itertools import combinations, permutations

from tcdd.models import Plan, Prefs, Seat

_Issue = tuple[str | None, str | None]
_Rule = Callable[[list[Seat], Prefs, "MapFacts"], _Issue]

_FACING_NOTE = (
    "Karşılıklı koltuk bilgisi haritadan çıkarılamadı, "
    "bu tercih uygulanamadı."
)
_DIRECTION_NOTE = (
    "Koltuk yönü çözülemedi (ayarlardan 'İleri yön rotation değeri' "
    "seçilmemiş), bu tercih uygulanamadı."
)
_GENDER_NOTE = (
    "Haritada dolu koltukların yolcu cinsiyeti yok, "
    "komşu cinsiyet kuralı kontrol edilemedi."
)


@dataclass(frozen=True)
class MapFacts:
    """Haritanın hangi alanları doldurduğunu tutar."""

    has_groups: bool
    has_rot: bool
    has_dir: bool
    has_gender: bool


def block_key(seat: Seat) -> str | None:
    """Yan yana oturan koltuk bloğunun kimliği."""
    if seat.blk is not None and seat.row is not None:
        return f"{seat.wagon}|{seat.row}|{seat.blk}"
    if seat.row is not None and seat.pos is not None:
        return f"{seat.wagon}|{seat.row}|{seat.pos // 2}"
    if seat.num is None:
        return None
    return f"{seat.wagon}|n|{(seat.num - 1) // 2}"


def row_key(seat: Seat) -> str | None:
    if seat.row is not None:
        return f"{seat.wagon}|{seat.row}"
    if seat.num is None:
        return None
    return f"{seat.wagon}|n|{(seat.num - 1) // 4}"


def _gender_ok(
    seat: Seat,
    gender: str,
    blocks: dict[str, list[Seat]],
    mine: set[str],
    has_gender: bool,
) -> bool:
    if seat.restrict is not None and seat.restrict != gender:
        return False
    if not has_gender:
        return True
    key = block_key(seat)
    if key is None:
        return True
    for neighbor in blocks.get(key, []):
        if neighbor.sid == seat.sid or neighbor.sid in mine or neighbor.free:
            continue
        if neighbor.gender is not None and neighbor.gender != gender:
            return False
    return True


def _permutations(genders: list[str]) -> list[list[str]]:
    unique = {perm for perm in permutations(genders)}
    return sorted((list(perm) for perm in unique), key="".join)


def _assign(
    chosen: list[Seat],
    perms: list[list[str]],
    blocks: dict[str, list[Seat]],
    has_gender: bool,
) -> list[str] | None:
    mine = {seat.sid for seat in chosen}
    for perm in perms:
        fits = all(
            _gender_ok(chosen[index], perm[index], blocks, mine, has_gender)
            for index in range(len(chosen))
        )
        if fits:
            return perm
    return None


def _sits_together(chosen: list[Seat]) -> bool:
    if len(chosen) == 2:
        key = block_key(chosen[0])
        return key is not None and key == block_key(chosen[1])
    keys = {row_key(seat) for seat in chosen}
    return len(keys) == 1 and None not in keys


def _rule_side_by_side(
    chosen: list[Seat], prefs: Prefs, facts: MapFacts
) -> _Issue:
    del facts
    if len(chosen) <= 1 or not prefs.side_by_side:
        return None, None
    if _sits_together(chosen):
        return None, None
    return "yan yana / aynı sıra", None


def _rule_facing(chosen: list[Seat], prefs: Prefs, facts: MapFacts) -> _Issue:
    if len(chosen) <= 1 or not prefs.facing:
        return None, None
    if not facts.has_groups:
        return None, _FACING_NOTE
    groups = {seat.group for seat in chosen}
    rotations = {seat.rot for seat in chosen if seat.rot is not None}
    same_group = len(groups) == 1 and None not in groups
    faces = len(rotations) >= 2 or not facts.has_rot
    if same_group and faces:
        return None, None
    return "karşılıklı", None


def _rule_direction(
    chosen: list[Seat], prefs: Prefs, facts: MapFacts
) -> _Issue:
    wants_direction = prefs.direction in ("F", "B")
    facing_overrides = len(chosen) > 1 and prefs.facing
    if not wants_direction or facing_overrides:
        return None, None
    if not facts.has_dir:
        return None, _DIRECTION_NOTE
    if any(seat.direction != prefs.direction for seat in chosen):
        return "koltuk yönü", None
    return None, None


_PREFERENCE_RULES: tuple[_Rule, ...] = (
    _rule_side_by_side,
    _rule_facing,
    _rule_direction,
)


def _evaluate(
    chosen: list[Seat], prefs: Prefs, facts: MapFacts
) -> tuple[list[str], list[str]]:
    unmet: list[str] = []
    notes: list[str] = []
    for rule in _PREFERENCE_RULES:
        gap, note = rule(chosen, prefs, facts)
        if gap:
            unmet.append(gap)
        if note:
            notes.append(note)
    return unmet, notes


def _candidates(pool: list[Seat], count: int) -> list[list[Seat]]:
    found: dict[frozenset[str], list[Seat]] = {}

    def add(group: list[Seat]) -> None:
        found.setdefault(frozenset(seat.sid for seat in group), group)

    by_wagon: dict[str, list[Seat]] = {}
    for seat in pool:
        by_wagon.setdefault(seat.wagon, []).append(seat)
    for free in by_wagon.values():
        if count == 1:
            for seat in free:
                add([seat])
            continue
        groups: dict[str, list[Seat]] = {}
        rows: dict[str, list[Seat]] = {}
        for seat in free:
            if seat.group is not None:
                groups.setdefault(seat.group, []).append(seat)
            key = row_key(seat)
            if key is not None:
                rows.setdefault(key, []).append(seat)
        if count <= 6:
            _add_groups(add, groups, count)
            _add_groups(add, rows, count)
        ordered = sorted(
            free, key=lambda seat: (seat.row or 0, seat.pos or 0, seat.num or 0)
        )
        for index in range(0, len(ordered) - count + 1):
            add(ordered[index : index + count])
    return list(found.values())


def _add_groups(add, buckets: dict[str, list[Seat]], count: int) -> None:
    limit = 8
    for bucket in buckets.values():
        if count <= len(bucket) <= limit:
            for combo in combinations(bucket, count):
                add(list(combo))


def _plan_key(chosen: list[Seat], unmet: list[str]) -> tuple:
    first = chosen[0]
    try:
        wagon_number = int(first.wagon)
        wagon_rank = (0, wagon_number, "")
    except ValueError:
        wagon_rank = (1, 0, first.wagon)
    return (len(unmet), *wagon_rank, first.num or 0, first.no)


def _facts(seats: list[Seat]) -> MapFacts:
    return MapFacts(
        has_groups=any(seat.group is not None for seat in seats),
        has_rot=any(seat.rot is not None for seat in seats),
        has_dir=any(seat.direction is not None for seat in seats),
        has_gender=any(
            (not seat.free) and seat.gender is not None for seat in seats
        ),
    )


def _blocks(seats: list[Seat]) -> dict[str, list[Seat]]:
    grouped: dict[str, list[Seat]] = {}
    for seat in seats:
        key = block_key(seat)
        if key is not None:
            grouped.setdefault(key, []).append(seat)
    return grouped


def make_plan(seats: list[Seat], prefs: Prefs) -> Plan | None:
    """Tercihlere en uygun koltuk grubunu seçer; yoksa None döner."""
    count = len(prefs.genders)
    if count < 1 or not seats:
        return None
    pool = [seat for seat in seats if seat.free and seat.kind in prefs.kinds]
    facts = _facts(seats)
    blocks = _blocks(seats)
    perms = _permutations(prefs.genders)
    best_key = None
    best: Plan | None = None
    for candidate in _candidates(pool, count):
        assigned = _assign(candidate, perms, blocks, facts.has_gender)
        if assigned is None:
            continue
        unmet, notes = _evaluate(candidate, prefs, facts)
        if prefs.strict and unmet:
            continue
        key = _plan_key(candidate, unmet)
        if best_key is None or key < best_key:
            best_key = key
            best = Plan(candidate, assigned, unmet, notes)
    if best is None:
        return None
    if not facts.has_gender:
        best.notes = [*best.notes, _GENDER_NOTE]
    return best


def still_free(ids: list[str], seats: list[Seat]) -> bool:
    free = {seat.sid for seat in seats if seat.free}
    return all(seat_id in free for seat_id in ids)


def describe(plan: Plan) -> str:
    """Öneriyi bildirim metnine çevirir."""
    lines = []
    for seat, gender in zip(plan.seats, plan.genders):
        who = "Kadın" if gender == "K" else "Erkek"
        extra = []
        if seat.direction == "F":
            extra.append("ileri yön")
        elif seat.direction == "B":
            extra.append("geri yön")
        extra.append(seat.kind.value)
        detail = ", ".join(extra)
        lines.append(
            f"Vagon {seat.wagon} · Koltuk {seat.no} ({who}) — {detail}"
        )
    if plan.unmet:
        lines.append("Karşılanamayan tercih: " + ", ".join(plan.unmet))
    for note in plan.notes:
        lines.append("Not: " + note)
    return "\n".join(lines)
