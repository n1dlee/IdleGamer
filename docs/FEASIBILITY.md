# FEASIBILITY — что реально осуществимо

Статусы: **✅ подтверждено** (запущено и проверено), **🟡 реализовано, не подтверждено на реальном аккаунте**, **⛔ заблокировано / сознательно не делается**.

| Функция | Статус | Доказательство / причина |
|---|---|---|
| Соединение с Steam CM с Android | ✅ | `SteamTransportSmokeTest` на эмуляторе Android 16: WebSocket к `cmp1-iad1.steamserver.net:443`, connect+disconnect за 2,7 с |
| JavaSteam crypto на Android (BouncyCastle) | ✅ | `PlatformConfigurationInstrumentedTest.realBouncyCastleIsRegisteredForJavaSteam` |
| Получение QR-челленджа (`BeginAuthSessionViaQR`) | ✅ | Ручной прогон debug **и** minified release на эмуляторе: QR отрисован, таймер истечения, Cancel → `AUTH_CANCELLED`; утечек в logcat нет |
| Одобрение на том же телефоне (передача ссылки в Steam Mobile) | 🟡 | Основание — App Links `s.team` → Steam Mobile (assetlinks.json). Отказ самозванцу и отсутствие приложения проверены на эмуляторе; реальный показ запроса в Steam Mobile — не проверен (нет Steam в эмуляторе) |
| Подтверждение QR в Steam Mobile → refresh token | 🟡 | Требует телефона владельца и аккаунта. Логика покрыта unit-тестами с fake gateway |
| Логон по refresh token, восстановление сессии | 🟡 | API подтверждён в исходниках JavaSteam 1.8.0 и используется форком; у нас — unit-тесты |
| Проверка identity (JWT `sub` == SteamID логона) | 🟡 | Unit-тест `token for another account is rejected` |
| Owned games через `Player.GetOwnedGames` без Web API key | 🟡 | Метод существует (`rpc/service/Player.kt`); на реальном аккаунте не вызывался |
| Presence (`ClientGamesPlayed`) | 🟡 | Отправка реализована; Steam не присылает ack. Засчитываются ли часы — неизвестно до проверки владельцем |
| Подтверждение presence от Steam | 🟡 частично | Только косвенно: `PersonaStateCallback` своего аккаунта или рост `playtime_forever` («Verify playtime») |
| Logout (`ClientLogOff`) | 🟡 | Реализовано; unit-тесты |
| Серверный отзыв токена (`RevokeToken`) | 🟡 | Метод есть; примет ли Steam его от этой сессии — не подтверждено. UI честно показывает «не подтверждено» + ручная инструкция |
| Продление refresh token | 🟡 | `GenerateAccessTokenForApp(allowRenewal)`; Steam сам решает, выдать ли новый |
| Шифрование токена Keystore AES-256-GCM | ✅ | 5 тестов на реальном Keystore + 17 JVM-тестов формата |
| Нет backup / device transfer | ✅ | `backupIsDisabled` + правила `data_extraction_rules.xml` |
| Foreground service `specialUse` + Stop | 🟡 | Манифест проверен тестом; запуск сессии требует аккаунта |
| Restore после process death без фантомных часов | ✅ (логика) | `ActivityEngineTest`, `DatabaseInstrumentedTest.openSessionsAreClosedAsInterruptedAtTheirCheckpoint` |
| Парольный вход / ввод Steam Guard кодов | ⛔ | По требованию: QR-only, пароль не запрашивается и не хранится |
| Shared secret / автогенерация 2FA-кодов | ⛔ | Обход 2FA, запрещено |
| Автопереключение игр по карточкам, парсинг badges | ⛔ | Требует веб-сессии и автоматизации наград (SSA §4.C); не входит в scope |
| Трейды, маркет, инвентарь | ⛔ | Запрещено требованиями |
| Автостарт после перезагрузки | ⛔ | Android 15 ограничения + требование явного действия пользователя |
| Гарантия 24/7 | ⛔ | Android вправе остановить процесс (ANDROID_LIMITATIONS.md) |
| > 32 игр одновременно | ⛔ | Лимит сети Steam (ASF `MaxGamesPlayedConcurrently = 32`) |

## Вывод

Вся цепочка до момента подтверждения QR владельцем проверена на реальном Steam. Дальнейшие
шаги (логон, библиотека, presence, revoke) реализованы через подтверждённые в исходниках API и
покрыты тестами на fake-транспорте, но **требуют ручной проверки владельцем на тестовом аккаунте**
(TEST_PLAN.md §5).
