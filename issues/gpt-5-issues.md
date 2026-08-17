# GPT-5 Audit Issues

Аудит приложения: `https://github.com/iisat/tenniscount2026`

Статусный чеклист для исправления найденных проблем.

---

## P1 — High priority

### [ ] 1. Исправить неверный итоговый счёт матча

**Проблема**

Финальный экран считает результат через:

- `completedSets`
- плюс `currentSet.score`

При этом `SetScore.winner` фактически возвращает второго игрока во всех случаях, когда первый не ведёт, включая `0:0`.

После завершения сета `MatchState` создаёт новый пустой текущий сет `0:0`, поэтому возможен сценарий:

```text
Матч: 6:4
Новый currentSet: 0:0
Finish Match
```

и итоговый экран может посчитать результат как `1:1` по сетам.

Также при ручном завершении матча посреди незаконченного сета, например `3:2`, этот сет может ошибочно попасть в итог как выигранный Player 1.

**Что исправить**

- Не считать `currentSet` завершённым сетом.
- Использовать уже существующую корректную логику `MatchSummary.setsWon()` и `MatchSummary.setsSummary()`.
- Убрать неоднозначное поведение `SetScore.winner`.

Рекомендуемый вариант:

```kotlin
val winner: Player?
    get() = when {
        gamesP1 > gamesP2 -> Player.ONE
        gamesP2 > gamesP1 -> Player.TWO
        else -> null
    }
```

В идеале `SetScore` должен представлять только завершённый сет.

**Acceptance criteria**

- [ ] Матч `6:4` отображается как `1:0`.
- [ ] Пустой новый сет `0:0` не влияет на итог.
- [ ] При ручном завершении матча на `3:2` незаконченный сет не считается выигранным.
- [ ] Итоговый экран использует ту же логику результата, что и история матчей.
- [ ] Добавлены regression tests на эти сценарии.

---

### [ ] 2. Защитить распаковку Vosk-модели от Zip Slip

**Проблема**

`ModelManager` распаковывает скачанный ZIP без проверки, что итоговый файл остаётся внутри staging/model directory.

Злонамеренный ZIP entry вида:

```text
../../some-file
```

может привести к записи за пределы каталога модели.

Также скачанный архив не проверяется по SHA-256.

**Что исправить**

Перед созданием каждого файла проверять canonical path:

```kotlin
val root = targetDir.canonicalFile
val outFile = File(root, relative).canonicalFile

require(outFile.path.startsWith(root.path + File.separator)) {
    "Unsafe ZIP entry: ${entry.name}"
}
```

Дополнительно:

- добавить проверку SHA-256 архива;
- зафиксировать ожидаемый hash для используемой версии модели;
- ограничить максимально допустимый размер скачиваемого файла;
- ограничить общий объём распакованных данных;
- ограничить количество ZIP entries;
- удалять staging directory при любой ошибке распаковки/валидации.

**Acceptance criteria**

- [ ] ZIP entry не может выйти за пределы model/staging directory.
- [ ] Архив проверяется по SHA-256 до распаковки.
- [ ] Повреждённый или подменённый архив отвергается.
- [ ] Есть тест с malicious ZIP (`../` / `../../`).
- [ ] После ошибки не остаётся частично установленной модели.

---

## P2 — Medium priority

### [ ] 3. Исправить ownership/lifecycle `ListeningService` и Vosk recognizer

**Проблема**

Сейчас обязанности разделены между:

- `ListeningService`
- singleton `ListeningController`
- `MatchViewModel`

При этом `ListeningService` использует `START_STICKY`.

После убийства процесса Android может восстановить foreground service отдельно от ViewModel/controller state. В результате возможно состояние:

- foreground notification активен;
- service считается запущенным;
- recognizer фактически не работает;
- callbacks ViewModel отсутствуют.

**Рекомендуемое решение**

Предпочтительный вариант:

- `ListeningService` владеет lifecycle `VoskRecognizer`;
- service запускает/останавливает recognizer;
- UI/ViewModel подписывается на состояние service/controller;
- callbacks не должны зависеть от существования конкретного ViewModel.

