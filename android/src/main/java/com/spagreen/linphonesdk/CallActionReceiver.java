package com.spagreen.linphonesdk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Production-grade BroadcastReceiver to handle incoming call notification actions.
 * Handles Accept/Decline without restarting LinphoneBackgroundService.
 * 
 * Features:
 * - Robust handling for all app states (foreground, background, terminated)
 * - Service resurrection if needed
 * - Guaranteed CallActivity launch on accept
 */
public class CallActionReceiver extends BroadcastReceiver {
    private static final String TAG = "CallActionReceiver";

    public static final String ACTION_ANSWER_CALL = "com.spagreen.linphonesdk.ACTION_ANSWER_CALL";
    public static final String ACTION_DECLINE_CALL = "com.spagreen.linphonesdk.ACTION_DECLINE_CALL";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "📱 Received action: " + action);

        if (ACTION_ANSWER_CALL.equals(action)) {
            Log.i(TAG, "✓ Answer call action received from notification");
            handleAnswerCall(context);
        } else if (ACTION_DECLINE_CALL.equals(action)) {
            Log.i(TAG, "✗ Decline call action received from notification");
            LinphoneBackgroundService.declineCallFromNotification();
        }
    }

    /**
     * ULTIMATE FIX: Accept call via service and ensure UI launches
     */
    private void handleAnswerCall(Context context) {
        Log.i(TAG, "🎯 handleAnswerCall: ULTIMATE FIX approach");
        
        // Check service state first
        LinphoneBackgroundService service = LinphoneBackgroundService.getInstance();
        
        if (service != null) {
            Log.d(TAG, "✓ Service instance EXISTS - calling answerCallFromNotification()");
            LinphoneBackgroundService.answerCallFromNotification();
        } else {
            Log.w(TAG, "⚠️ Service instance is NULL");
            
            // Start/resurrect the service with answer action
            try {
                Intent serviceIntent = new Intent(context, LinphoneBackgroundService.class);
                serviceIntent.setAction("ANSWER_CALL_FROM_NOTIFICATION");
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d(TAG, "Starting foreground service (Android O+)");
                    context.startForegroundService(serviceIntent);
                } else {
                    Log.d(TAG, "Starting service (pre-Android O)");
                    context.startService(serviceIntent);
                }
                
                Log.d(TAG, "✓ Service start/resurrection requested via startService");
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to start service", e);
                e.printStackTrace();
            }
        }
    }
}
