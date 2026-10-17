# Security policy

IdleGamer — персональный проект. Сообщения о проблемах безопасности: владельцу репозитория напрямую,
не в публичных issue.

## Что защищается

Steam refresh token (эквивалент входа в аккаунт до отзыва). Он хранится только на устройстве, зашифрован
AES-256-GCM неэкспортируемым ключом Android Keystore, исключён из backup и device transfer, не логируется и
не покидает устройство кроме как в соединении с Steam. Подробно: [docs/SECURITY_DESIGN.md](docs/SECURITY_DESIGN.md),
модель угроз: [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).

## Чего IdleGamer никогда не делает

* не запрашивает пароль Steam, коды Steam Guard, recovery-коды, shared/identity secret;
* не отправляет данные на собственные серверы (их нет), не содержит analytics/ads/crash SDK;
* не встраивает Steam Web API key;
* не выполняет трейды, операции маркета или подтверждения мобильного аутентификатора.

## Если токен мог утечь

1. IdleGamer → Settings → Log out.
2. store.steampowered.com/twofactor/manage → «Deauthorize all other devices».
3. Смените пароль Steam.

## Известные остаточные риски

На rooted/скомпрометированном устройстве злоумышленник, контролирующий процесс приложения, может
использовать ключ Keystore (не извлекая его) и читать токен из памяти во время сессии. UI-lock защищает только
интерфейс.

## Для разработчиков

* Никогда не коммитить токены, keystore, `keystore.properties`. `scripts/secret-scan.sh` проверяет это в CI.
* Не передавать `LogOnDetails` (JavaSteam data class с токеном) в логи или строки.
* Любой новый секрет — только через `SecureCredentialStore`, любой лог — только через `AppLogger`.
