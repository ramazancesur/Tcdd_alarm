"""Koltuk haritası ayrıştırıcı ve koltuk önerme motoru.

Alan adları e-bilet mobil uygulamasının APK'sından okundu. Android sürümündeki
SeatMapParser ve Planner ile aynı kuralları uygular.
"""
from __future__ import annotations

import enum
import math
import unicodedata
from dataclasses import dataclass, field
from itertools import combinations, permutations


class Kind(enum.Enum):
    ECONOMY = "Ekonomi"
    BUSINESS = "Business"
    WHEELCHAIR = "Tekerlekli sandalye"


def tr_norm(s: str) -> str:
    """Türkçe harfleri ve büyük/küçük farkını yok sayarak karşılaştırır."""
    t = s.replace("İ", "i").replace("I", "ı").lower().replace("ı", "i")
    d = unicodedata.normalize("NFKD", t)
    return "".join(ch for ch in d if unicodedata.category(ch) != "Mn")


def kind_of(*texts: str | None) -> Kind:
    t = " ".join(tr_norm(x) for x in texts if x)
    if "tekerlek" in t or "wheelchair" in t:
        return Kind.WHEELCHAIR
    if "busines" in t:
        return Kind.BUSINESS
    return Kind.ECONOMY


@dataclass
class Prefs:
    genders: list[str]
    side_by_side: bool = False
    facing: bool = False
    direction: str = "A"  # F ileri, B geri, A fark etmez
    strict: bool = False
    kinds: set[Kind] = field(default_factory=lambda: {Kind.ECONOMY, Kind.BUSINESS})


@dataclass
class Seat:
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
        digits = "".join(ch for ch in self.no if ch.isdigit())
        return int(digits) if digits else None


@dataclass
class Plan:
    seats: list[Seat]
    genders: list[str]
    unmet: list[str]
    notes: list[str]

    @property
    def ids(self) -> list[str]:
        return [s.sid for s in self.seats]


def _jstr(d: dict, k: str) -> str | None:
    if k not in d or d[k] is None:
        return None
    s = str(d[k])
    return s if s else None


def _jdbl(d: dict, k: str) -> float | None:
    if k not in d or d[k] is None:
        return None
    v = d[k]
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str):
        try:
            return float(v)
        except ValueError:
            return None
    return None


def norm_gender(v) -> str | None:
    if v is None:
        return None
    s = str(v).strip().upper().replace("İ", "I")
    if s in ("K", "F", "FEMALE", "WOMAN", "KADIN"):
        return "K"
    if s in ("E", "M", "MALE", "MAN", "ERKEK"):
        return "E"
    return None


def _gender_list(arr) -> list[str]:
    if not isinstance(arr, list):
        return []
    out = []
    for x in arr:
        if isinstance(x, dict):
            raw = x.get("gender") or x.get("name") or x.get("code") or x.get("type")
        else:
            raw = x
        g = norm_gender(raw)
        if g:
            out.append(g)
    return out


def _unit(values: list[float]) -> float:
    vs = sorted(set(values))
    best = None
    for i in range(1, len(vs)):
        gap = vs[i] - vs[i - 1]
        if gap > 0 and (best is None or gap < best):
            best = gap
    return 1.0 if best is None else best


def _fmt(d: float) -> str:
    if d == math.floor(d) and abs(d) < 1e15:
        return str(int(d))
    return str(d)


def _norm_rot(d: float | None) -> int | None:
    if d is None:
        return None
    return (int(d) % 360 + 360) % 360


def _find_wagon_list(o):
    if isinstance(o, dict):
        lst = o.get("seatMaps")
        if isinstance(lst, list) and lst and isinstance(lst[0], dict) and "seatMapTemplate" in lst[0]:
            return lst
        for v in o.values():
            found = _find_wagon_list(v)
            if found is not None:
                return found
    elif isinstance(o, list):
        for v in o:
            found = _find_wagon_list(v)
            if found is not None:
                return found
    return None


def _kind_for(car_name: str, item: dict, template: dict) -> Kind:
    props = []
    for p in item.get("itemProperties") or []:
        if isinstance(p, dict) and p.get("name"):
            props.append(str(p["name"]))
    item_type = item.get("itemType") if isinstance(item.get("itemType"), dict) else {}
    return kind_of(car_name, _jstr(item, "name"), _jstr(item_type, "name"), _jstr(template, "name"), " ".join(props))


