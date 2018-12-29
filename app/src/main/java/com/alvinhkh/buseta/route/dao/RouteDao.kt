package com.alvinhkh.buseta.route.dao

import android.arch.lifecycle.LiveData
import android.arch.persistence.room.*
import com.alvinhkh.buseta.route.model.LatLong
import com.alvinhkh.buseta.route.model.Route


@Dao
interface RouteDao {

    @Query("DELETE FROM routes WHERE company_code = :companyCode AND last_update < :lastUpdate")
    fun delete(companyCode: String, lastUpdate: Long): Int

    @Query("DELETE FROM routes WHERE company_code = :companyCode AND name = :routeNo AND last_update < :lastUpdate")
    fun delete(companyCode: String, routeNo: String, lastUpdate: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(data: Route): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(data: List<Route>): List<Long>

    @Query("SELECT * FROM routes ORDER BY company_code ASC, name ASC, sequence + 0 ASC, service_type + 0 ASC")
    fun liveData(): LiveData<MutableList<Route>>

    @Query("SELECT * FROM routes WHERE company_code = :companyCode AND name = :routeNo ORDER BY company_code ASC, name ASC, sequence + 0 ASC, service_type + 0 ASC")
    fun liveData(companyCode: String, routeNo: String): LiveData<MutableList<Route>>

    @Query("SELECT * FROM routes WHERE company_code = :companyCode AND name = :routeNo AND sequence = :sequence AND service_type = :serviceType AND info_key = :infoKey")
    fun get(companyCode: String, routeNo: String, sequence: String, serviceType: String, infoKey: String): Route

    @Query("UPDATE routes SET map_coordinates = '[]' WHERE company_code = :companyCode AND name = :routeNo AND sequence = :sequence AND service_type = :serviceType AND info_key = :infoKey")
    fun deleteCoordinates(companyCode: String, routeNo: String, sequence: String, serviceType: String, infoKey: String): Int

    @Query("UPDATE routes SET map_coordinates = :coordinates WHERE company_code = :companyCode AND name = :routeNo AND sequence = :sequence AND service_type = :serviceType AND info_key = :infoKey")
    fun updateCoordinates(companyCode: String, routeNo: String, sequence: String, serviceType: String, infoKey: String, coordinates: List<LatLong>): Int

}