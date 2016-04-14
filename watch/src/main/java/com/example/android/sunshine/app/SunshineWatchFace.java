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
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.example.android.sunshine.app.shared.Constants;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final String TAG = SunshineWatchFace.class.getSimpleName();
    static final int MSG_UPDATE_TIME = 0;
    /**
     * Update rate in milliseconds for normal (not ambient and not mute) mode. We update twice
     * a second to blink the colons.
     */
    private static final long NORMAL_UPDATE_RATE_MS = 500;

    /** How often update timer ticks in milliseconds. */
    long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

    /**
     * Update rate in milliseconds for mute mode. We update every minute, like in ambient mode.
     */
    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    public static final String COLOR_NAME_DEFAULT_AND_AMBIENT_BACKGROUND = "Black";
    public static final int COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND =
            parseColor(COLOR_NAME_DEFAULT_AND_AMBIENT_BACKGROUND);
    public static final String COLOR_NAME_DEFAULT_AND_AMBIENT_HOUR_DIGITS = "White";
    public static final int COLOR_VALUE_DEFAULT_AND_AMBIENT_HOUR_DIGITS =
            parseColor(COLOR_NAME_DEFAULT_AND_AMBIENT_HOUR_DIGITS);
    public static final String COLOR_NAME_DEFAULT_AND_AMBIENT_MINUTE_DIGITS = "White";
    public static final int COLOR_VALUE_DEFAULT_AND_AMBIENT_MINUTE_DIGITS =
            parseColor(COLOR_NAME_DEFAULT_AND_AMBIENT_MINUTE_DIGITS);
    public static final String COLOR_NAME_DEFAULT_AND_AMBIENT_SECOND_DIGITS = "Gray";
    public static final int COLOR_VALUE_DEFAULT_AND_AMBIENT_SECOND_DIGITS =
            parseColor(COLOR_NAME_DEFAULT_AND_AMBIENT_SECOND_DIGITS);

    private static int parseColor(String colorName) {
        return Color.parseColor(colorName.toLowerCase());
    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener  {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        static final String COLON_STRING = ":";

        /** Alpha value for drawing time when in mute mode. */
        static final int MUTE_ALPHA = 100;

        /** Alpha value for drawing time when not in mute mode. */
        static final int NORMAL_ALPHA = 255;

        private static final float DIVIDER_LINE_STROKE_WIDTH = 1f;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        /**
         * Handles time zone and locale changes.
         */
        final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };
        /**
         * Unregistering an unregistered receiver throws an exception. Keep track of the
         * registration state to prevent that.
         */
        boolean mRegisteredReceiver = false;

        int mTapCount;

        Paint mBackgroundPaint;
        Paint mDatePaint;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mSecondPaint;
        Paint mColonPaint;
        Paint mAmPmPaint;
        Paint mHighTmptPaint;
        Paint mLowTmptPaint;
        Paint mDividerLinePaint;
        Paint mGrayPaint;
        float mColonWidth;
        float mDividerLineLength;
        boolean mMute;

        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDateFormat;

        boolean mShouldDrawColons;
        float mYOffset;
        float mVerticalPaddingTimeDate;
        float mVerticalPaddingDateTempt;
        float mHorizontalPaddingIconTempt;
        float mHorizontalPaddingHighLowTempt;
        String mAmString;
        String mPmString;
        int mActiveModeBackgroundColor;
        int mActiveModeHourDigitsColor;
        int mActiveModeMinuteDigitsColor;
        int mActiveModeSecondDigitsColor;
        int mActiveModeColonColor;
        int mActiveModeAmPmColor;
        int mActiveModeDateColor;
        int mActiveModeHighTmptColor;
        int mActiveModeLowTmptColor;
        int mDividerLineColor;

        int mWeatherId = -1;
        double mHighTemperature;
        double mLowTemperature;
        private Bitmap mWeatherIcon;
        private int mWeatherIconResourceId;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        //boolean mAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.y_offset);
            mVerticalPaddingTimeDate = resources.getDimension(R.dimen.vertical_padding_time_date);
            mVerticalPaddingDateTempt = resources.getDimension(R.dimen.vertical_padding_date_tempt);
            mHorizontalPaddingIconTempt = resources.getDimension(R.dimen.horiz_padding_icon_tempt);
            mHorizontalPaddingHighLowTempt =
                    resources.getDimension(R.dimen.horiz_padding_high_low_tempt);
            mAmString = resources.getString(R.string.digital_am);
            mPmString = resources.getString(R.string.digital_pm);

            mActiveModeBackgroundColor = resources.getColor(R.color.background);
            mActiveModeHourDigitsColor = resources.getColor(R.color.primary_text);
            mActiveModeMinuteDigitsColor = resources.getColor(R.color.primary_text);
            mActiveModeSecondDigitsColor = resources.getColor(R.color.secondary_text);
            mActiveModeColonColor = resources.getColor(R.color.secondary_text);
            mActiveModeAmPmColor = resources.getColor(R.color.secondary_text);
            mActiveModeDateColor = resources.getColor(R.color.secondary_text);
            mActiveModeHighTmptColor = resources.getColor(R.color.primary_text);
            mActiveModeLowTmptColor = resources.getColor(R.color.secondary_text);
            mDividerLineColor = resources.getColor(R.color.secondary_text);
            mDividerLineLength = resources.getDimension(R.dimen.divider_line_length);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mActiveModeBackgroundColor);
            mHourPaint = createTextPaint(mActiveModeHourDigitsColor, BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(mActiveModeMinuteDigitsColor);
            mSecondPaint = createTextPaint(mActiveModeSecondDigitsColor);
            mColonPaint = createTextPaint(mActiveModeColonColor);
            mAmPmPaint = createTextPaint(mActiveModeAmPmColor);
            mDatePaint = createTextPaint(mActiveModeDateColor);
            mHighTmptPaint = createTextPaint(mActiveModeHighTmptColor, BOLD_TYPEFACE);
            mLowTmptPaint = createTextPaint(mActiveModeLowTmptColor);

            mDividerLinePaint = new Paint();
            mDividerLinePaint.setColor(mDividerLineColor);
            mDividerLinePaint.setStrokeWidth(DIVIDER_LINE_STROKE_WIDTH);
            mDividerLinePaint.setAntiAlias(true);

            mGrayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            mGrayPaint.setColorFilter(filter);

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();

            //Uncomment for testing when no phone connection available
            /*
            mWeatherId = 800; //Clear weather
            mWeatherIcon = BitmapFactory.decodeResource(getResources(),
                    Utility.getIconResourceForWeatherCondition(mWeatherId));
            mHighTemperature = 25f;
            mLowTemperature = 16f;
            */
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }
        private Paint createTextPaint(int color) {
            return createTextPaint(color, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int color, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();

            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void initFormats() {
            mDateFormat = new SimpleDateFormat("EEE, MMM d ''yy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.time_text_size_round : R.dimen.time_text_size);
            float amPmSize = resources.getDimension(isRound
                    ? R.dimen.am_pm_size_round : R.dimen.am_pm_size);
            float tmptTextSize = resources.getDimension(isRound
                    ? R.dimen.temperature_text_size_round : R.dimen.temperature_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);

            mHourPaint.setTextSize(timeTextSize);
            mMinutePaint.setTextSize(timeTextSize);
            mSecondPaint.setTextSize(timeTextSize);
            mAmPmPaint.setTextSize(amPmSize);
            mColonPaint.setTextSize(timeTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mHighTmptPaint.setTextSize(tmptTextSize);
            mLowTmptPaint.setTextSize(tmptTextSize);

            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            adjustPaintColorToCurrentMode(mBackgroundPaint, mActiveModeBackgroundColor,
                    COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND);

            adjustPaintColorToCurrentMode(mHourPaint, mActiveModeHourDigitsColor,
                    COLOR_VALUE_DEFAULT_AND_AMBIENT_HOUR_DIGITS);
            adjustPaintColorToCurrentMode(mMinutePaint, mActiveModeMinuteDigitsColor,
                    COLOR_VALUE_DEFAULT_AND_AMBIENT_MINUTE_DIGITS);
            // Actually, the seconds are not rendered in the ambient mode, so we could pass just any
            // value as ambientColor here.
            adjustPaintColorToCurrentMode(mSecondPaint, mActiveModeSecondDigitsColor,
                    COLOR_VALUE_DEFAULT_AND_AMBIENT_SECOND_DIGITS);

            adjustPaintColorToCurrentMode(mAmPmPaint, mActiveModeAmPmColor,
                    COLOR_VALUE_DEFAULT_AND_AMBIENT_SECOND_DIGITS);
            adjustPaintColorToCurrentMode(mColonPaint, mActiveModeColonColor,
                    COLOR_VALUE_DEFAULT_AND_AMBIENT_SECOND_DIGITS);

            adjustPaintColorToCurrentMode(mDatePaint, mActiveModeDateColor,
                    COLOR_VALUE_DEFAULT_AND_AMBIENT_SECOND_DIGITS);

            adjustPaintColorToCurrentMode(mDividerLinePaint, mDividerLineColor,
                    COLOR_VALUE_DEFAULT_AND_AMBIENT_SECOND_DIGITS);
            adjustPaintColorToCurrentMode(mHighTmptPaint, mActiveModeHighTmptColor,
                    COLOR_VALUE_DEFAULT_AND_AMBIENT_HOUR_DIGITS);
            adjustPaintColorToCurrentMode(mLowTmptPaint, mActiveModeLowTmptColor,
                    COLOR_VALUE_DEFAULT_AND_AMBIENT_MINUTE_DIGITS);

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mSecondPaint.setAntiAlias(antiAlias);
                mAmPmPaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
                mDatePaint.setAntiAlias(antiAlias);
                mDividerLinePaint.setAntiAlias(antiAlias);
                mLowTmptPaint.setAntiAlias(antiAlias);
                mHighTmptPaint.setAntiAlias(antiAlias);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onInterruptionFilterChanged: " + interruptionFilter);
            }
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);

            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                int alpha = inMuteMode ? MUTE_ALPHA : NORMAL_ALPHA;
                mHourPaint.setAlpha(alpha);
                mMinutePaint.setAlpha(alpha);
                mColonPaint.setAlpha(alpha);
                mAmPmPaint.setAlpha(alpha);
                mDatePaint.setAlpha(alpha);
                mHighTmptPaint.setAlpha(alpha);
                mLowTmptPaint.setAlpha(alpha);
                mDividerLinePaint.setAlpha(alpha);
                invalidate();
            }
        }

        public void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }
            mInteractiveUpdateRateMs = updateRateMs;

            // Stop and restart the timer so the new update rate takes effect immediately.
            if (shouldTimerBeRunning()) {
                updateTimer();
            }
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private String getAmPmString(int amPm) {
            return amPm == Calendar.AM ? mAmString : mPmString;
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFace.this);

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;
            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            // Draw the hours.
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            //center-align horizontally
            float x = (bounds.width() - mHourPaint.measureText(hourString + ":00:00")) / 2;
            float y = mYOffset;
            canvas.drawText(hourString, x, y, mHourPaint);
            x += mHourPaint.measureText(hourString);
            // In ambient and mute modes, always draw the first colon. Otherwise, draw the
            // first colon for the first half of each second.
            if (isInAmbientMode() || mMute || mShouldDrawColons) {
                canvas.drawText(COLON_STRING, x, y, mColonPaint);
            }
            x += mColonWidth;
            // Draw the minutes.
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, x, y, mMinutePaint);
            x += mMinutePaint.measureText(minuteString);
            // In non-muted interactive mode, draw a second blinking colon followed by the seconds.
            // Otherwise, if we're in 12-hour mode, draw AM/PM
            if (!isInAmbientMode() && !mMute) {
                if (mShouldDrawColons) {
                    canvas.drawText(COLON_STRING, x, y, mColonPaint);
                }
                x += mColonWidth;
                canvas.drawText(formatTwoDigitNumber(
                        mCalendar.get(Calendar.SECOND)), x, y, mSecondPaint);
            } else if (!is24Hour) {
                x += mColonWidth;
                canvas.drawText(getAmPmString(
                        mCalendar.get(Calendar.AM_PM)), x, y, mAmPmPaint);
            }

            // Only render date and weather if there is no peek card, so they do not bleed
            // into each other in ambient mode.
            if (getPeekCardPosition().isEmpty()) {
                //Draw the date
                String dateText = mDateFormat.format(mDate).toUpperCase();
                //center-align horizontally
                x = (bounds.width() - mDatePaint.measureText(dateText)) / 2;
                y += mDatePaint.getTextSize() + mVerticalPaddingTimeDate;
                canvas.drawText( dateText, x, y, mDatePaint);

                if (mWeatherIcon != null) {
                    //Draw horizontal divider line
                    float xLineStart = (bounds.width() - mDividerLineLength) / 2;
                    //Draw divider line at 3/4 of distance between date and weather data
                    y += mVerticalPaddingDateTempt * 3/4;
                    canvas.drawLine(xLineStart, y, xLineStart + mDividerLineLength, y,
                            mDividerLinePaint);

                    String highTmpt = String.format("%.0f" + (char) 0x00B0, mHighTemperature);
                    String lowTmpt = String.format("%.0f" + (char) 0x00B0, mLowTemperature);
                    float weatherIconWidth = mWeatherIcon.getWidth();
                    float highTwidth = mHighTmptPaint.measureText(highTmpt);
                    float lowTwidth = mHighTmptPaint.measureText(lowTmpt);
                    float weatherWidth = weatherIconWidth + mHorizontalPaddingIconTempt +
                            highTwidth + mHorizontalPaddingHighLowTempt + lowTwidth;
                    //Draw weather icon
                    x = (bounds.width() - weatherWidth) / 2; //center-align horizontally
                    //Drew divider line at 3/4 of distance between date and weather data
                    //so incrementing by remaining 1/4 to draw weather data
                    y += mVerticalPaddingDateTempt * 1/4;
                    //Align weather icon bottom-vertically against temperature text
                    canvas.drawBitmap(mWeatherIcon, x,
                            y + (mHighTmptPaint.getTextSize() - mWeatherIcon.getHeight()),
                            isInAmbientMode() ? mGrayPaint : mBackgroundPaint);
                    //Draw high temperature
                    x += mWeatherIcon.getWidth() + mHorizontalPaddingIconTempt;
                    y += mHighTmptPaint.getTextSize();
                    canvas.drawText(highTmpt, x, y, mHighTmptPaint);
                    //Draw low temperature
                    x += highTwidth + mHorizontalPaddingHighLowTempt;
                    canvas.drawText(lowTmpt, x, y, mLowTmptPaint);
                }
            }
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
                long delayMs = mInteractiveUpdateRateMs
                        - (timeMs % mInteractiveUpdateRateMs);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            Log.d(TAG, "onConnected: connection hint: " + connectionHint);
            //After connection check if data item with weather data is available
            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(new ResultCallback<DataItemBuffer>() {
                @Override
                public void onResult(DataItemBuffer dataItems) {
                    for (DataItem dataItem : dataItems) {
                        handleDataItem(dataItem);
                    }
                    dataItems.release();
                }
            });

            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this)
                    .setResultCallback(new ResultCallback<Status>() {
                        @Override
                        public void onResult(Status status) {
                            if (status.isSuccess()) {
                                Log.d(TAG, "Successfully added listener");
                            }
                        }
                    });
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            Log.d(TAG, "onConnectionSuspended: " + cause);
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            Log.d(TAG, "onConnectionFailed: " + result);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(TAG, "onDataChanged: " + dataEvents);
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    handleDataItem(event.getDataItem());
                }
            }
            dataEvents.release();
        }

        private void handleDataItem(DataItem item) {
            if (item.getUri().getPath().compareTo(Constants.WEATHER_PATH) == 0) {
                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                mWeatherId = dataMap.getInt(Constants.WEATHER_ID_KEY);
                mHighTemperature = dataMap.getDouble(Constants.HIGH_TEMPERATURE_KEY);
                mLowTemperature = dataMap.getDouble(Constants.LOW_TEMPERATURE_KEY);
                Log.d(TAG, String.format("Retrieved weather data: ID:%d, high:%.0f, low:%.0f",
                        mWeatherId, mHighTemperature, mLowTemperature));
                int iconResourceId = Utility.getIconResourceForWeatherCondition(mWeatherId);
                if (iconResourceId != mWeatherIconResourceId) {
                    mWeatherIconResourceId = iconResourceId;
                    if (iconResourceId != -1) {
                        mWeatherIcon = BitmapFactory.decodeResource(getResources(), iconResourceId);
                    } else {
                        mWeatherIcon = null;
                    }
                }
                invalidate();
            }
        }

    }
}
