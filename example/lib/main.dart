import 'package:flutter/material.dart';
import 'package:linphone_flutter_plugin/linphoneflutterplugin.dart';
import 'package:linphone_flutter_plugin/CallLog.dart';
import 'package:linphone_flutter_plugin/call_state.dart';
import 'dart:async';
import 'package:linphone_flutter_plugin/login_state.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'call_screen.dart';
 
void main() {
  runApp(const MyApp());
}

// Main application widget
class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  // Instance of the Linphone Flutter Plugin
  final _linphoneSdkPlugin = LinphoneFlutterPlugin();
  
  // Global key for ScaffoldMessenger
  final GlobalKey<ScaffoldMessengerState> scaffoldMessengerKey = GlobalKey<ScaffoldMessengerState>();
  
  // Global key for navigation
  final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

  // TextEditingControllers for handling user input in text fields
  late TextEditingController _userController;
  late TextEditingController _passController;
  late TextEditingController _domainController;
  final _textEditingController = TextEditingController();
  
  // Timer for checking active call status
  Timer? _callCheckTimer;
  bool _hasActiveCall = false;

  @override
  void initState() {
    super.initState();

    // Initialize TextEditingControllers
    _userController = TextEditingController();
    _passController = TextEditingController();
    _domainController = TextEditingController();

    // Load saved credentials
    _loadCredentials();

    // Request necessary permissions for using Linphone features
    requestPermissions();
    
    // Start checking for active calls
    _startCallCheckTimer();
  }
  
  void _startCallCheckTimer() {
    _callCheckTimer = Timer.periodic(const Duration(seconds: 2), (timer) async {
      try {
        bool hasCall = await _linphoneSdkPlugin.hasActiveCall();
        if (mounted && hasCall != _hasActiveCall) {
          setState(() {
            _hasActiveCall = hasCall;
          });
        }
      } catch (e) {
        // Ignore errors
      }
    });
  }

  // Load saved credentials from SharedPreferences
  Future<void> _loadCredentials() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _userController.text = prefs.getString('username') ?? '';
        _passController.text = prefs.getString('password') ?? '';
        _domainController.text = prefs.getString('domain') ?? '';
      });
    } catch (e) {
      print("Error loading credentials: ${e.toString()}");
    }
  }

  // Save credentials to SharedPreferences
  Future<void> _saveCredentials(
      String username, String password, String domain) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('username', username);
      await prefs.setString('password', password);
      await prefs.setString('domain', domain);
      print("Credentials saved successfully");
    } catch (e) {
      print("Error saving credentials: ${e.toString()}");
    }
  }

  // Request permissions needed by the Linphone SDK
  Future<void> requestPermissions() async {
    try {
      await _linphoneSdkPlugin.requestPermissions();
      scaffoldMessengerKey.currentState?.showSnackBar(
        const SnackBar(
          content: Text("✅ Permission request sent! Please allow all permissions in the system dialogs."),
          backgroundColor: Colors.green,
          duration: Duration(seconds: 3),
        ),
      );
    } catch (e) {
      // Permission request triggered - this is normal behavior
      // The actual grant/deny happens through Android's callback system
      print("Permission dialogs shown: ${e.toString()}");
      scaffoldMessengerKey.currentState?.showSnackBar(
        const SnackBar(
          content: Text("📱 Please allow ALL permissions when prompted by Android!"),
          backgroundColor: Colors.blue,
          duration: Duration(seconds: 4),
        ),
      );
    }
  }

  // Login method to authenticate the user using Linphone
  Future<void> login({
    required String username,
    required String pass,
    required String domain,
  }) async {
    try {
      await _linphoneSdkPlugin.login(
          userName: username, domain: domain, password: pass);

      // Save credentials after successful login
      await _saveCredentials(username, pass, domain);
    } catch (e) {
      // Show error message if login fails
      print("Error on login. ${e.toString()}");
    }
  }

  // Start background service to maintain registration even when app is closed
  Future<void> startBackgroundService({
    required String username,
    required String pass,
    required String domain,
  }) async {
    try {
      print("Starting background service...");
      
      // Request permissions first to ensure RECORD_AUDIO is granted
      try {
        await _linphoneSdkPlugin.requestPermissions();
        await Future.delayed(const Duration(milliseconds: 800));
      } catch (permError) {
        // Permissions dialog was shown, continue anyway
        print("Permission request triggered: ${permError.toString()}");
      }
      
      // Start the service - it will verify RECORD_AUDIO permission internally
      await _linphoneSdkPlugin.startBackgroundService(
          userName: username, domain: domain, password: pass);

      // Save credentials
      await _saveCredentials(username, pass, domain);

      scaffoldMessengerKey.currentState?.showSnackBar(
        const SnackBar(
          content: Text("✅ Background service started - you can now receive calls even when app is closed"),
          backgroundColor: Colors.green,
          duration: Duration(seconds: 3),
        ),
      );
    } catch (e) {
      print("Error starting background service. ${e.toString()}");
      
      // Check if it's a permission/security error
      if (e.toString().toLowerCase().contains("permission") || 
          e.toString().toLowerCase().contains("security")) {
        scaffoldMessengerKey.currentState?.showSnackBar(
          const SnackBar(
            content: Text("❌ RECORD_AUDIO permission required! Go to Settings → Apps → Permissions and enable Microphone, then restart the app."),
            backgroundColor: Colors.red,
            duration: Duration(seconds: 6),
          ),
        );
      } else {
        scaffoldMessengerKey.currentState?.showSnackBar(
          SnackBar(
            content: Text("Failed to start service: ${e.toString()}"),
            backgroundColor: Colors.red,
            duration: Duration(seconds: 4),
          ),
        );
      }
    }
  }

  // Stop background service
  Future<void> stopBackgroundService() async {
    try {
      await _linphoneSdkPlugin.stopBackgroundService();
      scaffoldMessengerKey.currentState?.showSnackBar(
        const SnackBar(content: Text("Background service stopped")),
      );
    } catch (e) {
      print("Error stopping background service. ${e.toString()}");
      scaffoldMessengerKey.currentState?.showSnackBar(
        SnackBar(content: Text("Failed to stop service: ${e.toString()}")),
      );
    }
  }

  // Navigate to call screen
  void navigateToCallScreen(String callerName, String callerNumber) {
    navigatorKey.currentState?.push(
      MaterialPageRoute(
        builder: (context) => CallScreen(
          callerName: callerName,
          callerNumber: callerNumber,
        ),
      ),
    );
  }

  // Check if service is running
  Future<void> checkServiceStatus() async {
    try {
      bool isRunning = await _linphoneSdkPlugin.isServiceRunning();
      scaffoldMessengerKey.currentState?.showSnackBar(
        SnackBar(content: Text("Service running: $isRunning")),
      );
    } catch (e) {
      print("Error checking service status. ${e.toString()}");
    }
  }

  // Method to initiate a call using the Linphone SDK
  Future<void> call() async {
    if (_textEditingController.text.isNotEmpty) {
      String number = _textEditingController.text;
      try {
        await _linphoneSdkPlugin.call(number: number);
      } catch (e) {
        // Show error message if the call fails
        print("Error on call. ${e.toString()}");
      }
    }
  }

  // Method to transfer an ongoing call to another number
  Future<void> forward() async {
    try {
      await _linphoneSdkPlugin.callTransfer(destination: "1000");
    } catch (e) {
      // Show error message if call transfer fails
      print("Error on call transfer. ${e.toString()}");
    }
  }

  // Method to hang up an ongoing call
  Future<void> hangUp() async {
    try {
      await _linphoneSdkPlugin.hangUp();
    } catch (e) {
      // Show error message if hang up fails
      scaffoldMessengerKey.currentState?.showSnackBar(
        SnackBar(content: Text("Hang up failed: ${e.toString()}")),
      );
    }
  }

  // Method to toggle the speaker on/off
  Future<void> toggleSpeaker() async {
    try {
      await _linphoneSdkPlugin.toggleSpeaker();
    } catch (e) {
      // Show error message if toggling the speaker fails
      print("Error on toggle speaker. ${e.toString()}");
    }
  }

  // Method to toggle mute on/off
  Future<void> toggleMute() async {
    try {
      bool isMuted = await _linphoneSdkPlugin.toggleMute();
      // Show feedback to the user about the mute status
      scaffoldMessengerKey.currentState?.showSnackBar(
        SnackBar(content: Text(isMuted ? "Muted" : "Unmuted")),
      );
    } catch (e) {
      // Show error message if toggling mute fails
      print("Error on toggle mute. ${e.toString()}");
    }
  }

  // Method to answer an incoming call
  Future<void> answer() async {
    try {
      await _linphoneSdkPlugin.answercall();
    } catch (e) {
      // Show error message if answering the call fails
      print("Error on answer call. ${e.toString()}");
    }
  }

  // Method to reject an incoming call
  Future<void> reject() async {
    try {
      await _linphoneSdkPlugin.rejectCall();
    } catch (e) {
      // Show error message if rejecting the call fails
      print("Error on reject call. ${e.toString()}");
    }
  }

  // Method to retrieve and print the call logs
  Future<void> callLogs() async {
    try {
      CallLogs callLogs = await _linphoneSdkPlugin.callLogs();
      print("---------call logs length: ${callLogs.callHistory.length}");
    } catch (e) {
      // Show error message if fetching call logs fails
      print("Error on call logs. ${e.toString()}");
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: navigatorKey,
      scaffoldMessengerKey: scaffoldMessengerKey,
      theme: ThemeData(
        primarySwatch: Colors.blue,
        visualDensity: VisualDensity.adaptivePlatformDensity,
      ),
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Linphone Flutter Plugin Example'),
        ),
        body: Column(
          children: [
            // Active Call Banner
            if (_hasActiveCall)
              InkWell(
                onTap: () async {
                  await _linphoneSdkPlugin.openCallScreen();
                },
                child: Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 16),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [Colors.green.shade600, Colors.green.shade700],
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withOpacity(0.3),
                        blurRadius: 6,
                        offset: const Offset(0, 3),
                      ),
                    ],
                  ),
                  child: Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.all(8),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.2),
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(Icons.call, color: Colors.white, size: 24),
                      ),
                      const SizedBox(width: 12),
                      const Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Call in Progress',
                              style: TextStyle(
                                color: Colors.white,
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            Text(
                              'Tap to return to call screen',
                              style: TextStyle(
                                color: Colors.white70,
                                fontSize: 13,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const Icon(Icons.arrow_forward_ios, color: Colors.white, size: 18),
                    ],
                  ),
                ),
              ),
            // Main Content
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(20),
                children: [
            // Username input field
            TextFormField(
              controller: _userController,
              decoration: const InputDecoration(
                icon: Icon(Icons.person),
                hintText: "Input username",
                labelText: "Username",
              ),
            ),
            // Password input field
            TextFormField(
              controller: _passController,
              obscureText: true,
              decoration: const InputDecoration(
                icon: Icon(Icons.lock),
                hintText: "Input password",
                labelText: "Password",
              ),
            ),
            // Domain input field
            TextFormField(
              controller: _domainController,
              decoration: const InputDecoration(
                icon: Icon(Icons.domain),
                hintText: "Input domain",
                labelText: "Domain",
              ),
            ),
            const SizedBox(height: 20),
            // Request Permissions Button - Do this FIRST
            ElevatedButton.icon(
              onPressed: requestPermissions,
              icon: const Icon(Icons.shield),
              label: const Text("1. Grant All Permissions"),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.orange,
                minimumSize: const Size(double.infinity, 50),
              ),
            ),
            const SizedBox(height: 10),
            // Login button
            ElevatedButton(
              onPressed: () {
                login(
                  username: _userController.text,
                  pass: _passController.text,
                  domain: _domainController.text,
                );
              },
              child: const Text("Login"),
            ),
            const SizedBox(height: 10),
            // Background Service Controls
            const Divider(thickness: 2),
            const Text(
              "Background Service (Receive Calls When App Closed)",
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 10),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.only(right: 5),
                    child: ElevatedButton.icon(
                      onPressed: () {
                        startBackgroundService(
                          username: _userController.text,
                          pass: _passController.text,
                          domain: _domainController.text,
                        );
                      },
                      icon: const Icon(Icons.play_arrow),
                      label: const Text("2. Start\nService"),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.green,
                        minimumSize: const Size(0, 60),
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.only(left: 5),
                    child: ElevatedButton.icon(
                      onPressed: stopBackgroundService,
                      icon: const Icon(Icons.stop),
                      label: const Text("Stop\nService"),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.red,
                        minimumSize: const Size(0, 60),
                      ),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            ElevatedButton.icon(
              onPressed: checkServiceStatus,
              icon: const Icon(Icons.info),
              label: const Text("Check Service Status"),
            ),
            const Divider(thickness: 2),
            const SizedBox(height: 20),
            // Display login status
            StreamBuilder<LoginState>(
              stream: _linphoneSdkPlugin.addLoginListener(),
              builder: (context, snapshot) {
                LoginState status = snapshot.data ?? LoginState.none;
                return Text("Login status: ${status.name}");
              },
            ),
            const SizedBox(height: 20),
            // Display call status
            StreamBuilder<CallState>(
              stream: _linphoneSdkPlugin.addCallStateListener(),
              builder: (context, snapshot) {
                CallState? status = snapshot.data;
                if (status == CallState.IncomingReceived) {
                  return AlertDialog(
                    title: const Text('Incoming Call'),
                    content: const Text('You have an incoming call.'),
                    actions: <Widget>[
                      TextButton(
                        onPressed: () async {
                          await reject();
                          if (mounted) Navigator.of(context).pop();
                        },
                        child: const Text('Reject'),
                      ),
                      TextButton(
                        onPressed: () async {
                          await answer();
                          if (mounted) Navigator.of(context).pop();
                        },
                        child: const Text('Answer'),
                      ),
                    ],
                  );
                }
                return Column(
                  children: [
                    Text("Call status: ${status?.name}"),
                    if (status == CallState.outgoingInit ||
                        status == CallState.outgoingProgress)
                      ElevatedButton(
                          onPressed: hangUp, child: const Text("Hang Up")),
                  ],
                );
              },
            ),
            const SizedBox(height: 20),
            // Phone number input field
            TextFormField(
              controller: _textEditingController,
              keyboardType: TextInputType.phone,
              decoration: const InputDecoration(
                icon: Icon(Icons.phone),
                hintText: "Input number",
                labelText: "Number",
              ),
            ),
            const SizedBox(height: 20),
            // Call button
            ElevatedButton(onPressed: call, child: const Text("Call")),
            const SizedBox(height: 20),
            // Answer button
            ElevatedButton(
              onPressed: () {
                answer();
              },
              child: const Text("Answer"),
            ),
            const SizedBox(height: 20),
            // Reject button
            ElevatedButton(
              onPressed: () {
                reject();
              },
              child: const Text("Reject"),
            ),
            // Hang up button
            ElevatedButton(
              onPressed: () {
                hangUp();
              },
              child: const Text("Hang Up"),
            ),
            const SizedBox(height: 20),
            // Toggle speaker button
            ElevatedButton(
              onPressed: () {
                toggleSpeaker();
              },
              child: const Text("Speaker"),
            ),
            const SizedBox(height: 20),
            // Toggle mute button
            ElevatedButton(
              onPressed: () {
                toggleMute();
              },
              child: const Text("Mute"),
            ),
            const SizedBox(height: 20),
            // Forward call button
            ElevatedButton(
              onPressed: () {
                forward();
              },
              child: const Text("Forward"),
            ),
            const SizedBox(height: 20),
            // Call log button
            ElevatedButton(
              onPressed: () {
                callLogs();
              },
              child: const Text("Call Log"),
            ),
            const SizedBox(height: 20),
            // Test Call Screen button
            ElevatedButton.icon(
              onPressed: () {
                navigateToCallScreen("Test User", "+1234567890");
              },
              icon: const Icon(Icons.phone_in_talk),
              label: const Text("Test Call Screen"),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.green,
                foregroundColor: Colors.white,
              ),
            ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    // Remove listeners and dispose of controllers to prevent memory leaks
    _callCheckTimer?.cancel();
    _linphoneSdkPlugin.removeLoginListener();
    _linphoneSdkPlugin.removeCallListener();
    _userController.dispose();
    _passController.dispose();
    _domainController.dispose();
    _textEditingController.dispose();
    super.dispose();
  }
}
