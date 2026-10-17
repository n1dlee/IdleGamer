# AUTHENTICATION

## Основной метод: логин + пароль + подтверждение в Steam Mobile

Решение владельца от 2026-10-17 (после проверки на устройстве: QR нельзя одобрить на том же телефоне).
Это тот же официальный поток `IAuthenticationService`, которым входит десктопный клиент Steam.

### Последовательность (`SteamSessionManager.runLogin` → `credentialsPhase`)

1. Форма: имя аккаунта и пароль. Поля хранятся в `remember` (не `rememberSaveable`: не попадают в saved instance
   state), окно `FLAG_SECURE`, клавиатура `Password` без автокоррекции, подсказки автозаполнения для менеджеров паролей.
2. По нажатию пароль передаётся как `CharArray`; массив вызывающей стороны обнуляется сразу, поле очищается.
3. `connect()` → `beginAuthSessionViaCredentials`: JavaSteam запрашивает у Steam RSA-ключ аккаунта
   (`GetPasswordRSAPublicKey`) и отправляет **только зашифрованный** пароль. Сразу после этого внутренняя копия
   обнуляется. Ссылка на `String`, которую требует API JavaSteam, отпускается в `finally` (JVM-строку нельзя затереть).
4. Неверный пароль (`InvalidPassword`) → `Failed(WrongCredentials)` без повторов; троттлинг Steam (`AccountLoginDeniedThrottle`) показывается как есть.
5. Steam сообщает допустимые способы подтверждения → `AwaitingSteamGuard`:
   * **push «Approve sign-in» в Steam Mobile** (`DeviceConfirmation`) — работает на том же телефоне;
   * ввод кода из Steam Guard (`DeviceCode`) — запасной вариант, если push не пришёл;
   * код из e-mail (`EmailCode`) — с подсказкой домена от Steam;
   * без Steam Guard — сразу к опросу.
   Код вводит **пользователь**; IdleGamer не генерирует коды и не подтверждает ничего сам (`IAuthenticator` не используется).
6. Опрос `pollAuthSessionStatus` — тот же ограниченный цикл, что для QR (интервал Steam, ≤ 5 мин, лимит опросов);
   истечение → `ApprovalExpired`.
7. Дальше общий путь `finishLogin`: логон по refresh token, проверка JWT `sub` == SteamID, смена аккаунта, сохранение в vault.

### Безопасность пароля

| Где | Что происходит |
|---|---|
| UI | `String` в памяти composable до нажатия, затем поле очищается; не сохраняется в Bundle/диск, экран не скриншотится |
| Session manager | копия `CharArray` обнуляется после отправки и в `finally` джоба входа |
| JavaSteam | `AuthSessionDetails.password` (String) → RSA-шифрование ключом Steam → `null` |
| Сеть | только RSA-шифротекст внутри TLS WebSocket |
| Диск / логи / журнал / диагностика | никогда (тест `logs never contain the password or the code`) |
| Остаточный риск | неуничтожимые JVM-строки до сборки мусора; на rooted-устройстве память процесса читаема; клавиатура (IME) видит ввод — используйте доверенную клавиатуру |

Пароль не используется повторно: при истечении сессии нужен новый вход.

`newGuardData` (запоминание устройства для e-mail Steam Guard) не сохраняется → аккаунты с e-mail-кодом вводят код при каждом входе.

## Запасной метод: QR со второго устройства

## QR login

### Одобрение на том же телефоне — невозможно (проверено на устройстве)

Телефон не может отсканировать QR со своего экрана. Была реализована передача QR-ссылки в официальное приложение
Steam явным intent (основание: App Links `s.team` → `com.valvesoftware.android.steam.community` в
`s.team/.well-known/assetlinks.json`, проверка подписи Valve). Результат на Samsung SM-S938B, Android 17,
2026-10-17: подпись распознана, Steam Mobile открылся, но **запроса на одобрение нет** — Steam Guard показывает
«For security purposes, please scan the Steam QR code again». Valve принимает QR-вход только со сканера камеры
(защита от фишинга). Кроме того, системный WindowManager Samsung записал полный intent со ссылкой в logcat.
Функция удалена. QR-вход требует второго устройства с Steam Mobile.

Для QR-входа пароль не нужен. Подтверждение в обоих методах выполняется в официальном приложении Steam Mobile —
Steam Guard остаётся частью официальной процедуры.

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

LoggedOut / Failed / ReauthenticationRequired / WaitingForApproval / AwaitingSteamGuard ──LoginRequested──► Initializing
Initializing ──SteamGuardRequired──► AwaitingSteamGuard ──GuardCode{Submitted,Rejected,Accepted}──► AwaitingSteamGuard
AwaitingSteamGuard | Initializing (без Steam Guard) ──LoginApproved──► Authenticating
Initializing ──QrChallengeIssued──► WaitingForApproval ──QrApproved──► Authenticating ──LoginSucceeded──► Authenticated
Initializing | WaitingForApproval | Authenticating ──LoginFailed──► Failed(error)
Initializing | WaitingForApproval | Authenticating ──LoginCancelled──► LoggedOut
Authenticated ──SessionRejected(reason)──► ReauthenticationRequired(reason)
* ──LoggedOut──► LoggedOut
```

Все допустимые и недопустимые переходы — `AuthStateMachineTest`.

## Почему не WebView / не свой сервер / не OpenID

* **WebView со страницей входа Steam** даёт веб-cookies (`steamLoginSecure`, аудитория `web`), а не refresh token
  клиентской CM-сессии — presence с ним невозможен. Веб-вход тоже использует QR. Страница входа внутри чужого
  приложения — фишинговый паттерн: приложение-хост может читать всё, что вводится, а пользователь не видит адрес.
* **Собственный сервер-посредник** хранил бы пароль или токен вне устройства: один взлом сервера = компрометация
  аккаунта; передача учётных данных третьей стороне противоречит SSA §1.C; нарушает принцип «no backend».
* **OpenID** не выдаёт токенов для CM (STEAM_PROTOCOL.md §1).
