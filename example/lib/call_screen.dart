// import 'package:flutter/material.dart';
// import 'dart:async';

// class CallScreen extends StatefulWidget {
//   final String callerName;
//   final String callerNumber;
  
//   const CallScreen({
//     Key? key,
//     required this.callerName,
//     required this.callerNumber,
//   }) : super(key: key);

//   @override
//   State<CallScreen> createState() => _CallScreenState();
// }

// class _CallScreenState extends State<CallScreen> with TickerProviderStateMixin {
//   bool isMuted = false;
//   bool isSpeakerOn = false;
//   bool isOnHold = false;
//   bool showDTMF = false;
  
//   late Timer _timer;
//   int _elapsedSeconds = 0;
  
//   late AnimationController _pulseController;
//   late Animation<double> _pulseAnimation;
  
//   @override
//   void initState() {
//     super.initState();
//     _startTimer();
    
//     // Initialize pulse animation for active call indicator
//     _pulseController = AnimationController(
//       vsync: this,
//       duration: const Duration(milliseconds: 1500),
//     )..repeat(reverse: true);
    
//     _pulseAnimation = Tween<double>(begin: 0.8, end: 1.0).animate(
//       CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
//     );
//   }
  
//   void _startTimer() {
//     _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
//       setState(() {
//         _elapsedSeconds++;
//       });
//     });
//   }
  
//   String _formatDuration(int seconds) {
//     int minutes = seconds ~/ 60;
//     int secs = seconds % 60;
//     return '${minutes.toString().padLeft(2, '0')}:${secs.toString().padLeft(2, '0')}';
//   }
  
//   @override
//   void dispose() {
//     _timer.cancel();
//     _pulseController.dispose();
//     super.dispose();
//   }
  
//   Widget _buildControlButton({
//     required IconData icon,
//     required String label,
//     required bool isActive,
//     required VoidCallback onPressed,
//     Color? activeColor,
//   }) {
//     return Column(
//       mainAxisSize: MainAxisSize.min,
//       children: [
//         AnimatedContainer(
//           duration: const Duration(milliseconds: 200),
//           width: 64,
//           height: 64,
//           decoration: BoxDecoration(
//             color: isActive 
//                 ? (activeColor ?? Colors.white)
//                 : Colors.white.withOpacity(0.2),
//             shape: BoxShape.circle,
//             boxShadow: isActive
//                 ? [
//                     BoxShadow(
//                       color: (activeColor ?? Colors.white).withOpacity(0.4),
//                       blurRadius: 20,
//                       spreadRadius: 2,
//                     ),
//                   ]
//                 : [],
//           ),
//           child: Material(
//             color: Colors.transparent,
//             child: InkWell(
//               borderRadius: BorderRadius.circular(32),
//               onTap: onPressed,
//               child: Icon(
//                 icon,
//                 color: isActive ? Colors.black : Colors.white,
//                 size: 28,
//               ),
//             ),
//           ),
//         ),
//         const SizedBox(height: 8),
//         Text(
//           label,
//           style: TextStyle(
//             color: Colors.white.withOpacity(0.9),
//             fontSize: 12,
//             fontWeight: FontWeight.w500,
//           ),
//         ),
//       ],
//     );
//   }
  
//   Widget _buildDTMFButton(String digit) {
//     return Material(
//       color: Colors.transparent,
//       child: InkWell(
//         borderRadius: BorderRadius.circular(30),
//         onTap: () {
//           // TODO: Send DTMF tone
//           print('DTMF: $digit');
//         },
//         child: Container(
//           width: 60,
//           height: 60,
//           decoration: BoxDecoration(
//             color: Colors.white.withOpacity(0.2),
//             shape: BoxShape.circle,
//           ),
//           child: Center(
//             child: Text(
//               digit,
//               style: const TextStyle(
//                 color: Colors.white,
//                 fontSize: 24,
//                 fontWeight: FontWeight.w400,
//               ),
//             ),
//           ),
//         ),
//       ),
//     );
//   }
  
