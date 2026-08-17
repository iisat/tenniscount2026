package com.tenniscount.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Завершённый матч в локальной истории. */
@Entity(tableName = "finished_matches")
data class FinishedMatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val finishedAt: Long,
    val player1Name: String,
    val player2Name: String,
    val setsP1: Int,
    val setsP2: Int,
    /** Счёт по сетам: «6:4 3:6 (2:1)». */
    val setsSummary: String,
)
