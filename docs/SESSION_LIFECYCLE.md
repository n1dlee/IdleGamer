# SESSION_LIFECYCLE

## 1. Токены

| | Refresh token | Access token |
|---|---|---|
| Происхождение | `PollAuthSessionStatus` после одобрения QR | там же |
| Формат | JWT (подпись Steam), аудитория `client` | JWT |
| Использование | `CMsgClientLogon.access_token` (так JavaSteam называет поле) | не используется |
| Хранение | vault | нигде |
| Продление | `GenerateAccessTokenForApp(allowRenewal=true)`, если до `exp` < 30 дней; новый токен сохраняется атомарно | — |
| Отзыв | `Authentication.RevokeToken(token, k_EAuthTokenRevokePermanent)` — best effort | — |

`exp` из JWT читается без проверки подписи и используется только для решения «пора ли продлевать».

## 2. Создание / валидация / восстановление

* Создание — AUTHENTICATION.md.
* Старт приложения: `ActivityEngine.recoverInterruptedSessions()` → `SteamSessionManager.initialize()` (чтение vault).
* Валидация — сам `ClientLogon`: только Steam решает, действителен ли токен. Пока соединения нет, UI показывает
  «Disconnected», а не «Online».

## 3. Цикл соединения

```
нет сети → Reconnecting(waitingForNetwork) → ждём сеть
Connecting → Connected → Authenticating → Authenticated ── держим, пока не Disconnected
  └ ошибка → классификация:
      InvalidCredentials / IdentityMismatch → AuthExpired → vault удалён → ReauthenticationRequired (без повторов)
      AccountRestricted / ProtocolIncompatible / Unsupported → Failed (без повторов; кнопка Reconnect)
      прочее → attempt++ → ReconnectPolicy:
          base 2 с × 2^(n-1), максимум 5 мин, equal jitter [d/2, d]
          RateLimited ≥ 10 мин, SessionReplaced/PlayingBlocked ≥ 5 мин
          > 25 попыток → Failed
          сетевые ошибки: смена сети (offline→online) прерывает ожидание
      соединение держалось ≥ 2 мин → счётчик попыток сбрасывается
```

Одновременно существует ровно один цикл (R5). Таймауты: connect 35 с, logon 60 с, запросы 20–40 с (R13).

## 4. Вход на другом устройстве (R6)

* `LoggedOff(LoggedInElsewhere | LogonSessionReplaced)` → `SessionReplaced` → reconnect не раньше чем через 5 мин.
* `PlayingSessionState(playingBlocked=true)` → активность в `Error(PlayingBlocked)`, время не считается; IdleGamer
  **не** выбивает чужую игровую сессию (`kickPlayingSession` не вызывается). При `playingBlocked=false` presence
  отправляется заново.
* Чтобы не выбивать десктопный клиент, логон использует случайный стабильный `loginId` на установку
  (`obfuscated_private_ip`). Поведение Steam при параллельных сессиях **не документировано** и требует проверки.

## 5. Активность и время

См. ActivityState: `Active` = локальная сессия открыта ∧ соединение `Authenticated` ∧ `ClientGamesPlayed`
отправлен в это соединение без ошибки. Время считается `elapsedRealtime()` только в `Active`; checkpoint в Room
раз в 60 с. После смерти процесса сессия закрывается как `INTERRUPTED` со временем последнего checkpoint
(теряется ≤ 60 с, фиктивное время не добавляется). Steam playtime показывается отдельно и только после
«Verify playtime».

## 6. Logout (ручной)

Порядок в `LogoutUseCase` → `SteamSessionManager.logout()`:

1. Остановить активность (пустой `ClientGamesPlayed`, закрыть запись истории `LOGGED_OUT`).
2. Если сеть есть — дождаться логона до 10 с и вызвать `RevokeToken` (**серверный отзыв**).
3. `ClientLogOff`, disconnect.
4. **Локальное удаление**: vault-файл и ключ Keystore.
5. Обнулить ссылки на credentials и профиль в памяти; `LoggedOut`; кэш игр очищается.
6. Сообщить пользователю, подтвердил ли Steam отзыв.

Серверный отзыв и локальное удаление — разные операции: шаг 4 выполняется всегда, даже если 2–3 невозможны.
История сессий (без секретов) сохраняется до «Clear session history».

## 7. Отзыв вручную в Steam

Steam (приложение или store.steampowered.com/twofactor/manage) → «Deauthorize all other devices» / управление
устройствами. После этого IdleGamer при следующем логоне получит отказ и перейдёт в `ReauthenticationRequired(SessionRevoked)`.

## 8. Перенос на другой телефон

Vault не попадает в backup/transfer, ключ непереносим → на новом телефоне приложение стартует в `LoggedOut`
(или `LocalCredentialsUnavailable`, если файл как-то перенесён) и требует новый QR-вход.
