# 🎯 Quick Start Guide - Making Calls with Dial Pad

## ✅ What Was Fixed

Previously, calls didn't work from the Flutter app because only the background service had SIP registration. Now, all calls route through the background service, ensuring they work perfectly!

## 🚀 How to Use (3 Easy Steps)

### Step 1: Grant Permissions (First Time Only)
```
1. Open the app
2. Tap "1. Grant All Permissions" button
3. Allow ALL permissions when Android prompts
```

### Step 2: Start Background Service
```
1. Enter your SIP credentials:
   - Username: your_username
   - Password: your_password
   - Domain: your_sip_domain.com
   
2. Tap "2. Start Service" button
3. Wait for "Registered as..." in notification
```

### Step 3: Make Calls! 📞

#### Option A: Beautiful Dial Pad (Recommended)
```
1. Tap the green "Open Dial Pad" button
2. Enter phone number using the keypad
3. Tap the green phone icon
4. Enjoy your call!
```

#### Option B: Quick Call
```
1. Enter number in the "Number" text field
2. Tap "Call" button
```

## 🎨 Dial Pad Features

### Beautiful UI
- ✨ Modern dark theme with gradient effects
- 📱 Traditional phone keypad layout
- 💫 Smooth animations on every tap
- 🎯 Haptic feedback for tactile response
- ✅ Clear visual feedback

### Smart Features
- **Backspace**: Tap to delete last digit
- **Clear All**: Long press backspace
- **Letters**: Shows ABC, DEF, etc. on buttons (like real phones)
- **Auto-disable**: Call button disabled when no number
- **Error handling**: Shows alerts if service not running

## 🔧 How It Works

```
┌─────────────┐
│  Dial Pad   │
│   Screen    │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Flutter   │
│   Plugin    │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Background │
│   Service   │  ◄─── Has SIP Registration
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Linphone  │
│    Core     │
└──────┬──────┘
       │
       ▼
   📞 SIP Call
```

## 📱 Call States You'll See

1. **OutgoingInit** - Call is being set up
2. **OutgoingProgress** - Dialing...
3. **OutgoingRinging** - Ringing on other end
4. **Connected** - Call connected!
5. **StreamsRunning** - Audio/video flowing
6. **Released** - Call ended

## ⚠️ Troubleshooting

### "Background service not running!"
**Solution:** Tap "2. Start Service" button first

### "Call failed"
**Possible causes:**
- Service not registered (check notification)
- Invalid number format
- Network issues
- SIP credentials incorrect

**Fix:**
1. Stop service
2. Re-enter credentials
3. Start service
4. Wait for "Registered as..." notification
5. Try call again

### Dial Pad won't open
**Solution:** Make sure you're on the main screen

### No audio during call
**Check:**
- Microphone permission granted
- Volume is up
- Try speaker button
- Check if muted

## 🎉 Success Indicators

✅ Notification shows "Registered as username@domain"
✅ Dial pad opens smoothly
✅ Call button turns green when number entered
✅ You hear dialing tone
✅ Other party's phone rings

## 💡 Pro Tips

1. **Keep Service Running**: The background service maintains registration even when app is closed, so you can receive calls anytime!

2. **Haptic Feedback**: Enable device haptics for the best dial pad experience

3. **Long Press Delete**: Quickly clear the entire number by long-pressing the backspace button

4. **Auto Format**: The plugin automatically formats your number as a SIP address

5. **Background Calls**: You can make calls even when the app is in the background!

## 🔐 Security Notes

- Credentials are securely stored in Android's encrypted SharedPreferences
- SIP communication uses standard security protocols
- Permissions are properly requested before use

## 📞 Example Usage

```dart
// Start service (once)
await _linphoneSdkPlugin.startBackgroundService(
  userName: "john",
  domain: "sip.example.com",
  password: "secret123",
);

// Open dial pad
navigateToDialPad();

// Or make quick call
await _linphoneSdkPlugin.call(number: "1234567890");

// Check service status
bool isRunning = await _linphoneSdkPlugin.isServiceRunning();
```

## 🎯 What's Different Now?

### Before ❌
- Calls didn't work from Flutter app
- Only background service had registration
- Basic UI with no dial pad

### After ✅
- Calls work perfectly from Flutter app
- Uses background service's registration
- Beautiful dial pad with animations
- Better error handling
- Professional UX

## 🚦 Ready to Test?

1. ✅ Start the app
2. ✅ Grant permissions
3. ✅ Enter SIP credentials
4. ✅ Start background service
5. ✅ Wait for registration
6. ✅ Open dial pad
7. ✅ Make a call!

**Enjoy your new dial pad! 🎉**

---

For technical details, see: [DIAL_PAD_IMPLEMENTATION.md](DIAL_PAD_IMPLEMENTATION.md)