def _parse_wagon(w: dict, forward_rot: int | None) -> list[Seat]:
    tpl = w.get("seatMapTemplate") if isinstance(w.get("seatMapTemplate"), dict) else {}
    car = tpl.get("car") if isinstance(tpl.get("car"), dict) else {}
    wagon = _jstr(w, "trainCarIndex") or _jstr(w, "trainCarId") or _jstr(car, "name") or _jstr(car, "id") or "?"
    car_name = _jstr(car, "name") or ""
    on_sale = w.get("trainCarOnSale") is not False

    occupied: dict[str, list[str]] = {}
    for a in w.get("allocationSeats") or []:
        if not isinstance(a, dict):
            continue
        n = _jstr(a, "seatNumber")
        if n is None:
            continue
        occupied[n] = _gender_list(a.get("genders"))

    raw = []
    for t in tpl.get("seatMaps") or []:
        if not isinstance(t, dict):
            continue
        no = _jstr(t, "seatNumber")
        if no is None:
            continue
        item = t.get("item") if isinstance(t.get("item"), dict) else {}
        if item.get("saleable") is False:
            continue
        raw.append((no, _jdbl(t, "positionX"), _jdbl(t, "positionY"), t, item))
    if not raw:
        return []

    have_pos = all(x is not None and y is not None for _, x, y, _, _ in raw)
    lc: dict[str, tuple[float, float]] = {}
    u_l = u_c = 1.0
    min_l = min_c = 0.0
    if have_pos:
        xs = [x for _, x, _, _, _ in raw]
        ys = [y for _, _, y, _, _ in raw]
        cross_is_y = (max(ys) - min(ys)) <= (max(xs) - min(xs))
        ux, uy = _unit(xs), _unit(ys)
        for no, x, y, _, _ in raw:
            lc[no] = (x, y) if cross_is_y else (y, x)
        u_l, u_c = (ux, uy) if cross_is_y else (uy, ux)
        min_l = min(p[0] for p in lc.values())
        min_c = min(p[1] for p in lc.values())

    blk: dict[str, str] = {}
    if have_pos:
        rows: dict[float, list[tuple[float, str]]] = {}
        for no, p in lc.items():
            rows.setdefault(p[0], []).append((p[1], no))
        for lst in rows.values():
            lst.sort(key=lambda it: it[0])
            start = 0.0
            prev = None
            for c, no in lst:
                if prev is None or c - prev > u_c * 1.01:
                    start = c
                blk[no] = _fmt(start)
                prev = c

    rot_of: dict[str, int | None] = {}
    for no, _, _, t, _ in raw:
        rot_of[no] = _norm_rot(_jdbl(t, "rotation"))
    by_pos = {p: no for no, p in lc.items()}
    fr = None if forward_rot is None else (forward_rot % 360 + 360) % 360

    def facing_group(no: str) -> str | None:
        if not have_pos:
            return None
        rn = rot_of.get(no)
        p = lc.get(no)
        if rn is None or p is None:
            return None
        for d_l in (-u_l, u_l):
            other = by_pos.get((p[0] + d_l, p[1]))
            if other is None:
                continue
            ro = rot_of.get(other)
            if ro is None:
                continue
            if (ro - rn) % 360 == 180:
                return f"{wagon}-{_fmt(min(p[0], p[0] + d_l))}-{blk.get(no)}"
        return None

    out: list[Seat] = []
    for no, _, _, t, item in raw:
        genders = occupied.get(no)
        allocated = bool(t.get("isAllocated")) or no in occupied
        rot = rot_of.get(no)
        direction = None
        if fr is not None and rot is not None:
            if rot == fr:
                direction = "F"
            elif rot == (fr + 180) % 360:
                direction = "B"
        row = pos = None
        p = lc.get(no)
        if have_pos and p is not None:
            row = int(round((p[0] - min_l) / u_l))
            pos = int(round((p[1] - min_c) / u_c))
        out.append(
            Seat(
                wagon=wagon,
                no=no,
                row=row,
                pos=pos,
                free=on_sale and not allocated,
                gender=None if not genders else genders[0],
                direction=direction,
                group=facing_group(no),
                kind=_kind_for(car_name, item, t),
                rot=rot,
                blk=blk.get(no),
                restrict=norm_gender(_jstr(t, "genderRestriction")),
            )
        )
    return out


