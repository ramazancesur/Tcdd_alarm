"""İzleme döngüsü ve bir sefer için bildirim kararı."""

from __future__ import annotations

import datetime as dt
import json
import random
import sys
import time
from collections.abc import Callable

import requests

from tcdd import notify
from tcdd.client import TcddClient
from tcdd.config import AlarmConfig
from tcdd.demo import DemoWorld
from tcdd.models import Prefs, Seat
from tcdd import paths
from tcdd.paths import SITE, TZ, BlockedError
from tcdd.planner import describe, make_plan, still_free
from tcdd.schedule import in_range
from tcdd.seatmap import parse_seatmap

Send = Callable[[str, str], None]


def load_state() -> dict:
    """Önerilen koltukları ve tek seferlik sayım bildirimlerini okur."""
    active: dict[str, list[str]] = {}
    counted: set[str] = set()
    if paths.STATE_FILE.exists():
        active, counted = _read_state()
    return {"active": active, "count_notified": counted}


def _read_state() -> tuple[dict[str, list[str]], set[str]]:
    active: dict[str, list[str]] = {}
    counted: set[str] = set()
    try:
        raw = json.loads(paths.STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return active, counted
    for key, value in (raw.get("active") or {}).items():
        if isinstance(value, list):
            active[str(key)] = [str(item) for item in value]
    counted = {str(item) for item in (raw.get("count") or [])}
    return active, counted


def save_state(state: dict) -> None:
    payload = {
        "active": state.get("active") or {},
        "count": sorted(state.get("count_notified") or []),
    }
    text = json.dumps(payload, ensure_ascii=False, indent=1)
    paths.STATE_FILE.write_text(text, encoding="utf-8")


def handle_train(
    state: dict,
    key: str,
    train: dict,
    seats: list[Seat],
    prefs: Prefs,
    send: Send,
    route: str,
) -> None:
    """Bir tren için bildirim kararını verir.

    Önerilen koltuklar boşsa susar. Biri satılırsa yenisini önerir.
    Harita okunamazsa boş yer sayısını bir kez bildirir.
    """
    active: dict = state.setdefault("active", {})
    counted: set = state.setdefault("count_notified", set())
    day, name, clock = _key_parts(key)
    head = f"{route}\n{day} {clock} — {name}"
    if train["seats"] < len(prefs.genders):
        active.pop(key, None)
        counted.discard(key)
        return
    if not seats:
        _notify_count_only(active, counted, key, train, send, head)
        return
    _suggest(active, key, train, seats, prefs, send, head, day, clock)


def _key_parts(key: str) -> tuple[str, str, str]:
    parts = key.split("|")
    day, name, clock = (parts + ["?", "?", "?"])[:3]
    return day, name, clock


def _notify_count_only(
    active, counted, key, train, send: Send, head: str
) -> None:
    if key in active:
        return
    if key in counted:
        return
    counted.add(key)
    cabins = ", ".join(
        f"{name}: {count}" for name, count in train["cabins"].items() if count
    )
    send(
        "🚆 Boş yer var!",
        f"{head}\n{cabins}\nKoltuk haritası okunamadı, "
        f"koltuk önerisi yapılamadı.\n{SITE}",
    )


def _suggest(active, key, train, seats, prefs, send, head, day, clock) -> None:
    del train
    previous = active.get(key)
    if previous and still_free(previous, seats):
        return
    plan = make_plan(seats, prefs)
    if plan is None:
        if previous:
            print(
                f"[{day} {clock}] Önerilen koltuklar satıldı, "
                "tercihlere uyan başka koltuk yok."
            )
            active.pop(key, None)
        return
    active[key] = plan.ids
    body = f"{head}\n{describe(plan)}\n{SITE}"
    if previous:
        send("🔄 Önerilen koltuk satıldı, yeni öneri", body)
    else:
        send("🚆 Boş yer var!", body)


def check_channel(config: AlarmConfig, demo: bool) -> None:
    """Eksik bildirim ayarında durur. Demoda konsola düşer."""
    missing = notify.missing_env(config.notify["channel"])
    if not missing:
        return
    if demo:
        names = ", ".join(missing)
        print(
            f"(Demo) {names} tanımlı değil, "
            "bildirimler sadece konsola yazılacak."
        )
        config.notify = {"channel": "console"}
        return
    joined = ", ".join(missing)
    sys.exit(
        "Bildirim için .env dosyasında şu değerler eksik: "
        f"{joined}\n"
        "Ayarlarınız kaydedildi. .env dosyasını doldurduktan sonra "
        "şunu çalıştırın:\n"
        "    python -m tcdd --resume"
    )


def monitor(config: AlarmConfig, demo: bool) -> None:
    """Seçilen günler geçene kadar seferleri aralıkla sorgular."""
    route = f"{config.origin.name} → {config.destination.name}"
    prefs = config.prefs()
    _announce(config, route)
    sender = _sender(config)
    world = DemoWorld() if demo else None
    client = None if demo else TcddClient()
    state = {"active": {}, "count_notified": set()} if demo else load_state()
    blocked = 0
    while True:
        days = _active_days(config.dates)
        if not days:
            print("Tüm tarihler geçti, çıkılıyor.")
            _persist(state, demo)
            return
        blocked, stop = _poll_days(
            config,
            days,
            demo,
            world,
            client,
            state,
            prefs,
            sender,
            route,
            blocked,
        )
        _persist(state, demo)
        if stop:
            return
        time.sleep(config.interval + random.randint(0, 15))


def _announce(config: AlarmConfig, route: str) -> None:
    passengers = ", ".join(
        "Kadın" if gender == "K" else "Erkek" for gender in config.genders
    )
    print(
        f"\nİzleniyor: {route}\n"
        f"  Günler: {', '.join(config.dates)}\n"
        f"  Saat: {config.time_from}-{config.time_to}  |  "
        f"Yolcular: {passengers}  |  Her {config.interval} sn  |  "
        "Durdurmak için Ctrl+C\n"
    )


def _sender(config: AlarmConfig) -> Send:
    def send(subject: str, body: str) -> None:
        try:
            notify.send(config.notify, subject, body)
        except (
            requests.RequestException,
            OSError,
            RuntimeError,
            ValueError,
        ) as error:
            print(f"Bildirim gönderilemedi: {error}")

    return send


def _active_days(dates: list[str]) -> list[str]:
    today = dt.datetime.now(TZ).date().isoformat()
    return [day for day in dates if day >= today]


def _persist(state: dict, demo: bool) -> None:
    if not demo:
        save_state(state)


def _poll_days(
    config,
    days,
    demo,
    world,
    client,
    state,
    prefs,
    sender,
    route,
    blocked,
) -> tuple[int, bool]:
    need = len(config.genders)
    for day in days:
        try:
            trains = _trains(config, day, demo, world, client)
            blocked = 0
            _handle_day(
                config,
                day,
                trains,
                need,
                demo,
                world,
                client,
                state,
                prefs,
                sender,
                route,
            )
        except BlockedError as error:
            blocked += 1
            _report_block(error)
            _persist(state, demo)
            if blocked >= 3:
                sys.exit(
                    "Üst üste 3 ret. Engeli aşmaya çalışmıyorum; "
                    "yeni TCDD_TOKEN alıp tekrar deneyin "
                    "ya da bir süre bekleyin."
                )
            return blocked, False
        except (requests.RequestException, ValueError) as error:
            clock = dt.datetime.now(TZ)
            print(f"[{clock:%H:%M:%S}] {day}: sorgu hatası: {error}")
    return blocked, False


def _report_block(error: BlockedError) -> None:
    clock = dt.datetime.now(TZ)
    print(
        f"[{clock:%H:%M:%S}] Sunucu isteği reddetti ({error}). "
        "Token süresi dolmuş ya da otomatik erişim engelleniyor olabilir."
    )


def _trains(config, day, demo, world, client: TcddClient | None) -> list[dict]:
    if demo:
        return _demo_trains(config, day, world)
    assert client is not None
    return client.trains(config, day)


def _demo_trains(config: AlarmConfig, day: str, world: DemoWorld) -> list[dict]:
    date = dt.date.fromisoformat(day)
    kinds = config.prefs().kinds
    trains = []
    for hour in (7, 9, 12, 15, 18):
        seats = parse_seatmap(
            world.seatmap((day, hour), tick=False), config.forward_rotation
        )
        free = sum(1 for seat in seats if seat.free and seat.kind in kinds)
        trains.append(
            {
                "id": f"{day}-{hour}",
                "name": f"YHT {config.origin.name} - {config.destination.name}",
                "departure": dt.datetime.combine(date, dt.time(hour, 30), TZ),
                "seats": free,
                "cabins": {"EKONOMİ": free},
            }
        )
    return trains


def _handle_day(
    config, day, trains, need, demo, world, client, state, prefs, sender, route
) -> None:
    matching = [
        train
        for train in trains
        if in_range(train["departure"], config.time_from, config.time_to)
    ]
    available = sum(1 for train in matching if train["seats"] >= need)
    clock = dt.datetime.now(TZ)
    print(
        f"[{clock:%H:%M:%S}] {day}: {len(matching)} sefer aralıkta, "
        f"{available} tanesinde yer var"
    )
    for train in matching:
        departure = train["departure"]
        local = departure.astimezone(TZ) if departure else None
        hour = local.strftime("%H:%M") if local else "?"
        key = f"{day}|{train['name']}|{hour}"
        seats = _seats_for(config, day, train, need, demo, world, client, local)
        handle_train(state, key, train, seats, prefs, sender, route)


def _seats_for(
    config, day, train, need, demo, world, client, local
) -> list[Seat]:
    if train["seats"] < need:
        return []
    if demo:
        hour = local.hour if local else 0
        return parse_seatmap(
            world.seatmap((day, hour)), config.forward_rotation
        )
    try:
        assert client is not None
        return client.seats(config, day, train)
    except (requests.RequestException, ValueError) as error:
        print(f"  Koltuk haritası alınamadı ({error})")
        return []

