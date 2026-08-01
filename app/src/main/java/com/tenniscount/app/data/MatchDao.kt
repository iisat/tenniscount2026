package com.tenniscount.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MatchDao {

    @Insert
    suspend fun insert(match: FinishedMatchEntity)

    @Query("SELECT * FROM finished_matches ORDER BY finishedAt DESC")
    fun observeAll(): Flow<List<FinishedMatchEntity>>

    @Query("DELETE FROM finished_matches WHERE id = :id")
    suspend fun delete(id: Long)
}
