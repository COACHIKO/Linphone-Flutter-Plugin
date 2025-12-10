package com.spagreen.linphonesdk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BroadcastReceiver to handle incoming call notification actions (Accept/Decline)
 * without restarting the LinphoneBackgroundService.
 */
public class CallActionReceiver extends BroadcastReceiver {
    private static final String TAG = "CallActionReceiver";
    
    public static final String ACTION_ANSWER_CALL = "com.spagreen.linphonesdk.ACTION_ANSWER_CALL";
    public static final String ACTION_DECLINE_CALL = "com.spagreen.linphonesdk.ACTION_DECLINE_CALL";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received action: " + action);
        
        if (ACTION_ANSWER_CALL.equals(action)) {
            Log.i(TAG, "Answer call action received from notification");
            LinphoneBackgroundService.answerCallFromNotification();
        } else if (ACTION_DECLINE_CALL.equals(action)) {
            Log.i(TAG, "Decline call action received from notification");
            LinphoneBackgroundService.declineCallFromNotification();
        }
    }
}