def parse_seatmap(data, forward_rot: int | None = None) -> list[Seat]:
    """Koltuk haritası cevabını Seat listesine çevirir. Tanınmazsa boş liste döner."""
    wagons = _find_wagon_list(data)
    if not wagons:
        return []
    out: list[Seat] = []
    for w in wagons:
        if isinstance(w, dict):
            out.extend(_parse_wagon(w, forward_rot))
    return out


# --- Planlayıcı ----------------------------------------------------------------


def block_key(s: Seat) -> str | None:
    if s.blk is not None and s.row is not None:
        return f"{s.wagon}|{s.row}|{s.blk}"
    if s.row is not None and s.pos is not None:
        return f"{s.wagon}|{s.row}|{s.pos // 2}"
    if s.num is None:
        return None
    return f"{s.wagon}|n|{(s.num - 1) // 2}"


def row_key(s: Seat) -> str | None:
    if s.row is not None:
        return f"{s.wagon}|{s.row}"
    if s.num is None:
        return None
    return f"{s.wagon}|n|{(s.num - 1) // 4}"


def _gender_ok(seat: Seat, g: str, blocks: dict[str, list[Seat]], mine: set[str], has_gender: bool) -> bool:
    if seat.restrict is not None and seat.restrict != g:
        return False
    if not has_gender:
        return True
    key = block_key(seat)
    if key is None:
        return True
    for n in blocks.get(key, []):
        if n.sid == seat.sid or n.sid in mine or n.free:
            continue
        # Cinsiyeti bilinmeyen dolu komşu kimseyi elemez.
        if n.gender is not None and n.gender != g:
            return False
    return True


def _perms(items: list[str]) -> list[list[str]]:
    uniq = {p for p in permutations(items)}
    return sorted((list(p) for p in uniq), key=lambda p: "".join(p))


def _assign(chosen: list[Seat], perms: list[list[str]], blocks, has_gender: bool) -> list[str] | None:
    mine = {s.sid for s in chosen}
    for perm in perms:
        if all(_gender_ok(chosen[i], perm[i], blocks, mine, has_gender) for i in range(len(chosen))):
            return perm
    return None


def _evaluate(chosen: list[Seat], prefs: Prefs, ctx: dict) -> tuple[list[str], list[str]]:
    n = len(chosen)
    unmet: list[str] = []
    notes: list[str] = []
    if n > 1 and prefs.side_by_side:
        if n == 2:
            k = block_key(chosen[0])
            ok = k is not None and k == block_key(chosen[1])
        else:
            keys = {row_key(s) for s in chosen}
            ok = len(keys) == 1 and None not in keys
        if not ok:
            unmet.append("yan yana / aynı sıra")
    if n > 1 and prefs.facing:
        if not ctx["has_groups"]:
            notes.append("Karşılıklı koltuk bilgisi haritadan çıkarılamadı, bu tercih uygulanamadı.")
        else:
            groups = {s.group for s in chosen}
            rots = {s.rot for s in chosen if s.rot is not None}
            ok = len(groups) == 1 and None not in groups and (len(rots) >= 2 or not ctx["has_rot"])
            if not ok:
                unmet.append("karşılıklı")
    if prefs.direction in ("F", "B") and not (n > 1 and prefs.facing):
        if not ctx["has_dir"]:
            notes.append(
                "Koltuk yönü çözülemedi (ayarlardan 'İleri yön rotation değeri' seçilmemiş), bu tercih uygulanamadı."
            )
        elif any(s.direction != prefs.direction for s in chosen):
            unmet.append("koltuk yönü")
    return unmet, notes


def _candidates(pool: list[Seat], n: int) -> list[list[Seat]]:
    out: dict[frozenset[str], list[Seat]] = {}

    def add(group: list[Seat]) -> None:
        out.setdefault(frozenset(s.sid for s in group), group)

    by_wagon: dict[str, list[Seat]] = {}
    for s in pool:
        by_wagon.setdefault(s.wagon, []).append(s)
    for free in by_wagon.values():
        if n == 1:
            for s in free:
                add([s])
            continue
        groups: dict[str, list[Seat]] = {}
        rows: dict[str, list[Seat]] = {}
        for s in free:
            if s.group is not None:
                groups.setdefault(s.group, []).append(s)
            rk = row_key(s)
            if rk is not None:
                rows.setdefault(rk, []).append(s)
        if n <= 6:
            for g in groups.values():
                if n <= len(g) <= 8:
                    for c in combinations(g, n):
                        add(list(c))
            for r in rows.values():
                if n <= len(r) <= 8:
                    for c in combinations(r, n):
                        add(list(c))
        ordered = sorted(free, key=lambda s: (s.row or 0, s.pos or 0, s.num or 0))
        for i in range(0, len(ordered) - n + 1):
            add(ordered[i : i + n])
    return list(out.values())


