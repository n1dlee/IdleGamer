# AUTHENTICATION

## Метод: Steam QR login (единственный)

### Одобрение на том же телефоне — невозможно (проверено на устройстве)

Телефон не может отсканировать QR со своего экрана. Была реализована передача QR-ссылки в официальное приложение
Steam явным intent (основание: App Links `s.team` → `com.valvesoftware.android.steam.community` в
`s.team/.well-known/assetlinks.json`, проверка подписи Valve). Результат на Samsung SM-S938B, Android 17,
2026-10-17: подпись распознана, Steam Mobile открылся, но **запроса на одобрение нет** — Steam Guard показывает
«For security purposes, please scan the Steam QR code again». Valve принимает QR-вход только со сканера камеры
(защита от фишинга). Кроме того, системный WindowManager Samsung записал полный intent со ссылкой в logcat.
Функция удалена. QR-вход требует второго устройства с Steam Mobile.

Пароль и коды Steam Guard **никогда** не вводятся в IdleGamer и не хранятся. Подтверждение выполняется в
официальном приложении Steam Mobile — Steam Guard остаётся частью официальной процедуры.

### Последовательность (`SteamSessionManager.runQrLogin`)

1. `QrLoginRequested` → `Initializing`. Если нет сети — сразу `Failed(NetworkUnavailable)`.
2. `gateway.connect()` (CM по WebSocket/TLS).
3. `beginAuthSessionViaQR(deviceFriendlyName = "IdleGamer (<производитель> <модель>)", platformType = SteamClient, persistentSession = true)`.
   Имя устройства честное — его видно в запросе подтверждения и в списке устройств Steam.
4. `WaitingForApproval(challengeUrl, expiresAt)`: QR для второго устройства (с пояснением, что на этом же телефоне
   одобрить нельзя), рисуется локально (ZXing), окно `FLAG_SECURE`, показывается предупреждение о чувствительности QR. Steam может прислать новый URL → `QrChallengeRefreshed`.
5. Опрос `pollAuthSessionStatus()` **собственным циклом**: пауза = интервал из ответа Steam (секунды, ≥ 1 с),
   не более `lifetime / interval + 1` опросов, общий срок жизни 5 минут.
   `pollingWaitForResult()` JavaSteam не используется (ошибка мс/с, RESEARCH.md §2.3).
6. Исход опроса: `Pending` → ждём; `Expired`/`FileNotFound` → `Failed(QrExpired)`; ошибка → `Failed(Steam(...))`.
7. `Approved(accountName, refreshToken)` → `Authenticating` → `logOn(accountName, refreshToken)`.
8. **Проверка identity:** SteamID из `LoggedOnCallback.clientSteamID` должен совпасть с `sub` JWT refresh-токена.
   Несовпадение → ClientLogOff, `Failed(IdentityMismatch)`, ничего не сохраняется.
9. Смена аккаунта → очистка кэша предыдущего аккаунта (`onAccountSignedIn`).
10. **Только теперь** credentials записываются в vault. Ошибка записи → ClientLogOff, `Failed(StorageFailure)`.
11. `LoginSucceeded` → `Authenticated`.

### Временные vs постоянные артефакты

| Артефакт | Время жизни | Где |
|---|---|---|
| challenge URL, clientId, requestId | до одобрения/истечения | RAM, экран |
| access token из poll | не используется | отбрасывается |
| refresh token, account name | до logout / отзыва | vault |
| `newGuardData` | не используется (только для e-mail guard при парольном логине) | отбрасывается |

### Защита от гонок

Новый `startQrLogin()` отменяет предыдущий job; все операции входа/выхода сериализуются `opMutex`.
Cancel возвращает в `LoggedOut` и закрывает соединение.

## State machine

```
Unknown ──StoredCredentialsFound──► Authenticated
   │ ──NoStoredCredentials──► LoggedOut
   └──StoredCredentialsUnreadable──► ReauthenticationRequired(LocalCredentialsUnavailable)

LoggedOut / Failed / ReauthenticationRequired / WaitingForApproval ──QrLoginRequested──► Initializing
Initializing ──QrChallengeIssued──► WaitingForApproval ──QrApproved──► Authenticating ──LoginSucceeded──► Authenticated
Initializing | WaitingForApproval | Authenticating ──LoginFailed──► Failed(error)
Initializing | WaitingForApproval | Authenticating ──LoginCancelled──► LoggedOut
Authenticated ──SessionRejected(reason)──► ReauthenticationRequired(reason)
* ──LoggedOut──► LoggedOut
```

Все допустимые и недопустимые переходы — `AuthStateMachineTest`.

## Почему не пароль / не WebView / не OpenID

* Пароль — лишний секрет и необходимость обрабатывать Steam Guard коды в приложении.
* WebView OAuth для клиентской сессии не существует; имитировать его — запрещено.
* OpenID не выдаёт токенов для CM (STEAM_PROTOCOL.md §1).
