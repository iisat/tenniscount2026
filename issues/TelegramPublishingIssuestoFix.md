# Telegram Publishing — Issues to Fix

Ветка: `feature/telegram_count_publish`

Аудит новой функции публикации счёта матча в Telegram.

## P1 — исправить до merge

### [x] 1. Не хранить Telegram bot token в backup-able `settings.xml`

**Проблема**

Bot token сейчас сохраняется в общие `SharedPreferences` приложения. Эти preferences находятся в `settings.xml`, который включён в Android Cloud Backup и device-to-device transfer.

Bot token является credential и не должен попадать в backup.

**Что исправить**

- Вынести token в отдельное хранилище, например `telegram_secrets`.
- Явно исключить его из Cloud Backup и device transfer.
- Желательно хранить token в зашифрованном виде с ключом из Android Keystore.
- `chatId` можно оставить обычной настройкой.
- Поле token в UI скрывать по умолчанию и добавить кнопку показать/скрыть.

**Acceptance criteria**

- [x] Token не хранится в `settings.xml`.
- [x] Token не попадает в Cloud Backup.
- [x] Token не попадает в device transfer.
- [x] Token скрыт в UI по умолчанию.
- [x] Перезапуск приложения сохраняет token локально.
- [x] Остальные настройки продолжают backup/restore как раньше.

---

### [x] 2. Исключить утечку bot token через логи и исключения

**Проблема**

Telegram API URL содержит token:

```text
https://api.telegram.org/bot<TOKEN>/...
```

При сетевой ошибке сейчас может логироваться `Throwable`. Некоторые network exceptions способны содержать URL/request details.

`AppLog.w/e` работают и в release, поэтому credential потенциально может попасть в logcat.

**Что исправить**

- Не логировать raw `Throwable` для Telegram HTTP request, если он может содержать URL.
- Не логировать полный Telegram endpoint.
- Не логировать request body с чувствительными данными.
- Оставлять безопасную диагностику:

```text
Telegram request failed: SocketTimeoutException
Telegram API returned HTTP 401
Telegram API error: Unauthorized
```

При использовании sanitizer гарантированно удалять token.

**Acceptance criteria**

- [x] Token отсутствует во всех `Log.*` / `AppLog.*`.
- [x] Token отсутствует в пользовательских error messages.
- [x] Ошибки остаются диагностируемыми.
- [x] Есть regression test/sanitizer test с exception, содержащим URL с token.

---

### [x] 3. Добавить HTTP connect/read timeout

**Проблема**

`HttpsURLConnection` используется без явных `connectTimeout` и `readTimeout`.

Такой запрос может зависнуть и удерживать Telegram mutex, блокируя следующие score updates и Finish.

**Что исправить**

Минимально:

```kotlin
connection.connectTimeout = 10_000
connection.readTimeout = 15_000
```

Желательно дополнительно ограничить весь request через `withTimeout()`.

**Acceptance criteria**

- [x] Connect timeout задан.
- [x] Read timeout задан.
- [x] Request не может зависнуть бесконечно.
- [x] Timeout не влияет на локальный матч.
- [x] После timeout следующая публикация может выполниться.
- [x] Mutex гарантированно освобождается.

---

### [x] 4. Устранить race между score update, Finish Match и New Match

**Проблема**

Telegram updates запускаются отдельными coroutine. Mutex сериализует HTTP-запросы, но не различает Telegram-сессии.

Возможен сценарий:

```text
последнее очко
→ queued live update

Finish Match
→ final publish

final выполняется первым
→ публикуется финал

старый live update выполняется позже
→ создаёт LIVE-сообщение уже после финального
```

Аналогично coroutine старого матча может выполниться после `New Match`.

**Что исправить**

Предпочтительно вынести orchestration в `TelegramScorePublisher`, который владеет:

```text
currentSession
currentMessageId
lastPublishedState
ordered event queue
```

События:

```text
StartMatch
ScoreChanged
GameChanged
FinishMatch
Reset
```

Минимально допустим `telegramSessionGeneration`: Finish/New Match инвалидируют все операции старой generation.

**Acceptance criteria**

- [x] Live update не может появиться после final message.
- [x] Старый матч не может изменить Telegram state нового матча.
- [x] `messageId` принадлежит только текущей session.
- [x] Rapid updates выполняются в определённом порядке.
- [x] Finish всегда является последним событием завершённого матча.
- [x] Есть concurrency regression tests.

---

### [x] 5. Исправить восстановление трансляции после process restart

**Проблема**

После restore:

```kotlin
telegramMessageId = null
lastTelegramState = restoredState
```

При следующем очке внутри того же гейма game/set boundary не изменён, а `messageId == null`, поэтому update пропускается.

Telegram-трансляция не оживает до следующего гейма.

**Что исправить**

После process restore Telegram session считать новой.

Минимально:

```kotlin
telegramMessageId = null
lastTelegramState = null
```

Лучше решать через новую session внутри `TelegramScorePublisher`.

**Acceptance criteria**

- [x] Запустить live.
- [x] Убить process.
- [x] Восстановить матч.
- [x] Изменить счёт на одно очко.
- [x] Появляется новое live-сообщение.
- [x] Следующие очки редактируют его.
- [x] Старый `messageId` после restart не используется.

---

### [x] 6. Не удалять старое live-сообщение до успешной отправки нового

**Проблема**

Сейчас на границе гейма возможна последовательность:

```text
delete old
→ send new
```

Если delete прошёл, а send упал, рабочее сообщение потеряно и session может застрять без `messageId`.

