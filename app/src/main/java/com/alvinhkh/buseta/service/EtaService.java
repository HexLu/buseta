package com.alvinhkh.buseta.service;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.alvinhkh.buseta.C;
import com.alvinhkh.buseta.R;
import com.alvinhkh.buseta.arrivaltime.dao.ArrivalTimeDatabase;
import com.alvinhkh.buseta.kmb.KmbService;
import com.alvinhkh.buseta.kmb.model.network.KmbEtaRes;
import com.alvinhkh.buseta.kmb.util.KmbEtaUtil;
import com.alvinhkh.buseta.arrivaltime.model.ArrivalTime;
import com.alvinhkh.buseta.model.Route;
import com.alvinhkh.buseta.model.RouteStop;
import com.alvinhkh.buseta.mtr.MtrService;
import com.alvinhkh.buseta.mtr.model.AESEtaBus;
import com.alvinhkh.buseta.mtr.model.AESEtaBusStop;
import com.alvinhkh.buseta.mtr.model.AESEtaBusRes;
import com.alvinhkh.buseta.mtr.model.AESEtaBusStopsRequest;
import com.alvinhkh.buseta.mtr.model.MtrSchedule;
import com.alvinhkh.buseta.mtr.model.MtrScheduleRes;
import com.alvinhkh.buseta.nlb.NlbService;
import com.alvinhkh.buseta.nlb.model.NlbEtaRequest;
import com.alvinhkh.buseta.nlb.model.NlbEtaRes;
import com.alvinhkh.buseta.nlb.util.NlbEtaUtil;
import com.alvinhkh.buseta.nwst.NwstService;
import com.alvinhkh.buseta.nwst.model.NwstEta;
import com.alvinhkh.buseta.nwst.util.NwstEtaUtil;
import com.alvinhkh.buseta.nwst.util.NwstRequestUtil;
import com.alvinhkh.buseta.utils.ConnectivityUtil;
import com.alvinhkh.buseta.utils.HashUtil;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.observers.DisposableObserver;
import okhttp3.ResponseBody;
import timber.log.Timber;

import static com.alvinhkh.buseta.nwst.NwstService.*;

// TODO: remove previous eta records
public class EtaService extends IntentService {

    private final CompositeDisposable disposables = new CompositeDisposable();

    private ArrivalTimeDatabase arrivalTimeDatabase;

    private final MtrService aesService = MtrService.Companion.getAes().create(MtrService.class);

    private final KmbService kmbEtaApi = KmbService.etav3.create(KmbService.class);

    private final MtrService mtrService = MtrService.Companion.getApi().create(MtrService.class);

    private final NwstService nwstApi = NwstService.api.create(NwstService.class);

    private final NlbService nlbApi = NlbService.api.create(NlbService.class);

    private FirebaseRemoteConfig firebaseRemoteConfig = FirebaseRemoteConfig.getInstance();

