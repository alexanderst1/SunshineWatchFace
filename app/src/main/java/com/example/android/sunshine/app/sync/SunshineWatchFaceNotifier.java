package com.example.android.sunshine.app.sync;

import android.content.Context;
import android.os.Bundle;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.example.android.sunshine.app.shared.Constants;
import android.util.Log;

/**
 * Created by Alexander on 4/8/2016.
 */
public class SunshineWatchFaceNotifier implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    private final String TAG = "ShWatchFaceNotifier";
    private GoogleApiClient mGoogleApiClient;

    private int mWeatherId;
    private double mLowTemperature;
    private double mHighTemperature;

    public SunshineWatchFaceNotifier(Context context) {
        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
    }

    public void notifyWatchFace(int weatherId, double highTemperature, double lowTemperature) {
        if (mGoogleApiClient == null) return;
        mWeatherId = weatherId;
        mHighTemperature = highTemperature;
        mLowTemperature = lowTemperature;
//        if (mGoogleApiClient.isConnected())
//            putWeatherDataItem();
//        else
            mGoogleApiClient.connect();
    }
    @Override //ConnectionCallbacks
    public void onConnected(Bundle connectionHint) {
        Log.d(TAG, "onConnected: " + connectionHint);
        putWeatherDataItem();
    }

    private void putWeatherDataItem() {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(Constants.WEATHER_PATH);
        DataMap dataMap = putDataMapReq.getDataMap();
        dataMap.putInt(Constants.WEATHER_ID_KEY, mWeatherId);
        dataMap.putDouble(Constants.LOW_TEMPERATURE_KEY, mLowTemperature);
        dataMap.putDouble(Constants.HIGH_TEMPERATURE_KEY, mHighTemperature);
        dataMap.putLong("counter", System.currentTimeMillis());
        putDataMapReq.setUrgent();
        PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.DataApi
                .putDataItem(mGoogleApiClient, putDataReq)
                .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
            @Override
            public void onResult(final DataApi.DataItemResult result) {
                String msg = "Successfully ";
                if (!result.getStatus().isSuccess()) msg = "Failed to ";
                Log.d(TAG, msg + " set data item set: " + result.getDataItem().getUri());
                mGoogleApiClient.disconnect();
            }
        });
    }

    @Override //ConnectionCallbacks
    public void onConnectionSuspended(int cause) {
        Log.d(TAG, "Connection to Google API client was suspended");
    }

    @Override //OnConnectionFailedListener
    public void onConnectionFailed(ConnectionResult result) {
        Log.d(TAG, "onConnectionFailed: " + result);
        if (result.getErrorCode() == ConnectionResult.API_UNAVAILABLE) {
            // The Wearable API is unavailable
        }
    }
}
