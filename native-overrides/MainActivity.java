package com.natsheh.daftar;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.core.app.ActivityCompat;
import com.getcapacitor.BridgeActivity;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    java.util.List<String> permsList = new java.util.ArrayList<>();
    permsList.add(Manifest.permission.ACCESS_FINE_LOCATION);
    permsList.add(Manifest.permission.ACCESS_COARSE_LOCATION);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      permsList.add(Manifest.permission.BLUETOOTH_CONNECT);
      permsList.add(Manifest.permission.BLUETOOTH_SCAN);
    }
    String[] perms = permsList.toArray(new String[0]);
    boolean needsRequest = false;
    for (String p : perms) {
      if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
        needsRequest = true;
        break;
      }
    }
    if (needsRequest) {
      ActivityCompat.requestPermissions(this, perms, 1001);
    }

    WebView webView = getBridge().getWebView();
    webView.addJavascriptInterface(new AndroidPrintBridge(), "AndroidPrint");
  }

  public class AndroidPrintBridge {
    @JavascriptInterface
    public void printReceipt() {
      runOnUiThread(() -> {
        WebView webView = getBridge().getWebView();
        PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
        String jobName = "فاتورة دفتر";
        PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(jobName);
        printManager.print(jobName, adapter, new PrintAttributes.Builder().build());
      });
    }

    @JavascriptInterface
    public String getPairedDevicesJson() {
      try {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return "[]";
        Set<BluetoothDevice> devices = adapter.getBondedDevices();
        JSONArray arr = new JSONArray();
        for (BluetoothDevice d : devices) {
          JSONObject o = new JSONObject();
          o.put("name", d.getName());
          o.put("address", d.getAddress());
          arr.put(o);
        }
        return arr.toString();
      } catch (Exception e) {
        return "[]";
      }
    }

    @JavascriptInterface
    public String printBluetoothRaw(String address, String base64Data) {
      BluetoothSocket socket = null;
      String lastError = "";
      try {
        byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return "ERROR:بلوتوث غير متوفر على الجهاز";
        try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
        BluetoothDevice device = adapter.getRemoteDevice(address);

        // Try 1: INSECURE RFCOMM via standard UUID — this is what the known-working
        // Gprinter SDK uses, and is very likely the missing piece for this printer.
        try {
          UUID sppUuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
          socket = device.createInsecureRfcommSocketToServiceRecord(sppUuid);
          socket.connect();
        } catch (Exception e0) {
          lastError = "insecure: " + (e0.getMessage() == null ? e0.toString() : e0.getMessage());
          socket = null;
        }

        // Try 2: fixed RFCOMM channel 1 via reflection (works for most clone ESC/POS printers
        // whose SDP records don't resolve correctly through the standard UUID lookup).
        if (socket == null) {
          try {
            java.lang.reflect.Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            socket = (BluetoothSocket) m.invoke(device, 1);
            socket.connect();
          } catch (Exception e1) {
            lastError += " | channel1: " + (e1.getMessage() == null ? e1.toString() : e1.getMessage());
            socket = null;
          }
        }

        // Try 3: standard SECURE SDP UUID lookup, as last resort.
        if (socket == null) {
          try {
            UUID sppUuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
            socket = device.createRfcommSocketToServiceRecord(sppUuid);
            socket.connect();
          } catch (Exception e2) {
            lastError += " | uuid: " + (e2.getMessage() == null ? e2.toString() : e2.getMessage());
            socket = null;
          }
        }

        if (socket == null) return "ERROR:" + lastError;

        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        OutputStream os = socket.getOutputStream();
        int chunkSize = 256;
        for (int i = 0; i < data.length; i += chunkSize) {
          int end = Math.min(data.length, i + chunkSize);
          os.write(data, i, end - i);
          os.flush();
          try { Thread.sleep(15); } catch (InterruptedException ignored) {}
        }
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        return "OK";
      } catch (Exception e) {
        return "ERROR:" + (e.getMessage() == null ? e.toString() : e.getMessage());
      } finally {
        if (socket != null) {
          try { socket.close(); } catch (Exception ignored) {}
        }
      }
    }
  }
}
