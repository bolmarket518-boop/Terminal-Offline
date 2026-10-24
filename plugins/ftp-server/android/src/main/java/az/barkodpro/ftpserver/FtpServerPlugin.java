package az.barkodpro.ftpserver;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * JS tərəfdən: Capacitor.Plugins.FtpServer.start({port, user, pass}) / stop() / status()
 * FTP-nin kök qovluğu: proqramın xarici qovluğu /TerminalFilesBarkodPRO (Filesystem "EXTERNAL" ilə eyni yer).
 */
@CapacitorPlugin(name = "FtpServer")
public class FtpServerPlugin extends Plugin {

    @PluginMethod
    public void start(final PluginCall call) {
        Context ctx = getContext();
        Integer p = call.getInt("port", 2221);
        int port = p == null ? 2221 : p;
        String user = call.getString("user", "");
        String pass = call.getString("pass", "");

        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File root = new File(base, "TerminalFilesBarkodPRO");
        root.mkdirs();

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try {
                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7731);
            } catch (Exception ignored) { }
        }

        Intent i = new Intent(ctx, FtpService.class);
        i.putExtra("port", port);
        i.putExtra("user", user);
        i.putExtra("pass", pass);
        i.putExtra("root", root.getAbsolutePath());
        try {
            ContextCompat.startForegroundService(ctx, i);
        } catch (Exception e) {
            call.reject("FTP xidməti başlamadı: " + e.getMessage());
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(800); } catch (InterruptedException ignored) { }
                call.resolve(statusObj());
            }
        }).start();
    }

    @PluginMethod
    public void stop(final PluginCall call) {
        getContext().stopService(new Intent(getContext(), FtpService.class));
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                call.resolve(statusObj());
            }
        }).start();
    }

    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(statusObj());
    }

    private JSObject statusObj() {
        JSObject o = new JSObject();
        MiniFtpServer s = FtpService.server;
        o.put("running", s != null && s.isRunning());
        o.put("ip", localIp());
        o.put("port", FtpService.serverPort);
        o.put("root", FtpService.serverRoot);
        o.put("error", FtpService.lastError);
        return o;
    }

    static String localIp() {
        try {
            String fallback = "";
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String n = ni.getName();
                        if (n != null && n.startsWith("wlan")) return a.getHostAddress();
                        if (fallback.isEmpty()) fallback = a.getHostAddress();
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return "";
        }
    }
}
