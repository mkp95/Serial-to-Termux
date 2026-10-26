# Serial-to-Termux

Minimal pure-Java Android USB serial bridge for non-rooted Android phones.\n\n- Application ID: `io.mkp95.serialtotermux`\n- Minimum Android version: Android 10 (API 29).

- No Gradle or Kotlin.
- Android SDK command-line build (`aapt`, `javac`, `d8`, `zipalign`, `apksigner`).
- Only runtime third-party library: `usb-serial-for-android`.
- Local-only TCP data socket at `127.0.0.1:8765` and control socket at `127.0.0.1:8766`.
- Termux helper exposes the data stream as a PTY using `socat`.

## Build with GitHub Actions

Push to `main` or run **Actions → Build APK → Run workflow**. Download the `Serial-to-Termux-debug` artifact from the completed run.

## Use

1. Install the APK and open **Serial to Termux**.
2. Connect the USB-serial adapter via OTG, tap **Connect USB serial**, and grant Android USB permission.
3. In Termux install dependencies: `pkg install python socat`.
4. Copy `termux/serial_bridge.py` to Termux and run `python serial_bridge.py status`.
5. Set the physical UART settings, for example `python serial_bridge.py configure --baudrate 9600 --data-bits 8 --parity none --stop-bits 1`.
6. Run `python serial_bridge.py pty` and leave it running.
7. Point a serial tool that supports a configurable port path at `$PREFIX/tmp/serial-bridge`.

Default UART is 115200 8N1. Use the control helper to change the physical serial settings; setting baud on the PTY alone does not configure the USB adapter.

## Notes

TCP and PTYs are byte streams, not packet transports. Your client must handle partial reads and packet framing. Tools requiring direct USB/libusb access or hardware reset-pin control may need adaptation. Keep the Android app open while the bridge is in use.
