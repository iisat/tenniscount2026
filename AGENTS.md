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
- Ядро счёта (чистый Kotlin, без Android-зависимостей): `app/src/main/java/com/tenniscount/app/score/`.
- Тесты: `app/src/test/java/com/tenniscount/app/score/`.

## Особенности

- PowerShell: heredoc-синтаксис bash (`cat <<'EOF'`) не работает — для длинных сообщений
  коммита использовать несколько флагов `git commit -m ... -m ...`.
- В AndroidManifest пока нет Activity и темы — они появятся на этапе M2 (UI).