Если автоматическое восстановление listening после process death не требуется — рассмотреть:

```kotlin
START_NOT_STICKY
```

**Acceptance criteria**

- [ ] После process death service и recognizer находятся в согласованном состоянии.
- [ ] Не бывает notification "Listening", если распознавание фактически не работает.
- [ ] Повторный запуск приложения корректно восстанавливает состояние.
- [ ] Добавлен тест или manual test-case на process kill / service recreation.

---

### [ ] 4. Останавливать foreground service при ошибке запуска recognizer

**Проблема**

`startListening()` запускает foreground service и `SignalPlayer.setKeepAlive(true)`, после чего асинхронно вызывает `controller.start()`.

Результат `controller.start()` не используется.

Если:

- модель не загрузилась;
- модель повреждена;
- Vosk не инициализировался;
- микрофон не стартовал;

service и keepalive могут остаться активными.

**Что исправить**

Обрабатывать результат запуска:

```kotlin
viewModelScope.launch {
    if (!controller.start()) {
        SignalPlayer.setKeepAlive(false)
        context.stopService(Intent(context, ListeningService::class.java))
    }
}
```

Также синхронизировать одновременные вызовы `start()` через `Mutex`, atomic state или аналогичный механизм.

**Acceptance criteria**

- [ ] При любой ошибке запуска foreground service останавливается.
- [ ] `keepAlive` всегда сбрасывается.
- [ ] Двойной быстрый вызов Start не создаёт параллельные процессы загрузки/инициализации.
- [ ] UI получает корректное состояние ошибки.

---

### [ ] 5. Снимать `keepScreenOn` при выходе со Scoreboard

**Проблема**

Экран устанавливает:

```kotlin
view.keepScreenOn = true
```

но не возвращает значение обратно при уходе со screen.

В результате Activity/View может продолжать удерживать экран включённым.

**Что исправить**

Использовать `DisposableEffect`:

```kotlin
DisposableEffect(view) {
    view.keepScreenOn = true

    onDispose {
        view.keepScreenOn = false
    }
}
```

**Acceptance criteria**

- [ ] На scoreboard экран не гаснет.
- [ ] После ухода со scoreboard системное поведение sleep/timeout восстанавливается.

---

### [ ] 6. Разделить слишком большой `MatchViewModel`

**Проблема**

`MatchViewModel` содержит слишком много обязанностей:

- orchestration матча;
- preferences;
- Room;
- speech recognition;
- foreground service;
- TTS;
- audio focus;
- MediaPlayer;
- signals;
- persistence;
- UI state.

Это усложняет тестирование и дальнейшее развитие.

**Что сделать**

Рекомендуемое разделение:

- `MatchRepository`
- `MatchSession` / `MatchCoordinator`
- `SpeechController`
- `AudioFeedbackManager`
- `MatchViewModel` — только orchestration + UI state

DI-фреймворк необязателен; достаточно constructor injection.

**Acceptance criteria**

- [ ] Android/audio/persistence responsibilities вынесены из ViewModel.
- [ ] Core match logic тестируется без Android runtime.
- [ ] ViewModel существенно упрощён.
- [ ] Компоненты можно unit-test'ить независимо.

---

### [ ] 7. Настроить backup rules

**Проблема**

В Manifest включено:

```xml
android:allowBackup="true"
```

При этом приложение хранит данные в:

- SharedPreferences;
- Room;
- `filesDir`;
- каталоге скачанной Vosk-модели.

Скачанную модель нет смысла переносить через backup.

**Что исправить**

Добавить explicit `data-extraction-rules` / backup rules.

Минимум:

- исключить каталог Vosk-модели;
- определить продуктовую политику для истории матчей;
- определить, должен ли active match переноситься между устройствами.

**Acceptance criteria**

- [ ] Vosk model не попадает в backup.
- [ ] Правила backup заданы явно.
- [ ] Поведение истории матчей при restore определено и протестировано.

---

### [ ] 8. Усилить валидацию `SetScore` / persistence

**Проблема**

`MatchStateCodec.decode()` недостаточно строго проверяет `completedSets`.

