# AUTHENTICATION

## Метод: Steam QR login (единственный)

### Одобрение на том же телефоне

Телефон не может отсканировать QR со своего же экрана, а Steam Mobile обычно стоит на том же телефоне, что и
IdleGamer. Поэтому основной путь на одном устройстве — **передать ссылку из QR в официальное приложение Steam**:

* Основание: `https://s.team/.well-known/assetlinks.json` (проверено 2026-10-17) объявляет Android App Links домена
  `s.team` (на нём живут QR-ссылки входа) для пакета `com.valvesoftware.android.steam.community` с отпечатками
  сертификатов Valve `5DFF6B05…FBDA45` и `FAC61745…033B9C`.
* `SteamAppHandoff` отдаёт ссылку **только** если: пакет установлен; он подписан одним из этих сертификатов
  (`PackageManager.hasSigningCertificate`, API 28+, с учётом ротации ключей; на 26–27 — все подписи должны совпасть);
  для явного intent (`setPackage`) находится activity; ссылка имеет вид `https://s.team/q/<n>/<id>`.
* Intent явный — без chooser, никакое другое приложение ссылку не получит. В буфер обмена и share sheet она не попадает.
* Пока пользователь в приложении Steam, IdleGamer продолжает опрос; после возврата вход завершается.
* Иначе экран объясняет причину (не установлен / подпись не Valve / не принимает ссылки) и предлагает QR для второго устройства.

**Не проверено:** что Steam Mobile действительно показывает запрос подтверждения по такой ссылке (в эмуляторе нет
Steam). Проверка — TEST_PLAN A14.

Пароль и коды Steam Guard **никогда** не вводятся в IdleGamer и не хранятся. Подтверждение выполняется в
официальном приложении Steam Mobile — Steam Guard остаётся частью официальной процедуры.

### Последовательность (`SteamSessionManager.runQrLogin`)

1. `QrLoginRequested` → `Initializing`. Если нет сети — сразу `Failed(NetworkUnavailable)`.
2. `gateway.connect()` (CM по WebSocket/TLS).
3. `beginAuthSessionViaQR(deviceFriendlyName = "IdleGamer (<производитель> <модель>)", platformType = SteamClient, persistentSession = true)`.
   Имя устройства честное — его видно в запросе подтверждения и в списке устройств Steam.
4. `WaitingForApproval(challengeUrl, expiresAt)`: кнопка «Approve in the Steam app» (если доступна, см. выше) и QR для
   второго устройства, рисуется локально (ZXing), окно `FLAG_SECURE`, показывается предупреждение о чувствительности QR. Steam может прислать новый URL → `QrChallengeRefreshed`.
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
