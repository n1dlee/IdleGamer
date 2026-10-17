# DEPENDENCIES

Все версии проверены 2026-10-17 по `maven-metadata.xml` Google Maven / Maven Central и собраны вместе
(`./gradlew assembleDebug assembleRelease testDebugUnitTest connectedDebugAndroidTest`). Источник истины —
`gradle/libs.versions.toml`.

## Toolchain

| Компонент | Версия | Примечание |
|---|---|---|
| Gradle | 9.8.1 | wrapper, `distributionSha256Sum` закреплён |
| Android Gradle Plugin | 9.4.1 | built-in Kotlin (плагин `kotlin-android` не применяется); требует Gradle ≥ 9.6 |
| Kotlin (Compose compiler plugin) | 2.4.21 | |
| KSP | 2.3.12 | для Room; KSP 2.3.x не привязан к версии Kotlin |
| JDK для сборки | 21 (Temurin/Oracle) | bytecode target Java 17 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | compileSdk 37 требуется актуальными AndroidX (core-ktx 1.19.1); targetSdk 36 — последняя версия, на которой приложение проверено (эмулятор API 36). Lint `OldTargetApi` — осознанное предупреждение |
| Build-tools | 37.0.0 | |

## Runtime-библиотеки

| Библиотека | Версия | Зачем | Лицензия |
|---|---|---|---|
| in.dragonbra:javasteam | 1.8.0 | CM-протокол Steam, QR auth, unified messages | MIT |
| org.bouncycastle:bcprov-jdk18on | 1.86 | **обязателен** для JavaSteam `CryptoHelper` (не объявлен в его POM) | Bouncy Castle (MIT-style) |
| com.google.protobuf:protobuf-java | 4.31.1 | JavaSteam объявляет его runtime-only, но его типы — часть вызываемого API. Закреплён на версии генерации JavaSteam; lint предлагает 4.36.2 — обновлять только вместе с JavaSteam | BSD-3 |
| androidx.core:core-ktx | 1.19.1 | NotificationCompat, ServiceCompat | Apache-2.0 |
| androidx.activity:activity-compose | 1.13.0 | setContent, edge-to-edge, LocalActivity | Apache-2.0 |
| androidx.lifecycle (runtime-compose, viewmodel-compose, service, process) | 2.11.0 | ViewModel, LifecycleService, ProcessLifecycleOwner | Apache-2.0 |
| androidx.navigation:navigation-compose | 2.10.2 | навигация | Apache-2.0 |
| androidx.room (runtime, ktx, compiler) | 2.8.5 | кэш, история, журнал | Apache-2.0 |
| androidx.datastore:datastore-preferences | 1.2.1 | настройки | Apache-2.0 |
| androidx.biometric:biometric | 1.1.0 | опциональный UI-lock | Apache-2.0 |
| androidx.compose BOM | 2026.09.00 | ui, material3, material-icons-extended | Apache-2.0 |
| kotlinx-coroutines-android | 1.11.0 | | Apache-2.0 |
| com.google.zxing:core | 3.5.4 | локальная генерация QR (без сети) | Apache-2.0 |
| io.coil-kt.coil3 (compose, network-okhttp) | 3.6.3 | обложки/аватар с CDN Steam | Apache-2.0 |

Транзитивно через JavaSteam: ktor-client 3.3.3, okhttp 5.3.2, commons-lang3 3.20.0, kotlin-stdlib.

## Тестовые

junit 4.13.2 (MockK и Turbine не нужны: ручные fakes в `app/src/test/.../testing/Fakes.kt` и `StateFlow.value` проще и прозрачнее),
kotlinx-coroutines-test 1.11.0, androidx.test.ext:junit 1.3.0, androidx.test:runner 1.7.0, room-testing, compose ui-test-junit4.

## Сознательно не используется

* **Hilt** — ручной DI (`AppContainer`) проще для одного модуля и снимает риск совместимости kapt/KSP с built-in Kotlin AGP 9 (R24).
* **EncryptedSharedPreferences / security-crypto** — устарел; собственный vault на Keystore (SECURITY_DESIGN.md).
* **compose ui-tooling, ui-test-manifest** — добавляют *exported* activity даже в debug.
* Retrofit, Gson, Firebase, Crashlytics, любые analytics/ads SDK.
