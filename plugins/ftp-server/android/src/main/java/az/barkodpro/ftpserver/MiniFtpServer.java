package az.barkodpro.ftpserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * Kiçik, asılılıqsız FTP server (passiv və aktiv rejim). Android-ə aid heç nə import etmir.
 * Yalnız bir kök qovluğa icazə verir, ".." ilə çıxışı bloklayır.
 */
public class MiniFtpServer {
    private final File root;
    private final int port;
    private final String user;
    private final String pass;
    private volatile boolean running = false;
    private ServerSocket server;
    private ExecutorService pool;
    private final Set<Socket> open = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());

    public MiniFtpServer(File root, int port, String user, String pass) {
        this.root = root;
        this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        if (!root.exists()) root.mkdirs();
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        pool = Executors.newCachedThreadPool();
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() { acceptLoop(); }
        }, "ftp-accept");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                open.add(s);
                pool.execute(new Runnable() {
                    public void run() {
                        try { new Session(s).run(); }
                        catch (IOException ignored) { }
                        finally {
                            open.remove(s);
                            try { s.close(); } catch (IOException ignored) { }
                        }
                    }
                });
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        for (Socket s : open) { try { s.close(); } catch (IOException ignored) { } }
        open.clear();
        if (pool != null) pool.shutdownNow();
    }

    public boolean isRunning() { return running; }

    // ───────────────────────── sessiya ─────────────────────────
    private class Session {
        final Socket ctl;
        final BufferedReader in;
        final OutputStream out;
        String cwd = "/";
        boolean authed = false;
        String uname = null;
        String rnfr = null;
        ServerSocket pasv = null;
        InetSocketAddress active = null;
        long rest = 0;

        Session(Socket s) throws IOException {
            ctl = s;
            ctl.setSoTimeout(300000);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
        }

        void send(String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void run() throws IOException {
            try {
                send("220 BarkodPRO Terminal FTP hazirdir");
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.length() == 0) continue;
                    String cmd, arg;
                    int sp = line.indexOf(' ');
                    if (sp < 0) { cmd = line; arg = ""; }
                    else { cmd = line.substring(0, sp); arg = line.substring(sp + 1).trim(); }
                    cmd = cmd.toUpperCase(Locale.ROOT);
                    if (!handle(cmd, arg)) break;
                }
            } catch (SocketTimeoutException e) {
                // sessiya bitdi
            } finally {
                closePasv();
            }
        }

        void closePasv() {
            if (pasv != null) {
                try { pasv.close(); } catch (IOException ignored) { }
                pasv = null;
            }
        }

        String norm(String arg) {
            String v = arg.startsWith("/") ? arg : (cwd.equals("/") ? "/" + arg : cwd + "/" + arg);
            LinkedList<String> parts = new LinkedList<String>();
            for (String s : v.split("/")) {
                if (s.isEmpty() || s.equals(".")) continue;
                if (s.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); continue; }
                parts.add(s);
            }
            StringBuilder sb = new StringBuilder();
            for (String p : parts) sb.append('/').append(p);
            return sb.length() == 0 ? "/" : sb.toString();
        }

        File file(String vpath) {
            return new File(root, vpath.equals("/") ? "" : vpath.substring(1));
        }

        Socket openData() throws IOException {
            try {
                if (pasv != null) {
                    Socket d = pasv.accept();
                    closePasv();
                    d.setSoTimeout(120000);
                    return d;
                }
                if (active != null) {
                    Socket d = new Socket();
                    d.connect(active, 15000);
                    d.setSoTimeout(120000);
                    active = null;
                    return d;
                }
            } catch (IOException e) {
                closePasv();
                send("425 Melumat baglantisi qurulmadi");
                return null;
            }
            send("425 Evvelce PASV ve ya PORT gonderin");
            return null;
        }

        String lsLine(File f) {
            long lm = f.lastModified();
            boolean recent = Math.abs(System.currentTimeMillis() - lm) < 180L * 24 * 3600 * 1000;
            SimpleDateFormat fm = new SimpleDateFormat(recent ? "MMM dd HH:mm" : "MMM dd  yyyy", Locale.US);
            return (f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--") + " 1 ftp ftp "
                    + String.format(Locale.US, "%12d", f.isDirectory() ? 4096L : f.length())
                    + " " + fm.format(new Date(lm)) + " " + f.getName();
        }

        boolean handle(String cmd, String arg) throws IOException {
            if (cmd.equals("USER")) {
                uname = arg; authed = false;
                if (pass.isEmpty()) { authed = true; send("230 Daxil oldunuz"); }
                else send("331 Parol lazimdir");
                return true;
            }
            if (cmd.equals("PASS")) {
                if (pass.isEmpty() || (uname != null && uname.equals(user) && arg.equals(pass))) {
                    authed = true; send("230 Daxil oldunuz");
                } else { authed = false; send("530 Giris ugursuz"); }
                return true;
            }
            if (cmd.equals("QUIT")) { send("221 Sag olun"); return false; }
            if (cmd.equals("SYST")) { send("215 UNIX Type: L8"); return true; }
            if (cmd.equals("NOOP")) { send("200 OK"); return true; }
            if (cmd.equals("OPTS")) { send("200 OK"); return true; }
            if (cmd.equals("FEAT")) {
                send("211-Features:"); send(" UTF8"); send(" SIZE"); send(" MDTM"); send(" REST STREAM"); send(" EPSV"); send("211 End");
                return true;
            }
            if (!authed) { send("530 Evvelce daxil olun"); return true; }

            if (cmd.equals("PWD") || cmd.equals("XPWD")) {
                send("257 \"" + cwd.replace("\"", "\"\"") + "\" cari qovluqdur");
            } else if (cmd.equals("CWD") || cmd.equals("XCWD")) {
                String v = norm(arg);
                if (file(v).isDirectory()) { cwd = v; send("250 OK"); } else send("550 Qovluq tapilmadi");
            } else if (cmd.equals("CDUP") || cmd.equals("XCUP")) {
                cwd = norm(".."); send("250 OK");
            } else if (cmd.equals("TYPE") || cmd.equals("MODE") || cmd.equals("STRU")) {
                send("200 OK");
            } else if (cmd.equals("PASV")) {
                closePasv(); active = null;
                pasv = new ServerSocket(0);
                pasv.setSoTimeout(30000);
                String ip = ctl.getLocalAddress().getHostAddress();
                if (ip.contains(":")) ip = "127.0.0.1";
                int p = pasv.getLocalPort();
                send("227 Entering Passive Mode (" + ip.replace('.', ',') + "," + (p >> 8) + "," + (p & 255) + ")");
            } else if (cmd.equals("EPSV")) {
                closePasv(); active = null;
                pasv = new ServerSocket(0);
                pasv.setSoTimeout(30000);
                send("229 Entering Extended Passive Mode (|||" + pasv.getLocalPort() + "|)");
            } else if (cmd.equals("PORT")) {
                try {
                    String[] n = arg.split(",");
                    String host = n[0] + "." + n[1] + "." + n[2] + "." + n[3];
                    int p = Integer.parseInt(n[4].trim()) * 256 + Integer.parseInt(n[5].trim());
                    closePasv();
                    active = new InetSocketAddress(host, p);
                    send("200 PORT OK");
                } catch (Exception e) { send("501 PORT xetasi"); }
            } else if (cmd.equals("LIST") || cmd.equals("NLST")) {
                String a = arg;
                while (a.startsWith("-")) { int i = a.indexOf(' '); a = i < 0 ? "" : a.substring(i + 1).trim(); }
                File f = a.isEmpty() ? file(cwd) : file(norm(a));
                if (!f.exists()) { send("550 Tapilmadi"); return true; }
                Socket d = openData();
                if (d == null) return true;
                try {
                    send("150 Siyahi gonderilir");
                    OutputStream o = d.getOutputStream();
                    List<File> items = new ArrayList<File>();
                    if (f.isDirectory()) {
                        File[] ls = f.listFiles();
                        if (ls != null) { Arrays.sort(ls); items.addAll(Arrays.asList(ls)); }
                    } else items.add(f);
                    for (File x : items) {
                        String l = cmd.equals("NLST") ? x.getName() : lsLine(x);
                        o.write((l + "\r\n").getBytes(StandardCharsets.UTF_8));
                    }
                    o.flush();
                } finally { try { d.close(); } catch (IOException ignored) { } }
                send("226 Hazirdir");
            } else if (cmd.equals("RETR")) {
                File f = file(norm(arg));
                if (!f.isFile()) { send("550 Fayl tapilmadi"); return true; }
                Socket d = openData();
                if (d == null) return true;
                boolean ok = true;
                FileInputStream fi = null;
                try {
                    send("150 Fayl gonderilir");
                    fi = new FileInputStream(f);
                    if (rest > 0) fi.skip(rest);
                    OutputStream o = d.getOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = fi.read(buf)) > 0) o.write(buf, 0, n);
                    o.flush();
                } catch (IOException e) { ok = false; }
                finally {
                    rest = 0;
                    try { if (fi != null) fi.close(); } catch (IOException ignored) { }
                    try { d.close(); } catch (IOException ignored) { }
                }
                send(ok ? "226 Hazirdir" : "426 Gonderme kesildi");
            } else if (cmd.equals("STOR") || cmd.equals("APPE")) {
                File f = file(norm(arg));
                File parent = f.getParentFile();
                if (parent == null || !parent.isDirectory()) { send("550 Qovluq yoxdur"); return true; }
                Socket d = openData();
                if (d == null) return true;
                boolean ok = true;
                FileOutputStream fo = null;
                try {
                    send("150 Fayl qebul edilir");
                    fo = new FileOutputStream(f, cmd.equals("APPE"));
                    InputStream is = d.getInputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
                    fo.flush();
                } catch (IOException e) { ok = false; }
                finally {
                    rest = 0;
                    try { if (fo != null) fo.close(); } catch (IOException ignored) { }
                    try { d.close(); } catch (IOException ignored) { }
                }
                send(ok ? "226 Hazirdir" : "426 Qebul kesildi");
            } else if (cmd.equals("DELE")) {
                File f = file(norm(arg));
                if (f.isFile() && f.delete()) send("250 Silindi"); else send("550 Silinmedi");
            } else if (cmd.equals("MKD") || cmd.equals("XMKD")) {
                File f = file(norm(arg));
                if (f.mkdirs() || f.isDirectory()) send("257 \"" + norm(arg) + "\" yaradildi"); else send("550 Yaradilmadi");
            } else if (cmd.equals("RMD") || cmd.equals("XRMD")) {
                File f = file(norm(arg));
                if (f.isDirectory() && f.delete()) send("250 Silindi"); else send("550 Silinmedi");
            } else if (cmd.equals("RNFR")) {
                File f = file(norm(arg));
                if (f.exists()) { rnfr = norm(arg); send("350 Hazirdir, RNTO gonderin"); } else send("550 Tapilmadi");
            } else if (cmd.equals("RNTO")) {
                if (rnfr == null) { send("503 Evvelce RNFR"); }
                else {
                    File a = file(rnfr), b = file(norm(arg));
                    rnfr = null;
                    if (a.renameTo(b)) send("250 Adi deyisdirildi"); else send("550 Deyisdirilmedi");
                }
            } else if (cmd.equals("SIZE")) {
                File f = file(norm(arg));
                if (f.isFile()) send("213 " + f.length()); else send("550 Fayl tapilmadi");
            } else if (cmd.equals("MDTM")) {
                File f = file(norm(arg));
                if (f.exists()) {
                    SimpleDateFormat fm = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                    fm.setTimeZone(TimeZone.getTimeZone("UTC"));
                    send("213 " + fm.format(new Date(f.lastModified())));
                } else send("550 Tapilmadi");
            } else if (cmd.equals("REST")) {
                try { rest = Long.parseLong(arg.trim()); send("350 REST qebul edildi"); }
                catch (Exception e) { send("501 REST xetasi"); }
            } else if (cmd.equals("ABOR")) {
                send("226 OK");
            } else {
                send("502 Komanda desteklenmir");
            }
            return true;
        }
    }
}
