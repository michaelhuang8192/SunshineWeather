package com.example.android.sunshine.app.wear;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class MyWearService extends WearableListenerService {

    final static String PATH_SYNC_WEATHER = "/sync-weather";

    public MyWearService() {

    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);

        if(messageEvent.getPath().equals(PATH_SYNC_WEATHER)) {
            Log.i("PKT", "Received Update Request From Wear");

            new WearSync(getApplicationContext()).sync(true);
        }
    }
}
