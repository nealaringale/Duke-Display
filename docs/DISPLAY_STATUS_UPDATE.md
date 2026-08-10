# Display status update

The phone app now exposes explicit display-link states:

- STARTING — BLE service is being initialized.
- DISPLAY STARTED — BLE advertising is active.
- DISPLAY CONNECTED — a display client is connected.
- DISPLAY FAILED — BLE setup/advertising failed and the button becomes RETRY DISPLAY.

BLE advertising now starts only after the GATT service has been successfully registered.
