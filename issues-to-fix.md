# Issues to Fix — сводный чеклист

Свод трёх независимых аудитов (`issues/devin-issues.md`, `issues/gpt-5-issues.md`,
`issues/opus-5-issues.md`). Пункты объединены по сути (несколько аудиторов часто
находили одну и ту же проблему), дубликаты убраны, порядок — от самого критичного
к самому незначительному. Источник(и) указаны в скобках.

---

## Критично

- [x] **1. Фантомный/неверный сет на экране «Матч завершён»**
  `SetScore.winner` объявлен non-null и при `gamesP1 <= gamesP2` (в т.ч. `0:0`) возвращает
  `Player.TWO`. Финальный экран считает `completedSets + currentSet.score`, из-за чего
  только что начатый пустой сет `0:0` или незавершённый сет при ручном завершении матча
  засчитывается как выигранный. Экран расходится с историей в Room, где используется
  корректная nullable-логика `MatchSummary.setsWon()`.
  Исправление: `SetScore.winner: Player?` (null при равенстве); на экране завершения
  учитывать `currentSet` только если он реально завершён / `totalGames > 0`; везде
  использовать единую логику `MatchSummary`. Добавить regression-тесты (`6:4→Finish`
  = `1:0`; `6:4→новый сет 0:0→Finish` = `1:0`; `3:2→ручное завершение` не засчитывает сет).
  (opus #1, gpt-5 #1)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\scoreboard\ScoreboardScreen.kt" lines="446-453" />
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\score\SetState.kt" lines="6-8" />

- [x] **2. Строгая валидация «ровно» / «больше» / «меньше» не работает**
  `MatchEngine.applyAnnouncement` для `Deuce`/`Advantage` не проверяет, что объявленный
  счёт достижим из текущего ровно за один розыгрыш. Голосовая команда «ровно» при
  счёте 0:0 выставляет 40:40, «больше» — 4:3. Ломает правило строгой валидации
  «на один розыгрыш» и может незаметно испортить реальный матч.
  Исправление: отклонять `Deuce`/`Advantage`, если ни один из двух возможных
  следующих розыгрышей не приводит к объявленному счёту; из 40:30/30:40 «ровно»
  допустимо, так как отстающий может сравнять счёт одним мячом.
  (devin P1)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\score\MatchEngine.kt" lines="136-163" />

- [x] **3. Утечка нативной памяти при каждом старт/стоп распознавания**
  `Recognizer` (нативный, `AutoCloseable`) создаётся в `VoskRecognizer`, но нигде не
  закрывается — `SpeechService.shutdown()` его не освобождает. Каждый цикл
  «Слушать → Стоп» (а также пауза/возобновление матча, перезапуск после ошибки)
  оставляет утёкший нативный объект; за долгий матч с частыми паузами возможен
  OOM/креш.
  Исправление: хранить ссылку на `Recognizer`, закрывать его в `stop()` после
  `shutdown()` сервиса, проверить защиту от двойного `close()`.
  (opus #2)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\speech\VoskRecognizer.kt" lines="52-74" />

- [x] **4. Гонка при инициализации TTS → навсегда приглушённая музыка**
  Колбэк `TextToSpeech(...) { status -> ... }` обращается к полю `tts`, которое
  присваивается только после возврата конструктора — гонка без гарантий. При
  проигрыше гонки `UtteranceProgressListener` не устанавливается,
  `releaseDuck()` никогда не вызывается, `duckCount` остаётся > 0 —
  музыка/аудио пользователя приглушены до конца матча.
  Исправление: настраивать движок через параметр лямбды, а не через внешнее поле;
  добавить watchdog на ducking (принудительный `abandonAudioFocusRequest`, если
  фокус удерживается дольше N секунд без активной озвучки).
  (opus #4)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\MatchViewModel.kt" lines="161-181" />

- [x] **5. `START_STICKY` без восстановления состояния распознавания**
  После убийства процесса Android перезапускает `ListeningService` с `intent == null`;
  срабатывает ветка, поднимающая уведомление «слушает счёт» с пустым состоянием,
  но `ListeningController`/`VoskRecognizer` фактически не слушают (пересоздаются в
  `OFF`). Пользователь видит активное прослушивание, которого нет.
  Исправление (один из вариантов): `START_NOT_STICKY`; либо на рестарте с
  `intent == null` не поднимать уведомление, а корректно останавливать сервис; либо
  полноценно восстанавливать распознавание из сохранённого состояния. Также
  синхронизировать owner recognizer'а (service/controller/ViewModel), чтобы
  результат `controller.start()` проверялся и при ошибке (модель не загрузилась,
  повреждена, микрофон не стартовал) foreground service и `keepAlive` гарантированно
  останавливались, а не «зависали».
  (opus #3, devin P2, gpt-5 #3 и #4)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\service\ListeningService.kt" lines="34-54" />
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\service\ListeningController.kt" lines="40-41" />

---

## Важно

- [x] **6. Слайдер громкости звуковых сигналов практически не работает**
  `BASE_AMPLITUDE = 0.6` + `softClip` ограничивают реальный диапазон: весь ход
  слайдера (150–250%) даёт ~2.4 дБ разницы, причём прирост берётся не из громкости,
  а из искажения (синус превращается в меандр). Это основная фича звукового тракта —
  сейчас регулятор бесполезен.
  Исправление: поднять базовую амплитуду ближе к full scale без выхода за неё;
  громкость относительно фоновой музыки регулировать ducking'ом
  (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` заранее, аудиотракт уже прогрет через
  `setKeepAlive`); рассмотреть `USAGE_ASSISTANCE_SONIFICATION`/`CONTENT_TYPE_SONIFICATION`;
  разборчивость сигнала набирать тембром/длительностью, а не амплитудой; пересмотреть
  диапазон слайдера.
  (opus #5)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\SignalPlayer.kt" lines="34-144" />

- [ ] **7. Zip Slip и отсутствие проверки целостности при загрузке Vosk-модели**
  `ModelManager.unzip` не проверяет canonical path записей zip (`../` → запись вне
  `filesDir`), не проверяет `responseCode`/`Content-Type` ответа (HTML-страница ошибки
  может быть сохранена как zip), не считает SHA-256 архива, не ограничивает число
  записей/суммарный распакованный размер, не убирает staging-каталог при ошибке.
  Исправление: проверка `outFile.canonicalPath.startsWith(targetDir.canonicalPath)`
  перед созданием каждого файла; зафиксированный SHA-256 для используемой версии
  модели и сверка после скачивания; лимиты на размер/число записей; удаление
  частично распакованных данных при любой ошибке; тест с malicious zip (`../`, `../../`).
  (devin P2, gpt-5 #2, opus #6)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\speech\ModelManager.kt" lines="46-87" />

- [ ] **8. Ручная правка очков тихо засчитывает гейм**
  Диалог правки позволяет независимо выставить «AD» одному игроку и «0/15/30»
  другому. `GameState(4, 0).winner == ONE` немедленно засчитывает гейм — пользователь
  правил очки, а получил +1 гейм без предупреждения.
  Исправление: блокировать «AD» для игрока, если у соперника меньше 40, либо явно
  подтверждать переход «такой счёт завершает гейм».
  (opus #7)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\scoreboard\ScoreboardScreen.kt" lines="482" />

- [ ] **9. Ручная правка геймов даёт ложный звуковой сигнал и TTS-объявление**
  Переход отслеживается по росту `totalGames` без учёта источника изменения — правка
  геймов (например 2:1 → 4:1) воспроизводит звонок и объявляет «Гейм, Игрок 1»,
  хотя розыгрыша не было.
  Исправление: передавать в `sync()` признак источника изменения (голос/очко/ручная
  правка) и не сигналить при ручных правках.
  (opus #8)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\MatchViewModel.kt" lines="659-682" />

- [ ] **10. Полный лог матча пишется в SharedPreferences на каждое событие**
  `persistCurrentMatch()` вызывается из каждого `sync()` (в т.ч. на нераспознанные
  фразы) и перезаписывает весь лог целиком; лог не ограничен по длине, а
  `MatchEngine.log` делает полную копию списка при каждом обращении. За длинный матч
  с непрерывным распознаванием — тысячи строк и сотни перезаписей XML-файла настроек.
  Исправление: разнести хранилища (настройки — prefs/DataStore, состояние матча и
  лог — Room), ограничить лог кольцевым буфером, не копировать список на каждое
  обращение.
  (opus #9, devin P3)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\MatchViewModel.kt" lines="621-627" />

- [ ] **11. Распознанная речь и прочие чувствительные данные пишутся в логи релизной сборки**
  `isMinifyEnabled = false`, правил стрипа логов нет — весь `Log.d` с распознанным
  текстом (и другими данными) попадает в logcat релизной сборки.
  Исправление: debug-only обёртка над логированием либо ProGuard-правило
  `assumenosideeffects` для `android.util.Log`.
  (opus #10)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\service\ListeningController.kt" lines="50" />

- [ ] **12. Недостаточная валидация состояния при decode / некорректные invariants**
  `MatchStateCodec.decode()` недостаточно строго проверяет `completedSets` —
  теоретически можно восстановить `-1:0`, `0:0` как completed, `100:1` и т.п. и
  использовать это в дальнейших расчётах (усугубляет проблему п.1).
  Исправление: добавить domain-инварианты (games >= 0, completed set обязан
  соответствовать правилам завершённого сета, `0:0` не может быть completed),
  отклонять/безопасно мигрировать повреждённые persisted-данные, добавить
  codec/domain тесты на invalid data.
  (gpt-5 #8)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\score\MatchStateCodec.kt" />

---

## Среднее

- [ ] **13. `keepScreenOn` не сбрасывается при уходе с табло**
  `ScoreboardScreen` выставляет `view.keepScreenOn = true` в `LaunchedEffect(Unit)`,
  но не сбрасывает флаг при dispose — экран продолжает гореть после ухода со
  scoreboard, расходуя батарею.
  Исправление: заменить на `DisposableEffect` со сбросом `keepScreenOn = false` в
  `onDispose`.
  (devin P1, gpt-5 #5, opus #16 — во всех трёх аудитах отмечено как одна и та же проблема)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\scoreboard\ScoreboardScreen.kt" lines="57-59" />

- [ ] **14. Настроить backup rules / `allowBackup`**
  `android:allowBackup="true"` без явных правил — история матчей, SharedPreferences
  и потенциально каталог скачанной Vosk-модели (~45 МБ, смысла в бэкапе нет) уходят
  в облачный бэкап без разбора.
  Исправление: добавить `data-extraction-rules`/`fullBackupContent`, исключить
  каталог модели, явно определить политику для истории матчей и активного матча.
  (devin P3, gpt-5 #7, opus hygiene)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\AndroidManifest.xml" lines="13-15" />

- [ ] **15. `AudioTrack` в `MODE_STATIC` без проверки `getMinBufferSize()`**
  `SignalPlayer.play` передаёт `samples.size * 2` напрямую в `buildTrack` без сверки
  с `getMinBufferSize()` — на части устройств возможен
  `IllegalArgumentException`/`IllegalStateException`.
  (devin P2)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\SignalPlayer.kt" lines="91-97" />

- [ ] **16. Мелочи в `SignalPlayer`**
  `track.release()` вызывается без предварительного `stop()`; каждый beep создаёт
  отдельную корутину и `AudioTrack` без сериализации — наложение сигналов порождает
  несколько параллельных треков.
  (opus #14)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\SignalPlayer.kt" lines="85-99" />

- [ ] **17. Keep-alive аудиотракта держится весь матч**
  Непрерывный поток тишины на `USAGE_MEDIA` держит A2DP-линк и аудиотракт активными
  все 1.5–2 часа матча — лишний расход батареи на телефоне и наушниках.
  Исправление: замерить реальный расход, рассмотреть включение keep-alive только
  вокруг ожидаемых событий (например, 10–15 сек после каждого распознанного очка).
  (opus #13)

- [ ] **18. Дублирование состояния и обновление уведомления через интенты**
  Каждая распознанная фраза порождает `Intent → onStartCommand → notify()`; состояние
  `paused` дублируется в сервисе и во ViewModel и синхронизируется вручную.
  Исправление: обновлять уведомление напрямую из общего состояния
  (`ListeningController.state`), убрать дублирование флага `paused`.
  (opus #15)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\MatchViewModel.kt" lines="360-365" />
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\service\ListeningService.kt" lines="44-48" />

- [ ] **19. Расхождения парсера и грамматики Vosk**
  Слово «сет» есть в грамматике, но не обрабатывается парсером (уходит в лог как
  «не счёт»); слова «матч» нет нигде, хотя оно заявлено в `task.md`; ветка
  `numbers.size >= 2` проверяется до терминов — фраза «тридцать пятнадцать отмена»
  применит счёт вместо отмены (у «отмены» должен быть высший приоритет);
  «больше»/«меньше» жёстко привязаны к подающему — если счёт объявил принимающий,
  преимущество уйдёт не тому игроку.
  Исправление: убрать неиспользуемые слова из грамматики либо добавить обработку;
  дать «отмене» высший приоритет разбора; определить поведение для 3+ распознанных
  чисел; отклонять неоднозначные фразы; задокументировать/исправить ограничение
  с «больше/меньше»; добавить parser-тесты на edge cases.
  (gpt-5 #10, opus #11)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\speech\ScoreParser.kt" lines="50-69" />
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\speech\VoskRecognizer.kt" lines="120-124" />

- [ ] **20. Удаление матча из истории без подтверждения**
  Одно нажатие безвозвратно удаляет матч из истории.
  Исправление: диалог подтверждения либо undo-снекбар.
  (opus #17)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\history\HistoryScreen.kt" lines="98-103" />

- [ ] **21. `MatchEngine`/`GameState`/`SetState` бросают исключения на путях, вызываемых из UI**
  `require(...)` используется там, где сейчас UI ограничивает диапазоны (поэтому не
  стреляет), но любое расхождение даст краш вместо деградации.
  Исправление: для операций, инициируемых пользователем, возвращать результат
  (например, `Result`/sealed-класс) вместо исключения.
  (opus #18)

- [ ] **22. Недостаточное покрытие тестами Android/service boundary**
  Domain-слой покрыт тестами хорошо (68 unit-тестов), а именно на Android
  lifecycle/service/UI boundary находятся основные найденные дефекты (сервис,
  recognizer lifecycle, разрешения, ZIP).
  Исправление: добавить минимум — тесты на process/service recreation, ошибку
  запуска recognizer, permission denied, malicious ZIP, повреждённую модель.
  (gpt-5 #9)

---

## Технический долг / архитектура

- [ ] **23. `MatchViewModel` — god object (~708 строк)**
  Смешаны orchestration матча, настройки/SharedPreferences, TTS, audio focus,
  MediaPlayer, foreground service, персист, история — усложняет тестирование и
  развитие (M5). Контраст: ядро счёта чистое и покрыто тестами, самая сложная и
  связная часть кода — без единого теста.
  Исправление: выделить `SettingsRepository`/`SettingsStore`, `MatchRepository`
  (персист текущего матча + история), `AudioFeedbackManager`/`AudioFeedback`
  (beep/ring/duck/TTS), `SpeechController`; оставить во ViewModel только
  orchestration + UI state; покрыть выделенные компоненты unit-тестами без
  Android runtime/Robolectric.
  (devin P3, gpt-5 #6, opus #19)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\MatchViewModel.kt" />

- [ ] **24. Релизный APK не сжат / не обфусцирован**
  `isMinifyEnabled = false`, нет `shrinkResources`, нет signing config и
  proguard-правил — APK больше нужного и код легко восстановить/декомпилировать.
  (devin P2, opus hygiene)
  <ref_snippet file="C:\projects\tenniscount2026\app\build.gradle.kts" lines="21-24" />

- [ ] **25. Локализация заблокирована хардкодом**
  README утверждает, что архитектура не блокирует добавление английского, но
  русский захардкожен в `Player.displayName`, `ScoreSpeech` (`POINT_WORDS` и все
  фразы), `MatchEngine` (записи лога), текстах ошибок/предупреждений в
  `MatchViewModel`.
  Исправление: вынести пользовательские строки в ресурсы, ядро оставить без
  текстов (типизированные события + форматирование в UI), либо скорректировать
  формулировку в README.
  (opus #20)

- [ ] **26. Неправильное склонение «сеты»/pluralization на экране результата**
  `FinishedContent`/`stringResource(R.string.sets_label).dropLast(1).lowercase()`
  выводит «1 сеты», «2 сеты» для любого числа.
  Исправление: использовать `<plurals>` в `strings.xml`.
  (devin P3, opus #12)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\ui\scoreboard\ScoreboardScreen.kt" lines="447-452" />

- [ ] **27. TTS всегда произносит «больше» при advantage**
  `ScoreSpeech.gameScore` для любого advantage говорит «больше, <имя>», даже если
  преимущество у принимающего (по теннисной терминологии должно быть «меньше»).
  (devin P3)
  <ref_snippet file="C:\projects\tenniscount2026\app\src\main\java\com\tenniscount\app\score\ScoreSpeech.kt" lines="14-26" />

- [ ] **28. `android.suppressUnsupportedCompileSdk=36` — подавление, а не решение**
  AGP 8.7.3 официально не поддерживает `compileSdk 36`, подавляется вручную.
  Исправление: обновить AGP и убрать флаг, как только SDK 36 будет официально
  поддержан.
  (devin P3, opus hygiene)

- [ ] **29. Инженерная гигиена сборки/репозитория**
  - CI отсутствует — добавить GitHub Actions (`assembleDebug` + `testDebugUnitTest` на push/PR)
  - LICENSE отсутствует — публичный репозиторий без лицензии
  - Иконка приложения отсутствует (`android:icon` не задан)
  - Уведомление использует системную иконку `android.R.drawable.ic_btn_speak_now` — заменить на свою
  - В Releases нет собранного APK
  - `distributionSha256Sum` не задан в `gradle-wrapper.properties`
  - Нет `gradle/verification-metadata.xml`
  - Нет Dependabot/Renovate
  - Нет ktlint/detekt/настроенного lint
  - Просевшие версии зависимостей (AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, Room 2.6.1, core-ktx 1.15.0) — провести dependency/Gradle maintenance pass
  - Мёртвая ветка проверки `Build.VERSION_CODES.Q` при `minSdk 29`
  - Нет `androidTest` — ни одного инструментального теста
  - README без скриншотов
  (opus hygiene, gpt-5 #11)

---

## Definition of Done

- [ ] Все пункты блока «Критично» закрыты и покрыты regression-тестами.
- [ ] Пункты «Важно» закрыты либо явно приняты как known technical debt.
- [ ] На каждый исправленный баг есть unit/instrumented тест.
- [ ] Release APK/AAB собирается успешно после включения minification.
- [ ] Проведён smoke test на физическом Android-устройстве: старт/пауза/возобновление/
  завершение матча, восстановление после background/process kill, работа без сети
  после установки модели, cold start без модели.
