"""Komut satırından güzergah, yolcu ve bildirim soruları."""

from __future__ import annotations

import datetime as dt

from tcdd import notify
from tcdd.config import AlarmConfig
from tcdd.models import Kind, tr_norm
from tcdd.paths import MIN_INTERVAL, TZ
from tcdd.stations import Station


def ask(prompt: str, default: str | None = None) -> str:
    suffix = f" [{default}]" if default else ""
    value = input(f"{prompt}{suffix}: ").strip()
    return value or (default or "")


def ask_yes(prompt: str, default: str = "h") -> bool:
    while True:
        value = tr_norm(ask(f"{prompt} (e/h)", default))
        if value in ("e", "evet", "y", "yes"):
            return True
        if value in ("h", "hayir", "n", "no"):
            return False
        print("  e veya h yazın.")


def ask_choice(prompt: str, options: dict[str, str], default: str) -> str:
    """Numara ya da anahtar kabul eder."""
    keys = list(options)
    print(prompt)
    for index, key in enumerate(keys, 1):
        print(f"  {index}) {options[key]}")
    while True:
        value = ask("Seçim", str(keys.index(default) + 1)).strip()
        if value.isdigit() and 1 <= int(value) <= len(keys):
            return keys[int(value) - 1]
        if value in options:
            return value
        print("  Geçerli bir numara girin.")


def ask_int(prompt: str, default: int, low: int, high: int) -> int:
    while True:
        value = ask(prompt, str(default))
        if value.isdigit() and low <= int(value) <= high:
            return int(value)
        print(f"  {low}-{high} arasında bir sayı girin.")


def _gender(index: int) -> str:
    while True:
        value = tr_norm(ask(f"{index}. yolcunun cinsiyeti (K=Kadın, E=Erkek)"))
        if value in ("k", "kadin"):
            return "K"
        if value in ("e", "erkek"):
            return "E"
        print("  K veya E yazın.")


def pick_station(
    stations: list[Station],
    label: str,
    allowed_ids: set[int] | None = None,
) -> Station:
    """Ad parçasıyla istasyon aratır."""
    choices = stations
    if allowed_ids:
        allowed_text = {str(item) for item in allowed_ids}
        limited = [
            station
            for station in stations
            if station.id in allowed_ids or str(station.id) in allowed_text
        ]
        choices = limited or stations
        print(
            f"  ({len(choices)} istasyon, seçtiğiniz kalkış istasyonundan "
            "gidilebilenlerle sınırlı)"
        )
    while True:
        query = ask(f"{label} istasyonu (adın bir kısmını yazın)")
        if not query:
            continue
        needle = tr_norm(query)
        matches = [
            station for station in choices if needle in tr_norm(station.name)
        ]
        if not matches:
            print("  Eşleşme yok, tekrar deneyin.")
            continue
        shown = matches[:15]
        for index, station in enumerate(shown, 1):
            print(f"  {index:>2}) {station.name}")
        if len(matches) > len(shown):
            hidden = len(matches) - len(shown)
            print(f"  ... {hidden} sonuç daha var, aramayı daraltın.")
        choice = ask("Numara (yeniden aramak için boş bırakın)")
        if choice.isdigit() and 1 <= int(choice) <= len(shown):
            chosen = shown[int(choice) - 1]
            print(f"  Seçildi: {chosen.name}")
            return chosen


def _parse_date(text: str) -> dt.date:
    for fmt in ("%d.%m.%Y", "%d/%m/%Y", "%Y-%m-%d", "%d-%m-%Y"):
        try:
            return dt.datetime.strptime(text.strip(), fmt).date()
        except ValueError:
            continue
    raise ValueError(text)


def _ask_dates() -> list[str]:
    while True:
        raw = ask("Tarih(ler) GG.AA.YYYY (birden fazlaysa virgülle ayırın)")
        try:
            dates = sorted(
                {_parse_date(part) for part in raw.split(",") if part.strip()}
            )
            if not dates:
                raise ValueError
        except ValueError:
            print(
                "  Tarih anlaşılamadı. Örnek: 15.11.2026 veya "
                "15.11.2026, 16.11.2026"
            )
            continue
        if any(day < dt.datetime.now(TZ).date() for day in dates):
            print("  Geçmiş tarih seçilemez.")
            continue
        return [day.isoformat() for day in dates]


