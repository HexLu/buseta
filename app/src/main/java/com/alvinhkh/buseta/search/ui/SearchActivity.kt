package com.alvinhkh.buseta.search.ui


import android.app.SearchManager
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.inputmethod.EditorInfo
import android.widget.TextView

import com.alvinhkh.buseta.C
import com.alvinhkh.buseta.R
import com.alvinhkh.buseta.kmb.model.KmbAppIntentData
import com.alvinhkh.buseta.mtr.ui.MtrBusActivity
import com.alvinhkh.buseta.kmb.ui.KmbActivity
import com.alvinhkh.buseta.lwb.ui.LwbActivity
import com.alvinhkh.buseta.route.model.RouteStop
import com.alvinhkh.buseta.mtr.ui.AESBusActivity
import com.alvinhkh.buseta.mtr.ui.MtrStationActivity
import com.alvinhkh.buseta.nlb.ui.NlbActivity
import com.alvinhkh.buseta.nwst.ui.NwstActivity
import com.alvinhkh.buseta.route.model.Route
import com.alvinhkh.buseta.search.dao.SuggestionDatabase
import com.alvinhkh.buseta.search.model.Suggestion
import com.alvinhkh.buseta.ui.PinnedHeaderItemDecoration
import com.alvinhkh.buseta.utils.PreferenceUtil
import com.google.gson.Gson
import kotlinx.android.synthetic.main.activity_suggestion.*

import java.util.regex.Pattern


class SearchActivity : AppCompatActivity() {

    private lateinit var suggestionDatabase: SuggestionDatabase

    private lateinit var viewModel: SuggestionViewModel
    private lateinit var viewAdapter: SuggestionViewAdapter

    private var isOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        suggestionDatabase = SuggestionDatabase.getInstance(this)!!
        setContentView(R.layout.activity_suggestion)

        val listener = object: SuggestionViewAdapter.OnItemClickListener {
            override fun onClick(suggestion: Suggestion?) {
                if (suggestion == null) return
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setClass(applicationContext, SearchActivity::class.java)
                intent.putExtra(C.EXTRA.ROUTE_NO, suggestion.route)
                intent.putExtra(C.EXTRA.COMPANY_CODE, suggestion.companyCode)
                startActivity(intent)
                finish()
            }

            override fun onLongClick(suggestion: Suggestion?) {
            }
        }
        viewModel = ViewModelProviders.of(this).get(SuggestionViewModel::class.java)
        with(recycler_view) {
            addItemDecoration(PinnedHeaderItemDecoration())
            layoutManager = LinearLayoutManager(context)
            viewAdapter = SuggestionViewAdapter(context, null, listener)
            adapter = viewAdapter
        }

        val newIntent = handleIntent(intent)
        if (!newIntent.hasExtra(SearchManager.QUERY)) {
            try {
                startActivity(newIntent)
                finish()
                return
            } catch (ignored: ActivityNotFoundException) { }
        } else {
            intent.putExtra(SearchManager.QUERY, newIntent.getStringExtra(SearchManager.QUERY))
        }