def _plan_key(chosen: list[Seat], unmet: list[str]) -> tuple:
    first = chosen[0]
    try:
        wn = int(first.wagon)
        w1, w2, w3 = 0, wn, ""
    except ValueError:
        w1, w2, w3 = 1, 0, first.wagon
    return (len(unmet), w1, w2, w3, first.num or 0, first.no)


def make_plan(seats: list[Seat], prefs: Prefs) -> Plan | None:
    """Tercihlere en uygun koltuk grubunu seçer; yoksa None döner."""
    n = len(prefs.genders)
    if n < 1 or not seats:
        return None
    pool = [s for s in seats if s.free and s.kind in prefs.kinds]
    blocks: dict[str, list[Seat]] = {}
    for s in seats:
        k = block_key(s)
        if k is not None:
            blocks.setdefault(k, []).append(s)
    ctx = {
        "has_groups": any(s.group is not None for s in seats),
        "has_rot": any(s.rot is not None for s in seats),
        "has_dir": any(s.direction is not None for s in seats),
        "has_gender": any((not s.free) and s.gender is not None for s in seats),
    }
    perms = _perms(prefs.genders)
    best_key = None
    best: Plan | None = None
    for cand in _candidates(pool, n):
        perm = _assign(cand, perms, blocks, ctx["has_gender"])
        if perm is None:
            continue
        unmet, notes = _evaluate(cand, prefs, ctx)
        if prefs.strict and unmet:
            continue
        key = _plan_key(cand, unmet)
        if best_key is None or key < best_key:
            best_key = key
            best = Plan(cand, perm, unmet, notes)
    if best is None:
        return None
    if not ctx["has_gender"]:
        best.notes = [
            *best.notes,
            "Haritada dolu koltukların yolcu cinsiyeti yok, komşu cinsiyet kuralı kontrol edilemedi.",
        ]
    return best


def still_free(ids: list[str], seats: list[Seat]) -> bool:
    free = {s.sid for s in seats if s.free}
    return all(i in free for i in ids)


def describe(plan: Plan) -> str:
    lines = []
    for seat, g in zip(plan.seats, plan.genders):
        who = "Kadın" if g == "K" else "Erkek"
        extra = []
        if seat.direction == "F":
            extra.append("ileri yön")
        elif seat.direction == "B":
            extra.append("geri yön")
        extra.append(seat.kind.value)
        lines.append(f"Vagon {seat.wagon} · Koltuk {seat.no} ({who}) — {', '.join(extra)}")
    if plan.unmet:
        lines.append("Karşılanamayan tercih: " + ", ".join(plan.unmet))
    for note in plan.notes:
        lines.append("Not: " + note)
    return "\n".join(lines)


class DemoWorld:
    """Ağ olmadan denenebilir 2+2 vagon. Her tick bir boş koltuğu satılmış sayar."""

    def __init__(self) -> None:
        self.sold: dict[tuple, set[str]] = {}

    def seatmap(self, key, tick: bool = True) -> dict:
        sold = self.sold.setdefault(key, {"2"})
        if tick:
            for n in range(1, 25):
                s = str(n)
                if s not in sold:
                    sold.add(s)
                    break
        return _demo_wagon(sold)


def _demo_wagon(sold: set[str]) -> dict:
    seats = []
    for r in range(6):
        for i, y in enumerate((0, 2, 6, 8)):
            no = str(r * 4 + i + 1)
            rot = 0 if r % 2 == 0 else 180
            seats.append(
                {
                    "seatNumber": no,
                    "positionX": r * 2,
                    "positionY": y,
                    "rotation": rot,
                    "isAllocated": no in sold and no != "2",
                    "genderRestriction": None,
                    "item": {"saleable": True, "itemType": {"name": "SEAT"}},
                }
            )
    allocation = [{"seatNumber": n, "genders": ["MALE"] if n == "2" else []} for n in sorted(sold, key=lambda s: int(s))]
    return {
        "data": {
            "seatMaps": [
                {
                    "trainCarId": 7,
                    "trainCarIndex": 3,
                    "trainCarOnSale": True,
                    "seatMapTemplate": {"car": {"name": "EKONOMİ"}, "seatMaps": seats},
                    "allocationSeats": allocation,
                }
            ]
        }
    }
