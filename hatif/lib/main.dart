import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:linphone_flutter_plugin/linphoneflutterplugin.dart';
import 'package:linphone_flutter_plugin/call_state.dart';
import 'package:linphone_flutter_plugin/login_state.dart';
import 'dart:async';
import 'sip_registration_screen.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'HATIF',
      theme: ThemeData(
        primarySwatch: Colors.blue,
        visualDensity: VisualDensity.adaptivePlatformDensity,
      ),
      home: const DialPadMainScreen(),
      debugShowCheckedModeBanner: false,
    );
  }
}

class DialPadMainScreen extends StatefulWidget {
  const DialPadMainScreen({super.key});

  @override
  State<DialPadMainScreen> createState() => _DialPadMainScreenState();
}

class _DialPadMainScreenState extends State<DialPadMainScreen>
    with TickerProviderStateMixin {
  final _linphoneSdkPlugin = LinphoneFlutterPlugin();

  String _phoneNumber = '';
  late AnimationController _pulseController;
  late AnimationController _deleteController;

  bool _isServiceRunning = false;
  bool _isRegistered = false; // Actual SIP registration state
  bool _hasActiveCall = false;
  Timer? _statusCheckTimer;
  StreamSubscription<LoginState>? _loginStateSubscription;
  LoginState _lastLoginState = LoginState.none;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 200),
    );
    _deleteController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 150),
    );

    // Start listening first to catch any immediate events
    _listenToCallState();
    _listenToRegistrationState();

    // Then check status and start timer
    _checkServiceStatus();
    _startStatusCheckTimer();
  }

  void _startStatusCheckTimer() {
    _statusCheckTimer =
        Timer.periodic(const Duration(seconds: 1), (timer) async {
      await _checkServiceStatus();
      await _checkActiveCall();
      // Update registration state based on last known state
      if (mounted) {
        final shouldBeRegistered = _lastLoginState == LoginState.ok;
        if (_isRegistered != shouldBeRegistered) {
          setState(() {
            _isRegistered = shouldBeRegistered;
          });
          debugPrint(
              '🔄 UI sync: Registration state updated to $_isRegistered (lastState: $_lastLoginState)');
        }
      }
    });
  }

  Future<void> _checkServiceStatus() async {
    try {
      final isRunning = await _linphoneSdkPlugin.isServiceRunning();
      if (mounted) {
        // If service is running, query current registration state
        if (isRunning) {
          final currentState =
              await _linphoneSdkPlugin.getCurrentRegistrationState();
          debugPrint('📡 Queried current registration state: $currentState');
          setState(() {
            _isServiceRunning = true;
            _lastLoginState = currentState;
            _isRegistered = (currentState == LoginState.ok);
          });
        } else {
          setState(() {
            _isServiceRunning = false;
            _lastLoginState = LoginState.none;
            _isRegistered = false;
          });
        }
      }
    } catch (e) {
      debugPrint('Error checking service status: $e');
    }
  }

  Future<void> _checkActiveCall() async {
    try {
      bool hasCall = await _linphoneSdkPlugin.hasActiveCall();
      if (mounted && hasCall != _hasActiveCall) {
        setState(() => _hasActiveCall = hasCall);
      }
    } catch (e) {
      // Ignore
    }
  }

  void _listenToCallState() {
    _linphoneSdkPlugin.addCallStateListener().listen((state) {
      if (state == CallState.IncomingReceived) {
        _showIncomingCallDialog();
      }
    });
  }

  void _listenToRegistrationState() {
    debugPrint('🎧 Setting up registration state listener...');
    _loginStateSubscription = _linphoneSdkPlugin.addLoginListener().listen(
      (state) {
        debugPrint('📡 Login state received: $state');
        _lastLoginState = state;
        final wasRegistered = _isRegistered;
        final isNowRegistered = state == LoginState.ok;

        if (mounted && wasRegistered != isNowRegistered) {
          setState(() {
            _isRegistered = isNowRegistered;
          });
          debugPrint('✅ Registration UI updated: $_isRegistered');
        }
      },
      onError: (error) {
        debugPrint('❌ Login state listener error: $error');
      },
      cancelOnError: false,
    );
  }

  void _showIncomingCallDialog() {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF1D1E33),
        title: const Text(
          'Incoming Call',
          style: TextStyle(color: Colors.white),
        ),
        content: const Text(
          'You have an incoming call.',
          style: TextStyle(color: Colors.white70),
        ),
        actions: [
          TextButton(
            onPressed: () async {
              await _linphoneSdkPlugin.rejectCall();
              if (!context.mounted) return;
              Navigator.pop(context);
            },
            child: const Text('Reject', style: TextStyle(color: Colors.red)),
          ),
          ElevatedButton(
            onPressed: () async {
              await _linphoneSdkPlugin.answercall();
              if (!context.mounted) return;
              Navigator.pop(context);
            },
            style: ElevatedButton.styleFrom(backgroundColor: Colors.green),
            child: const Text('Answer'),
          ),
        ],
      ),
    );
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _deleteController.dispose();
    _statusCheckTimer?.cancel();
    _loginStateSubscription?.cancel();
    super.dispose();
  }

  void _onDigitPressed(String digit) {
    HapticFeedback.lightImpact();
    setState(() => _phoneNumber += digit);
    _pulseController.forward(from: 0);
  }

  void _onDeletePressed() {
    if (_phoneNumber.isNotEmpty) {
      HapticFeedback.mediumImpact();
      setState(() =>
          _phoneNumber = _phoneNumber.substring(0, _phoneNumber.length - 1));
      _deleteController.forward(from: 0);
    }
  }

  void _clearNumber() {
    if (_phoneNumber.isNotEmpty) {
      HapticFeedback.mediumImpact();
      setState(() => _phoneNumber = '');
    }
  }

  Future<void> _makeCall() async {
    if (_phoneNumber.isEmpty) return;

    if (!_isServiceRunning || !_isRegistered) {
      _showSnackBar('⚠️ Please register first!', Colors.orange);
      _openRegistrationScreen();
      return;
    }

    HapticFeedback.heavyImpact();

    try {
      final numberToCall = _phoneNumber;

      // Initiate the call
      await _linphoneSdkPlugin.call(number: numberToCall);

      // Open native call screen immediately for better UX
      await _linphoneSdkPlugin.openCallScreen();

      _showSnackBar('📞 Calling $numberToCall...', Colors.green);
    } catch (e) {
      _showSnackBar('❌ Call failed: $e', Colors.red);
    }
  }

  void _openRegistrationScreen() async {
    final result = await Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => const SipRegistrationScreen()),
    );

    if (result == true) {
      await _checkServiceStatus();
    }
  }

  void _showSnackBar(String message, Color color) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: color,
        duration: const Duration(seconds: 2),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0A0E21),
      appBar: AppBar(
        backgroundColor: const Color(0xFF1D1E33),
        elevation: 0,
        title: Row(
          children: [
            const Text(
              'HATIF',
              style: TextStyle(
                color: Colors.white,
                fontSize: 24,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(width: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: _isRegistered ? Colors.green : Colors.red,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    _isRegistered ? Icons.check_circle : Icons.error,
                    color: Colors.white,
                    size: 14,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    _isRegistered ? 'Online' : 'Offline',
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings, color: Colors.white),
            onPressed: _openRegistrationScreen,
            tooltip: 'SIP Registration',
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            // Active call banner
            if (_hasActiveCall)
              InkWell(
                onTap: () async {
                  await _linphoneSdkPlugin.openCallScreen();
                },
                child: Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(16),
                  color: Colors.green,
                  child: const Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(Icons.phone_in_talk, color: Colors.white),
                      SizedBox(width: 8),
                      Text(
                        'Active Call - Tap to return',
                        style: TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                ),
              ),

            // Phone Number Display
            Expanded(
              flex: 2,
              child: Container(
                width: double.infinity,
                padding:
                    const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    AnimatedBuilder(
                      animation: _pulseController,
                      builder: (context, child) {
                        return Transform.scale(
                          scale: 1.0 + (_pulseController.value * 0.05),
                          child: child,
                        );
                      },
                      child: Text(
                        _phoneNumber.isEmpty ? 'Enter number' : _phoneNumber,
                        style: TextStyle(
                          fontSize: 38,
                          fontWeight: FontWeight.w300,
                          color: _phoneNumber.isEmpty
                              ? Colors.white38
                              : Colors.white,
                          letterSpacing: 3,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ),
                    const SizedBox(height: 16),
                    if (_phoneNumber.isNotEmpty)
                      TextButton.icon(
                        onPressed: _clearNumber,
                        icon: const Icon(Icons.close,
                            color: Colors.redAccent, size: 18),
                        label: const Text(
                          'Clear',
                          style:
                              TextStyle(color: Colors.redAccent, fontSize: 14),
                        ),
                      ),
                  ],
                ),
              ),
            ),

            // Dial Pad
            Expanded(
              flex: 5,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    _buildDialRow(['1', '2', '3']),
                    _buildDialRow(['4', '5', '6']),
                    _buildDialRow(['7', '8', '9']),
                    _buildDialRow(['*', '0', '#']),
                  ],
                ),
              ),
            ),

            // Action Buttons
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  // Delete Button
                  AnimatedBuilder(
                    animation: _deleteController,
                    builder: (context, child) {
                      return Transform.scale(
                        scale: 1.0 - (_deleteController.value * 0.2),
                        child: child,
                      );
                    },
                    child: Material(
                      color: const Color(0xFF1D1E33),
                      borderRadius: BorderRadius.circular(35),
                      child: InkWell(
                        borderRadius: BorderRadius.circular(35),
                        onTap: _onDeletePressed,
                        onLongPress: _clearNumber,
                        child: Container(
                          width: 70,
                          height: 70,
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(35),
                            border: Border.all(
                              color: _phoneNumber.isNotEmpty
                                  ? Colors.white24
                                  : Colors.white12,
                              width: 1,
                            ),
                          ),
                          child: Icon(
                            Icons.backspace_outlined,
                            color: _phoneNumber.isNotEmpty
                                ? Colors.white70
                                : Colors.white24,
                            size: 28,
                          ),
                        ),
                      ),
                    ),
                  ),

                  // Call Button
                  Material(
                    color: _phoneNumber.isNotEmpty && _isServiceRunning
                        ? const Color(0xFF00C853)
                        : Colors.grey.shade700,
                    borderRadius: BorderRadius.circular(40),
                    elevation:
                        _phoneNumber.isNotEmpty && _isServiceRunning ? 8 : 0,
                    shadowColor: const Color(0xFF00C853).withValues(alpha: 0.5),
                    child: InkWell(
                      borderRadius: BorderRadius.circular(40),
                      onTap: _phoneNumber.isNotEmpty && _isServiceRunning
                          ? _makeCall
                          : null,
                      child: Container(
                        width: 80,
                        height: 80,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(40),
                        ),
                        child: const Icon(
                          Icons.phone,
                          color: Colors.white,
                          size: 34,
                        ),
                      ),
                    ),
                  ),

                  // Menu Button
                  Material(
                    color: const Color(0xFF1D1E33),
                    borderRadius: BorderRadius.circular(35),
                    child: InkWell(
                      borderRadius: BorderRadius.circular(35),
                      onTap: () {
                        showModalBottomSheet(
                          context: context,
                          backgroundColor: const Color(0xFF1D1E33),
                          shape: const RoundedRectangleBorder(
                            borderRadius:
                                BorderRadius.vertical(top: Radius.circular(20)),
                          ),
                          builder: (context) => _buildMenuSheet(),
                        );
                      },
                      child: Container(
                        width: 70,
                        height: 70,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(35),
                          border: Border.all(color: Colors.white24, width: 1),
                        ),
                        child: const Icon(
                          Icons.more_vert,
                          color: Colors.white70,
                          size: 28,
                        ),
                      ),
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

  Widget _buildDialRow(List<String> digits) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: digits.map((digit) => _buildDialButton(digit)).toList(),
    );
  }

  Widget _buildDialButton(String digit) {
    final Map<String, String> letters = {
      '2': 'ABC',
      '3': 'DEF',
      '4': 'GHI',
      '5': 'JKL',
      '6': 'MNO',
      '7': 'PQRS',
      '8': 'TUV',
      '9': 'WXYZ',
    };

    return Expanded(
      child: Padding(
        padding: const EdgeInsets.all(8.0),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(40),
            splashColor: const Color(0xFF00C853).withValues(alpha: 0.3),
            highlightColor: Colors.white24,
            onTap: () => _onDigitPressed(digit),
            child: Container(
              height: 75,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(40),
                border: Border.all(color: Colors.white12, width: 1),
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    const Color(0xFF1D1E33).withValues(alpha: 0.8),
                    const Color(0xFF1D1E33),
                  ],
                ),
              ),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    digit,
                    style: const TextStyle(
                      fontSize: 28,
                      fontWeight: FontWeight.w400,
                      color: Colors.white,
                    ),
                  ),
                  if (letters.containsKey(digit))
                    Text(
                      letters[digit]!,
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w500,
                        color: Colors.white.withValues(alpha: 0.5),
                        letterSpacing: 1.5,
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildMenuSheet() {
    return Container(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ListTile(
            leading: const Icon(Icons.settings, color: Colors.white),
            title: const Text('SIP Registration',
                style: TextStyle(color: Colors.white)),
            onTap: () {
              Navigator.pop(context);
              _openRegistrationScreen();
            },
          ),
          ListTile(
            leading: const Icon(Icons.info, color: Colors.white),
            title: const Text('Registration Status',
                style: TextStyle(color: Colors.white)),
            subtitle: Text(
              _isRegistered ? 'Registered & Online' : 'Not Registered',
              style: TextStyle(
                color: _isRegistered ? Colors.green : Colors.red,
              ),
            ),
            onTap: () {
              Navigator.pop(context);
              _showSnackBar(
                _isRegistered
                    ? 'SIP account is registered'
                    : 'SIP account is not registered',
                _isRegistered ? Colors.green : Colors.red,
              );
            },
          ),
        ],
      ),
    );
  }
}
