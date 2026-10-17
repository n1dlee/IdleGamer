# IdleGamer

Персональное нативное Android-приложение (Kotlin, Jetpack Compose) для управления собственной сессией Steam:
вход через QR-код в официальном приложении Steam Mobile, просмотр библиотеки, запуск и остановка выбранного
пользователем game presence с честным отображением фактического состояния.

> ⚠️ **Прочитайте [docs/COMPLIANCE.md](docs/COMPLIANCE.md).** Presence без реальной игры, по тексту Steam
> Subscriber Agreement (§4.C, ревизия 2026-09-10), является фальсификацией игровой статистики и может привести
> к ограничению аккаунта. IdleGamer — неофициальный клиент, Valve его не поддерживает.

## Статус

Версия 0.1.0. Собирается, 95 unit- и 18 instrumented-тестов проходят, получение QR от Steam проверено на
эмуляторе (debug и release). **Логон, библиотека, presence и отзыв токена ещё не проверены на реальном
аккаунте** — см. [FEASIBILITY](docs/FEASIBILITY.md) и [TEST_PLAN §5](docs/TEST_PLAN.md).

## Требования

* Android 8.0+ (API 26); проверено на Android 16 (API 36).
* Официальное приложение Steam Mobile с включённым Steam Guard на телефоне для входа.
* Для сборки: JDK 17+ (проверено на 21), Android SDK Platform 37, Build-Tools 37.0.0.

## Сборка

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (applicationId `dev.idlegamer.app.debug`).

Release (минифицированный, **неподписанный** — подписывайте своим ключом, ключи в репозиторий не кладутся):

```bash
./gradlew :app:assembleRelease
```

## Установка

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Авторизация

1. Sign in with Steam → появится QR (скриншоты экрана в этот момент заблокированы).
2. Steam Mobile → щит Steam Guard → «Scan a QR code» → подтвердить вход для «IdleGamer (<ваше устройство>)».
3. IdleGamer проверяет, что Steam вошёл именно в аккаунт токена, и только потом сохраняет сессию.

Пароль и коды Steam Guard никогда не вводятся в IdleGamer. Подробно: [AUTHENTICATION](docs/AUTHENTICATION.md).

## Использование

Library → отметить игры (≤ 32) → Dashboard → Start → подтвердить уведомление о рисках. Остановка — Stop в
приложении или в уведомлении. «Local session» — время, пока presence был отправлен в живое соединение; это
**не** Steam playtime. «Verify playtime» в деталях перечитывает `playtime_forever` из Steam.

## Logout и отзыв сессии

Settings → Log out: остановка presence → попытка `RevokeToken` в Steam → ClientLogOff → удаление
зашифрованного токена и ключа. Приложение сообщает, подтвердил ли Steam отзыв.
Отзыв вручную: Steam → Settings → Security / store.steampowered.com/twofactor/manage → «Deauthorize all other devices».

## Удаление локальных данных

Settings → Clear library cache / Clear session history and event log; полностью — удалить приложение
(резервные копии не создаются, на другой телефон данные не переносятся).

## Ограничения

* Steam: нет подтверждения presence, лимит 32 игры, протокол может измениться. [KNOWN_LIMITATIONS](docs/KNOWN_LIMITATIONS.md)
* Android: система вправе остановить приложение; нет автозапуска после перезагрузки; при выключенном экране без
  опции «Keep CPU awake» сессия может обрываться. [ANDROID_LIMITATIONS](docs/ANDROID_LIMITATIONS.md)

## Тестирование

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
bash scripts/secret-scan.sh app/build/outputs/apk/debug/app-debug.apk
```

Опциональный сетевой тест (только транспорт к Steam CM, без аккаунта):
`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.steamNetwork=true`

## Документация

[RESEARCH](docs/RESEARCH.md) · [FEASIBILITY](docs/FEASIBILITY.md) · [DEPENDENCIES](docs/DEPENDENCIES.md) ·
[STEAM_PROTOCOL](docs/STEAM_PROTOCOL.md) · [ANDROID_LIMITATIONS](docs/ANDROID_LIMITATIONS.md) ·
[COMPLIANCE](docs/COMPLIANCE.md) · [RISK_REGISTER](docs/RISK_REGISTER.md) · [THREAT_MODEL](docs/THREAT_MODEL.md) ·
[SECURITY_DESIGN](docs/SECURITY_DESIGN.md) · [ARCHITECTURE](docs/ARCHITECTURE.md) ·
[AUTHENTICATION](docs/AUTHENTICATION.md) · [SESSION_LIFECYCLE](docs/SESSION_LIFECYCLE.md) ·
[TEST_PLAN](docs/TEST_PLAN.md) · [TROUBLESHOOTING](docs/TROUBLESHOOTING.md) ·
[KNOWN_LIMITATIONS](docs/KNOWN_LIMITATIONS.md) · [CHANGELOG](docs/CHANGELOG.md) · [SECURITY](SECURITY.md)

## Лицензии зависимостей

JavaSteam (MIT), Bouncy Castle (MIT-style), protobuf-java (BSD-3), AndroidX/Kotlin/OkHttp/Ktor/Coil/ZXing/
commons-lang3 (Apache-2.0). Код GPL-3.0 проекта UpdogFarmer не копировался. Список — в приложении
(Settings → Open-source licenses) и в [DEPENDENCIES](docs/DEPENDENCIES.md). Лицензия самого проекта не выбрана
(персональный проект, не распространяется).
