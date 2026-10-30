# TCDD Koltuk Alarmı

Seçtiğiniz gün ve saat aralığında TCDD e-bilet'te uygun boş koltuk çıkınca haber verir. Bilet satın almaz.

## Komut satırı

Python 3.11 veya daha yenisi gerekir.

```bash
pip install -r requirements.txt
cp .env.example .env
python -m tcdd
```

`.env` içine Telegram (`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) veya e-posta (`SMTP_HOST`, `SMTP_USER`, `SMTP_PASSWORD`) yazın. Kayıtlı ayarla devam etmek için `python -m tcdd --resume`, bildirimi denemek için `python -m tcdd --test-notify`, ağ olmadan akışı görmek için `python -m tcdd --demo`.

Testler: `python -m unittest discover -s tests -v`

## Android

`TcddAlarm` klasörünü Android Studio ile açın ya da komut satırından derleyin. `ANDROID_HOME` tanımlı olmalı ve SDK 34 yüklü olmalıdır.

```bash
cd TcddAlarm
chmod +x gradlew
./gradlew test assembleDebug
```

APK: `TcddAlarm/app/build/outputs/apk/debug/app-debug.apk`

Kullanım ayrıntısı `TcddAlarm/README.md` içindedir.
