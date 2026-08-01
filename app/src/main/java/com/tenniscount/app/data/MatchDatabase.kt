package com.tenniscount.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FinishedMatchEntity::class], version = 1)
abstract class MatchDatabase : RoomDatabase() {

    abstract fun matchDao(): MatchDao

    companion object {
        @Volatile
        private var instance: MatchDatabase? = null

        fun get(context: Context): MatchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MatchDatabase::class.java,
                "tenniscount.db",
            ).build().also { instance = it }
        }
    }
}
