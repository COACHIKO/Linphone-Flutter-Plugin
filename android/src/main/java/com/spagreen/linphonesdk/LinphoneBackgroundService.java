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
        
        // CRITICAL: Check for RECORD_AUDIO permission before starting foreground service
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted! Service cannot start as foreground.");
            stopSelf();
            return;
        }
        
        createNotificationChannel();
        
        // Start foreground with proper service type for Android 14+ (API 34)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, createNotification("Linphone Service", "Starting..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Linphone Service", "Starting..."));
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
                    answerCall();
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
        if (core != null) return;
        
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
                username, null, password, null, null, domain, null
            );
            
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
            
            updateNotification("Linphone Service", "Registering " + username + "@" + domain);
            Log.d(TAG, "Account registered: " + username + "@" + domain);
        } catch (Exception e) {
            Log.e(TAG, "Error registering account", e);
        }
    }

    private void unregisterAccount() {
        if (core == null) return;
        
        for (Account account : core.getAccountList()) {
            AccountParams params = account.getParams().clone();
            params.setRegisterEnabled(false);
            account.setParams(params);
        }
        
        clearCredentials();
        updateNotification("Linphone Service", "Unregistered");
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

    private void answerCall() {
        Call call = currentIncomingCall;
        
        if (call == null && core != null && core.getCallsNb() > 0) {
            call = core.getCurrentCall();
            if (call == null) call = core.getCalls()[0];
        }
        
        if (call == null) {
            Log.e(TAG, "No call to answer");
            return;
        }
        
        try {
            call.accept();
            Log.d(TAG, "Call answered");
            currentIncomingCall = null;
            
            // Launch CallActivity immediately after accepting
            Intent callActivityIntent = new Intent(this, CallActivity.class);
            callActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            callActivityIntent.putExtra("caller_name", call.getRemoteAddress().getDisplayName());
            callActivityIntent.putExtra("caller_number", call.getRemoteAddress().getUsername());
            startActivity(callActivityIntent);
            Log.d(TAG, "CallActivity launched from notification answer");
        } catch (Exception e) {
            Log.e(TAG, "Error answering call", e);
        }
    }

    private void declineCall() {
        Call call = currentIncomingCall;
        
        if (call == null && core != null && core.getCallsNb() > 0) {
            call = core.getCurrentCall();
            if (call == null) call = core.getCalls()[0];
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
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Create hangup action intent
            Intent hangupIntent = new Intent(this, LinphoneBackgroundService.class);
            hangupIntent.setAction("ACTION_HANGUP_CALL");
            PendingIntent hangupPendingIntent = PendingIntent.getService(
                this,
                4,
                hangupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Create intent to open CallActivity when notification is clicked
            Intent callActivityIntent = new Intent(this, CallActivity.class);
            callActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            callActivityIntent.putExtra("caller_name", call.getRemoteAddress().getDisplayName());
            callActivityIntent.putExtra("caller_number", call.getRemoteAddress().getUsername());
            PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                5,
                callActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Get current call time if available
            String contentText = callerName;
            // The timer will update this notification with time, we just need to update the button

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

            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
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
            Log.d(TAG, "Registration state changed: " + state.name());
            
            String username = account.getParams().getIdentityAddress().getUsername();
            String domain = account.getParams().getIdentityAddress().getDomain();
            
            switch (state) {
                case Ok:
                    updateNotification("Linphone Service", "Registered as " + username + "@" + domain);
                    break;
                case Progress:
                    updateNotification("Linphone Service", "Registering...");
                    break;
                case Failed:
                    updateNotification("Linphone Service", "Registration failed");
                    break;
                case Cleared:
                    updateNotification("Linphone Service", "Unregistered");
                    break;
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
                    updateNotification("Linphone Service", "Call connected");
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
                    updateNotification("Linphone Service", "Registered as " + username + "@" + domain);
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
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Create accept action intent
        Intent acceptIntent = new Intent(this, LinphoneBackgroundService.class);
        acceptIntent.setAction("ACTION_ANSWER_CALL");
        PendingIntent acceptPendingIntent = PendingIntent.getService(
            this,
            1,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Create decline action intent
        Intent declineIntent = new Intent(this, LinphoneBackgroundService.class);
        declineIntent.setAction("ACTION_DECLINE_CALL");
        PendingIntent declinePendingIntent = PendingIntent.getService(
            this,
            2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
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
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains SIP registration");
            
            // Incoming call notification channel
            NotificationChannel incomingCallChannel = new NotificationChannel(
                INCOMING_CALL_CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            );
            incomingCallChannel.setDescription("Incoming call notifications");
            incomingCallChannel.setSound(null, null);
            
            // Ongoing call notification channel
            NotificationChannel ongoingCallChannel = new NotificationChannel(
                CALL_CHANNEL_ID,
                "Ongoing Calls",
                NotificationManager.IMPORTANCE_LOW
            );
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

    private Notification createNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, getMainActivityClass());
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String title, String content) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(title, content));
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
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

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
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                notificationView.setOnClickPendingIntent(R.id.notification_hangup_button, hangupPendingIntent);
                
                notificationBuilder.setCustomContentView(notificationView)
                                  .setCustomBigContentView(notificationView);
            } else {
                // Fallback to simple notification if custom view fails
                notificationBuilder.setContentTitle("Ongoing Call")
                                  .setContentText("Call with " + callerName);
            }

            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
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

        final long[] callStartTime = {System.currentTimeMillis()};
        
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
                        RemoteViews notificationView = new RemoteViews(getPackageName(), R.layout.notification_call_control);
                        
                        // Set caller info
                        notificationView.setTextViewText(R.id.notification_caller_name, callerName);
                        notificationView.setTextViewText(R.id.notification_call_timer, timeString);

                        // Create hangup action intent
                        Intent hangupIntent = new Intent(LinphoneBackgroundService.this, LinphoneBackgroundService.class);
                        hangupIntent.setAction("ACTION_HANGUP_CALL");
                        PendingIntent hangupPendingIntent = PendingIntent.getService(
                            LinphoneBackgroundService.this,
                            4,
                            hangupIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        );
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
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        );

                        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(LinphoneBackgroundService.this, CALL_CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_menu_call)
                            .setCustomContentView(notificationView)
                            .setCustomBigContentView(notificationView)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setOngoing(true)
                            .setContentIntent(contentIntent)
                            .setOnlyAlertOnce(true)
                            .setSound(null)
                            .setVibrate(null);

                        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
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
            if (core != null && core.getDefaultAccount() != null) {
                Account account = core.getDefaultAccount();
                if (account.getState() == RegistrationState.Ok) {
                    String identity = account.getParams().getIdentityAddress().asStringUriOnly();
                    registrationStatus = "Registered as " + identity;
                } else {
                    registrationStatus = "Registration: " + account.getState().toString();
                }
            }
            
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                // Use the same NOTIFICATION_ID to restore the service notification
                notificationManager.notify(NOTIFICATION_ID, createNotification("Linphone Service", registrationStatus));
                Log.d(TAG, "Call notification dismissed, service notification restored");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing ongoing call notification", e);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        
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
