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

package com.example.android.wearable.watchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.util.TimeZone;

/**
 * Sample analog watch face with a ticking second hand. In ambient mode, the second hand isn't
 * shown. On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient
 * mode. The watch face is drawn with less contrast in mute mode.
 *
 * {@link SweepWatchFaceService} is similar but has a sweep second hand.
 */
public class UTCWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = "UTCWatchFaceService";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = 15; // ~60 fps

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        private static final String MINUTE_ABBREVIATION = "m";

        private static final int MSG_UPDATE_TIME = 0;

        private static final int ANIMATION_PIXELS_PER_SECOND = 200;

        private Paint mHourTickPaint;
        private Paint mMinutePaint;
        private Paint mUTCDiffPaint;
        private Paint mCurrentHourDotPaint;
        private Paint mUTCCirclePaint;
        private Paint mMinuteAbbreviationPaint;

        private boolean mMute;
        private Time mTime;

        private float mWatchHeight;
        private long mLastUpdate = -1;

        int mInteractiveHourTickColor =
                DigitalWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_HOUR_DIGITS;

        /** Handler to update the time once a second in interactive mode. */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "updating time");
                        }
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        boolean mRegisteredTimeZoneReceiver = false;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        Bitmap mBackgroundBitmap;
        Bitmap mBackgroundScaledBitmap;
        private float mYOffset;
        private float mMinuteAbbreviationWidth;
        private final Rect mCardBounds = new Rect();

        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCreate");
            }
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(UTCWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = UTCWatchFaceService.this.getResources();
            Drawable backgroundDrawable = resources.getDrawable(R.drawable.bg);
            mBackgroundBitmap = ((BitmapDrawable) backgroundDrawable).getBitmap();

            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mHourTickPaint = createTextPaint(resources.getColor(R.color.hour_tick), BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(resources.getColor(R.color.current_hour));
            mUTCDiffPaint = createTextPaint(resources.getColor(R.color.current_hour));

            mCurrentHourDotPaint = new Paint();
            mCurrentHourDotPaint.setColor(resources.getColor(R.color.current_hour));
            mCurrentHourDotPaint.setStrokeWidth(2f);
            mCurrentHourDotPaint.setAntiAlias(true);
            mCurrentHourDotPaint.setStrokeCap(Paint.Cap.ROUND);

            mUTCCirclePaint = new Paint();
            mUTCCirclePaint.setColor(resources.getColor(R.color.utc_hour));
            mUTCCirclePaint.setStrokeWidth(5f);
            mUTCCirclePaint.setAntiAlias(true);
            mUTCCirclePaint.setStyle(Paint.Style.STROKE);

            mMinuteAbbreviationPaint =
                    createTextPaint(resources.getColor(R.color.minute_abbrevation));

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPropertiesChanged: low-bit ambient = " + mLowBitAmbient);
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onTimeTick: ambient = " + isInAmbientMode());
            }
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);
            }
            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mHourTickPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mCurrentHourDotPaint.setAntiAlias(antiAlias);
                mMinuteAbbreviationPaint.setAntiAlias(antiAlias);
                mUTCCirclePaint.setAntiAlias(antiAlias);
                mUTCDiffPaint.setAntiAlias(antiAlias);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);
            boolean inMuteMode = (interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE);
            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                mHourTickPaint.setAlpha(inMuteMode ? 100 : 255);
                mMinutePaint.setAlpha(inMuteMode ? 100 : 255);
                mCurrentHourDotPaint.setAlpha(inMuteMode ? 80 : 255);
                mMinuteAbbreviationPaint.setAlpha(inMuteMode ? 100 : 255);
                mUTCDiffPaint.setAlpha(inMuteMode ? 80 : 180);
                mUTCCirclePaint.setAlpha(inMuteMode ? 80 : 255);
                invalidate();
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mTime.set(now);

            int width = bounds.width();
            int boundsHeight = bounds.height();

            // Draw the background, scaled to fit.
            if (mBackgroundScaledBitmap == null
                    || mBackgroundScaledBitmap.getWidth() != width
                    || mBackgroundScaledBitmap.getHeight() != boundsHeight) {
                mBackgroundScaledBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                        width, boundsHeight, true /* filter */);
            }
            canvas.drawBitmap(mBackgroundScaledBitmap, 0, 0, null);

            int desiredHeight;
            if (mCardBounds != null && mCardBounds.top > 0) {
                desiredHeight = mCardBounds.top; // Show watch above card.
            } else {
                desiredHeight = boundsHeight;
            }

            // Animate watch changing size.
            if (mLastUpdate < 0) {
                mWatchHeight = desiredHeight;
            } else if (desiredHeight != (int) mWatchHeight) {
                float elapseMs = now - mLastUpdate;
                float velocityPx = ANIMATION_PIXELS_PER_SECOND * elapseMs / 1000;
                int diff = desiredHeight - (int) mWatchHeight;
                if (Math.abs(diff) <= velocityPx) {
                    mWatchHeight = desiredHeight;
                } else if (diff > 0) {
                    mWatchHeight += velocityPx;
                } else if (diff < 0) {
                    mWatchHeight -= velocityPx;
                } else {
                    mWatchHeight = desiredHeight;
                }
            }
            mWatchHeight = Math.max(1, Math.min(boundsHeight, mWatchHeight));
            mLastUpdate = now;

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            float centerX = width / 2f;
            float centerY = mWatchHeight / 2f;

            float radius = Math.min(centerX, centerY);

            Rect textBounds = new Rect();
            mHourTickPaint.getTextBounds("24", 0, 1, textBounds);
            float textHeight = (float)textBounds.height();
            float spiralStep = (textHeight * 2.0f) / 12.0f;

            // Draw hour dot.
            float hour = mTime.hour;
            float hourRot = (float) (hour * Math.PI * 2 / 12);
            float hourRadius = radius - 10 - textHeight - spiralStep * hour;
            float dotX = (float) Math.sin(hourRot) * hourRadius;
            float dotY = (float) -Math.cos(hourRot) * hourRadius;
            Resources resources = UTCWatchFaceService.this.getResources();
            float dotRadius = resources.getDimension(R.dimen.utc_text_size) * .6f;
            canvas.drawCircle(centerX + dotX, centerY + dotY, dotRadius, mCurrentHourDotPaint);

            // Draw other hour circle.
            String zone = mTime.timezone;
            String gmtZone = "GMT";
            mTime.switchTimezone(gmtZone);

            hour = mTime.hour;
            hourRot = (float) (hour * Math.PI * 2 / 12);
            hourRadius = radius - 10 - textHeight - spiralStep * hour;
            dotX = (float) Math.sin(hourRot) * hourRadius;
            dotY = (float) -Math.cos(hourRot) * hourRadius;
            canvas.drawCircle(centerX + dotX, centerY + dotY, dotRadius + 2f, mUTCCirclePaint);
            mTime.switchTimezone(zone);

            // Draw the hours in a spiral.
            hourRadius = radius - 10 - textHeight;
            for (int tickIndex = 0; tickIndex < 24; tickIndex++) {

                float tickRot = (float) (tickIndex * Math.PI * 2 / 12);
                float innerX = (float) Math.sin(tickRot) * hourRadius;
                float innerY = (float) -Math.cos(tickRot) * hourRadius;
                String hourText = Integer.toString(tickIndex);
                float hourWidth = mHourTickPaint.measureText(hourText);
                canvas.drawText(hourText, centerX + innerX - (hourWidth / 2),
                        centerY + innerY + (textHeight / 2), mHourTickPaint);
                hourRadius -= spiralStep;
            }

            String minuteString = formatTwoDigitNumber(mTime.minute);
            float minuteWidth = mMinutePaint.measureText(minuteString);

            float x = centerX - (minuteWidth + mMinuteAbbreviationWidth) / 2;

            // Draw the minutes.
            canvas.drawText(minuteString, x, mYOffset, mMinutePaint);
            x += minuteWidth;

            // Draw the minute abbreviation.
            canvas.drawText(MINUTE_ABBREVIATION, x, mYOffset, mMinuteAbbreviationPaint);
            x += mMinuteAbbreviationWidth;

            // Draw the UTC diff.
            Rect minuteBounds = new Rect();
            mMinutePaint.getTextBounds(minuteString, 0, 1, minuteBounds);
            float minuteHeight = (float)minuteBounds.height();

            TimeZone tz = TimeZone.getTimeZone(mTime.timezone);
            long milliDiff = tz.getOffset(mTime.toMillis(false));
            float hourDiff = milliDiff / 1000f / 60f / 60f;

            String hourDiffString = formatUTCDiff(hourDiff);
            float hourDiffWidth = mUTCDiffPaint.measureText(hourDiffString);
            x = centerX - (hourDiffWidth) / 2;
            canvas.drawText(hourDiffString, x, mYOffset + minuteHeight + 10, mUTCDiffPaint);
            x += hourDiffWidth;
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private String formatUTCDiff(float hour) {
            int truncateAfter = (int) (hour * 2);
            int truncateBefore = ((int) hour) * 2;

            if (truncateAfter == truncateBefore) {
                if (hour >= 0) {
                    return "UTC+" + Math.abs((int)hour);
                } else {
                    return "UTC-" + Math.abs((int)hour);
                }
            } else {
                if (hour >= 0) { // must check float, not int
                    return "UTC+" + String.format("%d.5", Math.abs((int)hour));
                } else {
                    return "UTC-" + String.format("%d.5", Math.abs((int)hour));
                }
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onVisibilityChanged: " + visible);
            }

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
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
            UTCWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            UTCWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));
            }
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = UTCWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            float textSize = resources.getDimension(isRound
                    ? R.dimen.utc_text_size_round : R.dimen.utc_text_size);
            float minuteTextSize = resources.getDimension(isRound
                    ? R.dimen.utc_minute_text_size_round : R.dimen.utc_minute_text_size);
            float tickTextSize = resources.getDimension(isRound
                    ? R.dimen.utc_tick_text_size_round : R.dimen.utc_tick_text_size);

            mHourTickPaint.setTextSize(tickTextSize);
            mMinutePaint.setTextSize(minuteTextSize);
            mMinuteAbbreviationPaint.setTextSize(textSize);
            mMinuteAbbreviationWidth = mMinuteAbbreviationPaint.measureText(MINUTE_ABBREVIATION);
            mUTCDiffPaint.setTextSize(tickTextSize);
        }

        @Override
        public void onPeekCardPositionUpdate(Rect bounds) {
            super.onPeekCardPositionUpdate(bounds);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPeekCardPositionUpdate: " + bounds);
            }
            super.onPeekCardPositionUpdate(bounds);
            if (!bounds.equals(mCardBounds)) {
                mCardBounds.set(bounds);
                invalidate();
            }
        }
        
        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "updateTimer");
            }
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

    }
}
