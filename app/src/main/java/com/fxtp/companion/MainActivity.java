package com.fxtp.companion;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {
    private WebView webView, htmlOverlay;
    private static final int REQ_PERM = 1001, REQ_OVERLAY = 1002, REQ_MEDIA = 1003;
    private Handler mainHandler = new Handler();
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private boolean isMirroring = false;
    private HandlerThread mirrorThread;
    private Handler mirrorHandler;
    private boolean pendingMirror = false;
    private boolean pendingScreenshot = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        htmlOverlay = findViewById(R.id.htmlOverlay);
        requestPermissions();
        setupWebView();
        setupOverlay();
        webView.loadUrl("file:///android_asset/control.html");
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { injectBridge(); }
        });
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridgeInterface");
    }

    private void setupOverlay() {
        WebSettings s = htmlOverlay.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        htmlOverlay.setWebChromeClient(new WebChromeClient());
        htmlOverlay.setWebViewClient(new WebViewClient());
        htmlOverlay.setBackgroundColor(0x00000000);
        htmlOverlay.setVisibility(WebView.GONE);
    }

    private void injectBridge() {
        webView.loadUrl("javascript:if(typeof AndroidBridge === 'undefined') {" +
                "window.AndroidBridge = {" +
                "launchApp: function(pkg) { AndroidBridgeInterface.launchApp(pkg); }," +
                "listFiles: function(path) { AndroidBridgeInterface.listFiles(path); }," +
                "runShell: function(cmd) { AndroidBridgeInterface.runShell(cmd); }," +
                "takeScreenshot: function() { AndroidBridgeInterface.takeScreenshot(); }," +
                "startScreenMirror: function() { AndroidBridgeInterface.startScreenMirror(); }," +
                "stopScreenMirror: function() { AndroidBridgeInterface.stopScreenMirror(); }," +
                "sendNotification: function(title, body) { AndroidBridgeInterface.sendNotification(title, body); }," +
                "setClipboard: function(text) { AndroidBridgeInterface.setClipboard(text); }," +
                "getClipboard: function() { AndroidBridgeInterface.getClipboard(); }," +
                "downloadFile: function(path) { AndroidBridgeInterface.downloadFile(path); }," +
                "displayHtml: function(html) { AndroidBridgeInterface.displayHtml(html); }," +
                "closeHtml: function() { AndroidBridgeInterface.closeHtml(); }" +
                "};" +
                "console.log('AndroidBridge injected');" +
                "if (typeof window.onBridgeReady === 'function') window.onBridgeReady();" +
                "}");
    }

    private void requestPermissions() {
        String[] perms = {
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.POST_NOTIFICATIONS
        };
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, perms, REQ_PERM);
                break;
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA) {
            if (resultCode == Activity.RESULT_OK) {
                MediaProjectionManager mp = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                mediaProjection = mp.getMediaProjection(resultCode, data);
                Toast.makeText(this, "Screen capture granted", Toast.LENGTH_SHORT).show();
                if (pendingMirror) { startMirrorInternal(); pendingMirror = false; }
                if (pendingScreenshot) { takeScreenshotInternal(); pendingScreenshot = false; }
            } else {
                Toast.makeText(this, "Screen capture denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void requestMediaProjection() {
        MediaProjectionManager mp = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mp.createScreenCaptureIntent(), REQ_MEDIA);
    }

    // --- Public methods to send results to JavaScript ---
    public void sendResult(String type, String value) {
        mainHandler.post(() -> {
            String esc = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
            webView.loadUrl("javascript:if (window.handleBridgeResult) {" +
                    "window.handleBridgeResult('" + type + "', '" + esc + "');" +
                    "}");
        });
    }

    // --- Mirror and screenshot methods (accessible from onActivityResult) ---
    public void startMirrorInternal() {
        if (mediaProjection == null || isMirroring) return;
        try {
            DisplayMetrics m = getResources().getDisplayMetrics();
            int w = m.widthPixels, h = m.heightPixels, d = m.densityDpi;
            imageReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay("Mirror", w, h, d,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
            isMirroring = true;
            mirrorThread = new HandlerThread("MirrorThread");
            mirrorThread.start();
            mirrorHandler = new Handler(mirrorThread.getLooper());
            mirrorHandler.post(new Runnable() {
                @Override
                public void run() {
                    while (isMirroring) {
                        Image img = imageReader.acquireLatestImage();
                        if (img != null) {
                            ByteBuffer buf = img.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buf.remaining()];
                            buf.get(bytes);
                            img.close();
                            sendResult("mirror_frame", Base64.encodeToString(bytes, Base64.DEFAULT));
                        }
                        try { Thread.sleep(100); } catch (InterruptedException e) {}
                    }
                }
            });
            sendResult("mirror_started", "Mirror started");
        } catch (Exception e) { sendResult("mirror_started", "Error: " + e.getMessage()); }
    }

    public void takeScreenshotInternal() {
        if (mediaProjection == null) return;
        try {
            DisplayMetrics m = getResources().getDisplayMetrics();
            int w = m.widthPixels, h = m.heightPixels, d = m.densityDpi;
            imageReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay("Screenshot", w, h, d,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
            Image img = imageReader.acquireLatestImage();
            if (img != null) {
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                img.close();
                sendResult("screenshot_data", Base64.encodeToString(bytes, Base64.DEFAULT));
            } else sendResult("screenshot_data", "Failed");
            virtualDisplay.release();
            imageReader.close();
        } catch (Exception e) { sendResult("screenshot_data", "Error: " + e.getMessage()); }
    }

    // --- Android Bridge ---
    private class AndroidBridge {

        @JavascriptInterface public void launchApp(String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) { startActivity(i); sendResult("launch_result", "Launched: " + pkg); }
                else sendResult("launch_result", "App not found: " + pkg);
            } catch (Exception e) { sendResult("launch_result", "Error: " + e.getMessage()); }
        }

        @JavascriptInterface public void listFiles(String path) {
            try {
                File dir = new File(path);
                if (!dir.exists()) { sendResult("file_list", "Path not found"); return; }
                File[] files = dir.listFiles();
                StringBuilder sb = new StringBuilder();
                if (files != null) {
                    for (File f : files) sb.append(f.getName()).append(" (").append(f.isDirectory() ? "DIR" : f.length()).append(")\n");
                }
                sendResult("file_list", sb.toString());
            } catch (Exception e) { sendResult("file_list", "Error: " + e.getMessage()); }
        }

        @JavascriptInterface public void downloadFile(String path) {
            try {
                File file = new File(path);
                if (!file.exists()) { sendResult("file_data", "File not found"); return; }
                FileInputStream fis = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                fis.close();
                sendResult("file_data", Base64.encodeToString(data, Base64.DEFAULT));
            } catch (Exception e) { sendResult("file_data", "Error: " + e.getMessage()); }
        }

        @JavascriptInterface public void runShell(String cmd) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) out.append(line).append("\n");
                p.waitFor();
                sendResult("shell_result", out.toString());
            } catch (Exception e) { sendResult("shell_result", "Error: " + e.getMessage()); }
        }

        @JavascriptInterface public void takeScreenshot() {
            if (mediaProjection == null) { requestMediaProjection(); pendingScreenshot = true; return; }
            takeScreenshotInternal();
        }

        @JavascriptInterface public void startScreenMirror() {
            if (mediaProjection == null) { requestMediaProjection(); pendingMirror = true; return; }
            startMirrorInternal();
        }

        @JavascriptInterface public void stopScreenMirror() {
            isMirroring = false;
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
            if (mirrorThread != null) { mirrorThread.quitSafely(); mirrorThread = null; }
            sendResult("stop_mirror_ack", "Mirror stopped");
        }

        @JavascriptInterface public void sendNotification(String title, String body) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel("fxtp_channel", "FXTP", NotificationManager.IMPORTANCE_HIGH);
                NotificationManager mgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (mgr != null) mgr.createNotificationChannel(ch);
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(MainActivity.this, "fxtp_channel")
                    .setContentTitle(title).setContentText(body)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true);
            NotificationManager mgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (mgr != null) {
                mgr.notify((int) System.currentTimeMillis(), b.build());
                sendResult("notification_result", "Notification sent");
            }
        }

        @JavascriptInterface public void setClipboard(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("FXTP", text));
                sendResult("clipboard_result", "Copied");
            }
        }

        @JavascriptInterface public void getClipboard() {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                String text = cm.getPrimaryClip().getItemAt(0).getText().toString();
                sendResult("clipboard_result", text);
            } else sendResult("clipboard_result", "No clipboard");
        }

        @JavascriptInterface public void displayHtml(String html) {
            runOnUiThread(() -> {
                htmlOverlay.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                htmlOverlay.setVisibility(WebView.VISIBLE);
                sendResult("display_html_result", "HTML displayed");
            });
        }

        @JavascriptInterface public void closeHtml() {
            runOnUiThread(() -> {
                htmlOverlay.setVisibility(WebView.GONE);
                htmlOverlay.loadUrl("about:blank");
                sendResult("display_html_result", "HTML closed");
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mirrorThread != null) mirrorThread.quitSafely();
        isMirroring = false;
    }
        }
