#!/usr/bin/env python3
"""TCDD boş yer ve koltuk önerisi bildirimi.

Kullanıcı istasyonları (TCDD e-bilet'ten çekilen listeden), günleri, saat aralığını,
yolcu sayısını/cinsiyetlerini ve koltuk tercihlerini (yan yana, karşılıklı, yön) girer.
Uygulama sefer ve koltuk haritasını belirli aralıklarla sorgular; uygun koltuk çıkınca
E-POSTA veya TELEGRAM ile BİR KEZ haber verir. Önerilen koltuk satılırsa yenisini önerir.
Bilet SATIN ALMAZ.

Kullanım:
    python tcdd_alarm.py                # soruları sor, sonra izlemeye başla
    python tcdd_alarm.py --resume       # son seçimlerle devam et
    python tcdd_alarm.py --test-notify  # bildirim kanalını dene
    python tcdd_alarm.py --demo         # sahte verilerle akışı dene (ağ gerekmez)
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import random
import sys
import time
from pathlib import Path
from zoneinfo import ZoneInfo

import requests

import notify
from seats import DemoWorld, Prefs, Seat, describe, make_plan, parse_seatmap, still_free, tr_norm

TZ = ZoneInfo("Europe/Istanbul")
HERE = Path(__file__).resolve().parent
STATIONS_FILE = HERE / "stations.json"
CONFIG_FILE = HERE / "alarm.json"
DUMP_DIR = HERE / "dumps"

# --- TCDD ayarları (hepsi ortam değişkeniyle değiştirilebilir) -----------------
# NOT: TCDD resmi bir API yayınlamıyor; aşağıdaki değerler e-bilet sitesinin kendi
# kullandığı uç noktalara göre VARSAYIMDIR (özellikle koltuk haritası) ve değişebilir.
# Çalışmazsa tarayıcıda F12 > Network sekmesinden gerçek isteği görüp ortam
# değişkenleriyle düzeltin. TCDD_DUMP=1 ile ham koltuk haritası cevapları dumps/ klasörüne
# kaydedilir (ayrıştırıcıyı gerçek şemaya uydurmak için).
# Yol adları (train/train-availability, seat-maps/load-by-train-id, datas/station-pairs-MOBIL.json)
# ve istek/cevap alan adları e-bilet mobil uygulamasının APK'sından alındı. Sunucu adresleri:
#   web:    https://web-api-prod-ytp.tcddtasimacilik.gov.tr/tms   (varsayılan)
#   mobil:  https://mobil-api-prod-ytp.tcddtasimacilik.gov.tr     (yol öneki doğrulanmadı)
#   CDN:    https://cdn-api-prod-ytp.tcddtasimacilik.gov.tr       (istasyon listesi)
BASE = os.getenv("TCDD_BASE", "https://web-api-prod-ytp.tcddtasimacilik.gov.tr/tms")
CDN = os.getenv("TCDD_CDN", "https://cdn-api-prod-ytp.tcddtasimacilik.gov.tr")
STATIONS_URL = os.getenv("TCDD_STATIONS_URL", f"{CDN}/datas/station-pairs-MOBIL.json")
AVAIL_URL = os.getenv("TCDD_AVAIL_URL", f"{BASE}/train/train-availability")
SEATMAP_URL = os.getenv("TCDD_SEATMAP_URL", f"{BASE}/seat-maps/load-by-train-id")
DATE_FMT = os.getenv("TCDD_DATE_FMT", "%d-%m-%Y %H:%M:%S")
UNIT_ID = os.getenv("TCDD_UNIT_ID", "3895")
MIN_INTERVAL = 60  # saniye; sunucuyu yormamak için alt sınır
SITE = "https://ebilet.tcddtasimacilik.gov.tr/"
REQUIRED_CFG = {"genders", "notify", "side_by_side", "facing", "direction", "strict"}


def load_dotenv() -> None:
    env = HERE / ".env"
    if not env.exists():
        return
    for line in env.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


# --- Yardımcılar ----------------------------------------------------------------
def headers() -> dict:
    h = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "tr",
        "Origin": "https://ebilet.tcddtasimacilik.gov.tr",
        "Referer": SITE,
        "unit-id": UNIT_ID,
    }
    token = os.getenv("TCDD_TOKEN")
    if token:
        h["Authorization"] = token
    return h


class BlockedError(RuntimeError):
    """Sunucu isteği reddetti (401/403/429)."""


# --- İstasyonlar ----------------------------------------------------------------
def _pick(d: dict, *keys):
    for k in keys:
        if k in d and d[k] not in (None, ""):
            return d[k]
    return None


def _pair_ids(v) -> list:
    """StationItem.pairs: gidilebilen istasyonlar (id listesi ya da {id: ..} nesneleri)."""
    out = []
    for p in v if isinstance(v, list) else []:
        pid = p.get("id") if isinstance(p, dict) else p
        if isinstance(pid, (int, str)) and not isinstance(pid, bool):
            out.append(pid)
    return out


def parse_stations(data) -> list[dict]:
    """Farklı JSON şekillerinden [{id, name, pairs}] listesi çıkarır."""
    if isinstance(data, dict):
        for k in ("stations", "data", "result", "content", "items"):
            if isinstance(data.get(k), list):
                data = data[k]
                break
    out = []
    if isinstance(data, list):
        for s in data:
            if not isinstance(s, dict) or s.get("ticketSaleActive") is False:
                continue
            sid = _pick(s, "id", "stationId", "station_id")
            name = _pick(s, "name", "stationName", "station_name")
            if sid is not None and name:
                out.append({"id": sid, "name": str(name), "pairs": _pair_ids(s.get("pairs"))})
    return sorted(out, key=lambda x: tr_norm(x["name"]))


def get_stations(refresh: bool = False) -> list[dict]:
    if STATIONS_FILE.exists() and not refresh:
        return json.loads(STATIONS_FILE.read_text(encoding="utf-8"))
    print("İstasyon listesi e-bilet'ten alınıyor...")
    try:
        # CDN dosyası genelde herkese açıktır; önce kimliksiz dener, reddederse token ile tekrar dener.
        public = {k: v for k, v in headers().items() if k != "Authorization"}
        r = requests.get(STATIONS_URL, headers=public, timeout=30)
        if r.status_code in (401, 403):
            r = requests.get(STATIONS_URL, headers=headers(), timeout=30)
        if r.status_code in (401, 403, 429):
            raise BlockedError(f"HTTP {r.status_code}")
        r.raise_for_status()
        stations = parse_stations(r.json())
        if not stations:
            raise ValueError("Listede istasyon bulunamadı")
    except Exception as e:  # noqa: BLE001
        sys.exit(
            f"İstasyon listesi alınamadı ({e}).\n"
            "Seçenekler:\n"
            "  1) .env dosyasına TCDD_TOKEN=... ekleyin (e-bilet'te F12 > Network > bir isteğin 'Authorization' başlığı)\n"
            "  2) Tarayıcıda e-bilet'i açıp Network sekmesinde istasyon listesi isteğinin JSON cevabını\n"
            f"     {STATIONS_FILE.name} olarak bu klasöre kaydedin, ya da TCDD_STATIONS_URL ile doğru adresi verin."
        )
    STATIONS_FILE.write_text(json.dumps(stations, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(stations)} istasyon kaydedildi.")
    return stations


DEMO_STATIONS = [
    {"id": 98, "name": "ANKARA GAR"},
    {"id": 48, "name": "İSTANBUL(PENDİK)"},
    {"id": 49, "name": "İSTANBUL(SÖĞÜTLÜÇEŞME)"},
    {"id": 50, "name": "İSTANBUL(HALKALI)"},
    {"id": 60, "name": "ESKİŞEHİR"},
    {"id": 70, "name": "KONYA"},
    {"id": 80, "name": "İZMİR(BASMANE)"},
    {"id": 90, "name": "SİVAS"},
]


# --- Etkileşimli giriş ----------------------------------------------------------
def ask(prompt: str, default: str | None = None) -> str:
    suffix = f" [{default}]" if default else ""
    val = input(f"{prompt}{suffix}: ").strip()
    return val or (default or "")


def ask_yes(prompt: str, default: str = "h") -> bool:
    while True:
        v = tr_norm(ask(f"{prompt} (e/h)", default))
        if v in ("e", "evet", "y", "yes"):
            return True
        if v in ("h", "hayir", "n", "no"):
            return False
        print("  e veya h yazın.")


def ask_choice(prompt: str, options: dict[str, str], default: str) -> str:
    """options: {anahtar: etiket}. Numara ya da anahtar kabul eder."""
    keys = list(options)
    print(prompt)
    for i, k in enumerate(keys, 1):
        print(f"  {i}) {options[k]}")
    while True:
        v = ask("Seçim", str(keys.index(default) + 1)).strip()
        if v.isdigit() and 1 <= int(v) <= len(keys):
            return keys[int(v) - 1]
        if v in options:
            return v
        print("  Geçerli bir numara girin.")


def ask_int(prompt: str, default: int, lo: int, hi: int) -> int:
    while True:
        v = ask(prompt, str(default))
        if v.isdigit() and lo <= int(v) <= hi:
            return int(v)
        print(f"  {lo}-{hi} arasında bir sayı girin.")


def ask_gender(i: int) -> str:
    while True:
        v = tr_norm(ask(f"{i}. yolcunun cinsiyeti (K=Kadın, E=Erkek)"))
        if v in ("k", "kadin"):
            return "K"
        if v in ("e", "erkek"):
            return "E"
        print("  K veya E yazın.")


def pick_station(stations: list[dict], label: str, allowed_ids: set | None = None) -> dict:
    if allowed_ids:
        stations = [s for s in stations if s["id"] in allowed_ids or str(s["id"]) in allowed_ids] or stations
        print(f"  ({len(stations)} istasyon, seçtiğiniz kalkış istasyonundan gidilebilenlerle sınırlı)")
    while True:
        q = ask(f"{label} istasyonu (adın bir kısmını yazın)")
        if not q:
            continue
        nq = tr_norm(q)
        matches = [s for s in stations if nq in tr_norm(s["name"])]
        if not matches:
            print("  Eşleşme yok, tekrar deneyin.")
            continue
        shown = matches[:15]
        for i, s in enumerate(shown, 1):
            print(f"  {i:>2}) {s['name']}")
        if len(matches) > len(shown):
            print(f"  ... {len(matches) - len(shown)} sonuç daha var, aramayı daraltın.")
        c = ask("Numara (yeniden aramak için boş bırakın)")
        if c.isdigit() and 1 <= int(c) <= len(shown):
            chosen = shown[int(c) - 1]
            print(f"  Seçildi: {chosen['name']}")
            return chosen


def parse_date(s: str) -> dt.date:
    for fmt in ("%d.%m.%Y", "%d/%m/%Y", "%Y-%m-%d", "%d-%m-%Y"):
        try:
            return dt.datetime.strptime(s.strip(), fmt).date()
        except ValueError:
            pass
    raise ValueError(s)


def ask_dates() -> list[str]:
    while True:
        raw = ask("Tarih(ler) GG.AA.YYYY (birden fazlaysa virgülle ayırın)")
        try:
            dates = sorted({parse_date(p) for p in raw.split(",") if p.strip()})
            if not dates:
                raise ValueError
            if any(d < dt.datetime.now(TZ).date() for d in dates):
                print("  Geçmiş tarih seçilemez.")
                continue
            return [d.isoformat() for d in dates]
        except ValueError:
            print("  Tarih anlaşılamadı. Örnek: 15.11.2026 veya 15.11.2026, 16.11.2026")


def ask_time_range() -> tuple[str, str]:
    while True:
        raw = ask("Saat aralığı SS:DD-SS:DD", "00:00-23:59")
        try:
            a, b = raw.split("-")
            ta = dt.datetime.strptime(a.strip(), "%H:%M").time()
            tb = dt.datetime.strptime(b.strip(), "%H:%M").time()
            if ta > tb:
                print("  Başlangıç, bitişten sonra olamaz.")
                continue
            return ta.strftime("%H:%M"), tb.strftime("%H:%M")
        except ValueError:
            print("  Örnek: 08:00-14:30")


def ask_notify() -> dict:
    channel = ask_choice("\nBildirimi nereden almak istersiniz?", notify.CHANNELS, "telegram")
    settings = {"channel": channel}
    if channel in ("email", "both"):
        while True:
            addr = ask("Bildirim gelecek e-posta adresi")
            if "@" in addr and "." in addr.split("@")[-1]:
                settings["email"] = addr
                break
            print("  Geçerli bir e-posta adresi girin.")
    return settings


def build_config(stations: list[dict]) -> dict:
    print("\n--- Sefer ---")
    src = pick_station(stations, "Nereden")
    dst = pick_station(stations, "Nereye", {*src.get("pairs", []), *map(str, src.get("pairs", []))} or None)
    dates = ask_dates()
    t_from, t_to = ask_time_range()

    print("\n--- Yolcular ---")
    n = ask_int("Kaç kişi yolculuk yapacak", 1, 1, 6)
    genders = [ask_gender(i) for i in range(1, n + 1)]
    side_by_side = facing = False
    if n > 1:
        what = "yan yana" if n == 2 else "aynı sırada"
        side_by_side = ask_yes(f"Yolcular {what} oturmak istiyor mu?")
        facing = ask_yes("Karşılıklı koltuk istiyor musunuz?")
    direction = ask_choice(
        "Koltuk yönü tercihi:", {"A": "Fark etmez", "F": "Trenin gidiş yönüne (ileri)", "B": "Gidiş yönüne ters (geri)"}, "A"
    )
    strict = ask_choice(
        "Tercihler karşılanamazsa:",
        {"soft": "Yine de en yakın koltuğu öner (eksik tercihi belirt)", "strict": "Sadece tüm tercihlere uyan koltuk varsa haber ver"},
        "soft",
    ) == "strict"
    cabin = ask("Vagon tipi filtresi (örn. EKONOMİ, BUSİNESS; boş = hepsi)", "")

    settings = ask_notify()
    interval = ask_int(f"Kontrol aralığı (saniye, en az {MIN_INTERVAL})", 180, MIN_INTERVAL, 86400)
    return {
        "from": src, "to": dst, "dates": dates, "time_from": t_from, "time_to": t_to,
        "genders": genders, "side_by_side": side_by_side, "facing": facing,
        "direction": direction, "strict": strict, "cabin": cabin,
        "notify": settings, "interval": interval, "include_wheelchair": False,
    }


def prefs_from(cfg: dict) -> Prefs:
    return Prefs(
        genders=cfg["genders"], side_by_side=cfg["side_by_side"], facing=cfg["facing"],
        direction=cfg["direction"], strict=cfg["strict"], cabin=cfg.get("cabin", ""),
    )


# --- Sefer sorgusu --------------------------------------------------------------
def find_all(obj, key):
    """İç içe JSON'da verilen anahtarın tüm değerlerini döndürür."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == key:
                yield v
            yield from find_all(v, key)
    elif isinstance(obj, list):
        for v in obj:
            yield from find_all(v, key)


