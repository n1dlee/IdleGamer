# TEST_PLAN

## 1. Автоматические тесты и результаты (2026-10-17)

| Набор | Команда | Где | Результат |
|---|---|---|---|
| JVM unit (95 тестов, 14 классов) | `./gradlew :app:testDebugUnitTest` | JDK 21 | **95/95 pass** |
| Instrumented (18 тестов) | `./gradlew :app:connectedDebugAndroidTest` | эмулятор Android 16 (API 36, x86_64) | **17 pass + 1 skipped (opt-in)**. Примечание: AGP пишет пропуск через `assumeTrue` в XML как `failures=1` с `AssumptionViolatedException`; Gradle считает его пропуском, сборка успешна |
| Steam CM transport (opt-in) | `... -Pandroid.testInstrumentationRunnerArguments.steamNetwork=true` | эмулятор + реальный Steam CM | **pass** (connect/disconnect без аккаунта) |
| Lint | `./gradlew :app:lintDebug` | | 0 errors; предупреждения: OldTargetApi (осознанно), protobuf newer (закреплено), 2× PluralsCandidate |
| Release (R8) | `./gradlew :app:assembleRelease` | | собран, 9,6 МБ unsigned |
| Secret scan | `bash scripts/secret-scan.sh <apks>` | | clean; позитивный контроль (canary) обнаруживается |

### Покрытие unit-тестами

* **State machines:** все пути `AuthStateMachine` (старт, QR, отмена, ошибки, игнор событий вне порядка, logout из любого состояния); таблицы переходов Connection/Activity; транспорт ≠ авторизация.
* **Vault:** round-trip; отсутствие токена/аккаунта в ciphertext; 500 шифрований — 500 уникальных IV; инверсия **каждого** байта → отказ; перенос на другой SteamID/alias → отказ; усечение/мусор/неизвестная версия; удалённый и пересозданный ключ; файловое хранилище: corrupted, key lost, атомарная перезапись, `clear()` уничтожает ключ.
* **Логи:** Redactor (JWT, QR URL, key=value, Bearer, blobs, SteamID), `toString()` секретов, отсутствие токена во всех строках логгера за полный цикл login→logout.
* **Session manager:** QR happy path; интервал опроса ≥ 5 с (≤ 12 опросов/мин); истечение QR и срока жизни; чужой аккаунт; ошибка vault; отмена; второй логин отменяет первый; offline; восстановление сессии; невалидный токен — один логон и стоп; Revoked; reconnect после обрыва; ожидание сети без попыток; смена сети сокращает backoff; give-up и ручной retry; rate limit ≥ 10 мин; grace-период UI; продление токена; logout (revoke, удаление, без reconnect); offline-logout; отказ Steam в revoke.
* **Activity engine:** валидация (пусто, >32, 0, >2^24-1, нет согласия, не вошёл); точная длительность по монотонным часам; идемпотентный stop; время не идёт во время reconnect и при `PlayingBlocked`; потеря auth → `AUTH_LOST`; ошибка presence не скрывается; эхо persona; checkpoint раз в минуту; восстановление `INTERRUPTED`; 20 конкурентных start/stop; переключение игр; auto-stop.
* **Mapping:** коды EResult → постоянные/временные ошибки; исключения → сетевые ошибки.

### Покрытие instrumented

Keystore (round-trip, уникальные IV, AAD, **отказ caller-chosen IV**, ключ неэкспортируем, потеря ключа),
BouncyCastle для JavaSteam и неизменный дефолтный провайдер AES-GCM, `allowBackup=false`, только `MainActivity`
exported, сервис не exported и `specialUse`, нет лишних разрешений, Room (сохранение выбора при refresh,
`INTERRUPTED` по checkpoint, закрытая сессия неизменяема, ограничение журнала, очистка), открытие схемы v1,
UI smoke (экран входа, предупреждение, нет `FLAG_SECURE` без QR).

## 2. Ручные проверки, выполненные на эмуляторе

