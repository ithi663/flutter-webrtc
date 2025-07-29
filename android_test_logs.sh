#!/bin/bash

echo "🔍 Android Night Vision Debug Logger"
echo "This script will show logs related to night vision processing"
echo "Make sure your Android device is connected and the app is running"
echo ""
echo "Commands to test:"
echo "1. Enable night vision in the app"
echo "2. Adjust intensity slider"
echo "3. Watch the logs below for processing activity"
echo ""
echo "================== LOGS =================="

# Monitor Android logs for night vision related activity
adb logcat -s "FlutterWebRTCPlugin:D" "NightVisionProcessor:D" "NightVisionRenderer:D" -v time