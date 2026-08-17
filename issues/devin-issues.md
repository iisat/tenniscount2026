# Devin Issues — аудит Tennis Count 2026

Дата аудита: 2026-08-17.  
Юнит-тесты: `BUILD SUCCESSFUL` (`:app:testDebugUnitTest`).

## Высокий приоритет

- [ ] **[P1] Строгая валидация «ровно» / «больше» / «меньше» не работает**  
  `MatchEngine.applyAnnouncement` для `Deuce` и `Advantage` не проверяет, что оба игрока уже в зоне 40-40/advantage. Команда «ровно» при 0:0 ставит 40:40, «больше» — 4:3. Нарушает правило «ровно на один розыгрыш».  
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\score\MatchEngine.kt" lines="136-163" />

- [ ] **[P1] `keepScreenOn` не сбрасывается при уходе с табло**  
  `ScoreboardScreen` устанавливает `view.keepScreenOn = true` в `LaunchedEffect(Unit)`, но не очищает флаг при dispose. Экран продолжает гореть и тратить батарею.  
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\scoreboard\ScoreboardScreen.kt" lines="57-59" />

## Средний приоритет

- [ ] **[P2] Нет защиты от ZipSlip и отсутствует проверка целостности модели**  
  `ModelManager.unzip` не валидирует пути `..` и не проверяет checksum скачанного zip. При компрометации зеркала возможна запись вне `filesDir`.  
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\speech\ModelManager.kt" lines="71-87" />

- [ ] **[P2] Релизный APK не сжат / не обфусцирован**  
  `isMinifyEnabled = false`, нет `shrinkResources`. APK большой, код легко восстановить.  
  <ref_snippet file="C:\projects\tenniscount2026\app\build.gradle.kts" lines="21-24" />

- [ ] **[P2] Foreground service может перезапуститься без активного распознавания**  
  `ListeningService.onStartCommand` при неизвестном action (например, `START_STICKY`) вызывает `startForegroundWithNotification()`, но не стартует `ListeningController`.  
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\service\ListeningService.kt" lines="49-54" />

- [ ] **[P2] Остановка из уведомления не останавливает микрофон, если ViewModel уже умер**  
  `ACTION_STOP` вызывает `onStopRequested` callback, но если он `null`, `ListeningController`/`VoskRecognizer` продолжают работать.  
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\service\ListeningService.kt" lines="34-38" />
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\service\ListeningController.kt" lines="40-41" />

- [ ] **[P2] `AudioTrack` в `MODE_STATIC` без проверки `getMinBufferSize()`**  
  `SignalPlayer.play` передаёт `samples.size * 2` в `buildTrack`. На части устройств возможен `IllegalArgumentException`/`IllegalStateException`.  
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\SignalPlayer.kt" lines="91-97" />

## Низкий / информационный приоритет

- [ ] **[P3] `allowBackup="true"` — данные уходят в облако**  
  История матчей и `SharedPreferences` могут резервироваться Google. Стоит ограничить через `fullBackupContent` или `allowBackup="false"`.  
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\AndroidManifest.xml" lines="13-15" />

- [ ] **[P3] TTS при advantage всегда говорит «больше»**  
  `ScoreSpeech.gameScore` для любого advantage произносит «больше, <имя>», даже если advantage у принимающего (по грамматике — «меньше»). Может сбивать с толку.  
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\score\ScoreSpeech.kt" lines="14-26" />

- [ ] **[P3] Неправильное склонение «сеты» на экране результата**  
  `FinishedContent` выводит `<n> сеты` для любого числа: получится «1 сеты». Нужно склонение по числу.  
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\scoreboard\ScoreboardScreen.kt" lines="447-452" />

- [ ] **[P3] `MatchViewModel` слишком разросся**  
  708 строк, смешаны UI-state, TTS, audio focus, notifications, DB, `SharedPreferences`. Перед M5 выделить `SettingsRepository`, `MatchRepository`, `TtsPlayer`.

- [ ] **[P3] Частые записи в `SharedPreferences`**  
  `persistCurrentMatch` выполняется после каждого `sync` вместе с логом. При долгих матчах рост лога может замедлять запись. Для M5 лучше `DataStore` или отдельное хранение лога.  
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\MatchViewModel.kt" lines="621-627" />

- [ ] **[P3] `android.suppressUnsupportedCompileSdk=36` — технический долг**  
  AGP 8.7.3 + `compileSdk 36` подавляется вручную. Как только SDK 36 официально поддержан — убрать suppress.
