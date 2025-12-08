package com.spagreen.linphonesdk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
                    finish();
                } else if (state == Call.State.Connected || state == Call.State.StreamsRunning) {
                    if (callStartTime == 0) {
                        callStartTime = System.currentTimeMillis();
                        startCallTimer();
                    }
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        
        setContentView(createCallView());
        
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
        
        // Initialize audio routing - default to earpiece
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
            isSpeaker = false;
            Log.d(TAG, "Audio initialized to earpiece mode");
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
        
        setupDTMFKeypad();
        updateButtonStates();
        
        return view;
    }
    
    private void setupDTMFKeypad() {
        if (dtmfPanel != null) {
            GridLayout grid = (GridLayout) dtmfPanel;
            String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
            
            // Fixed button size for perfect circular buttons
            int buttonSize = 90; // Fixed size in dp converted to pixels
            float density = getResources().getDisplayMetrics().density;
            int buttonSizePx = (int) (buttonSize * density);
            
            for (String digit : digits) {
                TextView button = new TextView(this);
                button.setText(digit);
                button.setTextSize(32);
                button.setTextColor(0xFFFFFFFF);
                button.setGravity(android.view.Gravity.CENTER);
                button.setTypeface(null, android.graphics.Typeface.BOLD);
                
                // Create a circular background
                android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
                drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                drawable.setColor(0x50FFFFFF); // Semi-transparent white
                drawable.setStroke(4, 0x90FFFFFF);
                button.setBackground(drawable);
                
                final String digitToSend = digit;
                button.setOnClickListener(v -> {
                    sendDTMF(digitToSend);
                    // Visual feedback
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction(() -> 
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    ).start();
                });
                
                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                params.width = buttonSizePx;
                params.height = buttonSizePx;
                params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                params.setMargins(12, 12, 12, 12);
                button.setLayoutParams(params);
                
                grid.addView(button);
            }
            
            dtmfPanel.setVisibility(View.GONE);
        }
    }
    
    private void toggleDTMFPanel() {
        if (dtmfPanel != null) {
            dtmfPanel.setVisibility(
                dtmfPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
            );
        }
    }
    
    private void sendDTMF(String digit) {
        Core core = LinphoneBackgroundService.getCore();
        if (core != null && core.getCurrentCall() != null) {
            core.getCurrentCall().sendDtmf(digit.charAt(0));
            Log.d(TAG, "DTMF sent: " + digit);
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
        if (core != null && audioManager != null) {
            isSpeaker = !isSpeaker;
            
            Call currentCall = core.getCurrentCall();
            if (currentCall == null && core.getCallsNb() > 0) {
                currentCall = core.getCalls()[0];
            }
            
            try {
                if (isSpeaker) {
                    // Enable speaker - CRITICAL: Set AudioManager FIRST
                    audioManager.setSpeakerphoneOn(true);
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    
                    // Then set in Linphone core
                    if (currentCall != null) {
                        AudioDevice[] devices = core.getAudioDevices();
                        Log.d(TAG, "Available audio devices: " + devices.length);
                        
                        for (AudioDevice device : devices) {
                            Log.d(TAG, "Device: " + device.getDeviceName() + " Type: " + device.getType());
                            if (device.getType() == AudioDevice.Type.Speaker) {
                                currentCall.setOutputAudioDevice(device);
                                Log.d(TAG, "Speaker device set: " + device.getDeviceName());
                                break;
                            }
                        }
                    }
                    Log.d(TAG, "Speaker enabled - AudioManager: " + audioManager.isSpeakerphoneOn());
                } else {
                    // Use earpiece - CRITICAL: Set AudioManager FIRST
                    audioManager.setSpeakerphoneOn(false);
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    
                    // Then set in Linphone core
                    if (currentCall != null) {
                        AudioDevice[] devices = core.getAudioDevices();
                        
                        for (AudioDevice device : devices) {
                            if (device.getType() == AudioDevice.Type.Earpiece) {
                                currentCall.setOutputAudioDevice(device);
                                Log.d(TAG, "Earpiece device set: " + device.getDeviceName());
                                break;
                            }
                        }
                    }
                    Log.d(TAG, "Earpiece enabled - AudioManager: " + audioManager.isSpeakerphoneOn());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error toggling speaker", e);
                e.printStackTrace();
            }
            updateButtonStates();
        }
    }

    private void toggleHold() {
        Core core = LinphoneBackgroundService.getCore();
        if (core != null && core.getCallsNb() > 0) {
            Call call = core.getCurrentCall();
            if (call == null) call = core.getCalls()[0];
            
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
        Core core = LinphoneBackgroundService.getCore();
        if (core != null && core.getCallsNb() > 0) {
            Call call = core.getCurrentCall();
            if (call == null) call = core.getCalls()[0];
            
            if (call != null) {
                call.terminate();
            }
        }
        finish();
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
