# SECURITY_DESIGN

## 1. Принципы (выполнено)

| Требование | Как обеспечено |
|---|---|
| No custom backend / cloud sync / analytics / ads | Сетевые соединения только к Steam CM (WebSocket TLS), Steam Directory API, CDN картинок Steam. Зависимостей analytics/ads нет (DEPENDENCIES.md) |
| No hardcoded credentials / Web API key | `scripts/secret-scan.sh` по исходникам и APK (CI) |
| No plaintext token storage | `FileSecureCredentialStore` + AES-256-GCM Keystore |
| No implicit account switching | Сравнение с последним SteamID → очистка кэша; JWT `sub` == SteamID логона |
| No secret-containing crash reports | Нет crash-SDK; `Secret`/`SessionCredentials.toString()` скрывают значения |
| No unrestricted exported components | Только `MainActivity` (launcher, без data-фильтров); тест |
| No unnecessary permissions | 6 разрешений, ни одного dangerous кроме POST_NOTIFICATIONS; тест |
| No automatic inventory/trading, no Steam Guard secret extraction | Код отсутствует |

## 2. Классы данных и места хранения

| Данные | Хранилище | Защита |
|---|---|---|
| пароль Steam | только RAM на время отправки; RSA-шифрование ключом Steam | обнуление `CharArray`, FLAG_SECURE, не логируется, не сохраняется |
| refresh token, account name | `noBackupFilesDir/vault/session.bin` | AES-256-GCM, ключ Keystore |
| access token | не хранится | — |
| QR challenge URL | только RAM + экран; в другие приложения не передаётся (системный WindowManager логирует intent) | FLAG_SECURE, Redactor |
| SteamID64 последнего аккаунта, настройки | DataStore | не секрет |
| Кэш игр, выбор, история сессий, журнал | Room | не секрет; журнал проходит Redactor |

## 3. Vault

* **Ключ:** `KeyGenParameterSpec(alias="idlegamer.vault.v1", ENCRYPT|DECRYPT, GCM, NoPadding, 256 bit,
  randomizedEncryptionRequired=true, userAuthenticationRequired=false)`; StrongBox, если есть.
* **IV:** генерирует Keystore; передать свой IV для шифрования невозможно (`keystoreRefusesCallerChosenIv` — тест на устройстве).
* **Формат v1:** `"IGV1" | ver | purpose | steamId | ivLen | iv | ctLen | ct||tag` (CredentialEnvelope.kt).
* **AAD:** `dev.idlegamer.vault|v1|1|<alias>|<steamId>` — запись нельзя перенести на другой аккаунт/назначение/ключ.
* **Plaintext** внутри — длина-префиксированные поля; буферы обнуляются после использования (насколько позволяет JVM).
* **Атомарная запись:** temp-файл → `fsync` → `ATOMIC_MOVE`.
* **Base64/obfuscation не используется как «шифрование».**

## 4. Отказы и восстановление (R7, R15)

| Ситуация | Обнаружение | Реакция |
|---|---|---|
| Ключ удалён/инвалидирован (смена блокировки на некоторых OEM, перенос, очистка) | `KeyUnavailable` | vault + alias удаляются → `ReauthenticationRequired(LocalCredentialsUnavailable)` |
| Файл повреждён / подменён | GCM tag / формат | то же |
| Запись не удалась | `CredentialStoreException(IO)` | логин прерывается, ClientLogOff, ничего не сохраняется |
| DataStore повреждён | `ReplaceFileCorruptionHandler` | настройки по умолчанию |
| Миграция Room не найдена | Room бросает исключение | нет destructive fallback: данные не удаляются молча |

## 5. UI-lock (BiometricPrompt)

* Опционально. `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`. Блокировка снова включается при уходе Activity в фон.
* **Не привязан к ключу vault** — иначе фоновое восстановление сессии было бы невозможно.
* Ограничения: защищает только интерфейс от человека с разблокированным телефоном; не защищает данные
  на диске, не мешает фоновой сессии, не защищает от root.

## 6. Backup / Device transfer

`android:allowBackup="false"`, `fullBackupContent` и `dataExtractionRules` исключают все домены (cloud и
device-transfer), vault лежит в `noBackupFilesDir`. Даже при восстановлении файла ключ Keystore
непереносим → обнаруживается как `KeyUnavailable` → повторный вход.

## 7. Сеть

`usesCleartextTraffic=false`, `network_security_config`: только системные CA. CM по WebSocket поверх TLS
(TCP-транспорт JavaSteam с канальным шифрованием не используется).

## 8. Логирование

`DefaultAppLogger`: всё через `Redactor` (JWT, QR URL, `key=value` для token/password/cookie/…, Bearer,
blobs ≥ 48 символов, полные SteamID → `7656119…1234`). Debug-логи только в debug-сборке; JavaSteam-лог
подключается только в debug. Журнал в Room: ≤ 1000 записей, ≤ 14 дней. Экспорт — только по кнопке,
через системный share sheet, повторно прогоняется через Redactor.

`LogOnDetails` JavaSteam — data class, его `toString()` содержит токен: объект никогда не логируется.

## 9. Release

R8 + shrinkResources; keep-правила только для JavaSteam/protobuf/BouncyCastle (рефлексия). Release не
подписывается в репозитории; `*.jks`/`*.keystore` в `.gitignore` и проверяются сканером.
