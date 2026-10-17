# ARCHITECTURE

Один Gradle-модуль `:app`, разделённый на слои по пакетам. Многомодульность не оправдана для персонального
проекта; границы слоёв обеспечены интерфейсами и тем, что `in.dragonbra.*` импортируется **только** в
`data/steam/JavaSteamGateway.kt`.

```
┌──────────────────────────── presentation (Compose + ViewModel) ─────────────────────────────┐
│ auth/Login  dashboard  library  activity/Details  history  settings  navigation/AppRoot    │
│  — только StateFlow состояний; токенов во ViewModel state нет                               │
└───────────────┬───────────────────────────────────────────────────────────┬────────────────┘
                │ use cases (Start/Stop/Logout)                             │
┌───────────────▼──────────────── session (engines) ────────────────────────▼────────────────┐
│ SteamSessionManager: AuthState + ConnectionState, QR login, reconnect loop, logout          │
│ ActivityEngine: ActivityState, presence, monotonic time accounting, checkpoints            │
│ ReconnectPolicy (pure)                                                                      │
└──────┬───────────────────────┬────────────────────────┬─────────────────────┬──────────────┘
       │ SteamGateway (iface)  │ SecureCredentialStore  │ Repositories        │ NetworkMonitor
┌──────▼───────────┐  ┌────────▼──────────────┐  ┌──────▼────────────┐  ┌─────▼──────────┐
│ JavaSteamGateway │  │ FileSecureCredential- │  │ Room (games,      │  │ Connectivity-  │
│ (JavaSteam 1.8)  │  │ Store + Keystore AES  │  │ history, journal) │  │ Manager        │
│ CM over WSS      │  │ GCM CredentialEnvelope│  │ DataStore settings│  │                │
└──────────────────┘  └───────────────────────┘  └───────────────────┘  └────────────────┘
service/SteamSessionService — foreground (specialUse): держит процесс, уведомление, Stop, wake lock (opt-in)
core/di/AppContainer — ручной DI; core/logging — AppLogger + Redactor
```

## Пакеты

| Пакет | Содержимое |
|---|---|
| `domain.model` | `SteamId`, `SessionCredentials`, `SteamError`, state machines `AuthState`/`ConnectionState`/`ActivityState`, `Game` |
| `domain.repository` | `SecureCredentialStore`, `GameRepository`, `ActivityHistoryRepository`, `EventLogRepository`, `SettingsRepository` |
| `domain.usecase` | `StartActivityUseCase`, `StopActivityUseCase`, `LogoutUseCase`, `ForegroundSessionController` |
| `session` | `SteamSessionManager`, `ActivityEngine`, `ReconnectPolicy` |
| `data.steam` | `SteamGateway` (граница протокола), `JavaSteamGateway`, `SteamErrorMapper` |
| `data.local` / `data.repository` | Room, DataStore, реализации репозиториев |
| `core.security` | `Secret`, `CredentialCipher`, `KeystoreCredentialCipher`, `CredentialEnvelope`, `FileSecureCredentialStore`, `JwtClaims`, `CryptoProviders` |
| `service` | `SteamSessionService`, `WakeLockController`, `AndroidForegroundSessionController` |
| `presentation` | экраны, тема, компоненты |

## Ключевые решения

1. **Граница протокола `SteamGateway`.** Замена JavaSteam или адаптация к изменению протокола (R1) затрагивает один класс. Все тесты движков используют `FakeSteamGateway`.
2. **Состояния — явные sealed-иерархии с таблицами переходов** (`ConnectionStateMachine`, `ActivityStateMachine`) и чистым редьюсером `AuthStateMachine`. Неожиданный переход логируется и считается (`invalidTransitionCount`, тесты требуют 0).
3. **Демонд-модель соединения.** Соединение живёт, пока есть «спрос» (`Demand.UI` — видимый UI с 30-секундной задержкой отпускания; `Demand.ACTIVITY` — открытая сессия) **и** валидные credentials. `collectLatest` гарантирует ровно один цикл соединения.
4. **Сервис не содержит логики Steam.** Он только переводит процесс в foreground, пока открыта сессия, и закрывается сам, когда она закрыта.
5. **Ручной DI** вместо Hilt (DEPENDENCIES.md).
6. **Без WorkManager**: он не подходит для удержания сокета.

## Потоки и отмена

Все долгие операции — корутины в `AppContainer.appScope` (`SupervisorJob + Dispatchers.Default`), IO JavaSteam —
на `Dispatchers.IO`. Колбэки JavaSteam читаются одним pump-корутином (`runWaitCallbackAsync`). Каждая сетевая
операция обёрнута таймаутом (connect 35 с, запросы 20–60 с). Отмена (logout, Stop, отпускание спроса)
распространяется структурно; финальные отключения выполняются в `NonCancellable`.
