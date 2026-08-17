package com.tenniscount.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FinishedMatchEntity::class], version = 2)
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
            )
                // v1 → v2 удаляет колонку log: история — некритичные данные,
                // проще пересоздать таблицу, чем тащить миграцию.
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
