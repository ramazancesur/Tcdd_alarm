"""Koltuk modeli, koltuk haritası ayrıştırıcı ve koltuk önerme motoru.

Koltuk haritası şeması (alan adları) e-bilet mobil uygulamasının APK'sından okundu
(SeatMapsResponse → seatMaps[] → seatMapTemplate.seatMaps[] + allocationSeats[]).
Alan değerlerinin anlamı (rotation, genderRestriction vb.) gerçek bir cevapla
doğrulanmadı; doğrulanamayan yerler "not" olarak raporlanır, sessizce yok sayılmaz.
"""
from __future__ import annotations

import itertools
import os
import random
import unicodedata
from dataclasses import dataclass, field


def tr_norm(s: str) -> str:
    """Türkçe harfleri ve büyük/küçük farkını yok sayarak karşılaştırma için normalize eder."""
    s = s.replace("İ", "i").replace("I", "ı").lower().replace("ı", "i")
    s = unicodedata.normalize("NFKD", s)
    return "".join(c for c in s if not unicodedata.combining(c))


def _env_int(name: str) -> int | None:
    v = os.getenv(name, "").strip()
    return int(v) % 360 if v.lstrip("-").isdigit() else None


# Koltuğun "trenin gidiş yönüne bakması" hangi rotation değerine karşılık geliyor?
# Gerçek cevapta bir koltuğa bakıp 0 veya 180 olarak ayarlayın (TCDD_FORWARD_ROTATION).
# Ayarlanmazsa ileri/geri tercihi uygulanamaz ve bildirimde not düşülür.
FORWARD_ROT: int | None = _env_int("TCDD_FORWARD_ROTATION")


# --- Modeller -------------------------------------------------------------------
@dataclass(frozen=True)
class Seat:
    wagon: str
    no: str
    row: int | None
    pos: int | None          # sıradaki sütun (0'dan başlar)
    free: bool
    gender: str | None       # dolu koltuktaki yolcunun cinsiyeti: "K" | "E" | None
    direction: str | None    # "F" ileri | "B" geri | None
    group: str | None        # karşılıklı (masa) grubu
    cabin: str
    rot: int | None = None       # koltuğun dönüş açısı (0/180)
    blk: str | None = None       # yan yana oturan kesintisiz koltuk bloğu
    restrict: str | None = None  # koltuğa özel cinsiyet kısıtı: "K" | "E" | None

    @property
    def sid(self) -> str:
        return f"{self.wagon}-{self.no}"

    @property
    def num(self) -> int | None:
        digits = "".join(ch for ch in self.no if ch.isdigit())
        return int(digits) if digits else None


@dataclass
class Prefs:
    genders: list[str]                 # her yolcu için "K" veya "E"
    side_by_side: bool = False         # 2 kişi: yan yana, 3+ kişi: aynı sıra
    facing: bool = False               # karşılıklı koltuk
    direction: str = "A"               # "F" ileri, "B" geri, "A" fark etmez
    strict: bool = False               # True: tercih karşılanmıyorsa öneri yapma
    cabin: str = ""                    # vagon/kabin filtresi (boş = hepsi)


@dataclass
class Plan:
    seats: tuple[Seat, ...]
    genders: tuple[str, ...]
    unmet: list[str] = field(default_factory=list)   # karşılanamayan tercihler
    notes: list[str] = field(default_factory=list)   # veri eksikliği uyarıları

    @property
    def ids(self) -> list[str]:
        return [s.sid for s in self.seats]


@dataclass
class Ctx:
    has_groups: bool
    has_rot: bool
    has_dir: bool
    has_gender: bool


# --- Ayrıştırma yardımcıları ----------------------------------------------------
def _g(d: dict, *keys):
    for k in keys:
        if k in d and d[k] not in (None, ""):
            return d[k]
    return None


def norm_gender(v) -> str | None:
    if not isinstance(v, str):
        return None
    s = v.strip().upper().replace("İ", "I")
    if s in ("K", "F", "FEMALE", "WOMAN", "KADIN"):
        return "K"
    if s in ("E", "M", "MALE", "MAN", "ERKEK"):
        return "E"
    return None


def _gender_list(v) -> list[str]:
    out = []
    for x in v if isinstance(v, list) else []:
        if isinstance(x, dict):
            x = _g(x, "gender", "name", "code", "type")
        g = norm_gender(x)
        if g:
            out.append(g)
    return out


def norm_direction(v) -> str | None:
    if not isinstance(v, str):
        return None
    s = v.strip().upper().replace("İ", "I")
    if s == "F" or "FORWARD" in s or "ILERI" in s:
        return "F"
    if s == "B" or "BACK" in s or "REVERSE" in s or "GERI" in s:
        return "B"
    return None


