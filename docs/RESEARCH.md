# RESEARCH — исследование существующих реализаций

Дата исследования: 2026-10-17. Все выводы ниже основаны на чтении исходного кода
(shallow clone репозиториев и `javasteam-1.8.0-sources.jar` с Maven Central), а не на памяти.

## 1. Изученные проекты

| Проект | Язык | Последний коммит | Лицензия | Назначение |
|---|---|---|---|---|
| [steevp/UpdogFarmer](https://github.com/steevp/UpdogFarmer) (Idle Daddy, оригинал) | Java, Android Views | 2021-02-25 | GPL-3.0 | Idle-клиент Steam для Android: presence + сбор карточек |
| [LossyDragon/UpdogFarmer](https://github.com/LossyDragon/UpdogFarmer) (современный форк) | Kotlin, Compose, Koin | 2026-07-30 | GPL-3.0 | То же, переписано на Compose, JavaSteam 1.8.1-SNAPSHOT |
| [Longi94/JavaSteam](https://github.com/Longi94/JavaSteam) | Kotlin + Java | 2026-07-13 (master = 1.8.1-SNAPSHOT) | MIT | Порт SteamKit2 на JVM: CM-протокол, unified messages, auth |
| [SteamRE/SteamKit](https://github.com/SteamRE/SteamKit) | C# | — | LGPL-2.1 | Эталонная реализация CM-протокола; JavaSteam повторяет её API |

Дополнительно для проверки лимита одновременных игр прочитан
`ArchiSteamFarm/Steam/Integration/ArchiHandler.cs` (ASF, Apache-2.0):
`MaxGamesPlayedConcurrently = 32; // This is limit introduced by Steam Network`.

## 2. Ответы на 12 вопросов по каждому проекту

### 2.1 steevp/UpdogFarmer (оригинал)

1. **Задача:** держать Steam-сессию и сообщать Steam, что пользователь «играет» в выбранные игры, для накопления часов/карточек.
2. **Язык:** Java, AndroidX Views, Retrofit, JavaSteam 1.0-эпохи.
3. **Версии:** устаревшие (targetSdk ≤ 29, JavaSteam < 1.2).
4. **Авторизация:** классическая username + password + Steam Guard код; пароль хранился зашифрованным RSA-ключом Keystore (`CryptHelper.java`). Есть `SharedSecretDialog` — ввод shared secret мобильного аутентификатора для автоматической генерации кодов. **Это именно то, что наш проект запрещает** (извлечение секретов Steam Guard).
5. **Соединение:** `SteamService` (bound + started service), `CallbackManager.runWaitCallbacks` в отдельном потоке.
6. **Активность:** `CMsgClientGamesPlayed` с `game_id`.
7. **События:** подписки CallbackManager на Connected/Disconnected/LoggedOn/LoggedOff.
8. **Reconnect:** фиксированная задержка, без backoff.
9. **Секреты:** пароль (RSA-encrypted), loginkey/sentry в SharedPreferences.
10. **Техдолг:** старая схема логина Steam (loginkey) больше не работает; встроенные billing/ads/consent SDK.
11. **Совместимость:** не соответствует Android 12+ (FGS start), 13+ (уведомления), 14+ (FGS types).
12. **Полезно:** только общие идеи (сервис + уведомление + Stop action).

### 2.2 LossyDragon/UpdogFarmer (форк, 2026)

1. **Задача:** та же, плюс парсинг страницы значков (badges) через веб-сессию для автоматического переключения игр по карточкам, инвентарь, redeem ключей.
2. **Язык:** Kotlin 2.4.10, Compose BOM 2026.06.01, Koin 4.2.2, AGP 9.2.1, Navigation3.
3. **Версии:** актуальные, но JavaSteam `1.8.1-SNAPSHOT` (не релиз) и Material3 `1.5.0-alpha23`.
4. **Авторизация:** `beginAuthSessionViaCredentials` и `beginAuthSessionViaQR`, затем `authSession.pollingWaitForResult().await()`; refresh token передаётся в `LogOnDetails.accessToken`.
5. **Соединение:** один большой `SteamService.kt` (1585 строк — God class), собственный `CoroutineScope`.
6. **Активность:** `ClientMsgProtobuf<CMsgClientGamesPlayed.Builder>(…, EMsg.ClientGamesPlayed)`; stop = `game_id 0`.
7. **События:** `PlayingSessionStateCallback` (playing blocked), `LoggedOffCallback` с `LoggedInElsewhere`/`Revoked`.
8. **Reconnect:** фиксированные 5 секунд после disconnect; экспоненциальный backoff только для «blocked by other device».
9. **Секреты:** **refresh token хранится в обычных SharedPreferences открытым текстом** (`PrefsManager.writeRefreshToken`), `android:allowBackup="true"`. Это прямой риск утечки через backup/root.
10. **Техдолг:** God class, отсутствие тестов на state machine, wake lock по опции без таймаута, ignore-battery-optimizations permission.
11. **Совместимость:** `foregroundServiceType="specialUse"` с `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; комментарий в манифесте явно объясняет отказ от `dataSync` из-за 6-часового лимита Android 15.
12. **Полезно:** подтверждение, что JavaSteam 1.8 + BouncyCastle работают на Android; выбор `specialUse`; паттерн обработки `LoggedInElsewhere`.

### 2.3 JavaSteam 1.8.0 (релиз 2025-12-24, MIT)

1. **Задача:** клиентская библиотека CM-протокола Steam для JVM.
2. **Язык:** Kotlin 2.2.21 + Java; target Java 17.
3. **Runtime-зависимости (из `javasteam-1.8.0.module`):** ktor-client-core/cio/websockets 3.3.3, okhttp 5.3.2, okhttp-coroutines 5.3.2, commons-lang3 3.20.0, kotlinx-coroutines-core 1.10.2, kotlin-stdlib-jdk8 2.2.21, protobuf-java 4.31.1. **BouncyCastle НЕ объявлен, но обязателен**: `util/crypto/CryptoHelper.java` в static-инициализаторе делает `Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")` и бросает `SecurityException`, если класса нет.
4. **Авторизация:** `SteamClient.authentication: SteamAuthentication` →
   `beginAuthSessionViaQR(AuthSessionDetails): CompletableFuture<QrAuthSession>`;
   `QrAuthSession.challengeUrl`, `challengeUrlChanged: IChallengeUrlChanged?`;
   `AuthSession.pollAuthSessionStatus(): CompletableFuture<AuthPollResult?>` (null = ещё не подтверждено, исключение `AuthenticationException(result=Expired/FileNotFound/…)`);
   `AuthPollResult { accountName, refreshToken, accessToken, newGuardData }`;
   `generateAccessTokenForApp(steamID, refreshToken, allowRenewal)`.
5. **Соединение:** `CMClient.connect()`/`disconnect()`, `isConnected`, `steamID`; `SteamConfiguration.create { withProtocolTypes(…) … }`; `CallbackManager.subscribe<T>{}` + `runWaitCallbackAsync()` (suspend).
6. **Активность:** нет высокоуровневого метода для простого presence, кроме `SteamApps.notifyGamesPlayed(List<GamePlayedInfo>, EOSType)`, который требует `processId`, `ownerId`, `gameBuildId` (их пришлось бы выдумывать). Низкоуровневый путь — `ClientMsgProtobuf<CMsgClientGamesPlayed.Builder>(…, EMsg.ClientGamesPlayed)` + `steamClient.send(msg)` — публичный API.
7. **События:** `ConnectedCallback`, `DisconnectedCallback(isUserInitiated)`, `LoggedOnCallback(result, extendedResult, clientSteamID, …)`, `LoggedOffCallback(result)`, `AccountInfoCallback(personaName, …)`, `PlayingSessionStateCallback(isPlayingBlocked, playingAppID)`, `PersonaStateCallback(friendId, gamePlayedAppId, avatarHash, …)`.
8. **Reconnect:** библиотека **не** переподключается сама; это ответственность приложения.
9. **Секреты:** библиотека не хранит токены; логирование через `LogManager` выключено, пока не добавлен `LogListener`.
10. **Техдолг / найденные дефекты:**
    * `AuthSession.pollDeviceConfirmation()` делает `delay(pollingInterval.toLong())`, где `pollingInterval` — **секунды** (`Float` из `CAuthentication_BeginAuthSessionViaQR_Response.interval`). `delay()` принимает миллисекунды → фактический опрос Steam каждые ~5 мс + RTT. Использовать `pollingWaitForResult()` для QR нельзя (R12, rate limiting). Мы пишем свой цикл с корректным интервалом.
    * `AsyncJob.timeout = 10000` мс по умолчанию.
    * `CryptoHelper` вставляет BouncyCastle через `Security.insertProviderAt(…, 1)` с именем "BC". На Android уже есть встроенный урезанный провайдер "BC", поэтому вставка молча не происходит, и `getInstance(…, "BC")` попадёт в системный урезанный провайдер. Мы регистрируем настоящий BouncyCastle сами до первого обращения к JavaSteam (см. STEAM_PROTOCOL.md §6).
11. **Совместимость с Android:** `Utils.getOSType()` распознаёт Android по `java.runtime.name`; `HardwareUtils.getMachineName()` берёт имя устройства. Ktor CIO + OkHttp работают на Android (подтверждено форком).
12. **Полезно:** всё, что касается протокола. Используется как обычная зависимость (MIT), без копирования кода.

### 2.4 SteamKit2

Эталон, из которого портирован JavaSteam; использовался как вторичная ссылка для
семантики `pollingInterval` (в SteamKit — `TimeSpan.FromSeconds(response.interval)`) и
для понимания `ClientGamesPlayed`. Код не используется.

## 3. Лицензионные выводы (подробно — COMPLIANCE.md §5)

* JavaSteam — **MIT**: можно использовать как зависимость, нужно сохранить уведомление об авторских правах (экран «Open-source licenses»).
* UpdogFarmer (оба) — **GPL-3.0**: мы **не копировали** код. Изучение архитектуры и идей (сервис + уведомление, обработка `LoggedInElsewhere`) не создаёт производного произведения. Если в будущем будет скопирован хотя бы фрагмент, весь IdleGamer при распространении должен будет стать GPL-3.0 с раскрытием исходников.
* SteamKit — LGPL-2.1; не используется.

## 4. Ключевые решения, вытекающие из исследования

1. QR-логин через `beginAuthSessionViaQR` + **собственный** цикл `pollAuthSessionStatus()` с интервалом в секундах, ограниченным сверху числом попыток и временем жизни QR.
2. Логон по refresh token: `LogOnDetails(username = accountName, accessToken = refreshToken, shouldRememberPassword = true)`.
3. Библиотека игр — `Player.GetOwnedGames` через unified messages по уже установленному CM-соединению. **Web API key не нужен и не встраивается.**
4. Presence — `CMsgClientGamesPlayed` с одним только `game_id` (никаких выдуманных PID/owner/build), не более 32 игр.
5. Токен никогда не хранится в SharedPreferences/DataStore; только AES-256-GCM vault с ключом Android Keystore (в отличие от форка).
6. `allowBackup=false` + `dataExtractionRules`, исключающие всё (в отличие от форка).
7. Никаких shared secret / автоматической генерации Steam Guard кодов (в отличие от оригинала).
8. Никакого парсинга badges / автопереключения по карточкам: пользователь сам выбирает игры.
