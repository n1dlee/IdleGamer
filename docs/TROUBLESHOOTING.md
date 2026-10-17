# TROUBLESHOOTING

| Симптом | Вероятная причина | Что сделать |
|---|---|---|
| «No internet connection» при входе | нет валидированной сети | проверить сеть; Wi-Fi с captive portal считается offline |
| QR истёк / «Waited too long» | QR живёт ≤ 5 мин, Steam мог его отозвать | «Get a new code» |
| В Steam Mobile нет запроса после сканирования | сканирование не тем приложением | Steam Mobile → щит Steam Guard → «Scan a QR code» (не камера телефона) |
| «Steam rejected the credentials» / «saved session…» после входа | токен отозван, истёк или пароль сменён | войти заново по QR |
| «The locally stored session could not be read» | ключ Keystore недоступен (смена блокировки экрана на некоторых OEM, перенос, восстановление) | войти заново; это ожидаемое безопасное поведение |
| «Steam is rate limiting» | слишком частые входы | подождать ≥ 10 мин; IdleGamer ждёт сам |
| «Another device signed in» | вход/игра на другом устройстве | закрыть игру на другом устройстве; IdleGamer повторит через ≥ 5 мин |
| «playing a game on another device» | Steam блокирует presence, пока идёт игра на ПК | дождаться окончания игры |
| «Steam answered in an unexpected way» | вероятно изменение протокола | обновить JavaSteam/IdleGamer; экспортировать диагностику |
| «Android did not allow starting the background session» | Start нажат не из видимого приложения | открыть приложение и нажать Start |
| Сессия обрывается при выключенном экране | CPU засыпает, Doze | Settings → Keep CPU awake; исключить из оптимизации батареи |
| Нет кнопки Stop в шторке | уведомления запрещены | Settings → Notification settings |
| В истории «interrupted» | процесс был убит Android | ожидаемо; время учтено до последнего checkpoint |
| Падение при запуске после обновления версии | отсутствует миграция Room (ошибка разработки) | данные намеренно не удаляются молча; исправить миграцию в следующей версии, либо вручную «Очистить данные» приложения в настройках Android |

## Диагностика

Settings → Export diagnostics → выбрать, куда отправить. Отчёт содержит версии, состояния и последние 200
событий журнала; токены, QR и коды вырезаны, SteamID сокращён. Ничего не отправляется автоматически.

Для разработчика (debug-сборка): `adb logcat -s IdleGamer/event IdleGamer/Session IdleGamer/JavaSteam`.
