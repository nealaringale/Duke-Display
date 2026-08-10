# Duke Dash PC Display Test

This makes a Windows laptop act like the future ESP32 + TFT for a zero-hardware test.

## Requirements

- Windows 10/11
- Bluetooth Low Energy support on the laptop
- Python 3.10+ installed
- Duke Dash installed on the Android phone

## Run

1. Connect the phone and laptop over Bluetooth (normal Windows Bluetooth pairing is recommended).
2. Open Duke Dash on the phone.
3. Tap **START DISPLAY**. Keep the app running.
4. On the laptop, open PowerShell in this `pc_test` folder.
5. Install the BLE library:

```powershell
py -m pip install -r requirements.txt
```

6. Start the PC display:

```powershell
py duke_dash_pc.py
```

The window should find the Duke Dash BLE service and show the same navigation, music and messaging data that the future TFT will receive.

## If the phone is not found

- Make sure Bluetooth is ON on both devices.
- Make sure Windows has Bluetooth LE support.
- Tap **START DISPLAY** again in Duke Dash.
- Close other BLE scanner/connection apps that may be holding the phone's GATT connection.
- Press **RESCAN** in the PC test window.

The PC program does not use the internet, Google Maps, or the phone screen. It is simply acting as the BLE display receiver.
