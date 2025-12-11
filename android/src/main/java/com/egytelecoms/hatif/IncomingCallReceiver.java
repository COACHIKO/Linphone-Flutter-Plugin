package com.egytelecoms.hatif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * BroadcastReceiver to launch IncomingCallActivity from terminated state.
 * This ensures the incoming call screen appears even when app is completely
 * closed.
 */
public class IncomingCallReceiver extends BroadcastReceiver {
    private static final String TAG = "IncomingCallReceiver";

    public static final String ACTION_INCOMING_CALL = "com.egytelecoms.hatif.ACTION_INCOMING_CALL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_INCOMING_CALL.equals(intent.getAction())) {
            Log.d(TAG, "📞 Incoming call broadcast received - launching IncomingCallActivity");

            String callerName = intent.getStringExtra("caller_name");
            String callerNumber = intent.getStringExtra("caller_number");

            // Launch IncomingCallActivity
            Intent activityIntent = new Intent(context, IncomingCallActivity.class);
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            activityIntent.putExtra("caller_name", callerName);
            activityIntent.putExtra("caller_number", callerNumber);

            try {
                context.startActivity(activityIntent);
                Log.d(TAG, "✓ IncomingCallActivity launched from broadcast");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to launch IncomingCallActivity", e);
            }
        }
    }
}