def _num(v) -> float | None:
    if isinstance(v, bool) or v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str):
        try:
            return float(v)
        except ValueError:
            return None
    return None


def _unit(values: list[float]) -> float:
    """Farklı koordinatlar arasındaki en küçük pozitif adım (ölçekten bağımsız komşuluk için)."""
    vs = sorted(set(values))
    gaps = [b - a for a, b in zip(vs, vs[1:]) if b - a > 0]
    return min(gaps) if gaps else 1.0


def _find_wagon_list(data) -> list[dict] | None:
    """Cevapta 'seatMaps' (vagon listesi) içeren ilk yeri bulur."""
    stack = [data]
    while stack:
        o = stack.pop()
        if isinstance(o, dict):
            lst = o.get("seatMaps")
            if isinstance(lst, list) and lst and isinstance(lst[0], dict) and "seatMapTemplate" in lst[0]:
                return lst
            stack.extend(o.values())
        elif isinstance(o, list):
            stack.extend(o)
    return None


# --- Gerçek şema: SeatMapsResponse ----------------------------------------------
def _parse_wagon(w: dict) -> list[Seat]:
    tpl = w.get("seatMapTemplate") or {}
    car = tpl.get("car") or {}
    wagon = str(_g(w, "trainCarIndex", "trainCarId") or _g(car, "name", "id") or "?")
    cabin = str(car.get("name") or "")
    on_sale = w.get("trainCarOnSale") is not False

    occupied: dict[str, list[str]] = {}
    for a in w.get("allocationSeats") or []:
        if isinstance(a, dict) and a.get("seatNumber") is not None:
            occupied[str(a["seatNumber"])] = _gender_list(a.get("genders"))

    raw = []
    for t in tpl.get("seatMaps") or []:
        if not isinstance(t, dict):
            continue
        no = t.get("seatNumber")
        item = t.get("item") or {}
        if no in (None, "") or item.get("saleable") is False:
            continue  # koltuk değil (koridor, masa, WC vb.)
        x, y = _num(t.get("positionX")), _num(t.get("positionY"))
        raw.append((str(no), x, y, t, item))
    if not raw:
        return []

    xs = [r[1] for r in raw if r[1] is not None]
    ys = [r[2] for r in raw if r[2] is not None]
    have_pos = len(xs) == len(raw) == len(ys)
    seats_xy = {}
    if have_pos:
        # Vagonun genişliği boyundan küçüktür: daha dar eksen "yan yana" (sütun) ekseni.
        cross_is_y = (max(ys) - min(ys)) <= (max(xs) - min(xs))
        ux, uy = _unit(xs), _unit(ys)
        for no, x, y, t, item in raw:
            L, C = (x, y) if cross_is_y else (y, x)
            seats_xy[no] = (L, C)
        uL, uC = (ux, uy) if cross_is_y else (uy, ux)
        minL = min(v[0] for v in seats_xy.values())
        minC = min(v[1] for v in seats_xy.values())

    # Kesintisiz blok (yan yana oturan koltuklar; koridor blok keser)
    blk: dict[str, str] = {}
    rows: dict[float, list[tuple[float, str]]] = {}
    if have_pos:
        for no, (L, C) in seats_xy.items():
            rows.setdefault(L, []).append((C, no))
        for L, lst in rows.items():
            lst.sort()
            start, prev = None, None
            for C, no in lst:
                if prev is None or C - prev > uC * 1.01:
                    start = C
                blk[no] = f"{start:g}"
                prev = C

    rot_of = {no: (int(r) % 360 if (r := _num(t.get("rotation"))) is not None else None) for no, _, _, t, _ in raw}
    by_pos = {seats_xy[no]: no for no in seats_xy} if have_pos else {}

    def facing_group(no: str) -> str | None:
        if not have_pos or rot_of.get(no) is None:
            return None
        L, C = seats_xy[no]
        for dL in (-uL, uL):
            other = by_pos.get((L + dL, C))
            if other and rot_of.get(other) is not None and (rot_of[other] - rot_of[no]) % 360 == 180:
                return f"{wagon}-{min(L, L + dL):g}-{blk.get(no)}"
        return None

    out = []
    for no, x, y, t, item in raw:
        genders = occupied.get(no)
        allocated = bool(t.get("isAllocated")) or genders is not None
        rot = rot_of[no]
        direction = None
        if FORWARD_ROT is not None and rot is not None:
            direction = "F" if rot == FORWARD_ROT else ("B" if rot == (FORWARD_ROT + 180) % 360 else None)
        row = pos = None
        if have_pos:
            L, C = seats_xy[no]
            row, pos = round((L - minL) / uL), round((C - minC) / uC)
        out.append(Seat(
            wagon=wagon, no=no, row=row, pos=pos,
            free=on_sale and not allocated,
            gender=(genders[0] if genders else None),
            direction=direction, group=facing_group(no), cabin=cabin,
            rot=rot, blk=blk.get(no), restrict=norm_gender(t.get("genderRestriction")),
        ))
    return out


