package com.madmandriver

import android.content.Context
import androidx.room.Room
import com.madmandriver.data.AppDatabase
import com.madmandriver.data.SettingsRepository

/** Minimal service locator — enough for a single-module v1 app. */
object Graph {
    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set

    fun init(context: Context) {
        if (::db.isInitialized) return
        db = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "madman-driver.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        settings = SettingsRepository(context.applicationContext)
    }
}
