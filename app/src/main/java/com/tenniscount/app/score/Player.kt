package com.tenniscount.app.score

/** Участник матча. Имена задаются в UI, ядро оперирует только номером игрока. */
enum class Player(val displayName: String) {
    ONE("Игрок 1"),
    TWO("Игрок 2");

    val opponent: Player
        get() = if (this == ONE) TWO else ONE
}