    public EtaService() {
        super(EtaService.class.getSimpleName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        arrivalTimeDatabase = ArrivalTimeDatabase.Companion.getInstance(this);
    }

    @Override
    public void onDestroy() {
        disposables.clear();
        super.onDestroy();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        Bundle extras = intent.getExtras();
        if (extras == null) return;

        if (!ConnectivityUtil.isConnected(this)) return;    // network connection check

        int widgetId = extras.getInt(C.EXTRA.WIDGET_UPDATE, -1);
        int notificationId = extras.getInt(C.EXTRA.NOTIFICATION_ID, -1);
        int row = extras.getInt(C.EXTRA.ROW, -1);

        List<RouteStop> routeStopList = extras.getParcelableArrayList(C.EXTRA.STOP_LIST);
        if (routeStopList == null) {
            routeStopList = new ArrayList<>();
        }
        RouteStop stop = extras.getParcelable(C.EXTRA.STOP_OBJECT);
        if (stop != null) {
            routeStopList.add(stop);
        }
        for (int i = 0; i < routeStopList.size(); i++) {
            RouteStop routeStop = routeStopList.get(i);
            if (!TextUtils.isEmpty(routeStop.getCompanyCode())) {
                notifyUpdate(routeStop, C.EXTRA.UPDATING, widgetId, notificationId, row);
                switch (routeStop.getCompanyCode()) {
                    case C.PROVIDER.KMB:
                        disposables.add(kmbEtaApi.getEta(routeStop.getEtaGet())
                                .subscribeWith(kmbEtaObserver(routeStop, widgetId, notificationId, row, i == routeStopList.size() - 1)));
                        break;
                    case C.PROVIDER.NLB:
                        NlbEtaRequest request = new NlbEtaRequest(routeStop.getRouteId(), routeStop.getCode(), "zh");
                        disposables.add(nlbApi.eta(request)
                                .subscribeWith(nlbEtaObserver(routeStop, widgetId, notificationId, row, i == routeStopList.size() - 1)));
                        break;
                    case C.PROVIDER.CTB:
                    case C.PROVIDER.NWFB:
                    case C.PROVIDER.NWST:
                        Map<String, String> options = new HashMap<>();
                        options.put(QUERY_STOP_ID, Integer.toString(Integer.parseInt(routeStop.getCode())));
                        options.put(QUERY_SERVICE_NO, routeStop.getRoute());
                        options.put("removeRepeatedSuspend", "Y");
                        options.put("interval", "60");
                        options.put(QUERY_BOUND, routeStop.getDirection());
                        options.put(QUERY_STOP_SEQ, routeStop.getSequence());
                        options.put(QUERY_RDV, routeStop.getRouteId().replaceAll("-1$", "-2")); // TODO: why -1 to -2
                        options.put("showtime", "Y");
                        options.put(QUERY_LANGUAGE, LANGUAGE_TC);
                        options.put(QUERY_PLATFORM,  PLATFORM);
                        options.put(QUERY_APP_VERSION, APP_VERSION);
                        options.put(QUERY_SYSCODE, NwstRequestUtil.syscode());;
                        disposables.add(nwstApi.eta(options)
                                .subscribeWith(nwstEtaObserver(routeStop, widgetId, notificationId, row, i == routeStopList.size() - 1)));
                        break;
                    case C.PROVIDER.LRTFEEDER:
                        ArrivalTime arrivalTime = ArrivalTime.Companion.emptyInstance(getApplicationContext(), routeStop);
                        arrivalTime.setText(getString(R.string.provider_no_eta));
                        if (arrivalTimeDatabase != null) {
                            arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                        }
                        notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, row);
                        break;
                    case C.PROVIDER.AESBUS:
                    {
                        String key = HashUtil.md5("mtrMobile_" + new SimpleDateFormat("yyyyMMddHHmm", Locale.ENGLISH).format(new Date()));
                        if (!TextUtils.isEmpty(key)) {
                            disposables.add(aesService.getBusStopsDetail(new AESEtaBusStopsRequest(routeStop.getRoute(), "2", "zh", key))
                                    .subscribeWith(aesBusEtaObserver(routeStop, widgetId, notificationId, row, i == routeStopList.size() - 1)));
                        } else {
                            notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, row);
                        }
                        break;
                    }
                    case C.PROVIDER.MTR:
                    {
                        List<Route> routes = extras.getParcelableArrayList(C.EXTRA.ROUTE_LIST);
                        HashMap<String, String> codeMap = new HashMap<>();
                        if (routes != null) {
                            for (Route route: routes) {
                                if (TextUtils.isEmpty(route.getCode()) || TextUtils.isEmpty(route.getName())) continue;
                                codeMap.put(route.getCode(), route.getName());
                            }
                        }
                        String lang = "en";
                        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date());
                        String secret = firebaseRemoteConfig.getString("mtr_schedule_secret");
                        String key = HashUtil.sha1(routeStop.getRouteId() + "|" + routeStop.getCode() + "|" + lang + "|" + today + "|" + secret);
                        if (TextUtils.isEmpty(key) || routeStop == null || TextUtils.isEmpty(routeStop.getRouteId()) || TextUtils.isEmpty(routeStop.getCode())) {
                            notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, row);
                            break;
                        }
                        disposables.add(mtrService.getSchedule(key, routeStop.getRouteId(), routeStop.getCode(), lang)
                                .subscribeWith(mtrScheduleObserver(routeStop, widgetId, notificationId, row, i == routeStopList.size() - 1, codeMap)));
                        break;
                    }
                    default:
                        notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, row);
                        break;
                }
            } else {
                notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, row);
            }
        }
    }

    private void notifyUpdate(@NonNull RouteStop stop, @NonNull String status,
                              Integer widgetId, Integer notificationId, Integer row) {
        Intent intent = new Intent(C.ACTION.ETA_UPDATE);
        intent.putExtra(status, true);
        if (widgetId >= 0) {
            intent.putExtra(C.EXTRA.WIDGET_UPDATE, widgetId);
        }
        if (notificationId >= 0) {
            intent.putExtra(C.EXTRA.NOTIFICATION_ID, notificationId);
        }
        if (row >= 0) {
            intent.putExtra(C.EXTRA.ROW, row);
        }
        intent.putExtra(C.EXTRA.STOP_OBJECT, stop);
        sendBroadcast(intent);
    }

    DisposableObserver<KmbEtaRes> kmbEtaObserver(@NonNull final RouteStop routeStop,
                                                 final Integer widgetId,
                                                 final Integer notificationId,
                                                 final Integer rowNo,
                                                 final Boolean isLast) {
        // put kmb eta data to local eta database, EtaEntry
        return new DisposableObserver<KmbEtaRes>() {
            @Override
            public void onNext(KmbEtaRes res) {
                if (res != null && res.etas != null && res.etas.size() > 0) {
                    for (int i = 0; i < res.etas.size(); i++) {
                        ArrivalTime arrivalTime = KmbEtaUtil.toArrivalTime(getApplicationContext(), res.etas.get(i), res.generated);
                        arrivalTime.setRouteNo(routeStop.getRoute());
                        arrivalTime.setRouteSeq(routeStop.getDirection());
                        arrivalTime.setStopId(routeStop.getCode());
                        arrivalTime.setStopSeq(routeStop.getSequence());
                        arrivalTime.setOrder(Integer.toString(i));
                        if (arrivalTimeDatabase != null) {
                            arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                        }
                    }
                    notifyUpdate(routeStop, C.EXTRA.UPDATED, widgetId, notificationId, rowNo);
                    return;
                }
                ArrivalTime arrivalTime = ArrivalTime.Companion.emptyInstance(getApplicationContext(), routeStop);
                if (arrivalTimeDatabase != null) {
                    arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                }
                notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, rowNo);
            }

            @Override
            public void onError(Throwable e) {
                Timber.d(e);
                ArrivalTime arrivalTime = ArrivalTime.Companion.emptyInstance(getApplicationContext(), routeStop);
                arrivalTime.setText(getString(R.string.message_fail_to_request));
                if (arrivalTimeDatabase != null) {
                    arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                }
                notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, rowNo);
            }

            @Override
            public void onComplete() {
                if (isLast) notifyUpdate(routeStop, C.EXTRA.COMPLETE, widgetId, notificationId, rowNo);
            }
        };
    }

    DisposableObserver<NlbEtaRes> nlbEtaObserver(@NonNull final RouteStop routeStop,
                                                 final Integer widgetId,
                                                 final Integer notificationId,
                                                 final Integer rowNo,
                                                 final Boolean isLast) {
        return new DisposableObserver<NlbEtaRes>() {
            @Override
            public void onNext(NlbEtaRes res) {
                if (res != null && res.estimatedArrivalTime != null && !TextUtils.isEmpty(res.estimatedArrivalTime.html)) {
                    Document doc = Jsoup.parse(res.estimatedArrivalTime.html);
                    Elements divs = doc.body().getElementsByTag("div");
                    if (divs != null && divs.size() > 0) {
                        int s = divs.size();
                        if (s > 1) {
                            s -= 1;
                        }
                        for (int i = 0; i < s; i++) {
                            ArrivalTime arrivalTime = NlbEtaUtil.toArrivalTime(getApplicationContext(), divs.get(i));
                            arrivalTime.setOrder(Integer.toString(i));
                            arrivalTime.setRouteNo(routeStop.getRoute());
                            arrivalTime.setRouteSeq(routeStop.getDirection());
                            arrivalTime.setStopId(routeStop.getCode());
                            arrivalTime.setStopSeq(routeStop.getSequence());
                            if (arrivalTimeDatabase != null) {
                                arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                            }
                        }
                        notifyUpdate(routeStop, C.EXTRA.UPDATED, widgetId, notificationId, rowNo);
                        return;
                    }
                }
                ArrivalTime arrivalTime = ArrivalTime.Companion.emptyInstance(getApplicationContext(), routeStop);
                arrivalTime.setGeneratedAt(System.currentTimeMillis());
                if (arrivalTimeDatabase != null) {
                    arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                }
                notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, rowNo);
            }

            @Override
            public void onError(Throwable e) {
                Timber.d(e);
                ArrivalTime arrivalTime = ArrivalTime.Companion.emptyInstance(getApplicationContext(), routeStop);
                arrivalTime.setText(getString(R.string.message_fail_to_request));
                if (arrivalTimeDatabase != null) {
                    arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                }
                notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, rowNo);
            }

            @Override
            public void onComplete() {
                if (isLast) notifyUpdate(routeStop, C.EXTRA.COMPLETE, widgetId, notificationId, rowNo);
            }
        };
    }

    DisposableObserver<ResponseBody> nwstEtaObserver(@NonNull final RouteStop routeStop,
                                                     final Integer widgetId,
                                                     final Integer notificationId,
                                                     final Integer rowNo,
                                                     final Boolean isLast) {
        return new DisposableObserver<ResponseBody>() {
            @Override
            public void onNext(ResponseBody body) {
                try {
                    String text = body.string();
                    String serverTime = text.split("\\|")[0].trim();
                    String[] data = text.trim().replaceFirst("^[^|]*\\|##\\|", "").split("<br>");
                    for (int i = 0; i < data.length; i++) {
                        NwstEta nwstEta = NwstEta.Companion.fromString(data[i]);
                        if (nwstEta == null) continue;
                        nwstEta.setServerTime(serverTime);
                        ArrivalTime arrivalTime = NwstEtaUtil.toArrivalTime(getApplicationContext(), routeStop, nwstEta);
                        arrivalTime.setCompanyCode(routeStop.getCompanyCode());
                        arrivalTime.setRouteNo(routeStop.getRoute());
                        arrivalTime.setRouteSeq(routeStop.getDirection());
                        arrivalTime.setStopId(routeStop.getCode());
                        arrivalTime.setStopSeq(routeStop.getSequence());
                        arrivalTime.setOrder(Integer.toString(i));
                        if (arrivalTimeDatabase != null) {
                            arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                        }
                    }
                    notifyUpdate(routeStop, C.EXTRA.UPDATED, widgetId, notificationId, rowNo);
                    return;
                } catch (IOException e) {
                    Timber.d(e);
                }
                ArrivalTime arrivalTime = ArrivalTime.Companion.emptyInstance(getApplicationContext(), routeStop);
                arrivalTime.setGeneratedAt(System.currentTimeMillis());
                if (arrivalTimeDatabase != null) {
                    arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                }
                notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, rowNo);
            }

            @Override
            public void onError(Throwable e) {
                Timber.d(e);
                ArrivalTime arrivalTime = ArrivalTime.Companion.emptyInstance(getApplicationContext(), routeStop);
                arrivalTime.setText(getString(R.string.message_fail_to_request));
                if (arrivalTimeDatabase != null) {
                    arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                }
                notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, rowNo);
            }

            @Override
            public void onComplete() {
                if (isLast) notifyUpdate(routeStop, C.EXTRA.COMPLETE, widgetId, notificationId, rowNo);
            }
        };
    }

    DisposableObserver<AESEtaBusRes> aesBusEtaObserver(@NonNull final RouteStop routeStop,
                                                       final Integer widgetId,
                                                       final Integer notificationId,
                                                       final Integer rowNo,
                                                       final Boolean isLast) {
        return new DisposableObserver<AESEtaBusRes>() {

            Boolean isError = false;

            @Override
            public void onNext(AESEtaBusRes res) {
                if (res != null) {
                    if (res.getRouteName() == null || !res.getRouteName().equals(routeStop.getRoute())) return;
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ENGLISH);
                    Date statusTime = new Date();
                    if (res.getRouteStatusTime() != null) {
                        try {
                            statusTime = sdf.parse(res.getRouteStatusTime());
                        } catch (ParseException ignored) { }
                    }
                    Boolean isAvailable = false;
                    List<AESEtaBusStop> etas = res.getBusStops();
                    if (etas != null && etas.size() > 0) {
                        // TODO: better way to store and show aes eta
                        for (int i = 0; i < etas.size(); i++) {
                            AESEtaBusStop eta = etas.get(i);
                            if (eta.getBusStopId() == null) continue;
                            if (!eta.getBusStopId().equals(routeStop.getCode()) && !eta.getBusStopId().equals("999")) continue;
                            if (eta.getBuses() != null && eta.getBuses().size() > 0) {
                                for (int j = 0; j < eta.getBuses().size(); j++) {
                                    isAvailable = true;
                                    AESEtaBus bus = eta.getBuses().get(j);
                                    ArrivalTime arrivalTime = AESEtaBus.Companion.toArrivalTime(getApplicationContext(), bus, statusTime, routeStop);
                                    arrivalTime.setRouteNo(routeStop.getRoute());
                                    arrivalTime.setRouteSeq(routeStop.getDirection());
                                    arrivalTime.setStopId(routeStop.getCode());
                                    arrivalTime.setStopSeq(routeStop.getSequence());
                                    arrivalTime.setOrder(Integer.toString(j));
                                    arrivalTime.setGeneratedAt(statusTime.getTime());
                                    arrivalTime.setUpdatedAt(System.currentTimeMillis());
                                    if (arrivalTimeDatabase != null) {
                                        arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                                    }
                                }
                            }
                            if (isAvailable) {
                                notifyUpdate(routeStop, C.EXTRA.UPDATED, widgetId, notificationId, rowNo);
                            }
                        }
                    }
                    if (!isAvailable) {
                        ArrivalTime arrivalTime = ArrivalTime.Companion.emptyInstance(getApplicationContext(), routeStop);
                        if (!TextUtils.isEmpty(res.getRouteStatusRemarkTitle())) {
                            arrivalTime.setText(res.getRouteStatusRemarkTitle());
                        }
                        arrivalTime.setGeneratedAt(System.currentTimeMillis());
                        if (arrivalTimeDatabase != null) {
                            arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                        }
                        notifyUpdate(routeStop, C.EXTRA.UPDATED, widgetId, notificationId, rowNo);
                    }
                } else {
                    isError = true;
                }
            }

            @Override
            public void onError(Throwable e) {
                isError = true;
                Timber.d(e);
                ArrivalTime arrivalTime = ArrivalTime.Companion.emptyInstance(getApplicationContext(), routeStop);
                arrivalTime.setText(getString(R.string.message_fail_to_request));
                if (arrivalTimeDatabase != null) {
                    arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                }
                notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, rowNo);
            }

            @Override
            public void onComplete() {
                if (!isError) {
                    notifyUpdate(routeStop, C.EXTRA.COMPLETE, widgetId, notificationId, rowNo);
                }
            }
        };
    }

    DisposableObserver<MtrScheduleRes> mtrScheduleObserver(@NonNull RouteStop routeStop,
                                                           Integer widgetId,
                                                           Integer notificationId,
                                                           Integer rowNo,
                                                           Boolean isLast,
                                                           HashMap<String, String> codeMap) {
        return new DisposableObserver<MtrScheduleRes>() {
            Boolean isError = false;

            @Override
            public void onNext(MtrScheduleRes res) {
                if (res == null) return;
                if (res.getStatus() == 0) {
                    ArrivalTime arrivalTime = ArrivalTime.Companion.emptyInstance(getApplicationContext(), routeStop);
                    arrivalTime.setText(getString(R.string.provider_no_eta));
                    if (arrivalTimeDatabase != null) {
                        arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                    }
                    notifyUpdate(routeStop, C.EXTRA.UPDATED, widgetId, notificationId, rowNo);
                    return;
                }
                if (res.getData() != null) {
                    for (Map.Entry<String, MtrScheduleRes.Data> entry : res.getData().entrySet()) {
                        MtrScheduleRes.Data data = entry.getValue();
                        if (data == null) continue;
                        int i = 0;
                        if (data.getUp() != null) {
                            for (MtrSchedule schedule: data.getUp()) {
                                ArrivalTime arrivalTime = MtrSchedule.Companion.toArrivalTime(getApplicationContext(), "UT", schedule, data.getCurrentTime(), codeMap);
                                arrivalTime.setRouteNo(routeStop.getRoute());
                                arrivalTime.setRouteSeq(routeStop.getDirection());
                                arrivalTime.setStopId(routeStop.getCode());
                                arrivalTime.setStopSeq(routeStop.getSequence());
                                arrivalTime.setOrder(String.valueOf(i));
                                if (arrivalTimeDatabase != null) {
                                    arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                                }
                                i++;
                            }
                        }
                        if (data.getDown() != null) {
                            for (MtrSchedule schedule: data.getDown()) {
                                ArrivalTime arrivalTime = MtrSchedule.Companion.toArrivalTime(getApplicationContext(), "DT", schedule, data.getCurrentTime(), codeMap);
                                arrivalTime.setRouteNo(routeStop.getRoute());
                                arrivalTime.setRouteSeq(routeStop.getDirection());
                                arrivalTime.setStopId(routeStop.getCode());
                                arrivalTime.setStopSeq(routeStop.getSequence());
                                arrivalTime.setOrder(String.valueOf(i));
                                if (arrivalTimeDatabase != null) {
                                    arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                                }
                                i++;
                            }
                        }
                    }
                    notifyUpdate(routeStop, C.EXTRA.UPDATED, widgetId, notificationId, rowNo);
                }
            }

            @Override
            public void onError(Throwable e) {
                isError = true;
                Timber.d(e);
                ArrivalTime arrivalTime = ArrivalTime.Companion.emptyInstance(getApplicationContext(), routeStop);
                arrivalTime.setText(getString(R.string.message_fail_to_request));
                if (arrivalTimeDatabase != null) {
                    arrivalTimeDatabase.arrivalTimeDao().insert(arrivalTime);
                }
                notifyUpdate(routeStop, C.EXTRA.FAIL, widgetId, notificationId, rowNo);
            }

            @Override
            public void onComplete() {
                if (!isError) {
                    notifyUpdate(routeStop, C.EXTRA.COMPLETE, widgetId, notificationId, rowNo);
                }
            }
        };
    }
}
