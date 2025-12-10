package com.spagreen.linphonesdk;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import org.linphone.core.Call;
import org.linphone.core.CallParams;
import org.linphone.core.Core;
import org.linphone.core.CoreListenerStub;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;

public class IncomingCallActivity extends Activity {
    private static final String TAG = "IncomingCallActivity";
    
    private TextView callerNameText;
    private TextView callerNumberText;
    
    private Ringtone ringtone;
    private PowerManager.WakeLock wakeLock;
    
    private CoreListenerStub coreListener = new CoreListenerStub() {
        @Override
        public void onCallStateChanged(Core core, Call call, Call.State state, String message) {
            if (state == Call.State.End || state == Call.State.Released || state == Call.State.Error) {
                finish();
            }
        }
    };
    
    private BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            android.util.Log.d(TAG, "Received broadcast to close IncomingCallActivity");
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Show when locked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        
        // Dismiss keyguard
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, null);
        }
        
        // Wake lock
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK | 
            PowerManager.ACQUIRE_CAUSES_WAKEUP | 
            PowerManager.ON_AFTER_RELEASE,
            "Linphone:IncomingCall"
        );
        wakeLock.acquire(60000); // 60 seconds
        
        // Get caller info from intent
        String callerName = getIntent().getStringExtra("caller_name");
        String callerNumber = getIntent().getStringExtra("caller_number");
        
        if (callerName == null || callerName.isEmpty()) {
            callerName = "Unknown";
        }
        if (callerNumber == null) {
            callerNumber = "Unknown Number";
        }
        
        setContentView(createIncomingCallView(callerName, callerNumber));
        
        // Start ringtone
        playRingtone();
        
        // Register broadcast receiver to close this activity when notification accept is pressed
        IntentFilter filter = new IntentFilter("com.spagreen.linphonesdk.CLOSE_INCOMING_CALL");
        if (Build.VERSION.SDK_INT >= 33) { // Android 13+
            registerReceiver(closeReceiver, filter, 2); // RECEIVER_NOT_EXPORTED = 2
        } else {
            registerReceiver(closeReceiver, filter);
        }
        android.util.Log.d(TAG, "Registered broadcast receiver for closing activity");
        
        // Register listener
        Core core = LinphoneBackgroundService.getCore();
        if (core != null) {
            core.addListener(coreListener);
        }
    }

    private View createIncomingCallView(String callerName, String callerNumber) {
        // Inflate the XML layout
        View view = getLayoutInflater().inflate(R.layout.activity_incoming_call, null);
        
        // Find views
        callerNameText = view.findViewById(R.id.caller_name);
        callerNumberText = view.findViewById(R.id.caller_number);
        TextView callerInitial = view.findViewById(R.id.caller_initial);
        View acceptButton = view.findViewById(R.id.accept_button);
        View declineButton = view.findViewById(R.id.decline_button);
        
        // Set caller info
        callerNameText.setText(callerName);
        callerNumberText.setText(callerNumber);
        
        // Set caller initial (first letter of caller name)
        String initial = "?";
        if (callerName != null && !callerName.isEmpty() && !callerName.equals("Unknown")) {
            initial = callerName.substring(0, 1).toUpperCase();
        }
        callerInitial.setText(initial);
        
        // Set click listeners
        acceptButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                acceptCall();
            }
        });
        
        declineButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                declineCall();
            }
        });
        
        return view;
    }

    private void playRingtone() {
        try {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), notification);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.setLooping(true);
            }
            
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null && audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
                ringtone.play();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRingtone() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
    }

    private void acceptCall() {
        stopRingtone();
        
        Core core = LinphoneBackgroundService.getCore();
        if (core != null && core.getCallsNb() > 0) {
            Call call = core.getCurrentCall();
            if (call == null) call = core.getCalls()[0];
            if (call != null) {
                CallParams params = core.createCallParams(call);
                if (params != null) {
                    call.acceptWithParams(params);
                    
                    // Launch CallActivity immediately after accepting
                    android.content.Intent intent = new android.content.Intent(this, CallActivity.class);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra("caller_name", call.getRemoteAddress().getDisplayName());
                    intent.putExtra("caller_number", call.getRemoteAddress().getUsername());
                    startActivity(intent);
                }
            }
        }
        
        finish();
    }

    private void declineCall() {
        stopRingtone();
        
        Core core = LinphoneBackgroundService.getCore();
        if (core != null && core.getCallsNb() > 0) {
            Call call = core.getCurrentCall();
            if (call == null) call = core.getCalls()[0];
            if (call != null) {
                call.decline(org.linphone.core.Reason.Declined);
            }
        }
        
        finish();
    }

    @Override
    protected void onDestroy() {
        stopRingtone();
        
        // Unregister broadcast receiver
        try {
            unregisterReceiver(closeReceiver);
            android.util.Log.d(TAG, "Unregistered broadcast receiver");
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error unregistering broadcast receiver", e);
        }
        
        Core core = LinphoneBackgroundService.getCore();
        if (core != null) {
            core.removeListener(coreListener);
        }
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Prevent back button from closing the activity
    }
}