def parse_time(v) -> dt.datetime | None:
    if isinstance(v, (int, float)):
        return dt.datetime.fromtimestamp(v / 1000 if v > 1e11 else v, TZ)
    if isinstance(v, str):
        for fmt in ("%b %d, %Y %I:%M:%S %p", "%d-%m-%Y %H:%M:%S", "%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%dT%H:%M:%S"):
            try:
                t = dt.datetime.strptime(v, fmt)
                return t if t.tzinfo else t.replace(tzinfo=TZ)
            except ValueError:
                continue
    return None


def extract_trains(data, include_wheelchair: bool) -> list[dict]:
    """Cevaptan [{id, name, departure, seats, cabins}] çıkarır (şekle toleranslı)."""
    trains = []
    for lst in find_all(data, "trains"):
        if not isinstance(lst, list):
            continue
        for t in lst:
            if not isinstance(t, dict):
                continue
            dep = next((parse_time(x) for x in find_all(t, "departureTime") if parse_time(x)), None)
            cabins: dict[str, int] = {}
            for ca_list in find_all(t, "cabinClassAvailabilities"):
                for ca in (ca_list if isinstance(ca_list, list) else []):
                    cname = str((ca.get("cabinClass") or {}).get("name") or ca.get("name") or "?")
                    if not include_wheelchair and "tekerlek" in tr_norm(cname):
                        continue
                    cabins[cname] = cabins.get(cname, 0) + int(ca.get("availabilityCount") or 0)
            name = _pick(t, "commercialName", "name") or next(iter(find_all(t, "commercialName")), "Tren")
            tid = _pick(t, "id", "trainId") or next(iter(find_all(t, "trainId")), None)
            trains.append({
                "id": tid, "name": str(name), "departure": dep, "seats": sum(cabins.values()), "cabins": cabins,
                "from_id": t.get("departureStationId"), "to_id": t.get("arrivalStationId"),
            })
    return trains


