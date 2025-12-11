package com.egytelecoms.hatif;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.ImageView;

import org.linphone.core.Call;
import org.linphone.core.Core;
import org.linphone.core.CoreListenerStub;
import org.linphone.core.AudioDevice;

public class CallActivity extends Activity {
    private static final String TAG = "CallActivity";

    private TextView callerNameText;
    private TextView callerNumberText;
    private TextView callStatusText;
    private TextView callTimerText;

    private View muteButton;
    private View speakerButton;
    private View holdButton;
    private View hangupButton;
    private View dtmfButton;
    private View transferButton;
    private View dtmfPanel;

    private boolean isMuted = false;
    private boolean isSpeaker = false;
    private boolean isOnHold = false;

    private Handler timerHandler;
    private Runnable timerRunnable;
    private long callStartTime = 0;
    private AudioManager audioManager;

    private CoreListenerStub coreListener = new CoreListenerStub() {
        @Override
        public void onCallStateChanged(Core core, Call call, Call.State state, String message) {
            runOnUiThread(() -> {
                updateCallStatus(state);

                if (state == Call.State.End || state == Call.State.Released || state == Call.State.Error) {
                    Log.d(TAG, "📴 Call ended, closing activity. State: " + state);

                    // Stop timer
                    if (timerHandler != null && timerRunnable != null) {
                        timerHandler.removeCallbacks(timerRunnable);
                    }

                    // Close activity completely and remove from recent apps
                    if (!isFinishing()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            finishAndRemoveTask();
                        } else {
                            finish();
                        }
                        Log.d(TAG, "✅ Activity closed and removed from recent apps");
                    }
                } else if (state == Call.State.Connected || state == Call.State.StreamsRunning) {
                    if (callStartTime == 0) {
                        callStartTime = System.currentTimeMillis();
                        startCallTimer();
                        Log.d(TAG, "⏱ Call timer started");
                    }
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ensure this activity appears on lock screen too
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        setContentView(createCallView());

        // Setup DTMF keypad AFTER setContentView so findViewById works
        setupDTMFKeypad();

        // Get caller info from intent
        String callerName = getIntent().getStringExtra("caller_name");
        String callerNumber = getIntent().getStringExtra("caller_number");

        if (callerName == null || callerName.isEmpty()) {
            callerName = "Unknown";
        }
        if (callerNumber == null) {
            callerNumber = "Unknown Number";
        }

        callerNameText.setText(callerName);
        callerNumberText.setText(callerNumber);

        // Set caller initial
        TextView callerInitial = findViewById(R.id.caller_initial);
        if (callerInitial != null && callerName != null && !callerName.isEmpty()) {
            callerInitial.setText(callerName.substring(0, 1).toUpperCase());
        }

        // Initialize audio routing - CRITICAL: Proper order for stability
        if (audioManager != null) {
            Log.d(TAG, "🔧 Initializing audio routing to earpiece");

            // Set mode first
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            // Disable speaker (use earpiece by default)
            audioManager.setSpeakerphoneOn(false);
            isSpeaker = false;

            // Verify audio routing after small delay
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (audioManager != null) {
                    boolean speakerStatus = audioManager.isSpeakerphoneOn();
                    int mode = audioManager.getMode();
                    Log.d(TAG, "✅ Audio initialized - Mode: " + mode + ", Speaker: " + speakerStatus);

                    // Force earpiece again if speaker is still on
                    if (speakerStatus) {
                        audioManager.setSpeakerphoneOn(false);
                        Log.d(TAG, "🔄 Forced earpiece mode again");
                    }
                }
            }, 150);
        }

        // CRITICAL FIX: Auto-accept call if launched from notification accept button
        boolean acceptOnCreate = getIntent().getBooleanExtra("accept_on_create", false);
        if (acceptOnCreate) {
            Log.i(TAG, "🎯 accept_on_create=true, auto-accepting call from notification");
            // Accept the call immediately
            Core core = LinphoneBackgroundService.getCore();
            if (core != null) {
                Call currentCall = core.getCurrentCall();
                if (currentCall == null && core.getCallsNb() > 0) {
                    currentCall = core.getCalls()[0];
                }
                if (currentCall != null && currentCall.getState() == Call.State.IncomingReceived) {
                    try {
                        currentCall.accept();
                        Log.d(TAG, "✓ Call accepted successfully from CallActivity.onCreate()");
                        // Close IncomingCallActivity if it's open
                        sendBroadcast(new Intent("com.egytelecoms.hatif.CLOSE_INCOMING_CALL"));
                        // Dismiss incoming notification
                        if (LinphoneBackgroundService.getInstance() != null) {
                            LinphoneBackgroundService.getInstance().dismissIncomingCallNotification();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Failed to accept call", e);
                    }
                } else {
                    Log.w(TAG, "⚠️ No incoming call found to accept");
                }
            } else {
                Log.e(TAG, "❌ Core is null, cannot accept call");
            }
        }

        // Hide notification when call screen is visible
        LinphoneBackgroundService.setCallActivityVisible(true);

        // Register listener
        Core core = LinphoneBackgroundService.getCore();
        if (core != null) {
            core.addListener(coreListener);

            // Check current call state
            Call currentCall = core.getCurrentCall();
            if (currentCall != null) {
                updateCallStatus(currentCall.getState());

                if (currentCall.getState() == Call.State.Connected ||
                        currentCall.getState() == Call.State.StreamsRunning) {
                    callStartTime = System.currentTimeMillis() - (currentCall.getDuration() * 1000);
                    startCallTimer();
                }
            }
        }
    }

    private View createCallView() {
        View view = getLayoutInflater().inflate(R.layout.activity_call, null);

        callerNameText = view.findViewById(R.id.caller_name);
        callerNumberText = view.findViewById(R.id.caller_number);
        callStatusText = view.findViewById(R.id.call_status);
        callTimerText = view.findViewById(R.id.call_timer);

        muteButton = view.findViewById(R.id.mute_button);
        speakerButton = view.findViewById(R.id.speaker_button);
        holdButton = view.findViewById(R.id.hold_button);
        hangupButton = view.findViewById(R.id.hangup_button);
        dtmfButton = view.findViewById(R.id.dtmf_button);
        transferButton = view.findViewById(R.id.transfer_button);
        dtmfPanel = view.findViewById(R.id.dtmf_panel);

        muteButton.setOnClickListener(v -> toggleMute());
        speakerButton.setOnClickListener(v -> toggleSpeaker());
        holdButton.setOnClickListener(v -> toggleHold());
        hangupButton.setOnClickListener(v -> hangupCall());
        dtmfButton.setOnClickListener(v -> toggleDTMFPanel());
        transferButton.setOnClickListener(v -> showTransferDialog());

        updateButtonStates();

        return view;
    }

    private void setupDTMFKeypad() {
        // Setup hide button click listener
        View dtmfHideButton = findViewById(R.id.dtmf_hide);
        if (dtmfHideButton != null) {
            dtmfHideButton.setOnClickListener(v -> toggleDTMFPanel());
            Log.d(TAG, "DTMF hide button listener attached");
        } else {
            Log.e(TAG, "DTMF hide button not found!");
        }

        // Setup DTMF digit buttons
        int[] dtmfIds = {
                R.id.dtmf_1, R.id.dtmf_2, R.id.dtmf_3,
                R.id.dtmf_4, R.id.dtmf_5, R.id.dtmf_6,
                R.id.dtmf_7, R.id.dtmf_8, R.id.dtmf_9,
                R.id.dtmf_star, R.id.dtmf_0, R.id.dtmf_hash
        };

        String[] digits = { "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#" };

        for (int i = 0; i < dtmfIds.length; i++) {
            TextView button = findViewById(dtmfIds[i]);
            if (button != null) {
                final String digit = digits[i];
                button.setOnClickListener(v -> {
                    sendDTMF(digit);
                    // Visual feedback
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100)
                            .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()).start();
                });
                Log.d(TAG, "DTMF button " + digit + " listener attached");
            } else {
                Log.e(TAG, "DTMF button for digit " + digits[i] + " not found!");
            }
        }

        if (dtmfPanel != null) {
            dtmfPanel.setVisibility(View.GONE);
        }
    }