//   @override
//   Widget build(BuildContext context) {
//     return Scaffold(
//       body: Container(
//         decoration: BoxDecoration(
//           gradient: LinearGradient(
//             begin: Alignment.topCenter,
//             end: Alignment.bottomCenter,
//             colors: [
//               const Color(0xFF1a1a2e),
//               const Color(0xFF16213e),
//               const Color(0xFF0f3460),
//             ],
//           ),
//         ),
//         child: SafeArea(
//           child: Stack(
//             children: [
//               // Main content
//               Column(
//                 children: [
//                   const SizedBox(height: 60),
                  
//                   // Caller avatar with pulse animation
//                   ScaleTransition(
//                     scale: _pulseAnimation,
//                     child: Container(
//                       width: 140,
//                       height: 140,
//                       decoration: BoxDecoration(
//                         shape: BoxShape.circle,
//                         gradient: LinearGradient(
//                           colors: [
//                             Colors.blue.shade400,
//                             Colors.purple.shade400,
//                           ],
//                         ),
//                         boxShadow: [
//                           BoxShadow(
//                             color: Colors.blue.withOpacity(0.3),
//                             blurRadius: 30,
//                             spreadRadius: 10,
//                           ),
//                         ],
//                       ),
//                       child: Center(
//                         child: Text(
//                           widget.callerName.isNotEmpty 
//                               ? widget.callerName[0].toUpperCase()
//                               : '?',
//                           style: const TextStyle(
//                             color: Colors.white,
//                             fontSize: 64,
//                             fontWeight: FontWeight.w600,
//                           ),
//                         ),
//                       ),
//                     ),
//                   ),
                  
//                   const SizedBox(height: 30),
                  
//                   // Caller name
//                   Text(
//                     widget.callerName.isNotEmpty 
//                         ? widget.callerName 
//                         : widget.callerNumber,
//                     style: const TextStyle(
//                       color: Colors.white,
//                       fontSize: 32,
//                       fontWeight: FontWeight.w600,
//                     ),
//                     textAlign: TextAlign.center,
//                   ),
                  
//                   const SizedBox(height: 8),
                  
//                   // Caller number (if name exists)
//                   if (widget.callerName.isNotEmpty)
//                     Text(
//                       widget.callerNumber,
//                       style: TextStyle(
//                         color: Colors.white.withOpacity(0.7),
//                         fontSize: 18,
//                       ),
//                     ),
                  
//                   const SizedBox(height: 16),
                  
//                   // Call duration
//                   Container(
//                     padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
//                     decoration: BoxDecoration(
//                       color: Colors.white.withOpacity(0.1),
//                       borderRadius: BorderRadius.circular(20),
//                     ),
//                     child: Text(
//                       _formatDuration(_elapsedSeconds),
//                       style: TextStyle(
//                         color: Colors.white.withOpacity(0.9),
//                         fontSize: 16,
//                         fontWeight: FontWeight.w500,
//                         letterSpacing: 1.5,
//                       ),
//                     ),
//                   ),
                  
//                   const Spacer(),
                  
//                   // Control buttons
//                   if (!showDTMF) ...[
//                     Padding(
//                       padding: const EdgeInsets.symmetric(horizontal: 40),
//                       child: Row(
//                         mainAxisAlignment: MainAxisAlignment.spaceEvenly,
//                         children: [
//                           _buildControlButton(
//                             icon: isMuted ? Icons.mic_off : Icons.mic,
//                             label: 'Mute',
//                             isActive: isMuted,
//                             onPressed: () {
//                               setState(() {
//                                 isMuted = !isMuted;
//                               });
//                               // TODO: Call native method to toggle mute
//                             },
//                           ),
//                           _buildControlButton(
//                             icon: Icons.dialpad,
//                             label: 'Keypad',
//                             isActive: showDTMF,
//                             onPressed: () {
//                               setState(() {
//                                 showDTMF = true;
//                               });
//                             },
//                           ),
//                           _buildControlButton(
//                             icon: isSpeakerOn ? Icons.volume_up : Icons.volume_down,
//                             label: 'Speaker',
//                             isActive: isSpeakerOn,
//                             onPressed: () {
//                               setState(() {
//                                 isSpeakerOn = !isSpeakerOn;
//                               });
//                               // TODO: Call native method to toggle speaker
//                             },
//                           ),
//                         ],
//                       ),
//                     ),
                    
