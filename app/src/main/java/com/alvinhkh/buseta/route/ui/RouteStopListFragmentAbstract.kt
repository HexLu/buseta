package com.alvinhkh.buseta.route.ui

import android.Manifest
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.preference.PreferenceManager
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.LinearSmoothScroller
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager

import com.alvinhkh.buseta.C
import com.alvinhkh.buseta.R
import com.alvinhkh.buseta.arrivaltime.dao.ArrivalTimeDatabase
import com.alvinhkh.buseta.follow.dao.FollowDatabase
import com.alvinhkh.buseta.follow.model.Follow
import com.alvinhkh.buseta.kmb.KmbStopListWorker
import com.alvinhkh.buseta.lwb.LwbStopListWorker
import com.alvinhkh.buseta.mtr.ui.AESBusStopListFragment
import com.alvinhkh.buseta.nwst.NwstStopListWorker
import com.alvinhkh.buseta.route.model.Route
import com.alvinhkh.buseta.route.model.RouteStop
import com.alvinhkh.buseta.route.ui.RouteStopListViewAdapter.Data.Companion.TYPE_ROUTE_STOP
import com.alvinhkh.buseta.service.EtaService
import com.alvinhkh.buseta.ui.route.RouteAnnounceActivity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices

import com.alvinhkh.buseta.utils.ConnectivityUtil
import com.alvinhkh.buseta.utils.PreferenceUtil
import com.google.android.gms.maps.model.Marker
import timber.log.Timber
import java.util.UUID


