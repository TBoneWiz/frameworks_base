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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Color;
import android.hardware.fingerprint.FingerprintManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;

import com.android.internal.app.IBatteryStats;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.statusbar.phone.LockIcon;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;

/**
 * Controls the indications and error messages shown on the Keyguard
 */
public class KeyguardIndicationController {

    private static final String TAG = "KeyguardIndicationController";
    private static final boolean DEBUG_CHARGING_CURRENT = false;

    private static final int MSG_HIDE_TRANSIENT = 1;
    private static final int MSG_CLEAR_FP_MSG = 2;
    private static final long TRANSIENT_FP_ERROR_TIMEOUT = 1300;

    private final Context mContext;
    private final KeyguardIndicationTextView mTextView;
    private final IBatteryStats mBatteryInfo;

    private final int mSlowThreshold;
    private final int mFastThreshold;
    private final LockIcon mLockIcon;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    private String mRestingIndication;
    private String mTransientIndication;
    private int mTransientTextColor;
    private boolean mVisible;

    private boolean mPowerPluggedIn;
    private boolean mPowerCharged;
    private int mChargingSpeed;
    private int mChargingCurrent;
    private String mMessageToShowOnScreenOn;
    private static final String CURRENT_NOW = "/sys/class/power_supply/battery/current_now";

