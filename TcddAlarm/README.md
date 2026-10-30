# TCDD Koltuk Alarmı (Android)

Seçtiğiniz günlerde ve saat aralığında, TCDD e-bilet'te uygun boş koltuk çıkınca telefonunuza bildirim gönderir.
**Bilet satın ALMAZ.** Bildirime dokununca e-bilet sitesi açılır, satın alma sizde.

## Özellikler
- Nereden / nereye istasyon seçimi (arama + yalnızca geçerli çiftler), çoklu tarih, saat aralığı
- Yolcu sayısı, yolcu başına cinsiyet; yan yana ve karşılıklı koltuk seçenekleri
- Koltuk tipi: Ekonomi, Business, Tekerlekli sandalye
- Koltuk yönü: ileri / geri / fark etmez (gelişmiş ayarda yön kalibrasyonu)
- Ön plan servisi + kalıcı bildirim ("Durdur" düğmeli); ekran kapalıyken de çalışır
- Önerilen koltuk satılırsa yenisini önerir; aynı öneri için yalnızca bir kez bildirir
- Sunucu 3 kez üst üste 401/403/429 dönerse takip durur (engeli aşmaya çalışmaz); en az 60 sn aralık

## Derleme
1. Android Studio (Koala veya yeni) ile `TcddAlarm` klasörünü açın, Gradle senkronizasyonunu bekleyin.
2. Telefonda geliştirici modu + USB hata ayıklama açıp **Run**; ya da komut satırından:
   `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
3. Birim testleri: `./gradlew test`

## Kullanım
1. Uygulamayı açın, bildirim iznini verin.
2. Gelişmiş bölümünden **Authorization token**'ı girin (tarayıcıda ebilet.tcddtasimacilik.gov.tr → F12 → Network → bir `tms` isteğinin `Authorization` başlığı). Süresi dolunca yenileyin.
3. İstasyon, tarih, saat, yolcu ve tercihleri seçip **Başlat**.
4. Gelişmiş bölümündeki **pil optimizasyonunu kapat** düğmesiyle uygulamayı muaf tutun (aksi halde bazı telefonlar servisi öldürür).

## Dürüst sınırlar
- Uç noktalar ve koltuk haritası şeması e-Bilet APK'sının **statik analizinden** çıkarıldı; gerçek sunucuya karşı denenmedi. `departureDate` biçimi (önceki gün 21:00) ve vagon no = `trainCarIndex` varsayımdır.
- Koltuk yönü için `rotation` değerinin hangi yöne baktığı bilinmiyor; gelişmiş ayarda kalibre edin.
- Harita okunamazsa yalnızca "boş yer sayısı" ile uyarır.
- Mantık katmanı (planlayıcı, ayrıştırma, uyarı durumu) JVM birim testleriyle doğrulanır (`./gradlew test`). Arayüz ve servis katmanı SDK 34 ile derlenir (`./gradlew assembleDebug`).
- E-posta/Telegram yok; bildirimler yereldir.
