package io.mkp95.serialtotermux;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.mkp95.serialtotermux.USB_PERMISSION";
    private static final int DATA_PORT = 8765;
    private static final int CONTROL_PORT = 8766;

    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private UsbDeviceConnection usbConnection;
    private volatile boolean running;
    private volatile boolean dataClientConnected;
    private ServerSocket dataServer, controlServer;
    private Socket dataClient;
    private ExecutorService workers;
    private TextView status;
    private final Object portLock = new Object();
    private int baudRate = 115200, dataBits = 8, stopBits = 1, parity = UsbSerialPort.PARITY_NONE;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null)
                openDevice(device);
            else showStatus("USB permission denied.");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        workers = Executors.newCachedThreadPool();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 32, 28, 24);
        TextView title = new TextView(this);
        title.setText("USB Serial ↔ Termux");
        title.setTextSize(24);
        layout.addView(title);
        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, 20, 0, 20);
        layout.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button connect = new Button(this);
        connect.setText("Connect USB serial");
        connect.setOnClickListener(v -> connectUsb());
        layout.addView(connect);
        Button stop = new Button(this);
        stop.setText("Stop bridge");
        stop.setOnClickListener(v -> stopBridge());
        layout.addView(stop);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permissionReceiver, filter);

        showStatus("Connect your USB-serial adapter with OTG, then tap Connect.\n" +
                "Default UART: 115200 8N1\nData socket: 127.0.0.1:" + DATA_PORT +
                "\nControl socket: 127.0.0.1:" + CONTROL_PORT);
    }

    private void connectUsb() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            showStatus("No supported USB serial adapter found. Check OTG and adapter compatibility.");
            return;
        }
        UsbDevice device = drivers.get(0).getDevice();
        if (usbManager.hasPermission(device)) openDevice(device);
        else {
            Intent request = new Intent(ACTION_USB_PERMISSION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, request, flags);
            usbManager.requestPermission(device, pi);
            showStatus("Waiting for USB permission…");
        }
    }

    private void openDevice(UsbDevice device) {
        try {
            UsbSerialDriver selected = null;
            for (UsbSerialDriver driver : UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)) {
                if (driver.getDevice().getDeviceId() == device.getDeviceId()) { selected = driver; break; }
            }
            if (selected == null) throw new IOException("No matching serial driver");
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) throw new IOException("Android could not open USB device");
            UsbSerialPort port = selected.getPorts().get(0);
            port.open(connection);
            synchronized (portLock) {
                closePortLocked();
                usbConnection = connection;
                serialPort = port;
                applyParametersLocked();
            }
            startServers();
            showStatus("USB serial connected. " + settingsText() + "\nData: 127.0.0.1:" + DATA_PORT +
                    "\nControl: 127.0.0.1:" + CONTROL_PORT + "\nKeep this app open while capturing.");
        } catch (Exception e) {
            showStatus("USB open failed: " + e.getMessage());
        }
    }

    private void startServers() {
        stopServers();
        running = true;
        workers.execute(this::serveData);
        workers.execute(this::serveControl);
    }

    private void serveData() {
        try (ServerSocket server = new ServerSocket(DATA_PORT, 1, InetAddress.getByName("127.0.0.1"))) {
            dataServer = server;
            while (running) {
                try (Socket client = server.accept()) {
                    dataClient = client;
                    client.setTcpNoDelay(true);
                    dataClientConnected = true;
                    showStatus("Termux connected. " + settingsText());
                    ExecutorService pipes = Executors.newFixedThreadPool(2);
                    pipes.execute(() -> usbToSocket(client));
                    pipes.execute(() -> socketToUsb(client));
                    while (running && !client.isClosed()) {
                        try { Thread.sleep(100); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                    try { client.close(); } catch (IOException ignored) {}
                    pipes.shutdownNow();
                    dataClientConnected = false;
                    dataClient = null;
                    if (running) showStatus("Termux disconnected. Waiting for client.");
                } catch (IOException e) {
                    if (running) showStatus("Data socket error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) showStatus("Data listener failed: " + e.getMessage());
        }
    }

    private void usbToSocket(Socket client) {
        byte[] buffer = new byte[4096];
        try {
            while (running && !client.isClosed()) {
                UsbSerialPort port;
                synchronized (portLock) { port = serialPort; }
                if (port == null) break;
                int count = port.read(buffer, 500);
                if (count > 0) {
                    OutputStream out = client.getOutputStream();
                    out.write(buffer, 0, count);
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        } finally { try { client.close(); } catch (IOException ignored) {} }
    }

    private void socketToUsb(Socket client) {
        byte[] buffer = new byte[4096];
        try {
            int count;
            while (running && !client.isClosed() && (count = client.getInputStream().read(buffer)) != -1) {
                UsbSerialPort port;
                synchronized (portLock) { port = serialPort; }
                if (port == null) break;
                port.write(buffer, count, 1500);
            }
        } catch (Exception ignored) {
        } finally { try { client.close(); } catch (IOException ignored) {} }
    }

    private void serveControl() {
        try (ServerSocket server = new ServerSocket(CONTROL_PORT, 4, InetAddress.getByName("127.0.0.1"))) {
            controlServer = server;
            while (running) {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                     OutputStream out = client.getOutputStream()) {
                    JSONObject response = handleCommand(reader.readLine());
                    out.write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                    // One bad command must not stop the bridge.
                }
            }
        } catch (IOException e) {
            if (running) showStatus("Control listener failed: " + e.getMessage());
        }
    }

    private JSONObject handleCommand(String line) {
        JSONObject response = new JSONObject();
        try {
            JSONObject request = new JSONObject(line == null ? "{}" : line);
            String command = request.optString("command", "status");
            if ("status".equals(command)) {
                response.put("ok", true);
                response.put("connected", serialPort != null);
                response.put("data_client", dataClientConnected);
                response.put("baudrate", baudRate);
                response.put("data_bits", dataBits);
                response.put("stop_bits", stopBits);
                response.put("parity", parityName(parity));
            } else if ("configure".equals(command)) {
                synchronized (portLock) {
                    int newBaud = request.optInt("baudrate", baudRate);
                    int newBits = request.optInt("data_bits", dataBits);
                    int newStops = request.optInt("stop_bits", stopBits);
                    String p = request.optString("parity", parityName(parity)).toUpperCase();
                    int newParity = "ODD".equals(p) ? UsbSerialPort.PARITY_ODD :
                            "EVEN".equals(p) ? UsbSerialPort.PARITY_EVEN :
                            "MARK".equals(p) ? UsbSerialPort.PARITY_MARK :
                            "SPACE".equals(p) ? UsbSerialPort.PARITY_SPACE :
                            "NONE".equals(p) ? UsbSerialPort.PARITY_NONE : -1;
                    if (newBaud < 1 || newBits < 5 || newBits > 8 ||
                            (newStops != 1 && newStops != 2) || newParity < 0)
                        throw new IllegalArgumentException("Invalid serial parameters");
                    baudRate = newBaud; dataBits = newBits; stopBits = newStops; parity = newParity;
                    applyParametersLocked();
                }
                response.put("ok", true);
                response.put("message", "Serial settings applied");
                response.put("baudrate", baudRate);
                response.put("data_bits", dataBits);
                response.put("stop_bits", stopBits);
                response.put("parity", parityName(parity));
                showStatus("UART settings: " + settingsText());
            } else {
                response.put("ok", false);
                response.put("error", "Unknown command");
            }
        } catch (Exception e) {
            try { response.put("ok", false); response.put("error", e.getMessage() == null ? "Invalid request" : e.getMessage()); }
            catch (Exception ignored) {}
        }
        return response;
    }

    private void applyParametersLocked() throws IOException {
        if (serialPort != null) {
            int stops = stopBits == 2 ? UsbSerialPort.STOPBITS_2 : UsbSerialPort.STOPBITS_1;
            serialPort.setParameters(baudRate, dataBits, stops, parity);
        }
    }

    private String parityName(int value) {
        if (value == UsbSerialPort.PARITY_ODD) return "ODD";
        if (value == UsbSerialPort.PARITY_EVEN) return "EVEN";
        if (value == UsbSerialPort.PARITY_MARK) return "MARK";
        if (value == UsbSerialPort.PARITY_SPACE) return "SPACE";
        return "NONE";
    }
    private String settingsText() { return baudRate + " " + dataBits + parityName(parity).charAt(0) + stopBits; }

    private void stopServers() {
        running = false;
        try { if (dataClient != null) dataClient.close(); } catch (IOException ignored) {}
        try { if (dataServer != null) dataServer.close(); } catch (IOException ignored) {}
        try { if (controlServer != null) controlServer.close(); } catch (IOException ignored) {}
    }

    private void stopBridge() {
        stopServers();
        synchronized (portLock) { closePortLocked(); }
        showStatus("Bridge stopped.");
    }

    private void closePortLocked() {
        try { if (serialPort != null) serialPort.close(); } catch (Exception ignored) {}
        try { if (usbConnection != null) usbConnection.close(); } catch (Exception ignored) {}
        serialPort = null;
        usbConnection = null;
    }

    private void showStatus(String message) {
        runOnUiThread(() -> { if (status != null) status.setText(message); });
    }

    @Override protected void onDestroy() {
        stopBridge();
        try { unregisterReceiver(permissionReceiver); } catch (Exception ignored) {}
        if (workers != null) workers.shutdownNow();
        super.onDestroy();
    }
}