def query_day(cfg: dict, day: str) -> list[dict]:
    d = dt.date.fromisoformat(day)
    payload = {
        "searchRoutes": [{
            "departureStationId": cfg["from"]["id"], "departureStationName": cfg["from"]["name"],
            "arrivalStationId": cfg["to"]["id"], "arrivalStationName": cfg["to"]["name"],
            # Yerel gece yarısı UTC'de bir gün önce 21:00'dir (Android sürümüyle aynı biçim)
            "departureDate": (dt.datetime.combine(d, dt.time()) - dt.timedelta(hours=3)).strftime(DATE_FMT),
        }],
        "passengerTypeCounts": [{"id": 0, "count": len(cfg["genders"])}],
        "searchReservation": False,
        "searchType": "DOMESTIC",
        "blTrainTypes": ["TURISTIK_TREN"],
    }
    r = requests.post(AVAIL_URL, headers=headers(), json=payload, timeout=40)
    if r.status_code in (401, 403, 429):
        raise BlockedError(f"HTTP {r.status_code}")
    r.raise_for_status()
    return extract_trains(r.json(), cfg.get("include_wheelchair", False))


def query_seatmap(cfg: dict, day: str, train: dict) -> list[Seat]:
    """Seçilen trenin koltuk haritasını çeker. Okunamazsa boş liste döner."""
    if train.get("id") is None:
        return []
    # SeatMapsRequest alanları (APK): fromStationId, toStationId, trainId
    payload = {
        "fromStationId": train.get("from_id") or cfg["from"]["id"],
        "toStationId": train.get("to_id") or cfg["to"]["id"],
        "trainId": train["id"],
    }
    r = requests.post(SEATMAP_URL, headers=headers(), json=payload, timeout=40)
    if r.status_code in (401, 403, 429):
        raise BlockedError(f"HTTP {r.status_code}")
    r.raise_for_status()
    data = r.json()
    if os.getenv("TCDD_DUMP"):
        DUMP_DIR.mkdir(exist_ok=True)
        (DUMP_DIR / f"seatmap_{day}_{train['id']}.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    return parse_seatmap(data)


# --- Demo -----------------------------------------------------------------------
def demo_trains(cfg: dict, day: str, world: DemoWorld) -> list[dict]:
    d = dt.date.fromisoformat(day)
    out = []
    for h in (7, 9, 12, 15, 18):
        key = (day, h)
        seats = parse_seatmap(world.seatmap(key, tick=False))
        free = sum(1 for s in seats if s.free)
        out.append({
            "id": f"{day}-{h}", "name": f"YHT {cfg['from']['name']} - {cfg['to']['name']}",
            "departure": dt.datetime.combine(d, dt.time(h, 30), TZ),
            "seats": free, "cabins": {"EKONOMİ": free},
        })
    return out


# --- Bildirim mantığı -----------------------------------------------------------
def in_range(t: dt.datetime | None, a: str, b: str) -> bool:
    if t is None:
        return True  # saat okunamadıysa kaçırmamak için dahil et
    return a <= t.strftime("%H:%M") <= b


def handle_train(state: dict, key: tuple, train: dict, seats: list[Seat], prefs: Prefs, send, route: str) -> None:
    """Bir tren için bildirim kararını verir.

    - Önerilen koltuklar hâlâ boşsa tekrar bildirmez.
    - Önerilen koltuklardan biri satıldıysa yeni koltuk önerir.
    - Koltuk haritası okunamazsa yalnızca boş yer sayısını (tek sefer) bildirir.
    """
    active: dict = state.setdefault("active", {})
    count_notified: set = state.setdefault("count_notified", set())
    day, name, hh = key
    head = f"{route}\n{day} {hh} — {name}"

    if train["seats"] <= 0:
        active.pop(key, None)
        count_notified.discard(key)
        return

    if not seats:
        if key not in count_notified:
            count_notified.add(key)
            cab = ", ".join(f"{k}: {v}" for k, v in train["cabins"].items() if v)
            send("🚆 Boş yer var!", f"{head}\n{cab}\nKoltuk haritası okunamadı, koltuk önerisi yapılamadı.\n{SITE}")
        return

    prev = active.get(key)
    if prev and still_free(prev, seats):
        return  # önerilen koltuklar hâlâ boş → tekrar bildirme

    plan = make_plan(seats, prefs)
    if plan is None:
        if prev:
            print(f"[{day} {hh}] Önerilen koltuklar satıldı, tercihlere uyan başka koltuk yok.")
            active.pop(key, None)
        return

    active[key] = plan.ids
    if prev:
        send("🔄 Önerilen koltuk satıldı, yeni öneri", f"{head}\n{describe(plan)}\n{SITE}")
    else:
        send("🚆 Boş yer var!", f"{head}\n{describe(plan)}\n{SITE}")


def monitor(cfg: dict, demo: bool) -> None:
    route = f"{cfg['from']['name']} → {cfg['to']['name']}"
    prefs = prefs_from(cfg)
    pax = ", ".join("Kadın" if g == "K" else "Erkek" for g in cfg["genders"])
    print(f"\nİzleniyor: {route}\n  Günler: {', '.join(cfg['dates'])}\n"
          f"  Saat: {cfg['time_from']}-{cfg['time_to']}  |  Yolcular: {pax}  |  Her {cfg['interval']} sn  |  Durdurmak için Ctrl+C\n")

    def send(subject: str, body: str) -> None:
        notify.send(cfg["notify"], subject, body)

    world = DemoWorld() if demo else None
    state: dict = {}
    blocked = 0
    while True:
        today = dt.datetime.now(TZ).date().isoformat()
        active_days = [d for d in cfg["dates"] if d >= today]
        if not active_days:
            print("Tüm tarihler geçti, çıkılıyor.")
            return
        for day in active_days:
            try:
                trains = demo_trains(cfg, day, world) if demo else query_day(cfg, day)
                blocked = 0
                matching = [t for t in trains if in_range(t["departure"], cfg["time_from"], cfg["time_to"])]
                print(f"[{dt.datetime.now(TZ):%H:%M:%S}] {day}: {len(matching)} sefer aralıkta, "
                      f"{sum(1 for t in matching if t['seats'] > 0)} tanesinde yer var")
                for t in matching:
                    key = (day, t["name"], t["departure"].strftime("%H:%M") if t["departure"] else "?")
                    seats: list[Seat] = []
                    if t["seats"] > 0:
                        if demo:
                            seats = parse_seatmap(world.seatmap((day, t["departure"].hour)))
                        else:
                            try:
                                seats = query_seatmap(cfg, day, t)
                            except (requests.RequestException, ValueError) as e:
                                print(f"  Koltuk haritası alınamadı ({e})")
                    handle_train(state, key, t, seats, prefs, send, route)
            except BlockedError as e:
                blocked += 1
                print(f"[{dt.datetime.now(TZ):%H:%M:%S}] Sunucu isteği reddetti ({e}). "
                      "Token süresi dolmuş ya da otomatik erişim engelleniyor olabilir.")
                if blocked >= 3:
                    sys.exit("Üst üste 3 ret. Engeli aşmaya çalışmıyorum; yeni TCDD_TOKEN alıp tekrar deneyin "
                             "ya da bir süre bekleyin.")
            except (requests.RequestException, ValueError) as e:
                print(f"[{dt.datetime.now(TZ):%H:%M:%S}] {day}: sorgu hatası: {e}")
        time.sleep(cfg["interval"] + random.randint(0, 15))


def check_channel(cfg: dict, demo: bool) -> None:
    """Seçilen bildirim kanalının ayarları eksikse durdurur (demoda konsola düşer)."""
    missing = notify.missing_env(cfg["notify"]["channel"])
    if not missing:
        return
    if demo:
        print(f"(Demo) {', '.join(missing)} tanımlı değil, bildirimler sadece konsola yazılacak.")
        cfg["notify"] = {"channel": "console"}
        return
    sys.exit(
        "Bildirim için .env dosyasında şu değerler eksik: " + ", ".join(missing) + "\n"
        "Ayarlarınız kaydedildi. .env dosyasını doldurduktan sonra şunu çalıştırın:\n"
        "    python tcdd_alarm.py --resume"
    )


def main() -> None:
    load_dotenv()
    ap = argparse.ArgumentParser(description="TCDD boş yer ve koltuk önerisi bildirimi")
    ap.add_argument("--resume", action="store_true", help="son kaydedilen ayarlarla başla")
    ap.add_argument("--refresh-stations", action="store_true", help="istasyon listesini yeniden çek")
    ap.add_argument("--test-notify", action="store_true", help="kayıtlı bildirim kanalına deneme mesajı gönder")
    ap.add_argument("--demo", action="store_true", help="sahte verilerle dene (ağ kullanmaz)")
    args = ap.parse_args()

    try:
        cfg = None
        if (args.resume or args.test_notify) and CONFIG_FILE.exists():
            cfg = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
            if not REQUIRED_CFG <= cfg.keys():
                print("Kayıtlı ayarlar eski sürümden, yeniden girmeniz gerekiyor.")
                cfg = None
            else:
                print("Kayıtlı ayarlar yüklendi.")
        if args.test_notify:
            if cfg is None:
                sys.exit("Önce alarmı bir kez kurun (python tcdd_alarm.py).")
            check_channel(cfg, False)
            notify.send(cfg["notify"], "✅ TCDD alarm testi", "Bildirim kanalı çalışıyor.")
            return
        if cfg is None:
            stations = DEMO_STATIONS if args.demo else get_stations(args.refresh_stations)
            cfg = build_config(stations)
            if not args.demo:
                CONFIG_FILE.write_text(json.dumps(cfg, ensure_ascii=False, indent=1), encoding="utf-8")
        check_channel(cfg, args.demo)
        monitor(cfg, args.demo)
    except (KeyboardInterrupt, EOFError):
        print("\nDurduruldu.")


if __name__ == "__main__":
    main()
