package com.spagreen.linphonesdk;

import android.Manifest;
import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import org.linphone.core.CallLog;

import java.util.Map;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class MethodChannelHandler extends FlutterActivity implements MethodChannel.MethodCallHandler {
    private final String TAG = MethodChannelHandler.class.getSimpleName();
    private EventChannelHelper loginEventListener;
    private EventChannelHelper callEventListener;
    private LinPhoneHelper linPhoneHelper;
    private Activity activity;

    public MethodChannelHandler(Activity activity,
                                EventChannelHelper loginEventListener, EventChannelHelper callEventListener) {

        this.loginEventListener = loginEventListener;
        this.callEventListener = callEventListener;
        this.linPhoneHelper = new LinPhoneHelper(activity, loginEventListener, callEventListener);
        this.activity = activity;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {

            case "login":
                Map data = (Map) call.arguments;
                String userName = (String) data.get("userName");
                String domain = (String) data.get("domain");
                String password = (String) data.get("password");
                linPhoneHelper.login(userName, domain, password);
                result.success("Success");
                break;
            case "remove_listener":
                linPhoneHelper.removeLoginListener();
                result.success(true);
                break;
            case "remove_call_listener":
                linPhoneHelper.removeCallListener();
                result.success(true);
                break;
            case "hangUp":
                linPhoneHelper.hangUp();
                result.success(true);
                break;
            case "mute":
                boolean isMuted = linPhoneHelper.toggleMute();
                result.success(isMuted);
                break;

            case "call":
                Map callData = (Map) call.arguments;
                String number = (String) callData.get("number");
                
                // Check if background service is running
                LinphoneBackgroundService service = LinphoneBackgroundService.getInstance();
                if (service != null) {
                    // Use background service for call (preferred method)
                    boolean callSuccess = LinphoneBackgroundService.makeCall(number);
                    result.success(callSuccess);
                } else {
                    // Fallback to old method if service not running
                    linPhoneHelper.call(number);
                    result.success(true);
                }
                break;
            case "transfer":
                Map destinationMap = (Map) call.arguments;
                String destination = (String) destinationMap.get("destination");
               boolean isTransferred =  linPhoneHelper.callForward(destination);
                result.success(isTransferred);
                break;
            case "toggle_speaker":
                linPhoneHelper.toggleSpeaker();
                result.success(true);
                break;
            case "call_logs":
                String list = linPhoneHelper.callLogs();
                result.success(list);
                break;
            case "request_permissions":
                try {
                    String[] permissionArrays = new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.USE_SIP,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.ACCESS_NETWORK_STATE,
                            Manifest.permission.CHANGE_NETWORK_STATE,
                            Manifest.permission.ACCESS_WIFI_STATE,
                            Manifest.permission.CHANGE_WIFI_STATE,
                            Manifest.permission.MANAGE_OWN_CALLS,
                    };
                    boolean isSuccess = new Utils().checkPermissions(permissionArrays, activity);
                    if (isSuccess) {
                        result.success(true);
                    } else {
                        result.error("Permission Error", "Permission is not granted.", "Error");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    result.error(null, e.toString(), null);
                }
                break;
            case "answerCall":
                linPhoneHelper.answerCall();
                result.success(true);
                break;
            case "rejectCall":
                linPhoneHelper.rejectCall();
                result.success(true);
                break;
            case "start_background_service":
                Map serviceData = (Map) call.arguments;
                String svcUsername = (String) serviceData.get("userName");
                String svcDomain = (String) serviceData.get("domain");
                String svcPassword = (String) serviceData.get("password");
                startBackgroundService(svcUsername, svcPassword, svcDomain);
                result.success(true);
                break;
            case "stop_background_service":
                stopBackgroundService();
                result.success(true);
                break;
            case "is_service_running":
                boolean isRunning = isServiceRunning();
                result.success(isRunning);
                break;
            case "has_active_call":
                boolean hasCall = hasActiveCall();
                result.success(hasCall);
                break;
            case "open_call_screen":
                openCallScreen();
                result.success(true);
                break;
            default:
                result.notImplemented();
                break;
        }
    }
    
    private boolean hasActiveCall() {
        LinphoneBackgroundService service = LinphoneBackgroundService.getInstance();
        if (service != null) {
            org.linphone.core.Core core = service.getCore();
            if (core != null) {
                return core.getCallsNb() > 0;
            }
        }
        return false;
    }
    
    private void openCallScreen() {
        LinphoneBackgroundService service = LinphoneBackgroundService.getInstance();
        if (service != null && hasActiveCall()) {
            org.linphone.core.Core core = service.getCore();
            if (core != null && core.getCurrentCall() != null) {
                org.linphone.core.Call call = core.getCurrentCall();
                
                String callerName = "Unknown";
                String callerNumber = "Unknown";
                
                if (call.getRemoteAddress() != null) {
                    if (call.getRemoteAddress().getDisplayName() != null) {
                        callerName = call.getRemoteAddress().getDisplayName();
                    }
                    if (call.getRemoteAddress().getUsername() != null) {
                        callerNumber = call.getRemoteAddress().getUsername();
                    }
                }
                
                android.content.Intent intent = new android.content.Intent(activity, CallActivity.class);
                intent.putExtra("caller_name", callerName);
                intent.putExtra("caller_number", callerNumber);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(intent);
            }
        }
    }
    
    private void startBackgroundService(String username, String password, String domain) {
        // Check for RECORD_AUDIO permission before starting the service
        if (androidx.core.content.ContextCompat.checkSelfPermission(activity, 
                android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.util.Log.e("LinphonePlugin", "RECORD_AUDIO permission not granted! Cannot start background service.");
            throw new SecurityException("RECORD_AUDIO permission is required to start the background service");
        }
        
        android.content.Intent intent = new android.content.Intent(activity, LinphoneBackgroundService.class);
        intent.setAction("REGISTER");
        intent.putExtra("username", username);
        intent.putExtra("password", password);
        intent.putExtra("domain", domain);
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            activity.startForegroundService(intent);
        } else {
            activity.startService(intent);
        }
    }
    
    private void stopBackgroundService() {
        android.content.Intent intent = new android.content.Intent(activity, LinphoneBackgroundService.class);
        activity.stopService(intent);
    }
    
    private boolean isServiceRunning() {
        return LinphoneBackgroundService.getInstance() != null;
    }
}

