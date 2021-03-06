package danielfilho.ufc.br.com.predetect.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.support.annotation.Nullable;
import android.util.Log;

import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.file.FilePrinter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import danielfilho.ufc.br.com.predetect.constants.PredectConstants;
import danielfilho.ufc.br.com.predetect.datas.WiFiBundle;
import danielfilho.ufc.br.com.predetect.datas.WiFiData;
import danielfilho.ufc.br.com.predetect.managers.NetworkManager;
import danielfilho.ufc.br.com.predetect.managers.ParceableManager;
import danielfilho.ufc.br.com.predetect.receivers.NetworkReceiver;
import danielfilho.ufc.br.com.predetect.receivers.ObservingReceiver;

/**
 * Created by Daniel Filho on 5/31/16.
 */
public class NetworkObserverService extends Service implements Runnable{

    public static final int SERVICE_SUCCESS = 1;
    public static final int SERVICE_FAIL = 2;
    public static final int SERVICE_NO_WIFI = 3;


    private WifiManager wifiManager;
    private WiFiBundle wiFiBundle;
    private NetworkManager networkManager;

    private ResultReceiver networkResultReceiver;

    private Intent wakefulIntent;

    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        XLog.d("-------- SERVICE ON CREATE -------");
        Log.d(PredectConstants.LOG_TAG, "-------- SERVICE ON CREATE -------");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        try {
            XLog.init(LogLevel.ALL, new FilePrinter.Builder(PredectConstants.LOG_PATH+"predetect").build());
        }catch (Exception e){};


        this.powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        this.wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBSERVING_WAKE_LOCK");
        this.wakeLock.acquire();

        this.networkManager = NetworkManager.getInstance();
        this.networkManager.holdWifiLock(this);

        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

        try {
            XLog.init(LogLevel.ALL, new FilePrinter.Builder(PredectConstants.LOG_PATH).build());
        }catch (Exception e){};


        Log.d(PredectConstants.LOG_TAG, "--------- CREATING THE SERVICE ---------");
        XLog.d("--------- CREATING THE SERVICE ---------");

        initBundle(intent);

        this.wakefulIntent = intent;

        return START_REDELIVER_INTENT;

    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        this.networkManager.releaseWifiLock();
        this.wakeLock.release();
    }

    private void initBundle(Intent intent){
        try {

            wiFiBundle = ParceableManager.toParcelable(intent.getByteArrayExtra(PredectConstants.WIFI_BUNDLE), WiFiBundle.CREATOR);
            networkResultReceiver = intent.getParcelableExtra(PredectConstants.RESULT_RECEIVER);
            if(wiFiBundle != null) {
                new Thread(this).start();
                Log.d(PredectConstants.LOG_TAG, "--------- SERVICE STARTED ---------");
                XLog.d(System.currentTimeMillis()+"|  --------- SERVICE STARTED ---------");
            }else{
                Log.d(PredectConstants.LOG_TAG, "--------- SERVICE START ERROR: WiFi Bundle is NULL ---------");
                XLog.d(System.currentTimeMillis()+"|  --------- SERVICE START ERROR: WiFi Bundle is NULL ---------");
                if(networkResultReceiver != null){
                    networkResultReceiver.send(SERVICE_FAIL, null);
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private HashSet<WiFiData> removeReplicatedWifi(List<ScanResult> scanResults){
        HashSet<WiFiData> hashWifi = new HashSet<>();
        for(ScanResult sr : scanResults){
            WiFiData temp = new WiFiData(sr.BSSID);
            if(!hashWifi.contains(temp)) {
                double resultDistance = NetworkManager.rssiToDistance(sr.level);
                WiFiData data = new WiFiData(sr.BSSID, sr.level, resultDistance, sr.SSID);
                XLog.d(sr.BSSID+","+ sr.SSID+","+sr.level+","+resultDistance);
                hashWifi.add(data);
            }else{
//                Log.d(PredectConstants.LOG_TAG, "--------- There's one WiFi duplicated on WiFiManager ScanResults ---------");
            }
        }
        return hashWifi;
    }

    @Override
    public void run() {

        XLog.d("#################################################################################");

        int observedTime = 0;

        int timeInSeconds = wiFiBundle.getObserveTime() / 60000;

        List<WiFiData> wiFiDatas = new ArrayList<>();

        for (String MAC : wiFiBundle.getWifiData()) {
            wiFiDatas.add(new WiFiData(MAC));
        }

        while(observedTime < timeInSeconds) {
            /*
                If there's an AP with 2 antennas
                there will be 2 networks with same MAC on scanResults list.

                Removing replicated MACs
             */
            HashSet<WiFiData> wiFiDataCollection = removeReplicatedWifi(wifiManager.getScanResults());

            if(wiFiDataCollection.size() > 0){
                Iterator<WiFiData> iterator = wiFiDataCollection.iterator();
                while(iterator.hasNext()){
                    WiFiData wifiScan = iterator.next();

                    for (WiFiData wiFiData : wiFiDatas) {
                        if (wiFiData.getMAC().equals(wifiScan.getMAC()) && wiFiBundle.getDistanceRange() >= wifiScan.getDistance()) {

                            double percent = (wiFiData.getObserveCount() * 100) / timeInSeconds;
                            wiFiData.setPercent(percent);

                            int newAppear = wiFiData.getObserveCount();
                            wiFiData.setObserveCount(++newAppear);
                            Log.d(PredectConstants.LOG_TAG, "PERCENT: "+percent);
                        }
                    }

                }
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    Log.e(PredectConstants.LOG_TAG, PredectConstants.SLEEP_ERROR);

                    if(networkResultReceiver != null)
                        networkResultReceiver.send(SERVICE_FAIL, null);
                }

                observedTime++;

            }else{
                if(networkResultReceiver != null)
                    networkResultReceiver.send(SERVICE_NO_WIFI, null);
                //If there's no WIFI on ScanResults, stopping service.
                stopSelf();
            }

        }
        XLog.d(System.currentTimeMillis()+"|  --------- SERVICE ENDS ---------");
        Log.d(PredectConstants.LOG_TAG, "--------- SERVICE ENDS ---------");

        /*
            Seding the result for the result receiver
            telling that network observing end
         */
        Bundle bundle = new Bundle();
        bundle.putParcelableArrayList(PredectConstants.WIFI_SCANNED, (ArrayList<? extends Parcelable>) wiFiDatas);
        
        if(networkResultReceiver != null)
            networkResultReceiver.send(SERVICE_SUCCESS, bundle);

        //Send the intent for the broadcasts receivers
        Intent intent = new Intent(PredectConstants.ACTION_OBSERVING_ENDS);
        intent.putExtra(PredectConstants.BUNDLE_FINISH_OBSERVING, bundle);
        sendBroadcast(intent);


        ObservingReceiver.completeWakefulIntent(wakefulIntent);

        stopSelf();
    }






}
