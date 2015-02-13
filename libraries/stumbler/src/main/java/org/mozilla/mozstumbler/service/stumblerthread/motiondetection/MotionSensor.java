package org.mozilla.mozstumbler.service.stumblerthread.motiondetection;

import android.content.Context;
import android.content.Intent;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;

import org.mozilla.mozstumbler.service.AppGlobals;
import org.mozilla.mozstumbler.service.Prefs;
import org.mozilla.mozstumbler.svclocator.ServiceLocator;
import org.mozilla.mozstumbler.svclocator.services.ISystemClock;
import org.mozilla.mozstumbler.svclocator.services.log.ILogger;
import org.mozilla.mozstumbler.svclocator.services.log.LoggerUtil;

public class MotionSensor {

    public static final String ACTION_USER_MOTION_DETECTED = AppGlobals.ACTION_NAMESPACE + ".USER_MOVE";
    private static final String LOG_TAG = LoggerUtil.makeLogTag(MotionSensor.class);
    private static final ILogger Log = (ILogger) ServiceLocator.getInstance().getService(ILogger.class);
    static final long GPS_WARM_TIME_MS = 2 * 60 * 60 * 1000; // after this, the GPS is considered cold
    /// Testing code
    private final SensorManager mSensorManager;
    private final Context mAppContext;
    private IMotionSensor mMotionSensor;
    private static MotionSensor sDebugInstance;
    Handler mHandler = new Handler();

    boolean mIsStartMotionSensorRunnableScheduled;
    Runnable mStartMotionSensorRunnable = new Runnable() {
        public void run() {
            mIsStartMotionSensorRunnableScheduled = false;
            mMotionSensor.start();
        }
    };

    public MotionSensor(Context appCtx) {
        mAppContext = appCtx;
        mFalsePositiveFilter = new FalsePositiveFilter(mAppContext);

        mSensorManager = (SensorManager) mAppContext.getSystemService(Context.SENSOR_SERVICE);
        if (mSensorManager == null) {
            AppGlobals.guiLogInfo("No sensor manager.");
            return;
        }

        sDebugInstance = this;

        setTypeFromPrefs();
    }

    private void setTypeFromPrefs() {
        if (Prefs.getInstance(mAppContext).isMotionSensorTypeSignificant()) {
            mMotionSensor = SignificantMotionSensor.getSensor(mAppContext);
        } else {
            mMotionSensor = new LegacyMotionSensor(mAppContext);
        }
    }

    // Call when the scanning has stopped (not when paused).
    // This class can use this event to update its state
    public void scannerFullyStopped() {
        setTypeFromPrefs();
        mFalsePositiveFilter.reset();
    }

    public static void debugMotionDetected() {
        if (!sDebugInstance.mMotionSensor.isActive()) {
            return;
        }
        AppGlobals.guiLogInfo("TEST: Major motion detected.");
        LocalBroadcastManager.getInstance(sDebugInstance.mAppContext).sendBroadcastSync(new Intent(ACTION_USER_MOTION_DETECTED));
    }

    public static boolean hasSignificantMotionSensor(Context appCtx) {
        return SignificantMotionSensor.getSensor(appCtx) != null;
    }

    public void start() {
        if (!Prefs.getInstance(mAppContext).isMotionSensorEnabled() ||
                mMotionSensor.isActive() ||
                mIsStartMotionSensorRunnableScheduled) {
            return;
        }

        mHandler.removeCallbacks(mStartMotionSensorRunnable);

        long delay = Prefs.getInstance(mAppContext).getMotionDetectionMinPauseTime() * 1000;
        // During this sleep period, all motion is ignored, this ensures that even if the motion sensor
        // keeps falsely triggering, waking the scanning, and then pausing again, there will still
        // be some guaranteed battery savings because of this sleep.
        AppGlobals.guiLogInfo("Sleep for " + delay + "ms", "green", false, false);
        mIsStartMotionSensorRunnableScheduled = true;
        mHandler.postDelayed(mStartMotionSensorRunnable, delay);

        mFalsePositiveFilter.updateLocation();
    }

    public void stop() {
        if (!mMotionSensor.isActive()) {
            return;
        }

        mHandler.removeCallbacks(null);
        mIsStartMotionSensorRunnableScheduled = false;

        mMotionSensor.stop();

        if (mFalsePositiveFilter.isGPSStillWarm()) {
            mFalsePositiveFilter.start();
        }
    }

    // Try to filter false positives by quickly checking to see if the user location has changed.
    // If the GPS has gone cold (~2 hours), then stop using this filter.
    FalsePositiveFilter mFalsePositiveFilter;
    static class FalsePositiveFilter {
        private final Context mContext;
        private final Handler mHandler = new Handler();
        Location mLastLocation;
        private final long mMinMotionChangeDistanceMeters = 10;
        long mTimeStartedMs;

        private final Runnable mStopListeningTimer = new Runnable() {
            public void run() {
                stop();
            }
        };

        LocationListener mListener = new LocationListener() {
            public void onLocationChanged(Location location) {
                stop();
                if (mLastLocation == null) {
                    return;
                }
                double dist = mLastLocation.distanceTo(location);
                AppGlobals.guiLogInfo("Distance moved: " + String.format("%.1f", dist) + " m", "green", false, false);

                if (dist < mMinMotionChangeDistanceMeters) {
                    AppGlobals.guiLogInfo("not moved", "green", true, false);
                    Intent sendIntent = new Intent(LocationChangeSensor.ACTION_LOCATION_NOT_CHANGING);
                    LocalBroadcastManager.getInstance(mContext).sendBroadcastSync(sendIntent);
                } else {
                    reset();
                }
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {}
            public void onProviderEnabled(String provider) {}
            public void onProviderDisabled(String provider) {}
        };

        public FalsePositiveFilter(Context mAppContext) {
            mContext = mAppContext;
        }

        void updateLocation() {
            stop();

            if (mTimeStartedMs == 0) {
                setTime();
            }

            if (mLastLocation == null) {
                AppGlobals.guiLogInfo("Updated location for false pos. filter");
                LocationManager lm = (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
                mLastLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
        }

        void start() {
            stop();

            if (mLastLocation == null) {
                return;
            }
            LocationManager lm = (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, mListener);

            mHandler.postDelayed(mStopListeningTimer, 30 * 1000);
        }

        void stop() {
            LocationManager lm = (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
            lm.removeUpdates(mListener);
            mHandler.removeCallbacks(mStopListeningTimer);
        }

        private void setTime() {
            ISystemClock clock = (ISystemClock) ServiceLocator.getInstance().getService(ISystemClock.class);
            mTimeStartedMs = clock.currentTimeMillis();
        }

        public void reset() {
            mTimeStartedMs = 0;
            mLastLocation = null;
        }

        public boolean isGPSStillWarm() {
            if (mTimeStartedMs == 0) {
                setTime();
            }
            ISystemClock clock = (ISystemClock) ServiceLocator.getInstance().getService(ISystemClock.class);
            return clock.currentTimeMillis() - mTimeStartedMs < GPS_WARM_TIME_MS;
        }
    }
}
