package com.alvinhkh.buseta.mtr

import android.content.Context
import android.location.Location
import android.text.TextUtils
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.alvinhkh.buseta.C
import com.alvinhkh.buseta.R
import com.alvinhkh.buseta.arrivaltime.dao.ArrivalTimeDatabase
import com.alvinhkh.buseta.arrivaltime.model.ArrivalTime
import com.alvinhkh.buseta.mtr.model.AESEtaBusStopsRequest
import com.alvinhkh.buseta.route.dao.RouteDatabase
import com.alvinhkh.buseta.utils.HashUtil
import java.text.SimpleDateFormat
import java.util.*

class AESBusEtaWorker(private val context : Context, params : WorkerParameters)
    : Worker(context, params) {

    private val aesService = MtrService.aes.create(MtrService::class.java)

    private val arrivalTimeDatabase = ArrivalTimeDatabase.getInstance(context)!!

    private val routeDatabase = RouteDatabase.getInstance(context)!!

    override fun doWork(): Result {
        val widgetId = inputData.getInt(C.EXTRA.WIDGET_UPDATE, -1)
        val notificationId = inputData.getInt(C.EXTRA.NOTIFICATION_ID, -1)
        val companyCode = inputData.getString(C.EXTRA.COMPANY_CODE)?:C.PROVIDER.AESBUS
        val routeNo = inputData.getString(C.EXTRA.ROUTE_NO)?:return Result.failure()

        val outputData = Data.Builder()
                .putInt(C.EXTRA.WIDGET_UPDATE, widgetId)
                .putInt(C.EXTRA.NOTIFICATION_ID, notificationId)
                .putString(C.EXTRA.COMPANY_CODE, companyCode)
                .putString(C.EXTRA.ROUTE_NO, routeNo)
                .build()

        val routeStopList = routeDatabase.routeStopDao().get(companyCode, routeNo)
        if (routeStopList.size < 1) {
            return Result.failure(outputData)
        }

        val arrivalTimeList = arrayListOf<ArrivalTime>()
        val timeNow = System.currentTimeMillis()

        val key = HashUtil.md5("mtrMobile_" + SimpleDateFormat("yyyyMMddHHmm", Locale.ENGLISH).format(Date()))
        if (key.isEmpty()) {
            arrivalTimeDatabase.arrivalTimeDao().clear(companyCode, routeNo, timeNow)
            routeStopList.forEach { routeStop ->
                val arrivalTime = ArrivalTime.emptyInstance(applicationContext, routeStop)
                arrivalTime.text = context.getString(R.string.message_fail_to_request)
                arrivalTimeList.add(arrivalTime)
            }
            arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTimeList)
            return Result.failure(outputData)
        }
        
        val response = aesService.busStopsDetail(AESEtaBusStopsRequest(routeNo, "2", "zh", key)).execute()
        if (!response.isSuccessful) {
            arrivalTimeDatabase.arrivalTimeDao().clear(companyCode, routeNo, timeNow)
            routeStopList.forEach { routeStop ->
                val arrivalTime = ArrivalTime.emptyInstance(applicationContext, routeStop)
                arrivalTime.text = context.getString(R.string.message_fail_to_request)
                arrivalTimeList.add(arrivalTime)
            }
            arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTimeList)
            return Result.failure(outputData)
        }

        val res = response.body()

        if (res?.routeName != null && res.routeName.equals(routeNo)) {
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ENGLISH)
            var statusTime = Date()
            if (res.routeStatusTime != null) {
                try {
                    statusTime = sdf.parse(res.routeStatusTime)
                } catch (ignored: Throwable) {
                }
            }
            val etas = res.busStops
            if (etas != null && etas.isNotEmpty()) {
                routeStopList.forEach { routeStop ->
                    var isAvailable = false
                    arrivalTimeList.clear()
                    for (i in etas.indices) {
                        val (buses, _, busStopId) = etas[i]
                        if (busStopId == null) continue
                        if (busStopId != routeStop.stopId && busStopId != "999") continue
                        if (buses != null && buses.isNotEmpty()) {
                            for (j in 0 until buses.size) {
                                isAvailable = true
                                val aesEtaBus = buses[j]
                                val arrivalTime = ArrivalTime.emptyInstance(context, routeStop)
                                arrivalTime.estimate = aesEtaBus.arrivalTimeText.orEmpty()
                                val calendar = Calendar.getInstance()
                                calendar.time = statusTime
                                calendar.add(Calendar.SECOND, aesEtaBus.arrivalTimeInSecond.orEmpty().toInt())
                                arrivalTime.text = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(calendar.time)
                                if (!aesEtaBus.busRemark.isNullOrEmpty()) {
                                    arrivalTime.text += " " + aesEtaBus.busRemark
                                }
                                arrivalTime.plate = aesEtaBus.busId.orEmpty()
                                arrivalTime.isSchedule = aesEtaBus.isScheduled == "1"
                                arrivalTime.latitude = 0.0
                                arrivalTime.longitude = 0.0
                                arrivalTime.distanceKM = -1.0
                                if (!arrivalTime.isSchedule) {
                                    aesEtaBus.busLocation?.let {
                                        arrivalTime.latitude = it.latitude
                                        arrivalTime.longitude = it.longitude
                                    }
                                    if (!routeStop.latitude.isNullOrEmpty() &&
                                            !routeStop.longitude.isNullOrEmpty() &&
                                            arrivalTime.latitude != 0.0 &&
                                            arrivalTime.longitude != 0.0) {
                                        val stopLocation = Location("")
                                        stopLocation.latitude = routeStop.latitude!!.toDouble()
                                        stopLocation.longitude = routeStop.longitude!!.toDouble()
                                        val busLocation = Location("")
                                        busLocation.latitude = arrivalTime.latitude
                                        busLocation.longitude = arrivalTime.longitude
                                        if (busLocation.distanceTo(stopLocation).toDouble() != 0.0) {
                                            arrivalTime.distanceKM = busLocation.distanceTo(stopLocation) / 1000.0
                                        } else {
                                            arrivalTime.distanceKM = 0.0
                                        }
                                    }
                                }

                                arrivalTime.routeNo = routeStop.routeNo?:""
                                arrivalTime.routeSeq = routeStop.routeSequence?:""
                                arrivalTime.stopId = routeStop.stopId?:""
                                arrivalTime.stopSeq = routeStop.sequence?:""
                                arrivalTime.order = j.toString()
                                arrivalTime.generatedAt = statusTime.time
                                arrivalTime.updatedAt = timeNow
                                arrivalTimeList.add(arrivalTime)
                            }
                            arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTimeList)
                        }
                    }
                    if (!isAvailable) {
                        val arrivalTime = ArrivalTime.emptyInstance(applicationContext, routeStop)
                        if (!TextUtils.isEmpty(res.routeStatusRemarkTitle)) {
                            arrivalTime.text = res.routeStatusRemarkTitle?:""
                        }
                        arrivalTime.generatedAt = System.currentTimeMillis()
                        arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime)
                    }
                }
                arrivalTimeDatabase.arrivalTimeDao().clear(companyCode, routeNo, timeNow)
            }
        }

        return Result.failure(outputData)
    }
}