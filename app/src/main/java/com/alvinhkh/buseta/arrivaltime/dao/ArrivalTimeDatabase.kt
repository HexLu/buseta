package com.alvinhkh.buseta.arrivaltime.dao

import android.arch.persistence.room.Database
import android.arch.persistence.room.RoomDatabase
import android.arch.persistence.room.Room
import android.content.Context
import com.alvinhkh.buseta.arrivaltime.model.ArrivalTime


@Database(entities = [(ArrivalTime::class)], version = 1)
abstract class ArrivalTimeDatabase : RoomDatabase() {

    abstract fun arrivalTimeDao(): ArrivalTimeDao

    companion object {
        private var instance: ArrivalTimeDatabase? = null

        fun getInstance(context: Context): ArrivalTimeDatabase? {
            if (instance == null) {
                synchronized(ArrivalTimeDatabase::class.java) {
                    instance = Room.databaseBuilder(context.applicationContext,
                            ArrivalTimeDatabase::class.java, "arrival_time.db")
                            .allowMainThreadQueries()
                            .build()
                }
            }
            return instance
        }
    }
}
