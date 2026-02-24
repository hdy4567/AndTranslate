package com.viitor.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * ★ AndTranslateDatabase
 * 프로젝트의 모든 영속성 데이터를 관리하는 통합 데이터베이스 클래스.
 */
@Database(
    entities = [SubtitleHistory::class, Glossary::class, GlossaryFts::class],
    version = 1,
    exportSchema = false
)
abstract class AndTranslateDatabase : RoomDatabase() {
    abstract fun historyDao(): SubtitleHistoryDao
    abstract fun glossaryDao(): GlossaryDao

    companion object {
        @Volatile
        private var INSTANCE: AndTranslateDatabase? = null

        fun getDatabase(context: Context): AndTranslateDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AndTranslateDatabase::class.java,
                    "andtranslate_db"
                )
                .fallbackToDestructiveMigration() // 개발 단계에서는 스키마 변경 시 자동 초기화
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