//                     const SizedBox(height: 40),
                    
//                     // Hold button (centered)
//                     _buildControlButton(
//                       icon: isOnHold ? Icons.play_arrow : Icons.pause,
//                       label: isOnHold ? 'Resume' : 'Hold',
//                       isActive: isOnHold,
//                       onPressed: () {
//                         setState(() {
//                           isOnHold = !isOnHold;
//                         });
//                         // TODO: Call native method to toggle hold
//                       },
//                     ),
//                   ],
                  
//                   const SizedBox(height: 60),
                  
//                   // Hang up button
//                   GestureDetector(
//                     onTap: () {
//                       // TODO: Call native method to hang up
//                       Navigator.of(context).pop();
//                     },
//                     child: Container(
//                       width: 70,
//                       height: 70,
//                       decoration: BoxDecoration(
//                         color: Colors.red.shade500,
//                         shape: BoxShape.circle,
//                         boxShadow: [
//                           BoxShadow(
//                             color: Colors.red.withOpacity(0.4),
//                             blurRadius: 20,
//                             spreadRadius: 5,
//                           ),
//                         ],
//                       ),
//                       child: const Icon(
//                         Icons.call_end,
//                         color: Colors.white,
//                         size: 32,
//                       ),
//                     ),
//                   ),
                  
//                   const SizedBox(height: 50),
//                 ],
//               ),
              
//               // DTMF Keypad overlay
//               if (showDTMF)
//                 Positioned.fill(
//                   child: GestureDetector(
//                     onTap: () {
//                       setState(() {
//                         showDTMF = false;
//                       });
//                     },
//                     child: Container(
//                       color: Colors.black.withOpacity(0.7),
//                       child: Center(
//                         child: GestureDetector(
//                           onTap: () {}, // Prevent closing when tapping keypad
//                           child: ConstrainedBox(
//                             constraints: BoxConstraints(
//                               maxWidth: MediaQuery.of(context).size.width * 0.85,
//                             ),
//                             child: Container(
//                               padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 25),
//                               decoration: BoxDecoration(
//                                 color: const Color(0xFF1a1a2e),
//                                 borderRadius: BorderRadius.circular(30),
//                               ),
//                               child: Column(
//                                 mainAxisSize: MainAxisSize.min,
//                                 children: [
//                                   Row(
//                                     mainAxisAlignment: MainAxisAlignment.spaceBetween,
//                                     children: [
//                                       const Text(
//                                         'Enter Number',
//                                         style: TextStyle(
//                                           color: Colors.white,
//                                           fontSize: 18,
//                                           fontWeight: FontWeight.w600,
//                                         ),
//                                       ),
//                                       IconButton(
//                                         icon: const Icon(Icons.close, color: Colors.white, size: 20),
//                                         padding: EdgeInsets.zero,
//                                         constraints: const BoxConstraints(),
//                                         onPressed: () {
//                                           setState(() {
//                                             showDTMF = false;
//                                           });
//                                         },
//                                       ),
//                                     ],
//                                   ),
//                                   const SizedBox(height: 20),
//                                   ...List.generate(4, (row) {
//                                     return Padding(
//                                       padding: const EdgeInsets.only(bottom: 15),
//                                       child: Row(
//                                         mainAxisAlignment: MainAxisAlignment.spaceEvenly,
//                                         children: List.generate(3, (col) {
//                                           if (row == 3) {
//                                             if (col == 0) return _buildDTMFButton('*');
//                                             if (col == 1) return _buildDTMFButton('0');
//                                             if (col == 2) return _buildDTMFButton('#');
//                                           }
//                                           int digit = row * 3 + col + 1;
//                                           return _buildDTMFButton(digit.toString());
//                                         }),
//                                       ),
//                                     );
//                                   }),
//                                 ],
//                               ),
//                             ),
//                           ),
//                         ),
//                       ),
//                     ),
//                   ),
//                 ),
//             ],
//           ),
//         ),
//       ),
//     );
//   }
// }
