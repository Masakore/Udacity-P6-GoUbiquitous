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

package com.masakorelab.sunshine_wearable;

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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
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

  @Override
  public Engine onCreateEngine() {
    return new Engine();
  }

  private static class EngineHandler extends Handler {
    private final WeakReference<MyWatchFace.Engine> mWeakReference;

    public EngineHandler(MyWatchFace.Engine reference) {
      mWeakReference = new WeakReference<>(reference);
    }

    @Override
    public void handleMessage(Message msg) {
      MyWatchFace.Engine engine = mWeakReference.get();
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
    GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    final Handler mUpdateTimeHandler = new EngineHandler(this);
    boolean mRegisteredTimeZoneReceiver = false;
    Paint mBackgroundPaint;
    Paint mTimeTextPaint;
    Paint mDateTextPaint;
    Paint mHighAndLowTextPaint;
    Paint mWeatherIcon;

    boolean mAmbient;
    Calendar mCalendar;
//    private static final String TIME_FORMAT_WITHOUT_SECONDS = "%02d.%02d";
//    private static final String TIME_FORMAT_WITH_SECONDS = TIME_FORMAT_WITHOUT_SECONDS + ".%02d";
    private static final String TIME_FORMAT = "hh:mm";
    private static final String DATE_FORMAT = "E, MMM dd yyyy";


    final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        mCalendar.setTimeZone(TimeZone.getDefault());
        invalidate();
      }
    };
    int mTapCount;

    float mXOffset;
    float mYOffset;

    //Weather Data fetching from Google Data Api
    String mHighAndLow = null;
    Bitmap mWeatherBitmap;
    int mWeatherId = -1;

    /**
     * Whether the display supports fewer bits for each color in ambient mode. When true, we
     * disable anti-aliasing in ambient mode.
     */
    boolean mLowBitAmbient;

    //For GoogleApi
    private GoogleApiClient mGoogleApiClient;
    private boolean nodeConnected = false;

    @Override
    public void onCreate(SurfaceHolder holder) {
      super.onCreate(holder);

      setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
          .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
          .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
          .setShowSystemUiTime(false)
          .setAcceptsTapEvents(true)
          .build());
      Resources resources = MyWatchFace.this.getResources();
      mYOffset = resources.getDimension(R.dimen.digital_y_offset);

      mBackgroundPaint = new Paint();
      mBackgroundPaint.setColor(resources.getColor(R.color.background));

      mTimeTextPaint = new Paint();
      mTimeTextPaint = createTextPaint(resources.getColor(R.color.digital_time_text));

      mDateTextPaint = new Paint();
      mDateTextPaint = createTextPaint(resources.getColor(R.color.digital_date_text));

      mHighAndLowTextPaint = new Paint();
      //Todo create color reference later
      mHighAndLowTextPaint = createTextPaint(resources.getColor(R.color.digital_date_text));

      mWeatherIcon = new Paint();

      // allocate a Calendar to calculate local time using the UTC time and time zone
      mCalendar = Calendar.getInstance();

      mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
          .addApi(Wearable.API)
          .addConnectionCallbacks(this)
          .addOnConnectionFailedListener(this)
          .build();
      mGoogleApiClient.connect();

    }

    @Override
    public void onDestroy() {
      mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
      super.onDestroy();
    }

    private Paint createTextPaint(int textColor) {
      Paint paint = new Paint();
      paint.setColor(textColor);
      paint.setTypeface(NORMAL_TYPEFACE);
      paint.setAntiAlias(true);
      return paint;
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
      super.onVisibilityChanged(visible);

      if (visible) {
        registerReceiver();

        // Update time zone in case it changed while we weren't visible.
        mCalendar.setTimeZone(TimeZone.getDefault());
      } else {
        unregisterReceiver();

        Wearable.DataApi.removeListener(mGoogleApiClient, this);
        mGoogleApiClient.disconnect();
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
      MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
    }

    private void unregisterReceiver() {
      if (!mRegisteredTimeZoneReceiver) {
        return;
      }
      mRegisteredTimeZoneReceiver = false;
      MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
    }

    @Override
    public void onApplyWindowInsets(WindowInsets insets) {
      super.onApplyWindowInsets(insets);

      // Load resources that have alternate values for round watches.
      Resources resources = MyWatchFace.this.getResources();
      boolean isRound = insets.isRound();
      mXOffset = resources.getDimension(isRound
          ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
      float timeTextSize = resources.getDimension(isRound
          ? R.dimen.digital_time_text_size_round : R.dimen.digital_time_text_size);
      float dateTextSize = resources.getDimension(isRound
          ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);
      float tempTextSize = resources.getDimension(isRound
          ? R.dimen.digital_temp_text_size : R.dimen.digital_temp_text_size);

      mTimeTextPaint.setTextSize(timeTextSize);
      mDateTextPaint.setTextSize(dateTextSize);
      mHighAndLowTextPaint.setTextSize(tempTextSize);
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
    public void onAmbientModeChanged(boolean inAmbientMode) {
      super.onAmbientModeChanged(inAmbientMode);
      if (mAmbient != inAmbientMode) {
        mAmbient = inAmbientMode;
        if (mLowBitAmbient) {
          mTimeTextPaint.setAntiAlias(!inAmbientMode);
          mDateTextPaint.setAntiAlias(!inAmbientMode);
          mHighAndLowTextPaint.setAntiAlias(!inAmbientMode);
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
      Resources resources = MyWatchFace.this.getResources();
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

    @Override
    public void onDraw(Canvas canvas, Rect bounds) {
      // Draw the background.
      if (isInAmbientMode()) {
        canvas.drawColor(Color.BLACK);
      } else {
        canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
      }
      SimpleDateFormat time = new SimpleDateFormat(TIME_FORMAT);
      String timeText = time.format(mCalendar.getTime());
      float timeXOffset = computeXOffset(timeText, mTimeTextPaint, bounds);
      float timeYOffset = computeTimeYOffset(timeText, mTimeTextPaint, bounds);
      canvas.drawText(timeText, timeXOffset, timeYOffset, mTimeTextPaint);

      SimpleDateFormat date = new SimpleDateFormat(DATE_FORMAT);
      String dateText = date.format(mCalendar.getTime());
      float dateXOffset = computeXOffset(dateText, mDateTextPaint, bounds);
      float dateYOffset = computeYOffset(dateText, mDateTextPaint);
      canvas.drawText(dateText, dateXOffset, timeYOffset + dateYOffset, mDateTextPaint);

      float tempXOffset = 0;
      float tempYOffset = 0;
      if (mHighAndLow != null && !mHighAndLow.isEmpty()){
        tempXOffset = computeXOffset(mHighAndLow, mHighAndLowTextPaint, bounds);
        tempYOffset = computeYOffset(mHighAndLow, mHighAndLowTextPaint);
        canvas.drawText(mHighAndLow, tempXOffset, timeYOffset + dateYOffset + tempYOffset, mHighAndLowTextPaint);
      }

      if (mWeatherId != -1){
        int weatherId = Utility.getArtResourceForWeatherCondition(mWeatherId);
        if(weatherId != -1) {
          Resources resources = MyWatchFace.this.getResources();
          Drawable weatherDrawable = resources.getDrawable(weatherId, null);
          mWeatherBitmap = ((BitmapDrawable) weatherDrawable).getBitmap();

          float bitmapXOffset = computeBitmapXOffset(mWeatherBitmap, bounds);
          float bitmapYOffset = computeYOffset(timeText, mHighAndLowTextPaint);
          canvas.drawBitmap(mWeatherBitmap, bitmapXOffset,timeYOffset + dateYOffset + bitmapYOffset, null);
        }
      }
    }

    private float computeXOffset(String text, Paint paint, Rect watchBounds) {
      float centerX = watchBounds.exactCenterX();
      float length = paint.measureText(text);
      return centerX - (length / 2.0f);
    }

    private float computeBitmapXOffset(Bitmap bitmap, Rect watchBounds) {
      float centerX = watchBounds.exactCenterX();
      float length = bitmap.getWidth();
      return centerX - (length / 2.0f);
    }

    private float computeTimeYOffset(String timeText, Paint timePaint, Rect watchBounds) {
      float centerY = watchBounds.exactCenterY();
      Rect textBounds = new Rect();
      timePaint.getTextBounds(timeText, 0, timeText.length(), textBounds);
      int textHeight = textBounds.height();
      return centerY - textHeight;
    }

    private float computeYOffset(String text, Paint paint) {
      Rect textBounds = new Rect();
      paint.getTextBounds(text, 0, text.length(), textBounds);
      return textBounds.height() + 10.0f;
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

    @Override
    public void onConnected(Bundle bundle) {
      Wearable.DataApi.addListener(mGoogleApiClient, this);
      nodeConnected = true;
    }

    @Override
    public void onConnectionSuspended(int i) {
      nodeConnected = false;
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
      final String GOOGLE_API_PATH = "WEATHERDATA";
      final String HIGHLOWDATA = "HIGHLOWDATA";
      final String WEATHER_ID = "WEATHERID";

      for (DataEvent event : dataEvents) {
        if (event.getType() == DataEvent.TYPE_CHANGED) {
          // DataItem changed
          DataItem item = event.getDataItem();
          if (item.getUri().getPath().compareTo(GOOGLE_API_PATH) == 0) {
            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
            mHighAndLow = dataMap.getString(HIGHLOWDATA);
            mWeatherId = dataMap.getInt(WEATHER_ID);
          }
        } else if (event.getType() == DataEvent.TYPE_DELETED) {
          // DataItem deleted
        }
      }
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
      nodeConnected = false;
    }

  }
}