| # | Сценарий | Результат |
|---|---|---|
| M1 | Debug: Sign in → QR от Steam, таймер, Cancel | ✅ `AUTH_INITIATED` → `AUTH_CANCELLED`; скриншот при QR — чёрный (FLAG_SECURE) |
| M2 | Release (R8, локально подписан debug-ключом только для установки): то же | ✅ QR получен, падений нет |
| M3 | logcat за M1/M2: `s.team/q`, `eyJ…` | ✅ 0 совпадений |
| D1 | **Реальный телефон** SM-S938B, Android 17: установка debug, запуск | ✅ без падений; targetSdk 36 в режиме совместимости |
| D2 | D1: передача QR-ссылки в Steam Mobile (удалённая функция) | ❌ Steam открылся без запроса одобрения («scan the Steam QR code again»); функция удалена |
| M4 | Ресурсы release в покое | PSS ≈ 56 МБ, CPU 0 %, сервис не запущен |

## 3. Ручные проверки, требующие устройства/аккаунта (не выполнены)

| # | Сценарий | Как | Ожидание |
|---|---|---|---|
| A1 | Сворачивание при активной сессии | Home | уведомление, Stop работает |
| A2 | Экран выключен 30 мин | без wake lock / с wake lock | без: возможен обрыв, корректный Reconnecting; с: сессия жива |
| A3 | Wi-Fi off/on | `adb shell svc wifi disable/enable` | Reconnecting(waiting) → Authenticated, presence повторно |
| A4 | Wi-Fi → LTE | отключить Wi-Fi на телефоне | обрыв → быстрый reconnect |
| A5 | Process death | `adb shell am kill dev.idlegamer.app` (в фоне) | при запуске запись `INTERRUPTED`, presence не «активна» |
| A6 | Force-stop | Настройки → Остановить | то же, без автоперезапуска |
| A7 | Поворот | rotate | состояние сохраняется, соединение не рвётся |
| A8 | Low memory | `adb shell am send-trim-memory dev.idlegamer.app RUNNING_CRITICAL` | без падений |
| A9 | Уведомления запрещены | запрет в настройках | предупреждение на Dashboard, сессия работает |
| A10 | Doze | `adb shell dumpsys deviceidle force-idle` | честный статус после выхода |
| A11 | Battery restricted | Настройки → Батарея → Ограничено | возможная остановка — `SYSTEM_STOPPED`/`INTERRUPTED` |
| A12 | Reboot | перезагрузка | ничего не стартует само; запись `INTERRUPTED` |
| A13 | FGS timeout (тестовый) | `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS <pkg>` | specialUse не ограничен; onTimeout реализован |
| P1 | Батарея | `adb shell dumpsys batterystats --reset`, 1 ч сессии, `dumpsys batterystats <pkg>` | зафиксировать |

## 4. Security-проверки (сводка)

| # | Проверка | Статус |
|---|---|---|
| S1 | Токены не в SharedPreferences/DataStore | ✅ по коду (DataStore содержит только `AppSettings` + SteamID) |
| S2 | Нет секретов в Logcat | ✅ unit + M3 |
| S3 | Logout очищает | ✅ unit |
| S4 | Повреждённое хранилище | ✅ unit + instrumented |
| S5 | Backup exclusions | ✅ instrumented + правила |
| S6 | Инвалидированный ключ | ✅ instrumented |
| S7 | Повтор IV | ✅ instrumented (Keystore отказывает) + unit (уникальность) |
| S8 | Нет секретов в APK | ✅ secret-scan по debug и release APK |
| S9 | Нет секретов в фикстурах | ✅ тестовые JWT генерируются в рантайме, алгоритм `none`, без подписи |
| S10 | Exported components | ✅ instrumented |
| S11 | QR не попадает в скриншоты | ✅ M1 |

## 5. Проверка на реальном аккаунте (владелец)

Только **тестовый аккаунт без ценных предметов**. Claude не запрашивает пароль, коды или токены.

1. Read-only: Sign in (QR сканируется Steam Mobile на **втором** устройстве) → Dashboard: имя, «Online». Library → Refresh: игры и playtime совпадают со Steam. Logout → сообщение о revoke. Проверить в Steam список устройств.
2. Перезапуск приложения после входа → сессия восстанавливается без QR.
3. Только после п.1–2 и прочтения COMPLIANCE.md: одна игра, Start на 10 мин, Stop; через несколько часов «Verify playtime». Записать, изменился ли `playtime_forever`.
4. Отправить экспорт диагностики (Settings → Export) для разбора, если что-то пошло не так.