    private void sendDTMF(String digit) {
        Core core = LinphoneBackgroundService.getCore();
        if (core != null && core.getCurrentCall() != null) {
            core.getCurrentCall().sendDtmf(digit.charAt(0));
            Log.d(TAG, "DTMF sent: " + digit);
        }
    }

    private void toggleDTMFPanel() {
        if (dtmfPanel != null) {
            boolean isVisible = dtmfPanel.getVisibility() == View.VISIBLE;

            if (isVisible) {
                // Hide with animation
                dtmfPanel.animate()
                        .alpha(0f)
                        .translationY(dtmfPanel.getHeight())
                        .setDuration(250)
                        .withEndAction(() -> dtmfPanel.setVisibility(View.GONE))
                        .start();
            } else {
                // Show with animation
                dtmfPanel.setVisibility(View.VISIBLE);
                dtmfPanel.setAlpha(0f);
                dtmfPanel.setTranslationY(dtmfPanel.getHeight());
                dtmfPanel.animate()
                        .alpha(1f)
                        .translationY(0)
                        .setDuration(300)
                        .start();
            }

            Log.d(TAG, "DTMF panel toggled: " + (isVisible ? "hidden" : "visible"));
        }
    }

    private void showTransferDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Transfer Call");

        final EditText input = new EditText(this);
        input.setHint("Enter SIP address (e.g., sip:1234@domain.com)");
        input.setPadding(40, 40, 40, 40);
        builder.setView(input);

