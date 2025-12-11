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
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.linphoneflutterplugin.IncomingCallAnimationHelper;

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

    // Swipe gesture views
    private FrameLayout swipeButton;
    private FrameLayout swipeContainer;
    private LinearLayout declineHint;
    private LinearLayout acceptHint;
    private float initialX;
    private float swipeThreshold = 200f; // pixels to trigger action

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

        // CRITICAL: Make sure this activity appears above EVERYTHING including lock
        // screen
        Window window = getWindow();

        // For Android 8.0+ (Oreo) - use new APIs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            keyguardManager.requestDismissKeyguard(this, null);
        } else {
            // For older versions - use window flags
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }

        // Essential flags for all versions
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);

        // Make it full screen for better visibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(getResources().getColor(android.R.color.black));
        }

        // Wake lock - ensure screen turns on and stays on
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK |
                        PowerManager.ACQUIRE_CAUSES_WAKEUP |
                        PowerManager.ON_AFTER_RELEASE,
                "Linphone:IncomingCall");
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

        // Register broadcast receiver to close this activity when notification accept
        // is pressed
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

        // Find animation views
        View pulseRingOuter = view.findViewById(R.id.pulse_ring_outer);
        View pulseRingInner = view.findViewById(R.id.pulse_ring_inner);
        View avatarContainer = view.findViewById(R.id.avatar_container);
        LinearLayout callerInfo = view.findViewById(R.id.caller_info);
        swipeContainer = view.findViewById(R.id.swipe_container);
        declineHint = view.findViewById(R.id.decline_hint);
        acceptHint = view.findViewById(R.id.accept_hint);
        swipeButton = view.findViewById(R.id.swipe_button);
        View swipeInstruction = view.findViewById(R.id.swipe_instruction);

        // Set caller info
        callerNameText.setText(callerName);
        callerNumberText.setText(callerNumber);

        // Set caller initial (first letter of caller name)
        String initial = "?";
        if (callerName != null && !callerName.isEmpty() && !callerName.equals("Unknown")) {
            initial = callerName.substring(0, 1).toUpperCase();
        }
        callerInitial.setText(initial);

        // Start all animations
        IncomingCallAnimationHelper.startAllAnimations(
                pulseRingOuter,
                pulseRingInner,
                avatarContainer,
                callerInfo,
                swipeContainer,
                declineHint,
                acceptHint,
                swipeButton,
                swipeInstruction);

        // Set up swipe gesture
        setupSwipeGesture();

        return view;
    }

    private void setupSwipeGesture() {
        swipeButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = event.getRawX();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - initialX;

                        // Constrain movement to container width
                        float maxDistance = swipeContainer.getWidth() / 2f - swipeButton.getWidth() / 2f;
                        deltaX = Math.max(-maxDistance, Math.min(maxDistance, deltaX));

                        // Animate button during drag
                        IncomingCallAnimationHelper.animateSwipeDrag(swipeButton, deltaX, maxDistance);

                        // Highlight hints based on direction
                        if (Math.abs(deltaX) > swipeThreshold / 2) {
                            if (deltaX > 0) {
                                IncomingCallAnimationHelper.animateHintGlow(acceptHint, true);
                                IncomingCallAnimationHelper.animateHintGlow(declineHint, false);
                            } else {
                                IncomingCallAnimationHelper.animateHintGlow(acceptHint, false);
                                IncomingCallAnimationHelper.animateHintGlow(declineHint, true);
                            }
                        } else {
                            IncomingCallAnimationHelper.animateHintGlow(acceptHint, false);
                            IncomingCallAnimationHelper.animateHintGlow(declineHint, false);
                        }

                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float finalDeltaX = event.getRawX() - initialX;

                        if (finalDeltaX > swipeThreshold) {
                            // Swipe right - Accept
                            IncomingCallAnimationHelper.animateSwipeAccept(swipeButton, swipeContainer, new Runnable() {
                                @Override
                                public void run() {
                                    acceptCall();
                                }
                            });
                        } else if (finalDeltaX < -swipeThreshold) {
                            // Swipe left - Decline
                            IncomingCallAnimationHelper.animateSwipeDecline(swipeButton, swipeContainer,
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            declineCall();
                                        }
                                    });
                        } else {
                            // Return to center
                            IncomingCallAnimationHelper.animateSwipeReturn(swipeButton);
                            IncomingCallAnimationHelper.animateHintGlow(acceptHint, false);
                            IncomingCallAnimationHelper.animateHintGlow(declineHint, false);
                        }

                        return true;
                }
                return false;
            }
        });
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
            if (call == null)
                call = core.getCalls()[0];
            if (call != null) {
                CallParams params = core.createCallParams(call);
                if (params != null) {
                    call.acceptWithParams(params);

                    // Launch CallActivity immediately after accepting
                    android.content.Intent intent = new android.content.Intent(this, CallActivity.class);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
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
            if (call == null)
                call = core.getCalls()[0];
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
