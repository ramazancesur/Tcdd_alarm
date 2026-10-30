"""Komut satırı girişi: ``python -m tcdd``."""

from __future__ import annotations

import argparse
import json
import sys

import requests

from tcdd import notify
from tcdd.config import AlarmConfig
from tcdd.monitor import check_channel, monitor
from tcdd.paths import CONFIG_FILE, STATE_FILE, load_dotenv
from tcdd.prompts import build_config
from tcdd.stations import DEMO_STATIONS, get_stations


def main(argv: list[str] | None = None) -> None:
    load_dotenv()
    args = _parser().parse_args(argv)
    try:
        _run(args)
    except (KeyboardInterrupt, EOFError):
        print("\nDurduruldu.")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="TCDD boş yer ve koltuk önerisi bildirimi"
    )
    parser.add_argument(
        "--resume", action="store_true", help="son kaydedilen ayarlarla başla"
    )
    parser.add_argument(
        "--refresh-stations",
        action="store_true",
        help="istasyon listesini yeniden çek",
    )
    parser.add_argument(
        "--test-notify",
        action="store_true",
        help="kayıtlı bildirim kanalına deneme mesajı gönder",
    )
    parser.add_argument(
        "--demo",
        action="store_true",
        help="sahte verilerle dene (ağ kullanmaz)",
    )
    return parser


def _run(args: argparse.Namespace) -> None:
    config = _load_saved(args.resume or args.test_notify)
    if args.test_notify:
        _test_notify(config)
        return
    if config is None:
        if args.demo:
            stations = DEMO_STATIONS
        else:
            stations = get_stations(args.refresh_stations)
        config = build_config(stations)
        if not args.demo:
            _save(config)
    check_channel(config, args.demo)
    monitor(config, args.demo)


def _load_saved(resume: bool) -> AlarmConfig | None:
    if not resume or not CONFIG_FILE.exists():
        return None
    raw = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    config = AlarmConfig.from_dict(raw)
    if config is None:
        print("Kayıtlı ayarlar eski sürümden, yeniden girmeniz gerekiyor.")
        return None
    print("Kayıtlı ayarlar yüklendi.")
    return config


def _save(config: AlarmConfig) -> None:
    text = json.dumps(config.to_dict(), ensure_ascii=False, indent=1)
    CONFIG_FILE.write_text(text, encoding="utf-8")
    if STATE_FILE.exists():
        STATE_FILE.unlink()


def _test_notify(config: AlarmConfig | None) -> None:
    if config is None:
        sys.exit("Önce alarmı bir kez kurun (python -m tcdd).")
    check_channel(config, False)
    try:
        notify.send(
            config.notify, "✅ TCDD alarm testi", "Bildirim kanalı çalışıyor."
        )
    except (
        requests.RequestException,
        OSError,
        RuntimeError,
        ValueError,
    ) as error:
        sys.exit(f"Bildirim gönderilemedi: {error}")


if __name__ == "__main__":
    main()
