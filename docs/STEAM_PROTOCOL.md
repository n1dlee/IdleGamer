# STEAM_PROTOCOL — способы интеграции со Steam и подтверждённые API

Источник истины: `in.dragonbra:javasteam:1.8.0` (sources jar, Maven Central) и
`.proto`-файлы в репозитории JavaSteam. Ссылки на файлы — относительно корня пакета
`in/dragonbra/javasteam/`.

## 1. Какие механизмы существуют

| Механизм | Что это | Подходит нам? |
|---|---|---|
| **Steam Web API** (`api.steampowered.com`) | HTTP API; многие методы требуют личного Web API key | Нет: ключ нельзя встраивать в APK (R17), а GetOwnedGames доступен и через CM |
| **Steam OpenID** | Доказывает сайту «этот браузер принадлежит SteamID X» | **Нет.** OpenID не выдаёт никаких токенов для CM-сессии, presence или библиотеки. Успешный OpenID ≠ клиентская сессия |
| **Steam Client / CM protocol** | Бинарный протокол, которым пользуется официальный клиент (TCP или WebSocket к Connection Manager) | **Да**, единственный путь к presence |
| **SteamKit2 / JavaSteam** | Реализации CM-протокола (C# / JVM) | JavaSteam 1.8.0 |
| **IAuthenticationService** (unified messages поверх CM) | Современная авторизация: credentials или QR → refresh/access JWT | Да, QR-ветка |
| **Steam Guard** | 2FA: подтверждение в мобильном приложении, код из приложения, код по e-mail | Только через официальное приложение (QR-подтверждение) |
| **Steam QR authentication** | `BeginAuthSessionViaQR` → `challenge_url` → пользователь сканирует в Steam Mobile → `PollAuthSessionStatus` отдаёт токены | **Да, основной метод** |
| **Authentication tokens** | refresh token (JWT, аудитория `client`, долгоживущий) + access token (короткоживущий) | refresh token хранится в vault |
| **Presence / game activity** | `CMsgClientGamesPlayed` (EMsg.ClientGamesPlayed) | Да, с ограничениями (см. §5 и COMPLIANCE.md) |

## 2. Таблица функций

| Функция | API / библиотека | Подтверждена? | Ограничения |
|---|---|---|---|
| Авторизация | `SteamClient.authentication` → `SteamAuthentication` (`steam/authentication/SteamAuthentication.kt`) | **Да, в исходниках** | Требует установленного CM-соединения (`IllegalArgumentException`, если не connected) |
| QR login | `beginAuthSessionViaQR(AuthSessionDetails)` → `QrAuthSession.challengeUrl`; `pollAuthSessionStatus()` (`AuthSession.kt`) | **Да**: `BeginAuthSessionViaQR` проверен на эмуляторе против реального Steam (QR получен); одобрение и выдача токена — **требуют ручной проверки владельцем** | `pollingWaitForResult()` опрашивает слишком часто (ms vs s), используем свой цикл; QR истекает (`EResult.Expired`/`FileNotFound`), URL может обновляться (`new_challenge_url`) |
| Восстановление сессии | `SteamUser.logOn(LogOnDetails(username, accessToken = refreshToken, shouldRememberPassword = true))` (`handlers/steamuser/LogOnDetails.kt`: «This is actually your refresh token») | **Да, в исходниках** + используется форком | Невалидный токен → `LoggedOnCallback.result` = `InvalidPassword`/`AccessDenied`/`Expired`/`Revoked` → `ReauthenticationRequired` без повторов |
| Продление токена | `generateAccessTokenForApp(steamID, refreshToken, allowRenewal = true)` → `AccessTokenGenerateResult.refreshToken` (может быть пустым, если Steam не продлил) | **Да, в исходниках** | Steam сам решает, выдавать ли новый refresh token |
| Получение игр | `SteamUnifiedMessages.createService<Player>().getOwnedGames(CPlayer_GetOwnedGames_Request)` (`rpc/service/Player.kt`) | **Да, в исходниках** + используется форком | Web API key не нужен. Поля: appid, name, playtime_forever (мин.), playtime_2weeks, img_icon_url, rtime_last_played. Список owned ≠ установленные игры |
| Профиль | `AccountInfoCallback.personaName`; `SteamFriends.requestFriendInfo(selfId)` → `PersonaStateCallback.avatarHash` | Да, в исходниках | Аватар — best effort |
| Управление presence | `ClientMsgProtobuf<CMsgClientGamesPlayed.Builder>(CMsgClientGamesPlayed::class.java, EMsg.ClientGamesPlayed)` + `steamClient.send()` | **Да, в исходниках**; эффект на часы — **не подтверждён** до проверки на реальном аккаунте | Steam **не присылает ack**. Лимит 32 игры (ASF). Блокировка другим устройством → `PlayingSessionStateCallback.isPlayingBlocked` или `LoggedOff(LoggedInElsewhere)` |
| Подтверждение presence | `PersonaStateCallback` для собственного SteamID с `gamePlayedAppId` | Частично: поле существует; придёт ли апдейт для себя — **не подтверждено** | Показывается как «remote: unconfirmed», пока не пришёл апдейт |
| Проверка часов | Повторный `GetOwnedGames` с `appids_filter` и сравнение `playtime_forever` | Да (метод существует) | Steam обновляет счётчик с задержкой; дельта ≠ доказательство засчитанного времени в реальном времени |
| Logout | `SteamUser.logOff()` (отправляет `ClientLogOff` и вызывает `disconnect()`) | **Да, в исходниках** | Только завершает CM-сессию, токен остаётся валидным |
| Token revocation | `Authentication.revokeToken(CAuthentication_Token_Revoke_Request{token, revoke_action})` (`rpc/service/Authentication.kt`) | Метод **существует**; что CM принимает его для `client`-токена от этой же сессии — **не подтверждено** | `revokeRefreshToken` требует `token_id` + подпись — недоступно. Результат отзыва показывается пользователю честно; при неудаче — инструкция отзыва вручную |

## 3. Последовательность QR-логина (`JavaSteamGateway` + `SteamSessionManager.runQrLogin`)

```
connect() ──► ConnectedCallback
beginAuthSessionViaQR(details{deviceFriendlyName="IdleGamer (<model>)",
                              platformType=SteamClient, persistentSession=true,
                              websiteID="Client"})
   ──► QrAuthSession{challengeUrl, pollingInterval (sec), clientID, requestID}
UI рисует QR из challengeUrl (локально, zxing). FLAG_SECURE на экране.
На том же телефоне: challengeUrl → явный ACTION_VIEW в com.valvesoftware.android.steam.community
(только при подписи Valve; s.team App Links → Steam Mobile, см. AUTHENTICATION.md).
loop (≤ maxAttempts, ≤ qrLifetime):
    delay(max(interval, 1s))
    pollAuthSessionStatus() ─► null            → продолжаем
                           ─► AuthPollResult    → refresh/access token
                           ─► AuthenticationException(Expired|FileNotFound) → QrExpired
    challengeUrlChanged    ─► UI перерисовывает QR
logOn(accountName, refreshToken) ──► LoggedOnCallback(OK, clientSteamID)
Проверка identity: SteamID из JWT `sub` refresh-токена == clientSteamID
Сохранение credentials в vault ТОЛЬКО после успешного LoggedOn.
```

## 4. Жизненный цикл токенов (см. SESSION_LIFECYCLE.md)

* Refresh token — JWT, подписан Steam. Мы читаем payload **только для проверок** (`sub`, `exp`, `aud`), подпись не проверяем и не доверяем ему для авторизации.
* Access token в CM-логоне не нужен (используется refresh token). Access token **не сохраняется**.
* `newGuardData` нужен только для e-mail Steam Guard при credentials-логине; в QR-потоке не сохраняется.

## 5. Presence: что это и чем это не является

* `ClientGamesPlayed` сообщает Steam, что сессия «запустила» appid. Никакой игровой код не выполняется, достижения/прогресс не происходят.
* Учитывается ли это время в `playtime_forever` и даёт ли карточки — решает Steam; документация Valve этого не гарантирует, а SSA §4.C прямо запрещает «faking gameplay statistics … including … playtime» (см. COMPLIANCE.md).
* Стоп = отправка пустого списка `games_played`.

## 6. Android-специфика JavaSteam

* `CryptoHelper` требует настоящий BouncyCastle под именем "BC". `IdleGamerApplication.onCreate()` выполняет
  `Security.removeProvider("BC"); Security.addProvider(BouncyCastleProvider())` **до** первой загрузки классов JavaSteam.
  Провайдер добавляется в конец списка, чтобы не менять выбор по умолчанию для `Cipher.getInstance("AES/GCM/NoPadding")` (нужно для Android Keystore).
* Транспорт: только `ProtocolTypes.WEB_SOCKET` (TLS 443) — проходит через большинство мобильных сетей; TCP с канальным шифрованием CM не используется. Проверено: `SteamTransportSmokeTest`.
* Логи JavaSteam включаются только в debug-сборке и только через redacting-listener.

## 7. Что сознательно НЕ используется

`beginAuthSessionViaCredentials` (пароль), `IAuthenticator.getDeviceCode/getEmailCode`, Web-cookie (`steamLoginSecure`),
`SteamApps.requestFreeLicense`, трейды/маркет/инвентарь, `kickPlayingSession` (без явного действия пользователя),
`SteamFriends.setPersonaState` (не меняем статус/профиль).
