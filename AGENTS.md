# Tennis Count 2026 — информация для агентов

## Сборочное окружение (Windows)

- **Java:** Oracle JDK 17.0.12 (в PATH как `java`).
- **Gradle:** используется **wrapper** (`gradlew.bat`, Gradle 8.9) — системный Gradle не нужен.
  В системе также установлены `C:\gradle` (9.0.0) и `C:\gradle-8.4` (8.4) — для проекта не требуются.
- **Android SDK:** `%LOCALAPPDATA%\Android\Sdk` (т.е. `C:\Users\user\AppData\Local\Android\Sdk`).
  Путь уже прописан в `local.properties` (файл в `.gitignore`, при клонировании создать заново:
  `sdk.dir=C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk`).
  Установлены платформы `android-34`, `android-36`; build-tools 33.0.1–36.0.0.
- **Toolchain:** AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, minSdk 29, compileSdk/targetSdk 36
  (подавление предупреждения через `android.suppressUnsupportedCompileSdk=36` в `gradle.properties`).

## Команды

- Unit-тесты ядра счёта: `.\gradlew.bat :app:testDebugUnitTest`
- Сборка APK: `.\gradlew.bat :app:assembleDebug`
- Первый запуск wrapper'а скачивает дистрибутив Gradle 8.9 (~130 МБ) — нужна сеть.

## Структура

- Задание и статус этапов (M1–M5): `task.md` — при возобновлении читать его целиком.
- Ядро счёта (чистый Kotlin, без Android-зависимостей): `app/src/main/java/com/tenniscount/app/score/` —
  `MatchEngine.kt` (правила + `strictValidation`), `GameState/SetState/MatchState`,
  `MatchSummary.kt`, `ScoreSpeech.kt` (фразы TTS), `MatchStateCodec.kt`
  (сериализация состояния матча в строку для SharedPreferences).
- Распознавание речи: `app/src/main/java/com/tenniscount/app/speech/` —
  `ScoreParser.kt` (чистый Kotlin, unit-тесты), `ModelManager.kt` (bundled русская
  small-модель Vosk ~45 МБ: поставляется в APK как `assets/model-ru/`, версия и SHA-256
  архива зафиксированы в `app/build.gradle.kts` — там же задачи `downloadVoskModel`/
  `unpackVoskModel`, скачивающие модель при сборке; при первом запуске приложение
  атомарно копирует модель из assets во внутреннее хранилище через staging-каталог,
  сети нет вообще; attribution Apache 2.0 — `app/src/main/assets/vosk-model-NOTICE.txt`),
  `VoskRecognizer.kt` (обёртка над Vosk,
  грамматика ограничена словами счёта: ноль/нуль/пятнадцать/тридцать/сорок/ровно/больше/
  меньше/гейм/сет/сколько/отмена/отмени + `[unk]`; слова «счёт» в грамматике нет —
  запрос счёта это «сколько»).
- Фон и сервис: `app/src/main/java/com/tenniscount/app/service/` — `ListeningController.kt`
  (синглтон, владеет VoskRecognizer и состоянием прослушивания), `ListeningService.kt`
  (foreground service типа `microphone`, уведомление со счётом и кнопками Пауза/Стоп;
  остановка из ViewModel — только через `stopService`, не интентом ACTION_STOP, иначе зациклится).
- Звуковые сигналы: `app/src/main/java/com/tenniscount/app/ui/SignalPlayer.kt` — тоны
  синтезируются (PCM) и играются через AudioTrack на медиа-канале (не ToneGenerator —
  иначе громкость ограничена 100%); на время прослушивания тракт держится «тёплым»
  потоком тишины (`setKeepAlive`), иначе холодный старт съедает короткий сигнал.
- UI: `ui/` — `MatchViewModel.kt` (состояние, настройки, TTS, персист),
  экраны `setup/`, `scoreboard/`, `history/`.
- История матчей: `app/src/main/java/com/tenniscount/app/data/` — Room (KSP),
  сохранение при «Завершить матч», экран `ui/history/`.
- Тесты: `app/src/test/java/com/tenniscount/app/score/` и `.../speech/`.

## Особенности

- PowerShell: heredoc-синтаксис bash (`cat <<'EOF'`) не работает — для длинных сообщений
  коммита использовать несколько флагов `git commit -m ... -m ...`.
- ASR: Vosk `com.alphacephei:vosk-android:0.3.75` (Maven Central) — версия с нативными
  библиотеками, выровненными под 16 KB страницы (требование Google Play). Разрешения
  RECORD_AUDIO (runtime-запрос на табло), FOREGROUND_SERVICE +
  FOREGROUND_SERVICE_MICROPHONE (сервис прослушивания) и POST_NOTIFICATIONS
  (runtime-запрос вместе с RECORD_AUDIO на Android 13+) — в манифесте.
  Разрешения INTERNET нет: модель bundled в APK, приложение полностью офлайн.
  Первая сборка скачивает модель (~45 МБ) в `app/build/vosk-model/` — нужна сеть.
- БД: Room 2.6.1 через KSP (`com.google.devtools.ksp` 2.0.21-1.0.28) — версии в `libs.versions.toml`.
- Настройки и персист: SharedPreferences `settings` (в `MatchViewModel`) — имена игроков,
  первый подающий, громкость сигналов (относительная, 1.0–2.0), тумблеры TTS-озвучки,
  `strictValidation` (по умолчанию включён; выключается на табло — тогда любое объявление
  применяется как есть). Текущий матч (состояние через `MatchStateCodec`, без лога)
  сохраняется после каждого изменения и восстанавливается при запуске; сбрасывается
  только «Завершить матч» / «Новый матч». Лог событий матча живёт только в памяти
  (кольцевой буфер последних 100 записей для табло), никуда не персистится;
  в истории Room лог не хранится (БД версии 2, `fallbackToDestructiveMigration`).