Сейчас теоретически возможно восстановить некорректные значения:

```text
-1:0
0:0
100:1
```

и использовать их в дальнейших вычислениях.

**Что исправить**

Добавить domain invariants.

Например:

- games >= 0;
- completed set обязан соответствовать правилам завершённого сета;
- `0:0` не может быть completed set;
- некорректные persisted данные должны отклоняться или безопасно мигрироваться.

**Acceptance criteria**

- [ ] Некорректный completed set нельзя создать/восстановить.
- [ ] Повреждённые SharedPreferences не ломают расчёт результата.
- [ ] Добавлены codec/domain tests на invalid data.

---

### [ ] 9. Добавить Android/integration tests для критических boundary-сценариев

**Проблема**

Domain layer покрыт тестами заметно лучше, чем Android lifecycle/UI/service boundary.

Именно на Android boundary находятся несколько найденных дефектов.

**Добавить тесты**

Минимальный набор:

- [ ] `6:4 -> Finish` даёт `1:0`.
- [ ] `6:4 -> новый currentSet 0:0 -> Finish` даёт `1:0`.
- [ ] `3:2 -> manual Finish` не засчитывает незаконченный сет.
- [ ] Ошибка запуска recognizer останавливает foreground service.
- [ ] Process/service recreation не создаёт "мертвое" listening state.
- [ ] Permission denied корректно обрабатывается.
- [ ] Malicious ZIP не может записать файл вне target directory.
- [ ] Повреждённая модель не считается успешно установленной.

---

## P3 — Low priority / technical debt

### [ ] 10. Синхронизировать Vosk grammar и `ScoreParser`

**Проблема**

В grammar присутствуют слова/команды, которые parser фактически не обрабатывает.

Например `сет` может быть распознан, но не приводить к действию.

Также parser принимает первые два числа, если распознано больше двух, что может давать неоднозначные команды.

**Что исправить**

- удалить неиспользуемые слова из grammar либо добавить соответствующую команду;
- определить поведение для 3+ распознанных чисел;
- отклонять неоднозначные фразы;
- добавить parser tests.

**Acceptance criteria**

- [ ] Каждое командное слово grammar имеет определённую семантику.
- [ ] Неоднозначные score-команды не изменяют счёт.
- [ ] Parser покрыт edge-case тестами.

---

### [ ] 11. Провести dependency / Gradle maintenance pass

**Что проверить**

- Kotlin;
- Compose BOM;
- Android Gradle Plugin;
- Room;
- Vosk dependency;
- AndroidX;
- target/compile SDK;
- deprecated API usage.

Отдельно проверить release configuration:

- minification;
- shrinkResources;
- R8/ProGuard rules;
- reproducibility release build.

**Acceptance criteria**

- [ ] Критичные зависимости обновлены до поддерживаемых версий.
- [ ] Release build проходит без новых warnings/errors.
- [ ] Проверены release-only проблемы после R8/minification.

---

## Suggested implementation order

1. [ ] Final score bug.
2. [ ] Safe Vosk ZIP download/extraction.
3. [ ] Foreground service / recognizer lifecycle.
4. [ ] Cleanup after recognizer startup failure.
5. [ ] `keepScreenOn` cleanup.
6. [ ] Persistence/domain invariants.
7. [ ] Android/integration regression tests.
8. [ ] Backup rules.
9. [ ] `MatchViewModel` decomposition.
10. [ ] Speech parser cleanup.
11. [ ] Dependency maintenance.

---

## Definition of Done

Перед закрытием аудита:

- [ ] Все P1 закрыты.
- [ ] Все P2 либо закрыты, либо явно приняты как known technical debt.
- [ ] На каждый исправленный regression bug есть тест.
- [ ] Release APK/AAB собирается успешно.
- [ ] Проведён smoke test на физическом Android-устройстве.
- [ ] Проверено начало/пауза/возобновление/завершение матча.
- [ ] Проверено восстановление приложения после background/process kill.
- [ ] Проверена работа без сети после установленной Vosk-модели.
- [ ] Проверен cold start с отсутствующей моделью.
