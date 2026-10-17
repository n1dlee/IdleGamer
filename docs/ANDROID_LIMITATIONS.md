# ANDROID_LIMITATIONS — почему Android может остановить IdleGamer

Целевая платформа: `minSdk 26` (Android 8.0), `targetSdk 36` (Android 16), `compileSdk 36`.

## 1. Таблица ограничений по версиям

| Версия | Ограничение | Как IdleGamer с ним работает |
|---|---|---|
| 8.0 (26) | Background execution limits; `startForegroundService()` + `startForeground()` в течение ~5 с | Сервис стартует только из UI и сразу вызывает `startForeground()` |
| 9 (28) | Урезанный системный провайдер "BC" | Регистрируем настоящий BouncyCastle (STEAM_PROTOCOL.md §6) |
| 10 (29) | — | — |
| 11 (30) | — | — |
| 12 (31) | Запрет старта FGS из фона (`ForegroundServiceStartNotAllowedException`) | Start/Stop только из видимой Activity или из action уведомления уже работающего сервиса; исключение ловится и показывается как ошибка |
| 12 (31) | `dataExtractionRules` для backup и device-transfer | Всё исключено (SECURITY_DESIGN.md §6) |
| 13 (33) | Runtime-разрешение `POST_NOTIFICATIONS` | Запрашивается перед Start; при отказе сервис работает, но уведомление не видно — UI предупреждает |
| 14 (34) | Обязательный `foregroundServiceType` + соответствующее разрешение; пользователь может смахнуть FGS-уведомление; «Task Manager» может остановить приложение | `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; остановка из Task Manager = process death |
| 15 (35) | `dataSync`/`mediaProcessing`: 6 ч за 24 ч, затем `onTimeout(int,int)`; запрет запуска `dataSync` и др. из `BOOT_COMPLETED` | Не используем `dataSync`; `onTimeout` всё равно реализован (корректный stop) |
| 16 (36) | Квоты JobScheduler применяются и к jobs, запущенным при работающем FGS; edge-to-edge принудительно | WorkManager/JobScheduler не используются для удержания сокета; UI edge-to-edge |

## 2. Выбор типа foreground service

* `mediaPlayback`, `connectedDevice`, `location` и т.п. — **не подходят** и не объявляются: это был бы обход ограничений.
* `dataSync` — не соответствует сути (это не загрузка/выгрузка данных) и имеет лимит 6 ч/24 ч на Android 15+.
* `specialUse` — предназначен для «valid use cases not covered by the other types». Постоянное, начатое пользователем соединение со сторонним сервисом для поддержания его сессии — честное описание. Google Play требует ручного ревью описания; IdleGamer не публикуется в Play, но описание всё равно правдивое.
* **WorkManager не может бесконечно удерживать Steam-сокет**: work ограничен ~10 минутами выполнения и квотами. Он не используется.

## 3. Doze, App Standby, батарея

* **Doze**: при выключенном экране и неподвижном устройстве система откладывает jobs/alarms и ограничивает сеть для фоновых приложений. Процесс с FGS имеет более высокий приоритет, но **CPU может уснуть**: корутины-таймеры (в т.ч. heartbeat JavaSteam) не выполняются, Steam через некоторое время разорвёт сессию. Это **не проверено на реальном устройстве** — см. TEST_PLAN.md (`adb shell dumpsys deviceidle force-idle`).
* **Wake lock**: по умолчанию **не используется**. Опция «Keep CPU awake while active» берёт `PARTIAL_WAKE_LOCK` **с таймаутом** (60 мин), продлевает его только пока сессия `ACTIVE`, и гарантированно освобождает в `finally`/`onDestroy`. Это расходует батарею — пользователь предупреждён.
* **Battery optimization**: приложение **не** запрашивает `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. В настройках есть кнопка, открывающая системный экран оптимизации (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, разрешение не требуется) — решение за пользователем.
* **App Standby Buckets** на работающий FGS почти не влияют; влияют на всё остальное (нет jobs — нечему ограничиваться).
* Производители (Xiaomi, Huawei, Samsung и др.) добавляют собственные «убийцы» фона — вне контроля приложения (dontkillmyapp.com).

## 4. Process death, force-stop, reboot, update

| Событие | Что происходит | Поведение IdleGamer |
|---|---|---|
| System process death (нехватка памяти) | Процесс убит; `START_NOT_STICKY` → сервис не перезапускается | При следующем запуске UI видит в Room незакрытую сессию → помечает её `INTERRUPTED` (длительность до последнего heartbeat-чекпоинта), presence **не** показывается активным |
| Force-stop (Настройки → Остановить) | Все компоненты остановлены, алармы сняты; приложение не может ничего сделать до ручного запуска | То же, что выше, при следующем запуске |
| Смахивание из «Недавних» | Activity уничтожается, FGS продолжает работать | Сессия продолжается; уведомление с Stop |
| Reboot | Ничего не запускается автоматически (нет `BOOT_COMPLETED` receiver) | Незакрытая сессия → `INTERRUPTED`; пользователь сам нажимает Start |
| App update | Процесс убивается | Как process death |

Почему нет автозапуска после reboot: Android 15 запрещает старт ряда FGS-типов из `BOOT_COMPLETED`; кроме того, требование проекта — начинать активность только явным действием пользователя.

## 5. Сеть

* `ConnectivityManager.registerDefaultNetworkCallback` → `AndroidNetworkMonitor` (StateFlow `isOnline`, только validated-сети).
* Wi-Fi → LTE: TCP/WebSocket-соединение рвётся (новый IP) → `DisconnectedCallback` → reconnect с backoff (см. SESSION_LIFECYCLE.md).
* Нет сети: reconnect-цикл **приостанавливается** до появления сети (не жжёт батарею попытками).

## 6. Android Keystore

* AES-256-GCM ключ, `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, `setRandomizedEncryptionRequired(true)` (IV генерирует Keystore), без user-authentication (иначе фоновое восстановление сессии невозможно).
* StrongBox — если доступен (`setIsStrongBoxBacked(true)`, fallback при `StrongBoxUnavailableException`).
* Ключ может пропасть: сброс блокировки экрана на некоторых OEM, восстановление из бэкапа, перенос устройства, очистка данных. Обработка — SECURITY_DESIGN.md §4.

## 7. Java 17 / desugaring

`compileOptions` = Java 17. `minSdk 26` даёт `java.time`, `CompletableFuture` (24+) нативно — **core library desugaring не нужен** (JavaSteam использует `java.time.Instant` и `CompletableFuture`).

## 8. Реалистичные гарантии

IdleGamer **не может обещать** непрерывную круглосуточную работу. Гарантируется только:
честный статус после возвращения пользователя, корректная остановка по Stop, отсутствие
фантомных «часов» после убийства процесса.