        builder.setPositiveButton("Transfer", (dialog, which) -> {
            String address = input.getText().toString().trim();
            if (!address.isEmpty()) {
                transferCall(address);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void transferCall(String address) {
        Core core = LinphoneBackgroundService.getCore();
        if (core != null && core.getCurrentCall() != null) {
            try {
                core.getCurrentCall().transfer(address);
                Log.d(TAG, "Call transferred to: " + address);
            } catch (Exception e) {
                Log.e(TAG, "Error transferring call", e);
            }
        }
    }

    private void toggleMute() {
        Core core = LinphoneBackgroundService.getCore();
        if (core != null) {
            isMuted = !isMuted;
            core.enableMic(!isMuted);
            updateButtonStates();
            Log.d(TAG, "Microphone muted: " + isMuted);
        }
    }

    private void toggleSpeaker() {
        Core core = LinphoneBackgroundService.getCore();
        if (core == null || audioManager == null) {
            Log.e(TAG, "Core or AudioManager is null");
            return;
        }

        // Toggle state
        isSpeaker = !isSpeaker;
        Log.d(TAG, "🔊 Toggling speaker to: " + isSpeaker);

        try {
            // Get current call
            Call currentCall = core.getCurrentCall();
            if (currentCall == null && core.getCallsNb() > 0) {
                currentCall = core.getCalls()[0];
            }

            if (currentCall == null) {
                Log.e(TAG, "No active call found");
                return;
            }

            final Call finalCall = currentCall;

            // Update UI immediately
            updateButtonStates();

            // CRITICAL FIX: Change AudioManager AND force Linphone to reload audio
            if (isSpeaker) {
                Log.d(TAG, "▶ Enabling speaker mode");

                // Stop bluetooth
                if (audioManager.isBluetoothScoOn()) {
                    audioManager.stopBluetoothSco();
                    audioManager.setBluetoothScoOn(false);
                }

                // Enable speaker via AudioManager
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);

                // Boost volume
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
                if (currentVolume < maxVolume * 0.7) {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (int) (maxVolume * 0.8), 0);
                }

                // CRITICAL: Force Linphone to reload audio devices
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        // Find and set speaker device
                        AudioDevice[] devices = core.getAudioDevices();
                        AudioDevice speakerDevice = null;

                        for (AudioDevice device : devices) {
                            if (device.getType() == AudioDevice.Type.Speaker) {
                                speakerDevice = device;
                                break;
                            }
                        }

                        if (speakerDevice != null) {
                            // Set on both call and core
                            finalCall.setOutputAudioDevice(speakerDevice);
                            core.setOutputAudioDevice(speakerDevice);
                            Log.d(TAG, "✅ Forced Linphone to use speaker: " + speakerDevice.getDeviceName());
                        } else {
                            Log.e(TAG, "❌ No speaker device found");
                        }

                        // Final verification
                        boolean actualState = audioManager.isSpeakerphoneOn();
                        Log.d(TAG, "🔍 Final check - AudioManager: " + actualState);

                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error setting speaker", e);
                    }
                }, 150);

            } else {
                Log.d(TAG, "▶ Disabling speaker, using earpiece");

                // Disable speaker via AudioManager
                audioManager.setSpeakerphoneOn(false);
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

                // CRITICAL: Force Linphone to reload audio devices
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        // Find and set earpiece device
                        AudioDevice[] devices = core.getAudioDevices();
                        AudioDevice earpieceDevice = null;

                        for (AudioDevice device : devices) {
                            if (device.getType() == AudioDevice.Type.Earpiece) {
                                earpieceDevice = device;
                                break;
                            }
                        }

                        if (earpieceDevice != null) {
                            // Set on both call and core
                            finalCall.setOutputAudioDevice(earpieceDevice);
                            core.setOutputAudioDevice(earpieceDevice);
                            Log.d(TAG, "✅ Forced Linphone to use earpiece: " + earpieceDevice.getDeviceName());
                        } else {
                            Log.w(TAG, "⚠️ No earpiece device found");
                        }

                        // Final verification
                        boolean actualState = audioManager.isSpeakerphoneOn();
                        Log.d(TAG, "🔍 Final check - AudioManager: " + actualState);

                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error setting earpiece", e);
                    }
                }, 150);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Critical error toggling speaker", e);
            e.printStackTrace();
            isSpeaker = !isSpeaker;
            updateButtonStates();
        }
    }

    private void toggleHold() {
        Core core = LinphoneBackgroundService.getCore();
        if (core != null && core.getCallsNb() > 0) {
            Call call = core.getCurrentCall();
            if (call == null)
                call = core.getCalls()[0];

            if (call != null) {
                try {
                    isOnHold = !isOnHold;
                    if (isOnHold) {
                        call.pause();
                    } else {
                        call.resume();
                    }
                    updateButtonStates();
                    Log.d(TAG, "Call on hold: " + isOnHold);
                } catch (Exception e) {
                    Log.e(TAG, "Error toggling hold", e);
                }
            }
        }
    }

    private void updateButtonStates() {
        runOnUiThread(() -> {
            // Update mute button
            if (muteButton != null) {
                ImageView muteIcon = muteButton.findViewById(R.id.mute_icon);
                TextView muteLabel = muteButton.findViewById(R.id.mute_label);
                if (muteIcon != null) {
                    muteIcon.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic_on);
                }
                if (muteLabel != null) {
                    muteLabel.setText(isMuted ? "Unmute" : "Mute");
                }
                muteButton.setAlpha(isMuted ? 1.0f : 0.85f);
            }

            // Update speaker button
            if (speakerButton != null) {
                ImageView speakerIcon = speakerButton.findViewById(R.id.speaker_icon);
                TextView speakerLabel = speakerButton.findViewById(R.id.speaker_label);
                if (speakerIcon != null) {
                    speakerIcon.setImageResource(R.drawable.ic_speaker);
                    speakerIcon.setAlpha(isSpeaker ? 1.0f : 0.5f);
                }
                if (speakerLabel != null) {
                    speakerLabel.setText(isSpeaker ? "Speaker ON" : "Speaker");
                }
                speakerButton.setAlpha(isSpeaker ? 1.0f : 0.85f);
            }

            // Update hold button
            if (holdButton != null) {
                ImageView holdIcon = holdButton.findViewById(R.id.hold_icon);
                TextView holdLabel = holdButton.findViewById(R.id.hold_label);
                if (holdIcon != null) {
                    holdIcon.setImageResource(R.drawable.ic_pause);
                    holdIcon.setAlpha(isOnHold ? 1.0f : 0.5f);
                }
                if (holdLabel != null) {
                    holdLabel.setText(isOnHold ? "Resume" : "Hold");
                }
                holdButton.setAlpha(isOnHold ? 1.0f : 0.85f);
            }
        });
    }

    private void hangupCall() {
        Log.d(TAG, "Hangup button pressed");
        Core core = LinphoneBackgroundService.getCore();
        if (core != null && core.getCallsNb() > 0) {
            Call call = core.getCurrentCall();
            if (call == null)
                call = core.getCalls()[0];

            if (call != null) {
                Log.d(TAG, "Terminating call, current state: " + call.getState());
                call.terminate();
            }
        } else {
            Log.w(TAG, "No active call found, finishing activity anyway");
        }

        // Always finish the activity when hangup is pressed
        if (!isFinishing()) {
            finish();
        }
    }

    private void updateCallStatus(Call.State state) {
        String status;
        switch (state) {
            case IncomingReceived:
                status = "Incoming call...";
                break;
            case OutgoingInit:
            case OutgoingProgress:
            case OutgoingRinging:
                status = "Calling...";
                break;
            case Connected:
            case StreamsRunning:
                status = "Connected";
                break;
            case Paused:
            case PausedByRemote:
                status = "On Hold";
                break;
            case Updating:
            case UpdatedByRemote:
                status = "Updating...";
                break;
            default:
                status = "Call ended";
                break;
        }
        callStatusText.setText(status);
    }

    private void startCallTimer() {
        timerHandler = new Handler(Looper.getMainLooper());
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - callStartTime;
                int seconds = (int) (elapsed / 1000) % 60;
                int minutes = (int) (elapsed / 1000 / 60) % 60;
                int hours = (int) (elapsed / 1000 / 60 / 60);

                String time;
                if (hours > 0) {
                    time = String.format("%02d:%02d:%02d", hours, minutes, seconds);
                } else {
                    time = String.format("%02d:%02d", minutes, seconds);
                }

                callTimerText.setText(time);
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);
    }

    @Override
    public void onBackPressed() {
        // When back is pressed, return to Flutter app instead of closing
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        }
        // Don't call super.onBackPressed() to keep call activity alive in background
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        // Reset audio routing
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(false);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }

        Core core = LinphoneBackgroundService.getCore();
        if (core != null) {
            core.removeListener(coreListener);
        }

        LinphoneBackgroundService.setCallActivityVisible(false);
    }
}