def parse_seatmap(data) -> list[Seat]:
    """Koltuk haritası JSON'undan Seat listesi çıkarır. Şema tanınmazsa boş liste döner."""
    wagons = _find_wagon_list(data)
    if not wagons:
        return []
    seats: list[Seat] = []
    for w in wagons:
        if isinstance(w, dict):
            seats.extend(_parse_wagon(w))
    return seats


# --- Komşuluk ve sıra anahtarları -----------------------------------------------
def block_key(s: Seat):
    """Yan yana oturan koltuk bloğu. Blok bilgisi yoksa satır/sütun, o da yoksa numaraya göre."""
    if s.blk is not None and s.row is not None:
        return (s.wagon, s.row, s.blk)
    if s.row is not None and s.pos is not None:
        return (s.wagon, s.row, s.pos // 2)
    n = s.num
    return (s.wagon, "n", (n - 1) // 2) if n else None


def row_key(s: Seat):
    if s.row is not None:
        return (s.wagon, s.row)
    n = s.num
    return (s.wagon, "n", (n - 1) // 4) if n else None


def _wagon_sort(w: str):
    return (0, int(w)) if w.isdigit() else (1, w)


# --- Öneri motoru ---------------------------------------------------------------
def _gender_ok(seat: Seat, g: str, blocks: dict, mine: set[str], ctx: Ctx) -> bool:
    """Koltuğa özel kısıt + TCDD kuralı: yanındaki koltuk doluysa komşu aynı cinsiyetten olmalı."""
    if seat.restrict and seat.restrict != g:
        return False
    if not ctx.has_gender:
        return True
    key = block_key(seat)
    if key is None:
        return True
    for n in blocks.get(key, []):
        if n.sid == seat.sid or n.sid in mine or n.free:
            continue
        if n.gender != g:
            return False
    return True


def _assign(chosen: tuple[Seat, ...], genders: list[str], blocks: dict, ctx: Ctx):
    mine = {s.sid for s in chosen}
    for perm in sorted(set(itertools.permutations(genders))):
        if all(_gender_ok(s, g, blocks, mine, ctx) for s, g in zip(chosen, perm)):
            return perm
    return None


def _evaluate(chosen: tuple[Seat, ...], prefs: Prefs, ctx: Ctx) -> tuple[list[str], list[str]]:
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
        if not ctx.has_groups:
            notes.append("Karşılıklı koltuk bilgisi haritadan çıkarılamadı, bu tercih uygulanamadı.")
        else:
            groups = {s.group for s in chosen}
            rots = {s.rot for s in chosen} - {None}
            ok = len(groups) == 1 and None not in groups and (len(rots) >= 2 or not ctx.has_rot)
            if not ok:
                unmet.append("karşılıklı")
    if prefs.direction in ("F", "B") and not (n > 1 and prefs.facing):
        if not ctx.has_dir:
            notes.append("Koltuk yönü çözülemedi (TCDD_FORWARD_ROTATION ayarlı değil), bu tercih uygulanamadı.")
        elif any(s.direction != prefs.direction for s in chosen):
            unmet.append("koltuk yönü")
    return unmet, notes


def _candidates(pool: list[Seat], n: int) -> list[tuple[Seat, ...]]:
    out: dict[frozenset, tuple[Seat, ...]] = {}

    def add(t) -> None:
        out.setdefault(frozenset(s.sid for s in t), tuple(t))

    by_wagon: dict[str, list[Seat]] = {}
    for s in pool:
        by_wagon.setdefault(s.wagon, []).append(s)

    for free in by_wagon.values():
        if n == 1:
            for s in free:
                add((s,))
            continue
        groups: dict[str, list[Seat]] = {}
        rows: dict = {}
        for s in free:
            if s.group:
                groups.setdefault(s.group, []).append(s)
            rk = row_key(s)
            if rk:
                rows.setdefault(rk, []).append(s)
        if n <= 4:
            for g in groups.values():
                if n <= len(g) <= 8:
                    for c in itertools.combinations(g, n):
                        add(c)
            for r in rows.values():
                if n <= len(r) <= 6:
                    for c in itertools.combinations(r, n):
                        add(c)
        ordered = sorted(free, key=lambda s: (s.row or 0, s.pos or 0, s.num or 0))
        for i in range(len(ordered) - n + 1):
            add(ordered[i:i + n])
    return list(out.values())


def make_plan(seats: list[Seat], prefs: Prefs) -> Plan | None:
    """Tercihlere en uygun koltuk grubunu seçer; yoksa None döner."""
    n = len(prefs.genders)
    if n < 1 or not seats:
        return None
    cabin = tr_norm(prefs.cabin)
    pool = [
        s for s in seats
        if s.free and "tekerlek" not in tr_norm(s.cabin) and (not cabin or cabin in tr_norm(s.cabin))
    ]
    blocks: dict = {}
    for s in seats:
        k = block_key(s)
        if k:
            blocks.setdefault(k, []).append(s)
    ctx = Ctx(
        has_groups=any(s.group for s in seats),
        has_rot=any(s.rot is not None for s in seats),
        has_dir=any(s.direction for s in seats),
        has_gender=any(s.gender for s in seats if not s.free),
    )

    best = None
    for cand in _candidates(pool, n):
        perm = _assign(cand, prefs.genders, blocks, ctx)
        if perm is None:
            continue
        unmet, notes = _evaluate(cand, prefs, ctx)
        if prefs.strict and unmet:
            continue
        key = (len(unmet), _wagon_sort(cand[0].wagon), cand[0].num or 0, cand[0].no)
        if best is None or key < best[0]:
            best = (key, Plan(cand, perm, unmet, notes))
    plan = best[1] if best else None
    if plan and not ctx.has_gender:
        plan.notes.append("Haritada dolu koltukların yolcu cinsiyeti yok, komşu cinsiyet kuralı kontrol edilemedi.")
    return plan


def still_free(ids: list[str], seats: list[Seat]) -> bool:
    free = {s.sid for s in seats if s.free}
    return all(i in free for i in ids)


GENDER_LABEL = {"K": "Kadın", "E": "Erkek"}
DIR_LABEL = {"F": "ileri yön", "B": "geri yön"}


def describe(plan: Plan) -> str:
    lines = []
    for s, g in zip(plan.seats, plan.genders):
        extra = ", ".join(x for x in (DIR_LABEL.get(s.direction or ""), s.cabin) if x)
        lines.append(f"Vagon {s.wagon} · Koltuk {s.no} ({GENDER_LABEL.get(g, g)})" + (f" — {extra}" if extra else ""))
    if plan.unmet:
        lines.append("Karşılanamayan tercih: " + ", ".join(plan.unmet))
    for note in plan.notes:
        lines.append("Not: " + note)
    return "\n".join(lines)


# --- Demo koltuk dünyası (ağ kullanmadan deneme için; gerçek şemayla aynı biçimde) --
class DemoWorld:
    """Her sorguda bir kısım koltuğun satıldığı sahte koltuk haritaları üretir."""

    COLS = (0, 1, 3, 4)  # 2+2 düzen, 2. sütun koridor

    def __init__(self, seed: int | None = None, wagons: int = 2, rows: int = 8):
        self.rng = random.Random(seed)
        self.wagons, self.rows = wagons, rows
        self.state: dict = {}

    def _init(self) -> list[dict]:
        seats = []
        for w in range(1, self.wagons + 1):
            for r in range(self.rows):
                for ci, c in enumerate(self.COLS):
                    sold = self.rng.random() < 0.75
                    seats.append({
                        "wagon": w, "no": str(r * 4 + ci + 1), "x": r, "y": c,
                        "free": not sold, "gender": self.rng.choice(["MALE", "FEMALE"]) if sold else None,
                        "rot": 0 if r % 2 == 0 else 180,
                    })
        return seats

    def _to_json(self, st: list[dict]) -> dict:
        cars = []
        for w in range(1, self.wagons + 1):
            mine = [s for s in st if s["wagon"] == w]
            cars.append({
                "trainCarId": 1000 + w, "trainCarIndex": w, "trainCarOnSale": True,
                "capacity": len(mine), "availableSeatCount": sum(1 for s in mine if s["free"]),
                "seatMapTemplate": {
                    "car": {"name": "EKONOMİ"}, "rowSize": self.rows, "colSize": 5,
                    "seatMaps": [
                        {"seatNumber": s["no"], "positionX": s["x"], "positionY": s["y"], "rotation": s["rot"],
                         "isAllocated": not s["free"], "genderRestriction": None,
                         "item": {"saleable": True, "cabinClassId": 1, "itemType": {"name": "SEAT"}}}
                        for s in mine
                    ],
                },
                "allocationSeats": [{"seatNumber": s["no"], "genders": [s["gender"]]} for s in mine if not s["free"]],
            })
        return {"seatMaps": cars}

    def seatmap(self, key, tick: bool = True) -> dict:
        st = self.state.get(key)
        if st is None:
            st = self.state[key] = self._init()
        elif tick:
            for s in st:
                if s["free"] and self.rng.random() < 0.15:
                    s["free"], s["gender"] = False, self.rng.choice(["MALE", "FEMALE"])
        return self._to_json(st)
