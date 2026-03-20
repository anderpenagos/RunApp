package com.runapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Banco de dados Room do RunApp.
 *
 * DESIGN: singleton via companion object com double-checked locking.
 * Criado em [RunApp.AppContainer] e injetado no Service e no ViewModel.
 *
 * Versão 1: schema inicial com tabela route_points.
 * Futuras versões: incrementar version e adicionar Migration.
 */
@Database(
    entities = [RoutePointEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RunDatabase : RoomDatabase() {

    abstract fun routePointDao(): RoutePointDao

    companion object {
        @Volatile
        private var INSTANCE: RunDatabase? = null

        fun getInstance(context: Context): RunDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RunDatabase::class.java,
                    "runapp_database"
                )
                    // fallbackToDestructiveMigration: apenas aceitável porque todos os dados
                    // "importantes" são enviados ao intervals.icu. Se schema mudar, pontos de
                    // sessões antigas (não enviadas ainda) são perdidos — trade-off aceitável.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
