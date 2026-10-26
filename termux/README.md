# Termux client

Install: `pkg install python socat`

Run commands from the repository copy of `serial_bridge.py`:

```sh
python serial_bridge.py status
python serial_bridge.py baud 9600
python serial_bridge.py configure --baudrate 115200 --data-bits 8 --parity none --stop-bits 1
python serial_bridge.py pty
```

Keep the PTY command running. Configure a compatible serial tool to use `$PREFIX/tmp/serial-bridge`. Use the control helper to change the actual USB serial baud rate; changing the PTY's termios speed does not set the hardware baud rate.
