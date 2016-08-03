package com.example.android.sunshine.app.wear;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

public class WearSync implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    public static final String LOG_TAG = WearSync.class.getSimpleName();
    final static String PATH_WEATHER_TODAY = "/weather-today";

    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
    };

    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    private static Long sLastTs = 0L;

    private GoogleApiClient mGoogleApiClient;
    private DataMap mDataMap;
    private Context mContext;

    public WearSync(Context ctx) {
        mContext = ctx;

        mGoogleApiClient = new GoogleApiClient.Builder(ctx)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
    }

    public void sync(boolean forceUpdate) {
        Log.i(LOG_TAG, "Send Data To Wear");

        Context context = mContext;
        String locationQuery = Utility.getPreferredLocation(context);

        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());
        Cursor cursor = context.getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);

        if(!cursor.moveToFirst()) return;

        if(forceUpdate) sLastTs = System.currentTimeMillis();

        mDataMap = new DataMap();
        mDataMap.putLong("ts", sLastTs);
        mDataMap.putInt("weatherId", cursor.getInt(INDEX_WEATHER_ID));
        mDataMap.putString("highTemp", Utility.formatTemperature(context, cursor.getDouble(INDEX_MAX_TEMP)));
        mDataMap.putString("lowTemp", Utility.formatTemperature(context, cursor.getDouble(INDEX_MIN_TEMP)));

        mGoogleApiClient.connect();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_WEATHER_TODAY);
        //putDataMapReq.getDataMap().putLong("ts", System.currentTimeMillis());
        putDataMapReq.getDataMap().putAll(mDataMap);
        PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.DataApi.putDataItem(mGoogleApiClient, putDataReq).setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
            @Override
            public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                if(!dataItemResult.getStatus().isSuccess()) {
                    Log.i(LOG_TAG, "WearSync -> onConnected -> putDataItem Failed");
                }
                mGoogleApiClient.disconnect();
                mGoogleApiClient = null;
            }
        });
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.i(LOG_TAG, "WearSync -> onConnectionFailed " + connectionResult);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.i(LOG_TAG, "WearSync -> onConnectionSuspended " + i);
    }
}
