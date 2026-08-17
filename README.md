# Tennis Count 2026

[README на русском](README.RU.md)

An Android app that automatically keeps track of a tennis match score. It listens to the
microphone, recognizes the score announced out loud by the players ("fifteen-love",
"deuce", "advantage", "game"…), and maintains the full score itself: points, games, sets.
Recognition runs fully offline (Vosk, Russian model) — no internet needed on the court.

> Voice recognition and the UI are Russian-only for now; the architecture does not
> block adding English later.

## Features

- Offline Russian speech recognition (Vosk) with the grammar restricted to score words —
  improves accuracy and filters out unrelated conversations.
- Full amateur tennis rules: deuce/advantage, a set is won by a margin of 2 games
  (no tie-break), unlimited number of sets — the match is ended manually.
- Automatic server switch every game; the announced numbers are server points first.
- Announcement consistency check: a score is applied only if it is exactly one rally
  ahead of the current one (BACKWARD / DUPLICATE / SKIP are rejected). The check can
  be disabled in the scoreboard settings.
- Manual correction: +point for a player, undo last action ("отмена" works by voice too),
  editing the game score and set games.
- Audio signals (no need to look at the screen): short beep — command accepted, low
  double tone — rejected, bell — game won, triple bell — set won.
- TTS announcements: game-end games score, "set point", set summary — each can be
  toggled in settings. Saying «сколько» speaks the current game score.
- Listening with the screen off/locked (foreground service with microphone), pause
  without losing the score, notification with the score and Pause/Stop buttons.
- Current match score, player names and settings survive an app restart.
- Finished match history (Room), saved via the "Finish match" button.

## Voice commands

| Command | Action |
|---|---|
| «пятнадцать ноль», «тридцать пятнадцать»… | Score announcement (server points first) |
| «пятнадцать» (single number) | Shorthand for «15-0» |
| «ровно» | Deuce (40-40) |
| «больше» / «меньше» | Advantage server / receiver |
| «гейм» | Award the game (winner is derived from the score) |
| «сколько» | Speak the current game score |
| «отмена» / «отмени» | Undo the last action |

## Tech stack

- Kotlin, Jetpack Compose (Material 3, dark theme), MVVM, no DI framework.
- ASR: Vosk Android (`com.alphacephei:vosk-android:0.3.75`), Russian small model
  (~45 MB, downloaded on first launch).
- DB: Room (match history). Tests: JUnit (score engine and parser).
- minSdk 29 (Android 10), targetSdk 36.

## Build and test

```bat
gradlew.bat :app:assembleDebug       :: build the APK
gradlew.bat :app:testDebugUnitTest   :: unit tests (score engine + parser)
```

Requirements: JDK 17, Android SDK with the android-36 platform (path goes to
`local.properties`). Build environment details (in Russian) — see [AGENTS.md](AGENTS.md).

## Project structure

- `app/.../score/` — pure Kotlin tennis rules engine (`MatchEngine`, `GameState`,
  `SetState`, `MatchSummary`, `ScoreSpeech`, `MatchStateCodec`), no Android dependencies.
- `app/.../speech/` — Vosk: model download (`ModelManager`), recognizer
  (`VoskRecognizer`), announcement parser (`ScoreParser`, pure Kotlin).
- `app/.../service/` — listening foreground service (`ListeningService`) and its
  singleton controller (`ListeningController`).
- `app/.../ui/` — Compose screens (match setup, scoreboard, history), `MatchViewModel`,
  audio signals (`SignalPlayer`).
- `app/.../data/` — Room, finished match history.
- `app/src/test/...` — unit tests for the score engine and the parser.

The full specification and development milestone status (M1–M5, in Russian) — see
[task.md](task.md).