def _ask_time_range() -> tuple[str, str]:
    while True:
        raw = ask("Saat aralığı SS:DD-SS:DD", "00:00-23:59")
        try:
            start_text, end_text = raw.split("-")
            start = dt.datetime.strptime(start_text.strip(), "%H:%M").time()
            end = dt.datetime.strptime(end_text.strip(), "%H:%M").time()
        except ValueError:
            print("  Örnek: 08:00-14:30")
            continue
        if start > end:
            print("  Başlangıç, bitişten sonra olamaz.")
            continue
        return start.strftime("%H:%M"), end.strftime("%H:%M")


def _ask_notify() -> dict:
    channel = ask_choice(
        "\nBildirimi nereden almak istersiniz?", notify.CHANNELS, "telegram"
    )
    settings = {"channel": channel}
    if channel in ("email", "both"):
        while True:
            address = ask("Bildirim gelecek e-posta adresi")
            domain = address.split("@")[-1]
            if "@" in address and "." in domain:
                settings["email"] = address
                break
            print("  Geçerli bir e-posta adresi girin.")
    return settings


def _ask_kinds() -> list[str]:
    print("\n--- Koltuk türü ---")
    kinds = []
    if ask_yes("Ekonomi dahil edilsin mi?", "e"):
        kinds.append(Kind.ECONOMY.name)
    if ask_yes("Business dahil edilsin mi?", "e"):
        kinds.append(Kind.BUSINESS.name)
    if ask_yes("Tekerlekli sandalye dahil edilsin mi?", "h"):
        kinds.append(Kind.WHEELCHAIR.name)
    while not kinds:
        print("  En az bir koltuk türü seçin.")
        if ask_yes("Ekonomi?", "e"):
            kinds.append(Kind.ECONOMY.name)
        if ask_yes("Business?", "h"):
            kinds.append(Kind.BUSINESS.name)
        if ask_yes("Tekerlekli sandalye?", "h"):
            kinds.append(Kind.WHEELCHAIR.name)
    return kinds


def _ask_forward_rotation(direction: str) -> int | None:
    if direction not in ("F", "B"):
        return None
    rotation = ask_choice(
        "İleri yöne bakan koltuğun rotation değeri (yön tercihi için):",
        {"skip": "Bilmiyorum", "0": "rotation 0", "180": "rotation 180"},
        "skip",
    )
    if rotation == "skip":
        return None
    return int(rotation)


def build_config(stations: list[Station]) -> AlarmConfig:
    """Soruları sorar ve alarm ayarını döndürür."""
    print("\n--- Sefer ---")
    origin = pick_station(stations, "Nereden")
    allowed = set(origin.pairs) or None
    destination = pick_station(stations, "Nereye", allowed)
    dates = _ask_dates()
    time_from, time_to = _ask_time_range()

    print("\n--- Yolcular ---")
    count = ask_int("Kaç kişi yolculuk yapacak", 1, 1, 6)
    genders = [_gender(index) for index in range(1, count + 1)]
    side_by_side = False
    facing = False
    if count > 1:
        together = "yan yana" if count == 2 else "aynı sırada"
        side_by_side = ask_yes(f"Yolcular {together} oturmak istiyor mu?")
        facing = ask_yes("Karşılıklı koltuk istiyor musunuz?")
    direction = ask_choice(
        "Koltuk yönü tercihi:",
        {
            "A": "Fark etmez",
            "F": "Trenin gidiş yönüne (ileri)",
            "B": "Gidiş yönüne ters (geri)",
        },
        "A",
    )
    strict = (
        ask_choice(
            "Tercihler karşılanamazsa:",
            {
                "soft": "Yine de en yakın koltuğu öner (eksik tercihi belirt)",
                "strict": "Sadece tüm tercihlere uyan koltuk varsa haber ver",
            },
            "soft",
        )
        == "strict"
    )
    return AlarmConfig(
        origin=origin,
        destination=destination,
        dates=dates,
        time_from=time_from,
        time_to=time_to,
        genders=genders,
        side_by_side=side_by_side,
        facing=facing,
        direction=direction,
        strict=strict,
        kinds=_ask_kinds(),
        forward_rotation=_ask_forward_rotation(direction),
        notify=_ask_notify(),
        interval=ask_int(
            f"Kontrol aralığı (saniye, en az {MIN_INTERVAL})",
            180,
            MIN_INTERVAL,
            86400,
        ),
    )