    public KeyguardIndicationController(Context context, KeyguardIndicationTextView textView,
                                        LockIcon lockIcon) {
        mContext = context;
        mTextView = textView;
        mLockIcon = lockIcon;

        Resources res = context.getResources();
        mSlowThreshold = res.getInteger(R.integer.config_chargingSlowlyThreshold);
        mFastThreshold = res.getInteger(R.integer.config_chargingFastThreshold);


        mBatteryInfo = IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));
        KeyguardUpdateMonitor.getInstance(context).registerCallback(mUpdateMonitor);
        context.registerReceiverAsUser(
                mReceiver, UserHandle.OWNER, new IntentFilter(Intent.ACTION_TIME_TICK), null, null);
    }

    public void setVisible(boolean visible) {
        mVisible = visible;
        mTextView.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            hideTransientIndication();
            updateIndication();
        }
    }

    /**
     * Sets the indication that is shown if nothing else is showing.
     */
    public void setRestingIndication(String restingIndication) {
        mRestingIndication = restingIndication;
        updateIndication();
    }

    /**
     * Hides transient indication in {@param delayMs}.
     */
    public void hideTransientIndicationDelayed(long delayMs) {
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_HIDE_TRANSIENT), delayMs);
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(int transientIndication) {
        showTransientIndication(mContext.getResources().getString(transientIndication));
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(String transientIndication) {
        showTransientIndication(transientIndication, Color.WHITE);
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(String transientIndication, int textColor) {
        mTransientIndication = transientIndication;
        mTransientTextColor = textColor;
        mHandler.removeMessages(MSG_HIDE_TRANSIENT);
        updateIndication();
    }

    /**
     * Hides transient indication.
     */
    public void hideTransientIndication() {
        if (mTransientIndication != null) {
            mTransientIndication = null;
            mHandler.removeMessages(MSG_HIDE_TRANSIENT);
            updateIndication();
        }
    }

    public void cleanup() {
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mUpdateMonitor);
        mContext.unregisterReceiver(mReceiver);
    }

    private void updateIndication() {
        if (mVisible) {
            mTextView.switchIndication(computeIndication());
            mTextView.setTextColor(computeColor());
        }
    }

    private int computeColor() {
        if (!TextUtils.isEmpty(mTransientIndication)) {
            return mTransientTextColor;
        }
        return Color.WHITE;
    }

    private String computeIndication() {
        if (!TextUtils.isEmpty(mTransientIndication)) {
            return mTransientIndication;
        }
        if (mPowerPluggedIn) {
            String indication = computePowerIndication();
            if (DEBUG_CHARGING_CURRENT) {
                indication += ",  " + (mChargingCurrent / 1000) + " mA";
            }
            return indication;
        }
        return mRestingIndication;
    }

    private int getCurrentValue() {
        File f = null;
        f = new File(CURRENT_NOW);
        if (f.exists()) {
             return getCurrentValue(f);
        } else {
             return 100000000;
        }
    }

    private int getCurrentValue(File file) {
        String line = null;
        int value = 0;
        FileInputStream fs = null;
        DataInputStream ds = null;
        try {
            fs = new FileInputStream(file);
            ds = new DataInputStream(fs);
            line = ds.readLine();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                fs.close();
                ds.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        if (line != null) {
            try {
                value = Integer.parseInt(line);
            } catch (NumberFormatException nfe) {
                value = 0;
            }
        }
        return value;
    }

    private String computePowerIndication() {
        if (mPowerCharged) {
            return mContext.getResources().getString(R.string.keyguard_charged);
        }

        // Try fetching charging time from battery stats.
        long chargingTimeRemaining = 0;
        try {
            chargingTimeRemaining = mBatteryInfo.computeChargeTimeRemaining();

        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IBatteryStats: ", e);
        }
        final boolean hasChargingTime = chargingTimeRemaining > 0;

        int chargingId;
        if ((getCurrentValue() != 100000000) && (getCurrentValue() > 10)) {
            if (mChargingCurrent > (mFastThreshold / 1000)) {
                chargingId = hasChargingTime
                        ? R.string.keyguard_indication_charging_time_fast
                        : R.string.keyguard_plugged_in_charging_fast;
            }
            else if (mChargingCurrent < (mSlowThreshold / 1000)) {
                chargingId = hasChargingTime
                        ? R.string.keyguard_indication_charging_time_slowly
                        : R.string.keyguard_plugged_in_charging_slowly;
            }
            else {
                chargingId = hasChargingTime
                        ? R.string.keyguard_indication_charging_time
                        : R.string.keyguard_plugged_in;
            }
        }
        else {
            switch (mChargingSpeed) {
                case KeyguardUpdateMonitor.BatteryStatus.CHARGING_FAST:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_fast
                            : R.string.keyguard_plugged_in_charging_fast;
                    break;
                case KeyguardUpdateMonitor.BatteryStatus.CHARGING_SLOWLY:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_slowly
                            : R.string.keyguard_plugged_in_charging_slowly;
                    break;
                default:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time
                            : R.string.keyguard_plugged_in;
                    break;
            }
        }

        String chargingCurrent = "";

        if (mChargingCurrent != 0) {
            chargingCurrent = "\n" + mChargingCurrent + " mA";
        }

        if (hasChargingTime) {
            String chargingTimeFormatted = Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                    mContext, chargingTimeRemaining);
            if (getCurrentValue() == 100000000) {  
                return mContext.getResources().getString(chargingId, chargingTimeFormatted);
            } else if (getCurrentValue() >= 100) {        
                try {
                    Thread.sleep(200);
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                String chargingText = mContext.getResources().getString(chargingId, chargingTimeFormatted);
                return chargingText + chargingCurrent;
            } else {
                try {
                    Thread.sleep(700);
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                String chargingText = mContext.getResources().getString(chargingId, chargingTimeFormatted);
                return chargingText + chargingCurrent;
            }
        } else {
            if (getCurrentValue() == 100000000) {
                return mContext.getResources().getString(chargingId);
            }
            if (getCurrentValue() >= 100) {
                try {
                    Thread.sleep(200);
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                String chargingText = mContext.getResources().getString(chargingId);
                return chargingText + chargingCurrent;
            }
            else {
                try {
                    Thread.sleep(700);
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                String chargingText = mContext.getResources().getString(chargingId);
                return chargingText + chargingCurrent;
            }
        }
    }

    KeyguardUpdateMonitorCallback mUpdateMonitor = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onRefreshBatteryInfo(KeyguardUpdateMonitor.BatteryStatus status) {
            boolean isChargingOrFull = status.status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status.status == BatteryManager.BATTERY_STATUS_FULL;
            mPowerPluggedIn = status.isPluggedIn() && isChargingOrFull;
            mPowerCharged = status.isCharged();
            if (getCurrentValue() == 100000000) {
                mChargingCurrent = status.maxChargingCurrent;
                mChargingSpeed = status.getChargingSpeed(mSlowThreshold, mFastThreshold);
            } else if (getCurrentValue() >= 100) {
                try {
                    Thread.sleep(200);
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                mChargingCurrent = getCurrentValue();
            } else {
                try {
                    Thread.sleep(700);
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                mChargingCurrent = getCurrentValue();
            }
            updateIndication();
        }

        @Override
        public void onFingerprintHelp(int msgId, String helpString) {
            KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
            if (!updateMonitor.isUnlockingWithFingerprintAllowed()) {
                return;
            }
            int errorColor = mContext.getResources().getColor(R.color.system_warning_color, null);
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.showBouncerMessage(helpString, errorColor);
            } else if (updateMonitor.isDeviceInteractive()) {
                mLockIcon.setTransientFpError(true);
                showTransientIndication(helpString, errorColor);
                mHandler.removeMessages(MSG_CLEAR_FP_MSG);
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CLEAR_FP_MSG),
                        TRANSIENT_FP_ERROR_TIMEOUT);
            }
        }

        @Override
        public void onFingerprintError(int msgId, String errString) {
            KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
            if (!updateMonitor.isUnlockingWithFingerprintAllowed()
                    || msgId == FingerprintManager.FINGERPRINT_ERROR_CANCELED) {
                return;
            }
            int errorColor = mContext.getResources().getColor(R.color.system_warning_color, null);
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.showBouncerMessage(errString, errorColor);
            } else if (updateMonitor.isDeviceInteractive()) {
                    showTransientIndication(errString, errorColor);
                    // We want to keep this message around in case the screen was off
                    mHandler.removeMessages(MSG_HIDE_TRANSIENT);
                    hideTransientIndicationDelayed(5000);
             } else {
                    mMessageToShowOnScreenOn = errString;
            }
        }

        @Override
        public void onScreenTurnedOn() {
            if (mMessageToShowOnScreenOn != null) {
                int errorColor = mContext.getResources().getColor(R.color.system_warning_color,
                        null);
                showTransientIndication(mMessageToShowOnScreenOn, errorColor);
                // We want to keep this message around in case the screen was off
                mHandler.removeMessages(MSG_HIDE_TRANSIENT);
                hideTransientIndicationDelayed(5000);
                mMessageToShowOnScreenOn = null;
            }
        }

        @Override
        public void onFingerprintRunningStateChanged(boolean running) {
            if (running) {
                mMessageToShowOnScreenOn = null;
            }
        }
    };

    BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mVisible) {
                updateIndication();
            }
        }
    };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_HIDE_TRANSIENT && mTransientIndication != null) {
                mTransientIndication = null;
                updateIndication();
            } else if (msg.what == MSG_CLEAR_FP_MSG) {
                mLockIcon.setTransientFpError(false);
                hideTransientIndication();
            }
        }
    };

    public void setStatusBarKeyguardViewManager(
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }
}
