package com.alvinhkh.buseta.follow.ui

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.design.widget.BottomSheetDialogFragment
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import com.alvinhkh.buseta.C
import com.alvinhkh.buseta.R
import com.alvinhkh.buseta.follow.dao.FollowDatabase
import com.alvinhkh.buseta.follow.model.Follow
import com.alvinhkh.buseta.follow.model.FollowGroup
import java.lang.ref.WeakReference

class FollowGroupDialogFragment : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val follow: Follow = arguments?.getParcelable(C.EXTRA.STOP_OBJECT)?:Follow()
        val itemView = inflater.inflate(R.layout.bottom_sheet_route_select, container, false)
        val recyclerView = itemView.findViewById<RecyclerView>(R.id.recycler_view)
        val followDatabase = FollowDatabase.getInstance(context!!)!!
        val viewAdapter = FollowGroupViewAdapter(follow, followDatabase, WeakReference(this))
        with(recyclerView) {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            adapter = viewAdapter
        }
        val viewModel = ViewModelProviders.of(this).get(FollowGroupViewModel::class.java)
        viewModel.getAsLiveData()
                .observe(this, Observer<MutableList<FollowGroup>> { categories ->
                    viewAdapter.replaceItems(categories?: mutableListOf())
                    if (categories?.size?:0 > 0) {
                        // viewAdapter.add(0, FollowGroup("____clear", getString(R.string.action_unfollow)))
                    }
                    viewAdapter.addItem(viewAdapter.itemCount, FollowGroup("____add_new", getString(R.string.add_new_group)))
                })
        return itemView

    }

    companion object {

        fun newInstance(follow: Follow): FollowGroupDialogFragment {
            val fragment = FollowGroupDialogFragment()
            val args = Bundle()
            args.putParcelable(C.EXTRA.STOP_OBJECT, follow)
            fragment.arguments = args
            return fragment
        }
    }
}