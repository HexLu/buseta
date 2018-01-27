package com.alvinhkh.buseta.mtr.dao

import android.arch.persistence.room.Dao
import android.arch.persistence.room.Query
import com.alvinhkh.buseta.mtr.model.AESBusDistrict
import com.alvinhkh.buseta.mtr.model.AESBusRoute
import com.alvinhkh.buseta.mtr.model.AESBusStop
import io.reactivex.Flowable


@Dao
interface AESBusDao {

    @Query("SELECT * FROM Bus_new")
    fun getAllRoutes(): Flowable<List<AESBusRoute>>

    @Query("SELECT * FROM Stop_new")
    fun getAllStops(): Flowable<List<AESBusStop>>

    @Query("SELECT * FROM District")
    fun getAllDistricts(): Flowable<List<AESBusDistrict>>

}