**Что исправить**

Использовать:

```text
send new
→ если успешно:
    сохранить newMessageId
    обновить lastTelegramState
    удалить old
→ если ошибка:
    сохранить old messageId/state
    повторить позже
```

`lastTelegramState` менять только после успешной Telegram operation.

Результат `editMessageText()` тоже обязательно проверять.

**Acceptance criteria**

- [x] Failed send не уничтожает рабочую session.
- [x] Старый message удаляется только после успешного создания нового.
- [x] Failed operation не продвигает `lastTelegramState`.
- [x] Failed edit можно повторить.
- [x] После восстановления сети публикация продолжает работать.

---

## P2 — Medium

### [x] 7. Добавить проверку Telegram-настроек и видимый статус

**Проблема**

Пользователь может указать неправильный token/chatId или бот может не иметь доступа к чату. Сейчас UI не показывает состояние подключения.

**Что исправить**

Добавить кнопку:

```text
Проверить Telegram
```

И отображать состояния:

```text
Telegram: не настроен
Telegram: проверка...
Telegram: ✓ подключён
Telegram: ошибка авторизации
Telegram: нет доступа к чату
Telegram: ошибка сети
```

Telegram остаётся optional и не должен блокировать начало матча.

**Acceptance criteria**

- [x] Настройки можно проверить заранее.
- [x] Invalid token определяется.
- [x] Invalid/inaccessible chatId определяется.
- [x] Network failure отличается от credential error.
- [x] Telegram failure не блокирует матч.
- [x] Credential не показывается в error text.

---

### [x] 8. Добавить unit/integration tests для Telegram state machine

Желательно ввести:

```kotlin
interface TelegramClient {
    suspend fun sendMessage(...)
    suspend fun editMessage(...)
    suspend fun deleteMessage(...)
}
```

и `FakeTelegramClient`.

**Минимальные тесты**

- [x] initial score → `sendMessage`;
- [x] следующее очко → `editMessage`;
- [x] несколько очков редактируют один `messageId`;
- [x] новый гейм → новый message;
- [x] новый сет → новый message;
- [x] restore mid-game → первое изменение создаёт новый message;
- [x] failed send не продвигает state;
- [x] failed edit можно повторить;
- [x] rapid updates сохраняют порядок;
- [x] Finish при queued update остаётся последним;
- [x] New Match инвалидирует operations старого матча;
- [x] final formatter корректен при незавершённом текущем сете;
- [x] Telegram disabled → 0 API calls.

---

## Recommended refactoring

### [ ] 9. Вынести Telegram orchestration из `MatchViewModel`

Не отдельный пользовательский баг, а рекомендуемый способ нормально закрыть #4–#6 и не увеличивать `MatchViewModel`.

Целевая структура:

```text
TelegramScorePublisher
├── TelegramClient
├── currentSession
├── currentMessageId
├── lastPublishedState
└── sequential event queue
```

`MatchViewModel` только сообщает:

```kotlin
publisher.onMatchStarted(...)
publisher.onScoreChanged(...)
publisher.onMatchFinished(...)
publisher.reset()
```

**Acceptance criteria**

- [ ] `MatchViewModel` не хранит Telegram `messageId`.
- [ ] `MatchViewModel` не выполняет Telegram HTTP requests.
- [ ] Telegram state machine тестируется отдельно.
- [ ] Telegram failure не меняет `MatchState`.
- [ ] Telegram можно полностью отключить без влияния на scoring.

---

# Suggested implementation order

1. [x] Защитить bot token и убрать из backup.
2. [x] Исключить token из logging.
3. [x] Добавить HTTP timeout.
4. [x] Исправить sequencing/session race.
5. [x] Исправить restore после process death.
6. [x] Сделать безопасный переход между live messages.
7. [x] Добавить FakeTelegramClient и regression tests.
8. [x] Добавить UI проверки/status.
9. [ ] Завершить вынос `TelegramScorePublisher`, если он не был сделан в рамках #4–#6.

---

# Merge criteria

Перед merge `feature/telegram_count_publish` → `develop`:

- [x] Все P1 закрыты.
- [x] Bot token не попадает в backup.
- [x] Bot token не попадает в release logcat.
- [x] Все HTTP requests имеют timeout.
- [x] Telegram API failure не влияет на локальный матч.
- [x] Finish — последнее Telegram-событие матча.
- [x] New Match инвалидирует старую Telegram-session.
- [x] Process restart корректно восстанавливает публикацию.
- [x] Network failure не приводит к необратимой потере live session.
- [x] Есть автоматические тесты Telegram state machine.
- [ ] Выполнен smoke test на физическом Android-устройстве.

---

# Manual smoke test

- [ ] Telegram disabled — матч работает без сети.
- [ ] Правильный token/chatId — первое сообщение публикуется.
- [ ] Очки внутри гейма редактируют существующий message.
- [ ] Новый гейм создаёт новое сообщение.
- [ ] Новый сет создаёт новое сообщение.
- [ ] Pause/Resume не ломает Telegram-session.
- [ ] Отключение Интернета не ломает приложение.
- [ ] После восстановления сети трансляция возобновляется.
- [ ] Finish публикует финальный результат.
- [ ] После Finish live-сообщения больше не появляются.
- [ ] New Match не получает update от старого матча.
- [ ] Process kill/restart → следующая смена счёта создаёт новую live-session.
- [ ] Неверный token даёт понятную ошибку без утечки token.
- [ ] Неверный chatId даёт понятную ошибку.