        val query = intent.getStringExtra(SearchManager.QUERY)
        loadSearchResult(query?:"", true)
        with(search_et) {
            val action = intent.action
            if (Intent.ACTION_SEARCH == action && !query.isNullOrEmpty()) {
                text.clear()
                text.insert(0, query)
            }
            setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val t = text?.replace("[^a-zA-Z0-9]*".toRegex(), "")?.toUpperCase()
                    loadSearchResult(t?:"", true)
                    return@OnEditorActionListener true
                }
                false
            })
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(p0: Editable?) {
                    val t = p0?.replace("[^a-zA-Z0-9]*".toRegex(), "")?.toUpperCase()
                    loadSearchResult("$t%", false)
                }

                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                }

                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                }
            })
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        try {
            startActivity(handleIntent(intent))
            finish()
            return
        } catch (ignored: ActivityNotFoundException) {}
        if (intent.action == Intent.ACTION_SEARCH) {
            loadSearchResult(intent.getStringExtra(SearchManager.QUERY)?:"", true)
        }
    }

    private fun loadSearchResult(route: String, singleWillOpen: Boolean) {
        var lastCompanyCode = ""
        viewModel.getAsLiveData(route).observe(this@SearchActivity, Observer { list ->
            viewAdapter.clear()
            val routeNo = route.replace(Regex("[^a-zA-Z0-9 ]"), "")
            val shownCompanyCode = arrayListOf<String>()
            if (list?.size?:0 > 0) {
                if (list?.size?:0 == 1 && !list?.get(0)?.companyCode.isNullOrEmpty() && singleWillOpen && !isOpened) {
                    isOpened = true
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setClass(applicationContext, SearchActivity::class.java)
                    intent.putExtra(C.EXTRA.ROUTE_NO, routeNo)
                    intent.putExtra(C.EXTRA.COMPANY_CODE, list?.get(0)?.companyCode?:"")
                    startActivity(intent)
                    finish()
                } else {
                    list?.forEach { suggestion ->
                        if (lastCompanyCode != suggestion.companyCode) {
                            if (lastCompanyCode.isNotBlank() && routeNo.isNotBlank()) {
                                viewAdapter.addButton(Suggestion(0, lastCompanyCode, routeNo, 0, Suggestion.TYPE_DEFAULT))
                            }
                            val companyName = Route.companyName(applicationContext, suggestion.companyCode, suggestion.route)
                            viewAdapter.addSection(companyName)
                            shownCompanyCode.add(suggestion.companyCode)
                        }
                        viewAdapter.addItem(suggestion)
                        lastCompanyCode = suggestion.companyCode
                    }
                }
            }
            if (routeNo.isNotBlank()) {
                listOf(C.PROVIDER.CTB, C.PROVIDER.KMB, C.PROVIDER.NLB, C.PROVIDER.NWFB).forEach {
                    if (!shownCompanyCode.contains(it)) {
                        val companyName = Route.companyName(applicationContext, it, route.replace(Regex("[^a-zA-Z0-9]"), ""))
                        viewAdapter.addSection(companyName)
                        viewAdapter.addSection(getString(R.string.no_search_result))
                        viewAdapter.addButton(Suggestion(0, it, route.replace(Regex("[^a-zA-Z0-9]"), ""), 0, Suggestion.TYPE_DEFAULT))
                    }
                }
            }
        })
    }

    private fun providerIntent(companyCode: String): Intent {
        var intent: Intent
        when (companyCode) {
            C.PROVIDER.AESBUS -> intent = Intent(applicationContext, AESBusActivity::class.java)
            C.PROVIDER.CTB, C.PROVIDER.NWFB, C.PROVIDER.NWST -> intent = Intent(applicationContext, NwstActivity::class.java)
            C.PROVIDER.LRTFEEDER -> intent = Intent(applicationContext, MtrBusActivity::class.java)
            C.PROVIDER.MTR -> intent = Intent(applicationContext, MtrStationActivity::class.java)
            C.PROVIDER.NLB -> intent = Intent(applicationContext, NlbActivity::class.java)
            C.PROVIDER.KMB -> {
                intent = Intent(applicationContext, LwbActivity::class.java)
                if (PreferenceUtil.isUsingNewKmbApi(applicationContext)) {
                    intent = Intent(applicationContext, KmbActivity::class.java)
                }
            }
            else -> {
                intent = Intent(applicationContext, LwbActivity::class.java)
                if (PreferenceUtil.isUsingNewKmbApi(applicationContext)) {
                    intent = Intent(applicationContext, KmbActivity::class.java)
                }
            }
        }
        intent.putExtra(C.EXTRA.COMPANY_CODE, companyCode)
        return intent
    }

    private fun handleIntent(intent: Intent): Intent {
        val action = intent.action
        val data = intent.dataString

        if (Intent.ACTION_SEARCH == action) {
            val query = intent.getStringExtra(SearchManager.QUERY)
            if (!query.isNullOrEmpty()) {
                val company = intent.getStringExtra(C.EXTRA.COMPANY_CODE)
                if (!company.isNullOrEmpty()) {
                    val i = providerIntent(company)
                    i.putExtra(C.EXTRA.ROUTE_NO, query)
                    return i
                }
            }
        }

        if (Intent.ACTION_VIEW == action) {
            var routeStop: RouteStop? = intent.getParcelableExtra(C.EXTRA.STOP_OBJECT)
            val stopText = intent.getStringExtra(C.EXTRA.STOP_OBJECT_STRING)
            val company = intent.getStringExtra(C.EXTRA.COMPANY_CODE)
            val routeNo = intent.getStringExtra(C.EXTRA.ROUTE_NO)
            if (routeStop == null && !stopText.isNullOrEmpty()) {
                routeStop = Gson().fromJson(stopText, RouteStop::class.java)
            }
            if (routeStop != null) {
                val i = providerIntent(routeStop.companyCode!!)
                i.putExtra(C.EXTRA.ROUTE_NO, routeStop.routeNo)
                i.putExtra(C.EXTRA.STOP_OBJECT, routeStop)
                return i
            } else if (!routeNo.isNullOrEmpty() && !company.isNullOrEmpty()) {
                val i = providerIntent(company)
                i.putExtra(C.EXTRA.ROUTE_NO, routeNo)
                return i
            } else if (intent.data?.scheme == "app1933") {
                if (!data.isNullOrEmpty()) {
                    val decodedStr = String(Base64.decode(intent.data?.pathSegments?.get(0), Base64.DEFAULT))
                    val gson = Gson()
                    val intentData = gson.fromJson(decodedStr, KmbAppIntentData::class.java)
                    if (intentData != null) {
                        val i = providerIntent(C.PROVIDER.KMB)
                        i.putExtra(C.EXTRA.ROUTE_NO, intentData.route)
                        i.putExtra(C.EXTRA.ROUTE_SEQUENCE, intentData.bound)
                        i.putExtra(C.EXTRA.ROUTE_SERVICE_TYPE, intentData.serviceType)
                        i.putExtra(C.EXTRA.STOP_ID, intentData.stopCode)
                        val stopObject = RouteStop()
                        stopObject.companyCode = C.PROVIDER.KMB
                        stopObject.routeNo = intentData.route
                        stopObject.routeSequence = intentData.bound
                        stopObject.routeServiceType = intentData.serviceType
                        stopObject.stopId = intentData.stopCode
                        i.putExtra(C.EXTRA.STOP_OBJECT, stopObject)
                        return i
                    }
                }
                if (intent.resolveActivity(packageManager) != null) {
                    return intent
                }
            } else if (intent.data?.host == "search.kmb.hk") {
                if (intent.data?.getQueryParameter("action") == "routesearch") {
                    val i = providerIntent(C.PROVIDER.KMB)
                    i.putExtra(C.EXTRA.ROUTE_NO, intent.data?.getQueryParameter("route"))
                    return i
                }
                // let browser handle unknown urls
                val uri = Uri.parse("googlechrome://navigate?url=${intent.dataString}")
                val i = Intent(Intent.ACTION_VIEW, uri)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return i
            } else if (intent.data?.host == "mobile.nwstbus.com.hk") {
                if (intent.data?.getQueryParameter("action") == "ETA") {
                    val companyCode = intent.data?.getQueryParameter("compcode")?:C.PROVIDER.NWST
                    val serviceNo = intent.data?.getQueryParameter("serviceno")?:""
                    val stopId = intent.data?.getQueryParameter("stopid")?:""
                    if (serviceNo.isNotEmpty()) {
                        val i = providerIntent(companyCode)
                        i.putExtra(C.EXTRA.ROUTE_NO, serviceNo)
                        i.putExtra(C.EXTRA.STOP_ID, stopId)
                        return i
                    }
                }
                if (!intent.data?.getQueryParameter("ds").isNullOrEmpty()) {
                    val i = Intent(Intent.ACTION_SEARCH)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    i.setClass(this, SearchActivity::class.java)
                    i.putExtra(SearchManager.QUERY, intent.data?.getQueryParameter("ds")?:"")
                    return i
                }
                if (intent.data?.host?.startsWith("http") == true) {
                    // let browser handle unknown urls
                    val uri = Uri.parse("googlechrome://navigate?url=${intent.dataString}")
                    val i = Intent(Intent.ACTION_VIEW, uri)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    return i
                }
            } else if (!data.isNullOrEmpty()) {
                val lastQuery: String?
                val regex = "/route/(.*)/?"
                val regexPattern = Pattern.compile(regex)
                val match = regexPattern.matcher(data)
                if (match.find()) {
                    lastQuery = match.group(1)
                } else {
                    lastQuery = data.substring(data.lastIndexOf("/") + 1)
                }
                val i = providerIntent("")
                i.putExtra(C.EXTRA.ROUTE_NO, lastQuery)
                return i
            }
        }
        return Intent()
    }
}
