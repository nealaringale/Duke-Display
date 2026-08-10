# Duke Dash Android V1

This is the first zero-hardware test app.

## What it tests
- Android media-session access
- Android notification-listener access
- Basic app UI for future Duke Dash data
- No Bluetooth/ESP32 yet
- No ECU
- No GPS/navigation yet

## Open
1. Install Android Studio.
2. Open this folder as an existing Gradle project.
3. Let Gradle sync.
4. Enable Developer Options + USB debugging on the Realme.
5. Connect the phone with USB.
6. Run the app.
7. Tap "Enable Notification Access" and enable Duke Dash.
8. Play music and send a test notification.
9. Tap "Refresh Music + Status".

## Important
This is deliberately V1. Navigation is not implemented yet because we want to verify
the Android media + notification side before adding the harder navigation integration.