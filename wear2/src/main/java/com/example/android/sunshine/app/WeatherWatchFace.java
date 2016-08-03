/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.tinyappsdev.sharedres.SharedRes;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherWatchFace extends CanvasWatchFaceService {
    private static final String TAG = WeatherWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    final static String PATH_WEATHER_TODAY = "/weather-today";
    final static String PATH_SYNC_WEATHER = "/sync-weather";

    final static int DEFAULT_SURFACE_WIDTH = 320;
    final static int DEFAULT_SURFACE_HEIGHT = 320;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(WeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        float mAdjustmentMiddleOffsetY;
        float mNormalDeviceCenterYAdjustment;
        float mSmallDeviceCenterYAdjustment;

        float mTimeMidRelOffsetY;
        float mDateMidRelOffsetY;

        float mWeatherGap;
        float mWeatherOffsetY;
        float mWeatherFontSize;

        Paint mTimeTextPaint;
        Paint mAMPMTextPaint;
        Paint mDateTextPaint;
        Paint mLowTempTextPaint;
        Paint mHighTempTextPaint;

        int mBackgroundColor;

        int mSurfaceWidth = DEFAULT_SURFACE_WIDTH;
        int mSurfaceHeight = DEFAULT_SURFACE_HEIGHT;

        Bitmap mActiveBg;
        Bitmap mWeatherIcon;

        int mTextColor1;
        int mTextColor2;

        boolean mLowBitAmbient;

        GoogleApiClient mGoogleApiClient;
        DataMap mWeatherDataMap;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.RIGHT)
                    .build());
            Resources resources = WeatherWatchFace.this.getResources();

            mTimeMidRelOffsetY = resources.getDimension(R.dimen.time_midrel_offset_y);
            mDateMidRelOffsetY = resources.getDimension(R.dimen.date_midrel_offset_y);

            mNormalDeviceCenterYAdjustment = resources.getDimension(R.dimen.normal_device_center_y_adjustment);
            mSmallDeviceCenterYAdjustment = resources.getDimension(R.dimen.small_device_center_y_adjustment);

            mBackgroundColor = resources.getColor(R.color.background);
            mTextColor1 = resources.getColor(R.color.text1);
            mTextColor2 = resources.getColor(R.color.text2);

            mTimeTextPaint = createTextPaint(mTextColor1, resources.getDimension(R.dimen.time_text_size));
            mAMPMTextPaint = createTextPaint(mTextColor1, resources.getDimension(R.dimen.ampm_text_size));
            mDateTextPaint = createTextPaint(mTextColor2, resources.getDimension(R.dimen.date_text_size));

            mWeatherFontSize = resources.getDimension(R.dimen.weather_text_size);
            mLowTempTextPaint = createTextPaint(mTextColor2, mWeatherFontSize);
            mHighTempTextPaint = createTextPaint(mTextColor1, mWeatherFontSize);

            mWeatherGap = resources.getDimension(R.dimen.weather_gap);
            mWeatherOffsetY = DEFAULT_SURFACE_HEIGHT / 2 + resources.getDimension(R.dimen.weather_midrel_offset_y);

            mTime = new Time();

            mGoogleApiClient= new GoogleApiClient.Builder(WeatherWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        }

        public Bitmap loadBitmapFromWeatherId(int weatherId) {
            Resources resources = WeatherWatchFace.this.getResources();
            return ((BitmapDrawable)resources.getDrawable(
                    SharedRes.getIconResourceForWeatherCondition(weatherId)
                    , null)).getBitmap();
        }

        public float dpFromPx(float dp) {
            return dp * WeatherWatchFace.this.getResources().getDisplayMetrics().density;
        }

        protected void drawBg() {
            if(mWeatherDataMap == null)
                mActiveBg = null;
            else {
                mWeatherIcon = loadBitmapFromWeatherId(mWeatherDataMap.getInt("weatherId"));
                _drawBg(
                        mWeatherIcon,
                        mWeatherDataMap.getString("highTemp"),
                        mWeatherDataMap.getString("lowTemp")
                );
            }
        }

        protected void _drawBg(Bitmap weatherIcon, String highTemp, String lowTemp) {
            Bitmap bg = Bitmap.createBitmap(DEFAULT_SURFACE_WIDTH, DEFAULT_SURFACE_HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bg);
            canvas.drawColor(mBackgroundColor);

            float width = weatherIcon.getWidth() + mWeatherGap;

            float highTempOffsetX = width;
            width += mHighTempTextPaint.measureText(highTemp) + mWeatherGap;

            float lowTempOffsetX = width;
            width += mLowTempTextPaint.measureText(lowTemp);

            float offsetX = (DEFAULT_SURFACE_WIDTH - width) / 2;
            canvas.drawBitmap(weatherIcon, offsetX, mWeatherOffsetY + mAdjustmentMiddleOffsetY, null);

            //vertical alignment
            float offsetY = mAdjustmentMiddleOffsetY + mWeatherOffsetY + weatherIcon.getHeight() / 2
                    + (mHighTempTextPaint.descent() - mHighTempTextPaint.ascent()) / 2
                    - mHighTempTextPaint.descent();

            canvas.drawText(highTemp, offsetX + highTempOffsetX, offsetY, mHighTempTextPaint);
            canvas.drawText(lowTemp, offsetX + lowTempOffsetX, offsetY, mLowTempTextPaint);

            float lineWidth = dpFromPx(50);
            float startX = (DEFAULT_SURFACE_WIDTH - lineWidth) / 2;
            float startY = mAdjustmentMiddleOffsetY + DEFAULT_SURFACE_HEIGHT / 2;
            canvas.drawLine(startX, startY, startX + lineWidth, startY, mLowTempTextPaint);

            mActiveBg = scaleBg(bg);
        }

        protected Bitmap scaleBg(Bitmap bg) {
            if(bg.getWidth() != mSurfaceWidth || bg.getHeight() != mSurfaceHeight) {
                Log.i(TAG, String.format("scaleBg %s x %s -> %s x %s",
                        bg.getWidth(), bg.getHeight(),
                        mSurfaceWidth, mSurfaceHeight
                ));
                return Bitmap.createScaledBitmap(bg, mSurfaceWidth, mSurfaceHeight, true);
            }
            return bg;
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            //Wearable.NodeApi.addListener(mGoogleApiClient, Engine.this);

            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                @Override
                public void onResult(@NonNull NodeApi.GetConnectedNodesResult result) {
                    if(result.getStatus().isSuccess() && result.getNodes().size() > 0 && mGoogleApiClient.isConnected()) {
                        Log.i(TAG, "Send Message For Requesting Update");
                        Wearable.MessageApi.sendMessage(
                                mGoogleApiClient,
                                result.getNodes().get(0).getId(),
                                PATH_SYNC_WEATHER,
                                null
                        );
                    }
                }
            });

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.i(TAG, "onConnectionFailed: " + connectionResult);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.i(TAG, "onConnectionSuspended: " + i);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if(event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if(item.getUri().getPath().equals(PATH_WEATHER_TODAY)) {
                        Log.i(TAG, "Received Data Update From HandHeld ");
                        mWeatherDataMap = DataMapItem.fromDataItem(item).getDataMap();
                        mActiveBg = null;
                        invalidate();
                    }
                }
            }

        }

        protected void disconnectGoogleApiClient() {
            if (mGoogleApiClient != null) {
                Log.i(TAG, "disconnectGoogleApiClient");
                mWeatherDataMap = null;
                mActiveBg = null;
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, float textSize) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextSize(textSize);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                mGoogleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();

                disconnectGoogleApiClient();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            mSurfaceWidth = width;
            mSurfaceHeight = height;

            if(height <= 280)
                mAdjustmentMiddleOffsetY = mSmallDeviceCenterYAdjustment;
            else
                mAdjustmentMiddleOffsetY = mNormalDeviceCenterYAdjustment;

            if(mActiveBg == null || mActiveBg.getWidth() != width || mActiveBg.getHeight() != height)
                mActiveBg = null;

            super.onSurfaceChanged(holder, format, width, height);
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);
                    mAMPMTextPaint.setAntiAlias(!inAmbientMode);
                    mDateTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = WeatherWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    break;
            }
            //invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            float width;
            String text;
            float offset;

            if(mAmbient)
                canvas.drawColor(Color.BLACK);
            else {
                if(mActiveBg == null)
                    drawBg();

                if(mActiveBg == null)
                    canvas.drawColor(mBackgroundColor);
                else
                    canvas.drawBitmap(mActiveBg, 0, 0, null);
            }

            float middleOffsetY = mSurfaceHeight / 2 + mAdjustmentMiddleOffsetY;
            float timeOffsetY = middleOffsetY - mTimeMidRelOffsetY - mTimeTextPaint.descent();

            mTime.setToNow();
            text = (mAmbient ? mTime.format("%l:%M") : mTime.format("%l:%M:%S")).trim();
            width = mTimeTextPaint.measureText(text);
            offset = (mSurfaceWidth - width) / 2;
            canvas.drawText(text, offset, timeOffsetY, mTimeTextPaint);

            offset += width;
            canvas.drawText(mTime.format("%p"), offset, timeOffsetY, mAMPMTextPaint);

            if(mAmbient) return;

            float dateOffsetY = middleOffsetY - mDateMidRelOffsetY - mDateTextPaint.descent();
            text = mTime.format("%a, %b %e %Y").trim();
            width = mDateTextPaint.measureText(text);
            canvas.drawText(text, (mSurfaceWidth - width) / 2, dateOffsetY, mDateTextPaint);

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
