import 'package:flutter/material.dart';
import 'package:linphone_flutter_plugin/linphoneflutterplugin.dart';
import 'package:linphone_flutter_plugin/login_state.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:permission_handler/permission_handler.dart';
import 'dart:async';

class SipRegistrationScreen extends StatefulWidget {
  const SipRegistrationScreen({Key? key}) : super(key: key);

  @override
  State<SipRegistrationScreen> createState() => _SipRegistrationScreenState();
}

class _SipRegistrationScreenState extends State<SipRegistrationScreen> {
  final _linphoneSdkPlugin = LinphoneFlutterPlugin();

  final _userController = TextEditingController();
  final _passController = TextEditingController();
  final _domainController = TextEditingController();

  bool _isLoading = false;
  bool _isRegistered = false;
  String _statusMessage = '';

  @override
  void initState() {
    super.initState();
    _loadCredentials();
    _checkServiceStatus();
    _listenToLoginState();
  }

  Future<void> _loadCredentials() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _userController.text = prefs.getString('username') ?? '';
        _passController.text = prefs.getString('password') ?? '';
        _domainController.text = prefs.getString('domain') ?? '';
      });
    } catch (e) {
      print("Error loading credentials: $e");
    }
  }

  Future<void> _saveCredentials() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('username', _userController.text);
      await prefs.setString('password', _passController.text);
      await prefs.setString('domain', _domainController.text);
    } catch (e) {
      print("Error saving credentials: $e");
    }
  }

  Future<void> _checkServiceStatus() async {
    try {
      bool isRunning = await _linphoneSdkPlugin.isServiceRunning();
      setState(() {
        _isRegistered = isRunning;
        _statusMessage = isRunning ? 'Service Running' : 'Service Stopped';
      });
    } catch (e) {
      print("Error checking service status: $e");
    }
  }

  void _listenToLoginState() {
    _linphoneSdkPlugin.addLoginListener().listen((state) {
      setState(() {
        switch (state) {
          case LoginState.ok:
            _isRegistered = true;
            _statusMessage = 'Registered Successfully';
            break;
          case LoginState.progress:
            _statusMessage = 'Registering...';
            break;
          case LoginState.failed:
            _isRegistered = false;
            _statusMessage = 'Registration Failed';
            break;
          default:
            _statusMessage = 'Not Registered';
        }
      });
    });
  }

  Future<bool> _checkAndRequestPermissions() async {
    try {
      // Request notification permission
      final notificationStatus = await Permission.notification.request();

      // Request system alert window (overlay) permission
      final overlayStatus = await Permission.systemAlertWindow.request();

      // Request other permissions through the plugin
      await _linphoneSdkPlugin.requestPermissions();

      if (notificationStatus.isDenied || overlayStatus.isDenied) {
        _showSnackBar(
          '⚠️ Some permissions were denied. Please grant all permissions for proper functionality.',
          Colors.orange,
        );
        return false;
      }

      if (notificationStatus.isPermanentlyDenied ||
          overlayStatus.isPermanentlyDenied) {
        _showPermissionSettingsDialog();
        return false;
      }

      _showSnackBar('✅ All permissions granted!', Colors.green);
      return true;
    } catch (e) {
      _showSnackBar('❌ Error requesting permissions: $e', Colors.red);
      return false;
    }
  }

  void _showPermissionSettingsDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF1D1E33),
        title: const Text(
          'Permissions Required',
          style: TextStyle(color: Colors.white),
        ),
        content: const Text(
          'This app needs notification and overlay permissions to work properly. Please enable them in app settings.',
          style: TextStyle(color: Colors.white70),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel', style: TextStyle(color: Colors.grey)),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              openAppSettings();
            },
            style: ElevatedButton.styleFrom(backgroundColor: Colors.blue),
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }

  Future<void> _requestPermissions() async {
    await _checkAndRequestPermissions();
  }

  Future<void> _startService() async {
    if (_userController.text.isEmpty ||
        _passController.text.isEmpty ||
        _domainController.text.isEmpty) {
      _showSnackBar('⚠️ Please fill in all fields', Colors.orange);
      return;
    }

    setState(() => _isLoading = true);

    try {
      // Check permissions before starting service
      final notificationStatus = await Permission.notification.status;
      final overlayStatus = await Permission.systemAlertWindow.status;

      if (!notificationStatus.isGranted || !overlayStatus.isGranted) {
        setState(() => _isLoading = false);

        final shouldRequest = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            backgroundColor: const Color(0xFF1D1E33),
            title: const Text(
              'Permissions Required',
              style: TextStyle(color: Colors.white),
            ),
            content: const Text(
              'The app needs the following permissions to work properly:\n\n'
              '• Notification permission (for call notifications)\n'
              '• Overlay permission (to appear on top of other apps)\n\n'
              'Would you like to grant these permissions now?',
              style: TextStyle(color: Colors.white70),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child:
                    const Text('Cancel', style: TextStyle(color: Colors.grey)),
              ),
              ElevatedButton(
                onPressed: () => Navigator.pop(context, true),
                style: ElevatedButton.styleFrom(backgroundColor: Colors.blue),
                child: const Text('Grant Permissions'),
              ),
            ],
          ),
        );

        if (shouldRequest != true) {
          _showSnackBar('⚠️ Permissions are required to start the service',
              Colors.orange);
          return;
        }

        setState(() => _isLoading = true);
        final permissionsGranted = await _checkAndRequestPermissions();

        if (!permissionsGranted) {
          setState(() => _isLoading = false);
          _showSnackBar('❌ Cannot start service without required permissions',
              Colors.red);
          return;
        }
      }

      await _saveCredentials();
      await _linphoneSdkPlugin.startBackgroundService(
        userName: _userController.text,
        domain: _domainController.text,
        password: _passController.text,
      );

      _showSnackBar('✅ Service started successfully', Colors.green);

      // Wait a bit then check status
      await Future.delayed(const Duration(seconds: 2));
      await _checkServiceStatus();

      if (_isRegistered) {
        // Navigate back to dial pad
        if (mounted) Navigator.pop(context, true);
      }
    } catch (e) {
      _showSnackBar('❌ Failed to start service: $e', Colors.red);
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _stopService() async {
    setState(() => _isLoading = true);

    try {
      await _linphoneSdkPlugin.stopBackgroundService();
      setState(() {
        _isRegistered = false;
        _statusMessage = 'Service Stopped';
      });
      _showSnackBar('Service stopped', Colors.orange);
    } catch (e) {
      _showSnackBar('Failed to stop service: $e', Colors.red);
    } finally {
      if (mounted) setState(() => _isLoading = false);
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
  void dispose() {
    _userController.dispose();
    _passController.dispose();
    _domainController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0A0E21),
      appBar: AppBar(
        backgroundColor: const Color(0xFF1D1E33),
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: Colors.white),
          onPressed: () => Navigator.pop(context),
        ),
        title: const Text(
          'SIP Registration',
          style: TextStyle(color: Colors.white, fontWeight: FontWeight.w600),
        ),
        centerTitle: true,
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Status Card
              Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: const Color(0xFF1D1E33),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: _isRegistered ? Colors.green : Colors.orange,
                    width: 2,
                  ),
                ),
                child: Column(
                  children: [
                    Icon(
                      _isRegistered ? Icons.check_circle : Icons.info,
                      color: _isRegistered ? Colors.green : Colors.orange,
                      size: 48,
                    ),
                    const SizedBox(height: 12),
                    Text(
                      _statusMessage,
                      style: TextStyle(
                        color: _isRegistered ? Colors.green : Colors.orange,
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 32),

              // Permission Button
              ElevatedButton.icon(
                onPressed: _isLoading ? null : _requestPermissions,
                icon: const Icon(Icons.security),
                label: const Text('1. Grant Permissions'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.orange,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
              const SizedBox(height: 24),

              // Username Field
              _buildTextField(
                controller: _userController,
                label: 'Username',
                icon: Icons.person,
                enabled: !_isLoading && !_isRegistered,
              ),
              const SizedBox(height: 16),

              // Password Field
              _buildTextField(
                controller: _passController,
                label: 'Password',
                icon: Icons.lock,
                obscureText: true,
                enabled: !_isLoading && !_isRegistered,
              ),
              const SizedBox(height: 16),

              // Domain Field
              _buildTextField(
                controller: _domainController,
                label: 'Domain',
                icon: Icons.domain,
                enabled: !_isLoading && !_isRegistered,
              ),
              const SizedBox(height: 32),

              // Action Buttons
              if (!_isRegistered)
                ElevatedButton.icon(
                  onPressed: _isLoading ? null : _startService,
                  icon: _isLoading
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Icon(Icons.play_arrow),
                  label: Text(_isLoading ? 'Starting...' : '2. Start Service'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: const Color(0xFF00C853),
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 18),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                )
              else
                Column(
                  children: [
                    ElevatedButton.icon(
                      onPressed: () => Navigator.pop(context),
                      icon: const Icon(Icons.check),
                      label: const Text('Back to Dial Pad'),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFF00C853),
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 18),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    OutlinedButton.icon(
                      onPressed: _isLoading ? null : _stopService,
                      icon: const Icon(Icons.stop),
                      label: const Text('Stop Service'),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: Colors.red,
                        side: const BorderSide(color: Colors.red),
                        padding: const EdgeInsets.symmetric(vertical: 18),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                      ),
                    ),
                  ],
                ),

              const SizedBox(height: 24),

              // Info Text
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: const Color(0xFF1D1E33),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Text(
                  '💡 Tip: The background service keeps you registered even when the app is closed, so you can receive calls anytime.',
                  style: TextStyle(
                    color: Colors.white70,
                    fontSize: 14,
                  ),
                  textAlign: TextAlign.center,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTextField({
    required TextEditingController controller,
    required String label,
    required IconData icon,
    bool obscureText = false,
    bool enabled = true,
  }) {
    return TextField(
      controller: controller,
      obscureText: obscureText,
      enabled: enabled,
      style: const TextStyle(color: Colors.white),
      decoration: InputDecoration(
        labelText: label,
        labelStyle: TextStyle(
          color: enabled ? Colors.white70 : Colors.white38,
        ),
        prefixIcon: Icon(
          icon,
          color: enabled ? Colors.white70 : Colors.white38,
        ),
        filled: true,
        fillColor: const Color(0xFF1D1E33),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: Colors.white12),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: Color(0xFF00C853), width: 2),
        ),
        disabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: Colors.white12),
        ),
      ),
    );
  }
}
