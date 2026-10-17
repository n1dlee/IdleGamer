# CHANGELOG

## 0.1.0 — 2026-10-17 (первая версия, не опубликована)

### Добавлено
* Исследование (RESEARCH, STEAM_PROTOCOL, FEASIBILITY, COMPLIANCE, ANDROID_LIMITATIONS, DEPENDENCIES), risk register, STRIDE threat model.
* Проект: AGP 9.4.1 (built-in Kotlin), Gradle 9.8.1 с закреплённой контрольной суммой, Compose BOM 2026.09.00, JavaSteam 1.8.0.
* QR-вход через Steam Mobile с собственным циклом опроса, проверкой identity, FLAG_SECURE.
* Vault: AES-256-GCM, неэкспортируемый ключ Android Keystore, версионированный формат с AAD.
* Менеджер сессии: явные state machines, ограниченный backoff с jitter, сетевая осведомлённость, продление и отзыв токена.
* Движок активности: presence, монотонный учёт времени, checkpoints, восстановление после смерти процесса.
* Foreground service `specialUse` со Stop в уведомлении, опциональный wake lock с таймаутом, auto-stop.
* UI: вход, Dashboard, библиотека (поиск, сортировка, фильтры, list/grid, pull-to-refresh, ручной App ID), детали, история, настройки, лицензии, UI-lock.
* Журнал событий с редактированием секретов, экспорт диагностики.
* 95 JVM- и 18 instrumented-тестов, secret scan, CI.

### После проверки на реальном телефоне
* Попытка одобрять QR на том же телефоне (передача ссылки в Steam Mobile) удалена: Steam принимает QR только со сканера камеры, а системный журнал Samsung записывал ссылку. Экран QR теперь прямо говорит, что нужен второй девайс.

### Исправлено в ходе разработки
* Гонка logout ↔ reconnect (цикл мог запланировать переподключение после выхода).
* Порядок правил Redactor (значение Bearer маскировалось не полностью).
* Secret scan молча сообщал «clean» при ошибке `grep -P` (локаль) — теперь ошибка сканера = провал.
* Удалены debug-зависимости, добавлявшие exported activity.
