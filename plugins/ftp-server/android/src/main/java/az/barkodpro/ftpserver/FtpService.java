package az.barkodpro.ftpserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;

/** FTP serveri ön plan xidməti kimi saxlayır ki, ekran sönəndə Android onu bağlamasın. */
public class FtpService extends Service {
    static final String CHANNEL = "ftp_server";
    static final int NOTIF_ID = 4721;

    static volatile MiniFtpServer server;
    static volatile int serverPort = 0;
    static volatile String serverRoot = "";
    static volatile String lastError = "";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int port = intent != null ? intent.getIntExtra("port", 2221) : 2221;
        String user = intent != null ? intent.getStringExtra("user") : "";
        String pass = intent != null ? intent.getStringExtra("pass") : "";
        String root = intent != null ? intent.getStringExtra("root") : null;

        try {
            startAsForeground(port);
        } catch (Exception e) {
            lastError = "Ön plan xidməti başlamadı: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }

        stopServerInternal();
        lastError = "";
        try {
            File dir;
            if (root != null) {
                dir = new File(root);
            } else {
                File base = getExternalFilesDir(null);
                if (base == null) base = getFilesDir();
                dir = new File(base, "TerminalFilesBarkodPRO");
            }
            dir.mkdirs();
            MiniFtpServer s = new MiniFtpServer(dir, port, user, pass);
            s.start();
            server = s;
            serverPort = port;
            serverRoot = dir.getAbsolutePath();
            acquireLocks();
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground(int port) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "FTP server", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = null;
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            pi = PendingIntent.getActivity(this, 0, launch, fl);
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL);
        else b = new Notification.Builder(this);
        b.setContentTitle("BarkodPRO Terminal FTP")
                .setContentText("FTP server işləyir (port " + port + ")")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true);
        if (pi != null) b.setContentIntent(pi);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "barkodpro:ftp");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) { }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "barkodpro:ftp");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) { }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        wakeLock = null;
        wifiLock = null;
    }

    private void stopServerInternal() {
        MiniFtpServer s = server;
        server = null;
        if (s != null) s.stop();
    }

    @Override
    public void onDestroy() {
        stopServerInternal();
        releaseLocks();
        super.onDestroy();
    }
}
