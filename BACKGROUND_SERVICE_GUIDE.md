# Background Service & Native Incoming Call Implementation

This document explains how to use the new background service feature that maintains SIP registration and handles incoming calls even when the app is closed.

## Features

### 1. **Persistent Background Service**
- Maintains SIP registration 24/7
- Keeps running even after app is closed
- Auto-reconnects on registration failures
- Runs as a foreground service with notification

### 2. **Native Incoming Call UI**
- Shows incoming calls even when app is closed or locked
- Works on lock screen
- Full-screen incoming call activity
- Answer/Decline buttons
- Plays ringtone for incoming calls
- Automatically wakes screen and dismisses keyguard

### 3. **Credential Management**
- Automatically saves login credentials
- Auto-registers when service starts
- Secure storage using SharedPreferences

## How to Use

### Starting the Background Service

```dart
// Start the background service with credentials
await LinphoneFlutterPlugin().startBackgroundService(
  userName: "1004",
  domain: "demo.egytelecoms.com",
  password: "your_password",
);
```

Once started, the service will:
1. Register with the SIP server
2. Maintain registration automatically
3. Keep running in the background
4. Show incoming calls with native UI
5. Auto-restart after device reboot (if configured)

### Stopping the Background Service

```dart
await LinphoneFlutterPlugin().stopBackgroundService();
```

### Checking Service Status

```dart
bool isRunning = await LinphoneFlutterPlugin().isServiceRunning();
print("Service is running: $isRunning");
```

## Required Permissions

The following permissions are already added to the AndroidManifest.xml:

```xml
<!-- Core Linphone permissions -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Foreground service permissions -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />

<!-- Phone call permissions -->
<uses-permission android:name="android.permission.MANAGE_OWN_CALLS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />

<!-- Incoming call UI permissions -->
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.DISABLE_KEYGUARD" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Architecture

### Components

1. **LinphoneBackgroundService**
   - Persistent background service
   - Manages Linphone Core lifecycle
   - Handles registration
   - Monitors call states
   - Triggers incoming call UI

2. **IncomingCallActivity**
   - Full-screen incoming call UI
   - Works on lock screen
   - Handles accept/decline actions
   - Plays ringtone
   - Manages wake locks

3. **Service Integration**
   - Flutter method channels for service control
   - SharedPreferences for credential storage
   - Foreground service with notification

### Service Lifecycle

```
App Start
   ↓
[User enters credentials]
   ↓
[Start Background Service] ← Creates foreground service
   ↓
[Service registers with SIP server]
   ↓
[Maintains registration continuously]
   ↓
[Incoming Call Detected]
   ↓
[Launch IncomingCallActivity]
   ↓
[User answers/declines]
   ↓
[Service continues running]
```

## Important Notes

### Battery Optimization
On some devices (especially Xiaomi, Huawei, Oppo), you need to:
1. Disable battery optimization for the app
2. Allow autostart
3. Allow background activity

### Testing

1. **Start the service:**
   ```dart
   await plugin.startBackgroundService(
     userName: "your_username",
     domain: "your_domain.com",
     password: "your_password",
   );
   ```

2. **Close the app completely** (swipe away from recent apps)

3. **Make a call to your registered number** from another device

4. **Incoming call screen should appear** even with the app closed

### Notification Channel

The service creates a notification channel called "Linphone Service" that shows:
- Current registration status
- Username and domain
- Call status when active

You can customize the notification in `LinphoneBackgroundService.java`:
```java
private Notification createNotification(String title, String content) {
    // Customize notification here
}
```

## Troubleshooting

### Service Not Starting
- Check if all permissions are granted
- Verify credentials are correct
- Check logcat for errors: `adb logcat | grep LinphoneBackgroundSvc`

### Incoming Calls Not Showing
- Ensure `USE_FULL_SCREEN_INTENT` permission is granted
- Check battery optimization settings
- Verify service is running: `isServiceRunning()`

### Registration Fails
- Check network connectivity
- Verify SIP server is reachable
- Check credentials are correct
- Review logs for registration errors

### App Crashes on Call
- Ensure all foreground service type permissions are added
- Check target SDK is properly configured
- Verify `FOREGROUND_SERVICE_PHONE_CALL` permission is present

## Auto-Start on Device Reboot (Optional)

To make the service start automatically after device reboot, add:

1. **Permission in AndroidManifest.xml:**
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

2. **Broadcast Receiver in AndroidManifest.xml:**
```xml
<receiver
    android:name=".BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

3. **Create BootReceiver.java:**
```java
package com.spagreen.linphonesdk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Check if service was previously running
            SharedPreferences prefs = context.getSharedPreferences("LinphonePrefs", Context.MODE_PRIVATE);
            boolean wasRegistered = prefs.getBoolean("is_registered", false);
            
            if (wasRegistered) {
                Intent serviceIntent = new Intent(context, LinphoneBackgroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
```

## API Reference

### Flutter Methods

```dart
// Start background service
Future<void> startBackgroundService({
  required String userName,
  required String domain,
  required String password,
})

// Stop background service
Future<void> stopBackgroundService()

// Check if service is running
Future<bool> isServiceRunning()
```

### Service Intents

```java
// Register account
Intent intent = new Intent(context, LinphoneBackgroundService.class);
intent.setAction("REGISTER");
intent.putExtra("username", "1004");
intent.putExtra("password", "pass");
intent.putExtra("domain", "example.com");

// Unregister account
intent.setAction("UNREGISTER");

// Answer call
intent.setAction("ANSWER_CALL");

// Decline call
intent.setAction("DECLINE_CALL");
```

## Best Practices

1. **Always start the service after successful login**
   ```dart
   await login(...);
   await startBackgroundService(...);
   ```

2. **Handle service status in UI**
   ```dart
   bool isRunning = await isServiceRunning();
   // Show appropriate UI based on status
   ```

3. **Stop service on logout**
   ```dart
   await stopBackgroundService();
   await clearCredentials();
   ```

4. **Monitor registration state**
   - Listen to login state stream
   - Update UI based on registration status
   - Handle registration failures gracefully

## Security Considerations

- Credentials are stored in SharedPreferences
- Consider encrypting credentials for production
- Clear credentials on logout
- Implement proper session management

## Example Implementation

See the updated `example/lib/main.dart` for a complete implementation with:
- Service start/stop buttons
- Status checking
- Automatic credential saving
- Proper error handling
