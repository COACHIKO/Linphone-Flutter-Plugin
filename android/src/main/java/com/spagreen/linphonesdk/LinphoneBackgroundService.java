package com.spagreen.linphonesdk;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.linphone.core.Account;
import org.linphone.core.AccountParams;
import org.linphone.core.Address;
import org.linphone.core.AuthInfo;
import org.linphone.core.Call;
import org.linphone.core.Core;
import org.linphone.core.CoreListener;
import org.linphone.core.CoreListenerStub;
import org.linphone.core.Factory;
import org.linphone.core.RegistrationState;
import org.linphone.core.TransportType;

import java.util.Timer;
import java.util.TimerTask;

public class LinphoneBackgroundService extends Service {
    private static final String TAG = "LinphoneBackgroundSvc";
    private static final String CHANNEL_ID = "LinphoneServiceChannel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String INCOMING_CALL_CHANNEL_ID = "IncomingCallChannel";
    private static final int INCOMING_CALL_NOTIFICATION_ID = 2001;
    private static final String CALL_CHANNEL_ID = "OngoingCallChannel";
    private static final int ONGOING_CALL_NOTIFICATION_ID = 2002;

    private static Core core = null;
    private Timer timer;
    private static LinphoneBackgroundService instance = null;
    private static Call currentIncomingCall = null;
    private android.media.Ringtone ringtone;
    private Handler notificationUpdateHandler;
    private Runnable notificationUpdateRunnable;
    private boolean isCallMuted = false;
    private static boolean isCallActivityVisible = false;

    // Network monitoring and reconnection
    private Handler reconnectHandler;
    private Runnable reconnectRunnable;
    private boolean isNetworkAvailable = true;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = Integer.MAX_VALUE; // Infinite retries
    private static final long RECONNECT_DELAY_MS = 5000; // 5 seconds
    private static final long RECONNECT_MAX_DELAY_MS = 60000; // Max 60 seconds

    // Shared preferences keys
    private static final String PREFS_NAME = "LinphonePrefs";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_DOMAIN = "domain";
    private static final String KEY_IS_REGISTERED = "is_registered";

    public static void setCallActivityVisible(boolean visible) {
        Log.d(TAG, "setCallActivityVisible: " + visible);
        isCallActivityVisible = visible;
        if (instance != null) {
            if (visible) {
                Log.d(TAG, "Activity visible - dismissing notification");
                instance.dismissOngoingCallNotification();
            } else if (core != null && core.getCurrentCall() != null) {
                Log.d(TAG, "Activity NOT visible - showing notification");
                instance.showOngoingCallNotification(core.getCurrentCall());
            } else {
                Log.d(TAG, "Cannot show notification - no active call");
            }
        } else {
            Log.d(TAG, "Cannot update notification - service instance is null");
        }
    }

    public static LinphoneBackgroundService getInstance() {
        return instance;
    }

    public static Core getCore() {
        return core;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "Service created");