// TODO: better way to find nearest stop
// TODO: keep (nearest) stop on top
// TODO: auto refresh eta for follow stop and nearby stop
abstract class RouteStopListFragmentAbstract : Fragment(),  SwipeRefreshLayout.OnRefreshListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    protected var route: Route? = null

    protected var navToStop: RouteStop? = null

    protected var scrollToPos: Int? = 0


    private lateinit var arrivalTimeDatabase: ArrivalTimeDatabase

    private lateinit var followDatabase: FollowDatabase

    protected lateinit var swipeRefreshLayout: SwipeRefreshLayout

    protected lateinit var recyclerView: RecyclerView

    protected lateinit var emptyView: View

    protected lateinit var progressBar: ProgressBar

    protected lateinit var emptyText: TextView

    private lateinit var viewModel: RouteStopListViewModel

    private lateinit var viewAdapter: RouteStopListViewAdapter

    private var requestId: UUID? = null

    private val initLoadHandler = Handler()

    private val initLoadRunnable = object : Runnable {
        override fun run() {
            if (view != null) {
                if (userVisibleHint) {
                    refreshHandler.post(refreshRunnable)
                    adapterUpdateHandler.post(adapterUpdateRunnable)
                    if (activity != null) {
                        val fab = activity!!.findViewById<FloatingActionButton>(R.id.fab)
                        fab?.setOnClickListener { onRefresh() }
                    }
                } else {
                    refreshHandler.removeCallbacksAndMessages(null)
                    adapterUpdateHandler.removeCallbacksAndMessages(null)
                }
                initLoadHandler.removeCallbacksAndMessages(null)
            } else {
                initLoadHandler.postDelayed(this, 5000)  // try every 5 sec
            }
        }
    }

    protected val adapterUpdateHandler = Handler()

    protected val adapterUpdateRunnable: Runnable = object : Runnable {
        override fun run() {
            viewAdapter.notifyDataSetChanged()
            adapterUpdateHandler.postDelayed(this, 30000)  // refresh every 30 sec
        }
    }

    private var refreshInterval = 0

    protected val refreshHandler = Handler()

    protected val refreshRunnable: Runnable = object : Runnable {
        override fun run() {
            if (refreshInterval > 0) {
                onRefresh()
                viewAdapter.notifyDataSetChanged()
                refreshHandler.postDelayed(this, (refreshInterval * 1000).toLong())
            }
        }
    }

    private var locationCallback: LocationCallback? = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult?) {
            if (locationResult == null) {
                return
            }
            for (location in locationResult.locations) {
                if (location != null) {
                    viewAdapter.setCurrentLocation(location)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        if (arguments != null) {
            route = arguments!!.getParcelable(C.EXTRA.ROUTE_OBJECT)
        }
        if (context == null) return
        PreferenceManager.getDefaultSharedPreferences(context!!)
                .registerOnSharedPreferenceChangeListener(this)
        arrivalTimeDatabase = ArrivalTimeDatabase.getInstance(context!!)!!
        followDatabase = FollowDatabase.getInstance(context!!)!!
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val rootView = inflater.inflate(R.layout.fragment_list, container, false)
        emptyView = rootView.findViewById(R.id.empty_view)
        emptyView.visibility = View.VISIBLE
        progressBar = rootView.findViewById(R.id.progressBar)
        progressBar.visibility = View.VISIBLE
        emptyText = rootView.findViewById(R.id.empty_text)
        emptyText.setText(R.string.message_loading)
        if (fragmentManager == null) return rootView
        if (context != null && ActivityCompat.checkSelfPermission(context!!,
                        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(context!!,
                        Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationServices.getFusedLocationProviderClient(context!!)
                    .lastLocation
                    .addOnSuccessListener { location -> viewAdapter.setCurrentLocation(location) }
        }

        swipeRefreshLayout = rootView.findViewById(R.id.swipe_refresh_layout)
        swipeRefreshLayout.isEnabled = false
        swipeRefreshLayout.setOnRefreshListener(this)

        if (arguments != null) {
            navToStop = arguments!!.getParcelable(C.EXTRA.STOP_OBJECT)
        }
        swipeRefreshLayout.visibility = View.VISIBLE
        swipeRefreshLayout.isRefreshing = false

        if (!loadStopList(route?:Route())) {
            return rootView
        }

        // load route stops from database
        recyclerView = rootView.findViewById(R.id.recycler_view)
        with(recyclerView) {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(context)
            viewAdapter = RouteStopListViewAdapter(activity!!, this, route?:Route())
            adapter = viewAdapter
            viewModel = ViewModelProviders.of(this@RouteStopListFragmentAbstract).get(RouteStopListViewModel::class.java)
            viewModel.getAsLiveData(route?.companyCode?:"", route?.name?:"", route?.sequence?:"", route?.serviceType?:"")
                    .observe(this@RouteStopListFragmentAbstract, Observer<MutableList<RouteStop>> { stops ->
                        if (stops != null) {
                            if (!swipeRefreshLayout.isRefreshing) {
                                swipeRefreshLayout.isRefreshing = true
                            }
                            viewAdapter.addItems(stops, true)
                            var count = 0
                            if (route?.description?.isEmpty() == false) {
                                viewAdapter.addHeader(route?.description?:"")
                                count = 1
                            }
                            stops.forEachIndexed { index, routeStop ->
                                val id = routeStop.companyCode + routeStop.routeNo + routeStop.routeSequence + routeStop.routeServiceType + routeStop.stopId + routeStop.sequence
                                val arrivalTimeLiveData = arrivalTimeDatabase.arrivalTimeDao().getLiveData(routeStop.companyCode?:"", routeStop.routeNo?:"", routeStop.routeSequence?:"", routeStop.stopId?:"", routeStop.sequence?:"")
                                arrivalTimeLiveData.removeObservers(this@RouteStopListFragmentAbstract)
                                arrivalTimeLiveData.observe(this@RouteStopListFragmentAbstract, Observer { etas ->
                                    if (etas != null && id == (routeStop.companyCode + routeStop.routeNo + routeStop.routeSequence + routeStop.routeServiceType + routeStop.stopId + routeStop.sequence)) {
                                        routeStop.etas = listOf()
                                        etas.forEach { eta ->
                                            if (eta.updatedAt > System.currentTimeMillis() - 600000) {
                                                routeStop.etas += eta
                                            }
                                        }
                                        viewAdapter.replaceItem(index + count, routeStop)
                                    }
                                })
                                val followCount = followDatabase.followDao().liveCount(if (routeStop.companyCode == C.PROVIDER.MTR) Follow.TYPE_RAILWAY_STOP else Follow.TYPE_ROUTE_STOP,
                                        routeStop.companyCode?:"", routeStop.routeNo?:"",
                                        routeStop.routeSequence?:"", routeStop.routeServiceType?:"",
                                        routeStop.stopId?:"", routeStop.sequence?:"")
                                followCount.removeObservers(this@RouteStopListFragmentAbstract)
                                followCount.observe(this@RouteStopListFragmentAbstract, Observer {
                                    viewAdapter.notifyItemChanged(index + count)
                                })
                            }

                            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
                            val fab = activity?.findViewById<FloatingActionButton>(R.id.fab)
                            if (preferences.getString("load_eta", "0")?.toInt()?:0 < 1) {
                                fab?.show()
                            }

                            if (emptyView.visibility == View.VISIBLE || recyclerView.visibility == View.GONE) {
                                emptyView.visibility = if (viewAdapter.itemCount > 0) View.GONE else View.VISIBLE

                                var isScrollToPosition: Boolean? = false
                                var scrollToPosition: Int? = 0
                                refreshHandler.post(refreshRunnable)
                                if (viewAdapter.itemCount > 0) {
                                    if (route != null && navToStop != null
                                            && route!!.companyCode != null && route!!.name != null
                                            && route!!.sequence != null && route!!.serviceType != null
                                            && navToStop!!.companyCode != null && navToStop!!.name != null
                                            && navToStop!!.sequence != null && navToStop!!.routeServiceType != null
                                            && route!!.companyCode == navToStop!!.companyCode
                                            && route!!.name == navToStop!!.routeNo
                                            && route!!.sequence == navToStop!!.routeSequence
                                            && route!!.serviceType == navToStop!!.routeServiceType) {
                                        for (i in 0 until viewAdapter.itemCount) {
                                            val item = viewAdapter.getItem(i)?: continue
                                            if (item.type != TYPE_ROUTE_STOP) continue
                                            val stop = item.obj as RouteStop
                                            if (stop.name != null &&
                                                    stop.sequence != null &&
                                                    stop.name == navToStop!!.name &&
                                                    stop.sequence == navToStop!!.sequence) {
                                                scrollToPosition = i
                                                isScrollToPosition = true
                                            }
                                        }
                                    }
                                    if (scrollToPos!! > 0) {
                                        scrollToPosition = scrollToPos
                                        isScrollToPosition = true
                                        scrollToPos = 0
                                    }
                                    recyclerView.visibility = View.VISIBLE
                                    if (isScrollToPosition!!) {
                                        recyclerView.scrollToPosition(scrollToPosition!!)
                                    }
                                    emptyView.visibility = View.GONE
                                    progressBar.visibility = View.GONE
                                }
                            }
                        }
                        if (swipeRefreshLayout.isRefreshing) {
                            swipeRefreshLayout.isRefreshing = false
                        }
                    })
        }

        return rootView
    }

    override fun setUserVisibleHint(isUserVisible: Boolean) {
        super.setUserVisibleHint(isUserVisible)
        initLoadHandler.postDelayed(initLoadRunnable, 10)
    }

    override fun onResume() {
        super.onResume()
        if (context != null) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context!!)
            refreshInterval = preferences.getString("load_eta", "0")?.toInt()?:0
            if (refreshInterval < 1) {
                val fab = activity?.findViewById<FloatingActionButton>(R.id.fab)
                fab?.show()
            }
        }
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val lastFirstVisiblePosition = (recyclerView.layoutManager as LinearLayoutManager)
                .findFirstVisibleItemPosition()
        outState.putInt(SCROLL_POSITION_STATE_KEY, lastFirstVisiblePosition)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (savedInstanceState != null) {
            scrollToPos = savedInstanceState.getInt(SCROLL_POSITION_STATE_KEY, 0)
        }
    }

    override fun onDestroy() {
        if (requestId != null) {
            WorkManager.getInstance().cancelWorkById(requestId!!)
            requestId = null
        }
        adapterUpdateHandler.removeCallbacksAndMessages(null)
        initLoadHandler.removeCallbacksAndMessages(null)
        refreshHandler.removeCallbacksAndMessages(null)
        if (context != null) {
            PreferenceManager.getDefaultSharedPreferences(context!!)
                    .unregisterOnSharedPreferenceChangeListener(this)
        }
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        val id = item!!.itemId
        when (id) {
            R.id.action_notice -> if (route != null) {
                val intent = Intent(context, RouteAnnounceActivity::class.java)
                intent.putExtra(C.EXTRA.ROUTE_OBJECT, route)
                startActivity(intent)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String) {
        if (key == "load_wheelchair_icon" || key == "load_wifi_icon") {
            // to reflect changes when toggle display icon
            if (viewAdapter.itemCount > 0) {
                viewAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onRefresh() {
        swipeRefreshLayout.isRefreshing = false
        if (context != null && viewAdapter.itemCount > 0) {
            // TODO: refresh stop list directly in eta service, instead of sending routestop objects
            val routeStopList = arrayListOf<RouteStop>()
            for (i in 0 until viewAdapter.itemCount) {
                if (viewAdapter.getItem(i)?.type == TYPE_ROUTE_STOP && viewAdapter.getItem(i)?.obj is RouteStop) {
                    routeStopList.add(viewAdapter.getItem(i)?.obj as RouteStop)
                    if (this is AESBusStopListFragment) {
                        break
                    }
                }
            }
            try {
                val intent = Intent(context, EtaService::class.java)
                intent.putParcelableArrayListExtra(C.EXTRA.STOP_LIST, routeStopList)
                context!!.startService(intent)
            } catch (ignored: IllegalStateException) {
            }
        }
    }


    private fun loadStopList(route: Route): Boolean {
        val companyCode = route.companyCode?:""
        when (companyCode) {
            C.PROVIDER.AESBUS, C.PROVIDER.LRTFEEDER, C.PROVIDER.NLB -> {
                initLoadHandler.post(initLoadRunnable)
                return true
            }
            "" -> return false
        }

        val data = Data.Builder()
                .putString(C.EXTRA.COMPANY_CODE, companyCode)
                .putString(C.EXTRA.ROUTE_NO, route.name?:"")
                .putString(C.EXTRA.ROUTE_SEQUENCE, route.sequence?:"")
                .putString(C.EXTRA.ROUTE_SERVICE_TYPE, route.serviceType?:"")
                .putString(C.EXTRA.ROUTE_INFO_KEY, route.infoKey?:"")
                .build()
        val request = when (companyCode) {
            C.PROVIDER.KMB -> {
                if (PreferenceUtil.isUsingNewKmbApi(context!!)) {
                    OneTimeWorkRequest.Builder(KmbStopListWorker::class.java)
                            .setInputData(data)
                            .build()
                } else {
                    OneTimeWorkRequest.Builder(LwbStopListWorker::class.java)
                            .setInputData(data)
                            .build()
                }
            }
            C.PROVIDER.NWST, C.PROVIDER.NWFB, C.PROVIDER.CTB ->
                OneTimeWorkRequest.Builder(NwstStopListWorker::class.java)
                        .setInputData(data)
                        .build()
            else -> return false
        }
        if (!swipeRefreshLayout.isRefreshing) {
            swipeRefreshLayout.isRefreshing = true
        }
        if (requestId != null) {
            WorkManager.getInstance().cancelWorkById(requestId!!)
            requestId = null
        }
        requestId = request.id
        WorkManager.getInstance().enqueue(request)
        WorkManager.getInstance().getWorkInfoByIdLiveData(request.id)
                .observe(this, Observer { workInfo ->
                    if (workInfo?.state == WorkInfo.State.FAILED) {
                        if (!ConnectivityUtil.isConnected(context)) {
                            showEmptyMessage(getString(R.string.message_no_internet_connection))
                        } else {
                            showEmptyMessage(getString(R.string.message_fail_to_request))
                        }
                    }
                    if (workInfo?.state == WorkInfo.State.SUCCEEDED) {
                        onWorkerSucceeded(workInfo.outputData)
                        initLoadHandler.post(initLoadRunnable)
                    }
                })
        return true
    }

    open fun onWorkerSucceeded(workerOutputData: Data) {

    }

    fun onMarkerClick(marker: Marker) {
        if (marker.tag is RouteStop) {
            val markerStop = marker.tag as RouteStop?
            for (i in 0 until viewAdapter.itemCount) {
                if (viewAdapter.getItem(i)?.type == TYPE_ROUTE_STOP && viewAdapter.getItem(i)?.obj is RouteStop) {
                    val routeStop = viewAdapter.getItem(i)?.obj as RouteStop
                    if (routeStop.sequence == markerStop?.sequence) {
                        val smoothScroller = object : LinearSmoothScroller(recyclerView.context) {
                            override fun getVerticalSnapPreference(): Int {
                                return LinearSmoothScroller.SNAP_TO_START
                            }
                        }
                        smoothScroller.targetPosition = i
                        recyclerView.layoutManager?.startSmoothScroll(smoothScroller)
                        break
                    }
                }
            }
        }
    }

    private fun showEmptyMessage(s: String?) {
        if (swipeRefreshLayout.isRefreshing) {
            swipeRefreshLayout.isRefreshing = false
        }
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        emptyText.text = s
    }

    private fun startLocationUpdates() {
        if (context == null || locationCallback == null) return
        if (ActivityCompat.checkSelfPermission(context!!,
                        Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(context!!,
                        Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            val locationRequest = LocationRequest.create()
            locationRequest.interval = 10000
            locationRequest.fastestInterval = (10000 / 2).toLong()
            locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            LocationServices.getFusedLocationProviderClient(context!!)
                    .requestLocationUpdates(locationRequest, locationCallback!!, null/* Looper */)
        }
    }

    private fun stopLocationUpdates() {
        if (context == null || locationCallback == null) return
        LocationServices.getFusedLocationProviderClient(context!!).removeLocationUpdates(locationCallback!!)
    }

    companion object {

        private const val SCROLL_POSITION_STATE_KEY = "SCROLL_POSITION_STATE_KEY"

    }
}