        // CRITICAL: Check for RECORD_AUDIO permission before starting foreground
        // service
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted! Service cannot start as foreground.");
            stopSelf();
            return;
        }

        createNotificationChannel();

        // Start foreground with proper service type for Android 14+ (API 34)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, createNotification("HATIF", "Starting...", false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, createNotification("HATIF", "Starting...", false));
        }

        // Initialize Linphone Core
        initializeLinphoneCore();

        // Start core iterate timer
        startCoreIteration();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");

        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "REGISTER":
                    String username = intent.getStringExtra("username");
                    String password = intent.getStringExtra("password");
                    String domain = intent.getStringExtra("domain");
                    registerAccount(username, password, domain);
                    break;
                case "UNREGISTER":
                    unregisterAccount();
                    break;
                case "ANSWER_CALL":
                case "ACTION_ANSWER_CALL":
                    // First, open the Flutter app
                    openFlutterAppAndAnswer();
                    dismissIncomingCallNotification();
                    break;
                case "DECLINE_CALL":
                case "ACTION_DECLINE_CALL":
                    declineCall();
                    dismissIncomingCallNotification();
                    break;
                case "ACTION_MUTE_CALL":
                    toggleMute();
                    break;
                case "ACTION_HANGUP_CALL":
                    hangupCall();
                    break;
            }
        } else {
            // Auto-register if credentials are saved
            autoRegister();
        }

        return START_STICKY;
    }

    private void initializeLinphoneCore() {
        if (core != null)
            return;

        try {
            Factory factory = Factory.instance();
            factory.setDebugMode(true, TAG);
            core = factory.createCore(null, null, this);

            // Disable automatic CoreService start - we manage our own service
            core.setNativeRingingEnabled(false);

            core.addListener(coreListener);
            core.start();
            Log.d(TAG, "Linphone Core initialized");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Linphone Core", e);
        }
    }

    private void startCoreIteration() {
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (core != null) {
                    core.iterate();
                }
            }
        }, 0, 20);
    }

    private void registerAccount(String username, String password, String domain) {
        if (core == null) {
            initializeLinphoneCore();
        }

        try {
            // Save credentials
            saveCredentials(username, password, domain);

            // Clear existing accounts
            for (Account account : core.getAccountList()) {
                core.removeAccount(account);
            }
            core.clearAllAuthInfo();

            // Create auth info
            AuthInfo authInfo = Factory.instance().createAuthInfo(
                    username, null, password, null, null, domain, null);

            // Create account params
            AccountParams params = core.createAccountParams();
            String sipAddress = "sip:" + username + "@" + domain;
            Address identity = Factory.instance().createAddress(sipAddress);
            params.setIdentityAddress(identity);

            Address address = Factory.instance().createAddress("sip:" + domain);
            address.setTransport(TransportType.Udp);
            params.setServerAddress(address);
            params.setRegisterEnabled(true);

            // Enable push notifications parameters
            params.setPushNotificationAllowed(true);
            params.setRemotePushNotificationAllowed(true);

            // Create and add account
            Account account = core.createAccount(params);
            core.addAuthInfo(authInfo);
            core.addAccount(account);
            core.setDefaultAccount(account);

            updateNotification("HATIF", "Registering " + username + "@" + domain, false);
            Log.d(TAG, "Account registered: " + username + "@" + domain);
        } catch (Exception e) {
            Log.e(TAG, "Error registering account", e);
        }
    }

    private void unregisterAccount() {
        if (core == null)
            return;

        for (Account account : core.getAccountList()) {
            AccountParams params = account.getParams().clone();
            params.setRegisterEnabled(false);
            account.setParams(params);
        }

        clearCredentials();
        updateNotification("HATIF", "Unregistered", false);
    }

    private void autoRegister() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String username = prefs.getString(KEY_USERNAME, null);
        String password = prefs.getString(KEY_PASSWORD, null);
        String domain = prefs.getString(KEY_DOMAIN, null);

        if (username != null && password != null && domain != null) {
            Log.d(TAG, "Auto-registering with saved credentials");
            registerAccount(username, password, domain);
        }
    }

    private void openFlutterAppAndAnswer() {
        Call tempCall = currentIncomingCall;

        if (tempCall == null && core != null && core.getCallsNb() > 0) {
            tempCall = core.getCurrentCall();
            if (tempCall == null)
                tempCall = core.getCalls()[0];
        }

        if (tempCall == null) {
            Log.e(TAG, "No call to answer");
            return;
        }

        final Call call = tempCall; // Make it final for lambda

        try {
            // Send broadcast immediately to close IncomingCallActivity
            Intent closeIncomingIntent = new Intent("com.spagreen.linphonesdk.CLOSE_INCOMING_CALL");
            sendBroadcast(closeIncomingIntent);
            Log.d(TAG, "Sent broadcast to close IncomingCallActivity");

            // Accept the call
            call.accept();
            Log.d(TAG, "Call answered from notification");
            currentIncomingCall = null;

            // Get caller info for CallActivity
            String callerName = call.getRemoteAddress().getDisplayName();
            if (callerName == null || callerName.isEmpty()) {
                callerName = call.getRemoteAddress().getUsername();
            }
            String callerNumber = call.getRemoteAddress().getUsername();

            // Launch CallActivity immediately
            Intent callActivityIntent = new Intent(this, CallActivity.class);
            callActivityIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            callActivityIntent.putExtra("caller_name", callerName);
            callActivityIntent.putExtra("caller_number", callerNumber);
            startActivity(callActivityIntent);

            Log.d(TAG, "CallActivity launched immediately after accepting call");
        } catch (Exception e) {
            Log.e(TAG, "Error accepting call", e);
        }
    }

    private void answerCall() {
        Call call = currentIncomingCall;

        if (call == null && core != null && core.getCallsNb() > 0) {
            call = core.getCurrentCall();
            if (call == null)
                call = core.getCalls()[0];
        }

        if (call == null) {
            Log.e(TAG, "No call to answer");
            return;
        }

        try {
            call.accept();
            Log.d(TAG, "Call answered - Flutter CallScreen will be shown via state listener");
            currentIncomingCall = null;
        } catch (Exception e) {
            Log.e(TAG, "Error answering call", e);
        }
    }

    private void declineCall() {
        Call call = currentIncomingCall;

        if (call == null && core != null && core.getCallsNb() > 0) {
            call = core.getCurrentCall();
            if (call == null)
                call = core.getCalls()[0];
        }

        if (call == null) {
            Log.e(TAG, "No call to decline");
            return;
        }

        try {
            call.decline(org.linphone.core.Reason.Declined);
            Log.d(TAG, "Call declined");
            currentIncomingCall = null;
        } catch (Exception e) {
            Log.e(TAG, "Error declining call", e);
        }
    }

    private void toggleMute() {
        if (core == null) {
            Log.e(TAG, "Core is null, cannot toggle mute");
            return;
        }

        // Toggle the mute state
        isCallMuted = core.micEnabled();
        core.enableMic(!isCallMuted);
        isCallMuted = !isCallMuted;

        Log.d(TAG, "Microphone " + (isCallMuted ? "muted" : "unmuted"));

        // Update the ongoing notification to reflect the new mute state
        Call call = core.getCurrentCall();
        if (call != null) {
            updateOngoingCallNotification(call);
        }
    }

    private void updateOngoingCallNotification(Call call) {
        try {
            String callerName = call.getRemoteAddress().getDisplayName();
            if (callerName == null || callerName.isEmpty()) {
                callerName = call.getRemoteAddress().getUsername();
            }

            // Create mute action intent
            Intent muteIntent = new Intent(this, LinphoneBackgroundService.class);
            muteIntent.setAction("ACTION_MUTE_CALL");
            PendingIntent mutePendingIntent = PendingIntent.getService(
                    this,
                    3,
                    muteIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Create hangup action intent
            Intent hangupIntent = new Intent(this, LinphoneBackgroundService.class);
            hangupIntent.setAction("ACTION_HANGUP_CALL");
            PendingIntent hangupPendingIntent = PendingIntent.getService(
                    this,
                    4,
                    hangupIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Create intent to open CallActivity when notification is clicked
            Intent callActivityIntent = new Intent(this, CallActivity.class);
            callActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            callActivityIntent.putExtra("caller_name", call.getRemoteAddress().getDisplayName());
            callActivityIntent.putExtra("caller_number", call.getRemoteAddress().getUsername());
            PendingIntent contentIntent = PendingIntent.getActivity(
                    this,
                    5,
                    callActivityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Get current call time if available
            String contentText = callerName;
            // The timer will update this notification with time, we just need to update the
            // button

            // Determine mute button text and icon based on current state
            String muteButtonText = isCallMuted ? "Unmute" : "Mute";
            int muteIcon = isCallMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic_on;

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, CALL_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle("Ongoing Call")
                    .setContentText(contentText)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setOngoing(true)
                    .setContentIntent(contentIntent)
                    .addAction(muteIcon, muteButtonText, mutePendingIntent)
                    .addAction(R.drawable.ic_hangup, "Hang Up", hangupPendingIntent);

            NotificationManager notificationManager = (NotificationManager) getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.notify(ONGOING_CALL_NOTIFICATION_ID, notificationBuilder.build());
            }

            Log.d(TAG, "Ongoing call notification updated with mute state: " + isCallMuted);
        } catch (Exception e) {
            Log.e(TAG, "Error updating ongoing call notification", e);
        }
    }

    private void hangupCall() {
        if (core == null) {
            Log.e(TAG, "Core is null, cannot hang up");
            return;
        }

        Call call = core.getCurrentCall();
        if (call != null) {
            try {
                call.terminate();
                Log.d(TAG, "Call terminated");
            } catch (Exception e) {
                Log.e(TAG, "Error terminating call", e);
            }
        } else {
            Log.d(TAG, "No active call to hang up");
        }
    }

    // ===== Static methods for CallActivity to use (avoiding null core issues)
    // =====

    public static void hangUpFromActivity() {
        if (core == null || core.getCallsNb() == 0) {
            Log.d(TAG, "hangUpFromActivity: No call to hang up");
            return;
        }

        Call call = core.getCurrentCall();
        if (call == null && core.getCalls().length > 0) {
            call = core.getCalls()[0];
        }

        if (call != null) {
            try {
                call.terminate();
                Log.d(TAG, "Call terminated from activity");
            } catch (Exception e) {
                Log.e(TAG, "Error terminating call from activity", e);
            }
        }
    }

    public static boolean toggleMuteFromActivity() {
        if (core == null) {
            Log.e(TAG, "toggleMuteFromActivity: Core is null");
            return false;
        }

        Call call = core.getCurrentCall();
        if (call == null) {
            Log.e(TAG, "toggleMuteFromActivity: No active call");
            return false;
        }

        try {
            boolean currentlyMuted = call.getMicrophoneMuted();
            call.setMicrophoneMuted(!currentlyMuted);
            Log.d(TAG, "Mute toggled to: " + !currentlyMuted);

            // Update instance's mute state for notification
            if (instance != null) {
                instance.isCallMuted = !currentlyMuted;
                instance.updateOngoingCallNotification(call);
            }

            return !currentlyMuted;
        } catch (Exception e) {
            Log.e(TAG, "Error toggling mute from activity", e);
            return false;
        }
    }

    public static void toggleSpeakerFromActivity() {
        if (core == null) {
            Log.e(TAG, "toggleSpeakerFromActivity: Core is null");
            return;
        }

        Call call = core.getCurrentCall();
        if (call == null) {
            Log.e(TAG, "toggleSpeakerFromActivity: No active call");
            return;
        }

        try {
            org.linphone.core.AudioDevice currentDevice = call.getOutputAudioDevice();
            boolean isSpeaker = currentDevice != null &&
                    currentDevice.getType() == org.linphone.core.AudioDevice.Type.Speaker;

            // Find and switch to appropriate device
            for (org.linphone.core.AudioDevice device : core.getAudioDevices()) {
                if (isSpeaker && device.getType() == org.linphone.core.AudioDevice.Type.Earpiece) {
                    call.setOutputAudioDevice(device);
                    Log.d(TAG, "Switched to earpiece");
                    return;
                } else if (!isSpeaker && device.getType() == org.linphone.core.AudioDevice.Type.Speaker) {
                    call.setOutputAudioDevice(device);
                    Log.d(TAG, "Switched to speaker");
                    return;
                }
            }

            Log.d(TAG, "No suitable audio device found for toggle");
        } catch (Exception e) {
            Log.e(TAG, "Error toggling speaker from activity", e);
        }
    }

    public static void toggleHoldFromActivity() {
        if (core == null) {
            Log.e(TAG, "toggleHoldFromActivity: Core is null");
            return;
        }

        Call call = core.getCurrentCall();
        if (call == null) {
            Log.e(TAG, "toggleHoldFromActivity: No active call");
            return;
        }

        try {
            Call.State state = call.getState();
            if (state == Call.State.Paused || state == Call.State.Pausing) {
                call.resume();
                Log.d(TAG, "Call resumed");
            } else if (state == Call.State.StreamsRunning || state == Call.State.Connected) {
                call.pause();
                Log.d(TAG, "Call paused");
            } else {
                Log.d(TAG, "Cannot toggle hold in state: " + state);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error toggling hold from activity", e);
        }
    }

    public static void sendDTMFFromActivity(char digit) {
        if (core == null) {
            Log.e(TAG, "sendDTMFFromActivity: Core is null");
            return;
        }

        Call call = core.getCurrentCall();
        if (call == null) {
            Log.e(TAG, "sendDTMFFromActivity: No active call");
            return;
        }

        try {
            call.sendDtmf(digit);
            Log.d(TAG, "DTMF sent: " + digit);
        } catch (Exception e) {
            Log.e(TAG, "Error sending DTMF from activity", e);
        }
    }

    public static boolean isCallOnHold() {
        if (core == null)
            return false;
        Call call = core.getCurrentCall();
        if (call == null)
            return false;

        Call.State state = call.getState();
        return state == Call.State.Paused || state == Call.State.Pausing;
    }

    public static boolean isCallMuted() {
        if (core == null)
            return false;
        Call call = core.getCurrentCall();
        if (call == null)
            return false;

        try {
            return call.getMicrophoneMuted();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isOnSpeaker() {
        if (core == null)
            return false;
        Call call = core.getCurrentCall();
        if (call == null)
            return false;

        try {
            org.linphone.core.AudioDevice currentDevice = call.getOutputAudioDevice();
            return currentDevice != null &&
                    currentDevice.getType() == org.linphone.core.AudioDevice.Type.Speaker;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Static method called by CallActionReceiver when accept button is pressed in
     * notification.
     * This avoids restarting the service and properly coordinates with
     * IncomingCallActivity.
     */
    public static void answerCallFromNotification() {
        Log.i(TAG, "answerCallFromNotification: Called from broadcast receiver");
        if (instance != null) {
            instance.openFlutterAppAndAnswer();
            instance.dismissIncomingCallNotification();
        } else {
            Log.e(TAG, "answerCallFromNotification: Service instance is null");
        }
    }

    /**
     * Static method called by CallActionReceiver when decline button is pressed in
     * notification.
     */
    public static void declineCallFromNotification() {
        Log.i(TAG, "declineCallFromNotification: Called from broadcast receiver");
        if (instance != null) {
            instance.declineCall();
            instance.dismissIncomingCallNotification();
        } else {
            Log.e(TAG, "declineCallFromNotification: Service instance is null");
        }
    }

    // ===== End of static methods for CallActivity =====

    private void saveCredentials(String username, String password, String domain) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_USERNAME, username);
        editor.putString(KEY_PASSWORD, password);
        editor.putString(KEY_DOMAIN, domain);
        editor.putBoolean(KEY_IS_REGISTERED, true);
        editor.apply();
    }

    private void clearCredentials() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_IS_REGISTERED, false);
        editor.apply();
    }

    private CoreListener coreListener = new CoreListenerStub() {
        @Override
        public void onAccountRegistrationStateChanged(@NonNull Core core, @NonNull Account account,
                RegistrationState state, @NonNull String message) {
            Log.d(TAG, "Registration state changed: " + state.name() + " - Message: " + message);

            String username = account.getParams().getIdentityAddress().getUsername();
            String domain = account.getParams().getIdentityAddress().getDomain();

            switch (state) {
                case Ok:
                    Log.i(TAG, "✓ Registration successful for " + username + "@" + domain);
                    updateNotification("HATIF", "Ready for calls", true);
                    // Reset reconnect attempts on successful registration
                    reconnectAttempts = 0;
                    cancelReconnectTimer();
                    break;
                case Progress:
                    Log.d(TAG, "Registration in progress...");
                    updateNotification("HATIF", "Registering...", false);
                    break;
                case Failed:
                    Log.e(TAG, "✗ Registration failed: " + message);
                    updateNotification("HATIF", "Registration failed - Retrying...", false);
                    // Schedule reconnection attempt
                    scheduleReconnect();
                    break;
                case Cleared:
                    Log.w(TAG, "Registration cleared");
                    updateNotification("HATIF", "Unregistered", false);
                    // Try to re-register if we have credentials
                    scheduleReconnect();
                    break;
            }
        }

        @Override
        public void onNetworkReachable(@NonNull Core core, boolean reachable) {
            Log.i(TAG, "Network reachable: " + reachable);
            isNetworkAvailable = reachable;

            if (reachable) {
                Log.d(TAG, "Network is back, attempting to restore registration...");
                // Network is back, try to re-register
                if (core.getDefaultAccount() != null) {
                    Account account = core.getDefaultAccount();
                    if (account.getState() != RegistrationState.Ok) {
                        Log.d(TAG, "Refreshing registration after network restore");
                        account.setParams(account.getParams());
                        reconnectAttempts = 0; // Reset counter
                    }
                } else {
                    // Try auto-register if we have saved credentials
                    scheduleReconnect();
                }
            } else {
                Log.w(TAG, "Network lost, will retry when network is back");
                updateNotification("HATIF", "No network - Waiting...", false);
            }
        }

        @Override
        public void onCallStateChanged(@NonNull Core core, @NonNull Call call,
                Call.State state, @NonNull String message) {
            Log.d(TAG, "Call state changed: " + state.name());

            switch (state) {
                case IncomingReceived:
                    // Show incoming call notification/UI
                    handleIncomingCall(call);
                    break;
                case Connected:
                case StreamsRunning:
                    // Stop ringtone
                    stopRingtone();
                    // Dismiss incoming call notification
                    dismissIncomingCallNotification();
                    // Launch call activity first (will set visibility flag)
                    launchCallActivity(call);
                    // Show ongoing call notification (will check visibility flag)
                    showOngoingCallNotification(call);
                    updateNotification("HATIF", "Call connected", true);
                    break;
                case End:
                case Released:
                case Error:
                    // Stop ringtone
                    stopRingtone();
                    // Dismiss all call notifications
                    dismissIncomingCallNotification();
                    dismissOngoingCallNotification();
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    String username = prefs.getString(KEY_USERNAME, "Unknown");
                    String domain = prefs.getString(KEY_DOMAIN, "");
                    updateNotification("HATIF", "Ready for calls", true);
                    break;
            }
        }
    };

    private void handleIncomingCall(Call call) {
        Log.d(TAG, "handleIncomingCall: Launching IncomingCallActivity");

        // Play ringtone
        playRingtone();

        // Store the current call reference
        currentIncomingCall = call;

        // Create intent for the incoming call activity
        Intent intent = new Intent(this, IncomingCallActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("caller_name", call.getRemoteAddress().getDisplayName());
        intent.putExtra("caller_number", call.getRemoteAddress().getUsername());

        // Create full-screen intent for incoming call notification
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Create accept action intent - use broadcast instead of service to avoid
        // restart
        Intent acceptIntent = new Intent(CallActionReceiver.ACTION_ANSWER_CALL);
        acceptIntent.setPackage(getPackageName()); // Required for security
        PendingIntent acceptPendingIntent = PendingIntent.getBroadcast(
                this,
                1,
                acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Create decline action intent - use broadcast instead of service to avoid
        // restart
        Intent declineIntent = new Intent(CallActionReceiver.ACTION_DECLINE_CALL);
        declineIntent.setPackage(getPackageName()); // Required for security
        PendingIntent declinePendingIntent = PendingIntent.getBroadcast(
                this,
                2,
                declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Show incoming call notification with full-screen intent
        String callerName = call.getRemoteAddress().getDisplayName();
        if (callerName == null || callerName.isEmpty()) {
            callerName = call.getRemoteAddress().getUsername();
        }

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, INCOMING_CALL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("Incoming Call")
                .setContentText(callerName)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setAutoCancel(false)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_call, "Accept", acceptPendingIntent)
                .addAction(android.R.drawable.ic_delete, "Decline", declinePendingIntent);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, notificationBuilder.build());
        }

        // Also try to start the activity directly
        try {
            startActivity(intent);
            Log.d(TAG, "handleIncomingCall: Activity started successfully");
        } catch (Exception e) {
            Log.e(TAG, "handleIncomingCall: Failed to start activity", e);
        }
    }

    private void dismissIncomingCallNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Service notification channel
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "HATIF",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Maintains SIP registration");

            // Incoming call notification channel
            NotificationChannel incomingCallChannel = new NotificationChannel(
                    INCOMING_CALL_CHANNEL_ID,
                    "Incoming Calls",
                    NotificationManager.IMPORTANCE_HIGH);
            incomingCallChannel.setDescription("Incoming call notifications");
            incomingCallChannel.setSound(null, null);

            // Ongoing call notification channel
            NotificationChannel ongoingCallChannel = new NotificationChannel(
                    CALL_CHANNEL_ID,
                    "Ongoing Calls",
                    NotificationManager.IMPORTANCE_LOW);
            ongoingCallChannel.setDescription("Ongoing call notifications");
            ongoingCallChannel.setSound(null, null);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                manager.createNotificationChannel(incomingCallChannel);
                manager.createNotificationChannel(ongoingCallChannel);
            }
        }
    }

    private Notification createNotification(String title, String content, boolean isRegistered) {
        Intent notificationIntent = new Intent(this, getMainActivityClass());
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Load and scale up your custom icon to make it much bigger and more visible
        android.graphics.Bitmap originalIcon = android.graphics.BitmapFactory.decodeResource(
                getResources(), R.drawable.ic_launcher);

        // Scale to 256x256 for maximum visibility in notification
        android.graphics.Bitmap largeIcon = android.graphics.Bitmap.createScaledBitmap(
                originalIcon, 256, 256, true);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_launcher) // Your custom icon
                .setLargeIcon(largeIcon) // Scaled large icon for maximum visibility
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String title, String content, boolean isRegistered) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(title, content, isRegistered));
        }
    }

    private Class<?> getMainActivityClass() {
        String packageName = getPackageName();
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent != null) {
            String className = launchIntent.getComponent().getClassName();
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "Could not find main activity class", e);
            }
        }
        return null;
    }

    private void playRingtone() {
        try {
            if (ringtone == null) {
                Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                ringtone = RingtoneManager.getRingtone(getApplicationContext(), ringtoneUri);
            }
            if (ringtone != null && !ringtone.isPlaying()) {
                ringtone.play();
                Log.d(TAG, "Ringtone started playing");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing ringtone", e);
        }
    }

    private void stopRingtone() {
        try {
            if (ringtone != null && ringtone.isPlaying()) {
                ringtone.stop();
                Log.d(TAG, "Ringtone stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping ringtone", e);
        }
    }

    private void launchCallActivity(Call call) {
        try {
            Intent intent = new Intent(this, CallActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("caller_name", call.getRemoteAddress().getDisplayName());
            intent.putExtra("caller_number", call.getRemoteAddress().getUsername());
            startActivity(intent);
            Log.d(TAG, "CallActivity launched");
        } catch (Exception e) {
            Log.e(TAG, "Error launching CallActivity", e);
        }
    }

    private void showOngoingCallNotification(Call call) {
        Log.d(TAG, "showOngoingCallNotification called");
        Log.d(TAG, "Creating ongoing call notification...");
        try {
            String callerName = call.getRemoteAddress().getDisplayName();
            String callerNumber = call.getRemoteAddress().getUsername();
            if (callerName == null || callerName.isEmpty()) {
                callerName = callerNumber;
            }

            // Try to create custom notification view with fallback
            RemoteViews notificationView = null;
            boolean useCustomView = true;

            try {
                notificationView = new RemoteViews(getPackageName(), R.layout.notification_call_control);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create custom notification view, using fallback", e);
                useCustomView = false;
            }

            // Create intent to open CallActivity when notification is clicked
            Intent callActivityIntent = new Intent(this, CallActivity.class);
            callActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            callActivityIntent.putExtra("caller_name", callerName);
            callActivityIntent.putExtra("caller_number", callerNumber);
            PendingIntent contentIntent = PendingIntent.getActivity(
                    this,
                    5,
                    callActivityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, CALL_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setContentIntent(contentIntent)
                    .setOnlyAlertOnce(true)
                    .setSound(null)
                    .setVibrate(null);

            if (useCustomView && notificationView != null) {
                // Set caller info
                notificationView.setTextViewText(R.id.notification_caller_name, callerName);
                notificationView.setTextViewText(R.id.notification_call_timer, "00:00");

                // Create hangup action intent
                Intent hangupIntent = new Intent(this, LinphoneBackgroundService.class);
                hangupIntent.setAction("ACTION_HANGUP_CALL");
                PendingIntent hangupPendingIntent = PendingIntent.getService(
                        this,
                        4,
                        hangupIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                notificationView.setOnClickPendingIntent(R.id.notification_hangup_button, hangupPendingIntent);

                notificationBuilder.setCustomContentView(notificationView)
                        .setCustomBigContentView(notificationView);
            } else {
                // Fallback to simple notification if custom view fails
                notificationBuilder.setContentTitle("Ongoing Call")
                        .setContentText("Call with " + callerName);
            }

            NotificationManager notificationManager = (NotificationManager) getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                // Use the same notification ID as the service notification to replace it
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());

                // Start updating notification with call timer
                startNotificationTimer(call);
            }

            Log.d(TAG, "Ongoing call notification shown with " + (useCustomView ? "custom" : "fallback") + " view");
        } catch (Exception e) {
            Log.e(TAG, "Error showing ongoing call notification", e);
        }
    }

    private void startNotificationTimer(Call call) {
        // Initialize handler and runnable for notification updates
        if (notificationUpdateHandler == null) {
            notificationUpdateHandler = new Handler(Looper.getMainLooper());
        }

        final long[] callStartTime = { System.currentTimeMillis() };

        notificationUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (call.getState() == Call.State.Connected || call.getState() == Call.State.StreamsRunning) {
                        long elapsedMillis = System.currentTimeMillis() - callStartTime[0];
                        int seconds = (int) (elapsedMillis / 1000);
                        int minutes = seconds / 60;
                        seconds = seconds % 60;
                        String timeString = String.format("%02d:%02d", minutes, seconds);

                        String callerName = call.getRemoteAddress().getDisplayName();
                        String callerNumber = call.getRemoteAddress().getUsername();
                        if (callerName == null || callerName.isEmpty()) {
                            callerName = callerNumber;
                        }

                        // Create custom notification view
                        RemoteViews notificationView = new RemoteViews(getPackageName(),
                                R.layout.notification_call_control);

                        // Set caller info
                        notificationView.setTextViewText(R.id.notification_caller_name, callerName);
                        notificationView.setTextViewText(R.id.notification_call_timer, timeString);

                        // Create hangup action intent
                        Intent hangupIntent = new Intent(LinphoneBackgroundService.this,
                                LinphoneBackgroundService.class);
                        hangupIntent.setAction("ACTION_HANGUP_CALL");
                        PendingIntent hangupPendingIntent = PendingIntent.getService(
                                LinphoneBackgroundService.this,
                                4,
                                hangupIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                        notificationView.setOnClickPendingIntent(R.id.notification_hangup_button, hangupPendingIntent);

                        // Create intent to open CallActivity
                        Intent callActivityIntent = new Intent(LinphoneBackgroundService.this, CallActivity.class);
                        callActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        callActivityIntent.putExtra("caller_name", callerName);
                        callActivityIntent.putExtra("caller_number", callerNumber);
                        PendingIntent contentIntent = PendingIntent.getActivity(
                                LinphoneBackgroundService.this,
                                5,
                                callActivityIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(
                                LinphoneBackgroundService.this, CALL_CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.ic_menu_call)
                                .setCustomContentView(notificationView)
                                .setCustomBigContentView(notificationView)
                                .setPriority(NotificationCompat.PRIORITY_LOW)
                                .setOngoing(true)
                                .setContentIntent(contentIntent)
                                .setOnlyAlertOnce(true)
                                .setSound(null)
                                .setVibrate(null);

                        NotificationManager notificationManager = (NotificationManager) getSystemService(
                                Context.NOTIFICATION_SERVICE);
                        if (notificationManager != null) {
                            // Use the same notification ID as the service notification
                            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
                        }

                        // Schedule next update in 1 second
                        notificationUpdateHandler.postDelayed(this, 1000);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error updating notification timer", e);
                }
            }
        };

        // Start the timer
        notificationUpdateHandler.post(notificationUpdateRunnable);
    }

    private void dismissOngoingCallNotification() {
        try {
            // Stop the notification timer
            if (notificationUpdateHandler != null && notificationUpdateRunnable != null) {
                notificationUpdateHandler.removeCallbacks(notificationUpdateRunnable);
            }

            // Restore the original service notification showing registration status
            String registrationStatus = "Service running";
            Account account = null;
            if (core != null && core.getDefaultAccount() != null) {
                account = core.getDefaultAccount();
                if (account.getState() == RegistrationState.Ok) {
                    String identity = account.getParams().getIdentityAddress().asStringUriOnly();
                    registrationStatus = "Ready for calls";
                } else {
                    registrationStatus = "Registration: " + account.getState().toString();
                }
            }

            NotificationManager notificationManager = (NotificationManager) getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                // Use the same NOTIFICATION_ID to restore the service notification
                boolean isRegistered = (account != null && account.getState() == RegistrationState.Ok);
                notificationManager.notify(NOTIFICATION_ID,
                        createNotification("HATIF", registrationStatus, isRegistered));
                Log.d(TAG, "Call notification dismissed, service notification restored");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing ongoing call notification", e);
        }
    }

    /**
     * Schedule a reconnection attempt with exponential backoff
     */
    private void scheduleReconnect() {
        // Cancel any existing reconnection timer
        cancelReconnectTimer();

        // Calculate delay with exponential backoff
        // Formula: delay = min(initial_delay * 2^attempts, max_delay)
        long delay = Math.min(
                RECONNECT_DELAY_MS * (1L << reconnectAttempts),
                RECONNECT_MAX_DELAY_MS);

        Log.d(TAG, "⏰ Scheduling reconnection attempt #" + (reconnectAttempts + 1) +
                " in " + (delay / 1000) + " seconds");

        // Initialize handler if needed
        if (reconnectHandler == null) {
            reconnectHandler = new Handler(Looper.getMainLooper());
        }

        // Create reconnection runnable
        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "🔄 Attempting reconnection #" + (reconnectAttempts + 1));
                attemptReregistration();
            }
        };

        // Schedule the reconnection
        reconnectHandler.postDelayed(reconnectRunnable, delay);

        // Increment counter for next attempt
        reconnectAttempts++;
    }

    /**
     * Cancel any scheduled reconnection attempts
     */
    private void cancelReconnectTimer() {
        if (reconnectHandler != null && reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            Log.d(TAG, "⏹️ Reconnection timer cancelled");
        }
    }

    /**
     * Attempt to re-register the account
     */
    private void attemptReregistration() {
        if (core == null) {
            Log.e(TAG, "❌ Cannot reconnect - Core is null");
            return;
        }

        if (!isNetworkAvailable) {
            Log.d(TAG, "⚠️ Network not available, waiting for network...");
            updateNotification("HATIF", "No network - Waiting...", false);
            return;
        }

        try {
            // Get the default account
            Account account = core.getDefaultAccount();

            if (account != null) {
                // Refresh the account to trigger re-registration
                Log.d(TAG, "🔄 Refreshing account registration...");
                AccountParams params = account.getParams();
                account.setParams(params);

                updateNotification("HATIF", "Reconnecting...", false);
            } else {
                // No account exists, try to create one from saved credentials
                Log.d(TAG, "⚠️ No account found, attempting auto-registration...");

                // Get saved credentials
                SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                String savedUsername = prefs.getString("flutter.username", null);
                String savedPassword = prefs.getString("flutter.password", null);
                String savedDomain = prefs.getString("flutter.domain", null);

                if (savedUsername != null && savedPassword != null && savedDomain != null) {
                    Log.d(TAG, "📱 Found saved credentials, attempting auto-registration");
                    registerAccount(savedUsername, savedPassword, savedDomain);
                } else {
                    Log.e(TAG, "❌ No saved credentials found, cannot auto-register");
                    updateNotification("HATIF", "Registration failed - No credentials", false);
                    // Stop trying if we have no credentials
                    reconnectAttempts = 0;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error during reconnection attempt", e);
            updateNotification("HATIF", "Reconnection error - Retrying...", false);
            // Schedule next attempt
            scheduleReconnect();
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");

        // Cancel reconnection timer
        cancelReconnectTimer();

        // Stop ringtone
        stopRingtone();

        // Stop notification timer
        if (notificationUpdateHandler != null && notificationUpdateRunnable != null) {
            notificationUpdateHandler.removeCallbacks(notificationUpdateRunnable);
        }

        if (timer != null) {
            timer.cancel();
            timer = null;
        }

        if (core != null) {
            core.removeListener(coreListener);
            core.stop();
            core = null;
        }

        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
