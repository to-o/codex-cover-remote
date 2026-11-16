package local.codex.cover;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-initiated, public GitHub Release updater. Never embeds GitHub credentials. */
public final class UpdateManager {
    private static final String LATEST_MANIFEST =
            "https://github.com/to-o/codex-cover-remote/releases/latest/download/manifest.json";
    private static final String ASSET_PREFIX =
            "https://github.com/to-o/codex-cover-remote/releases/download/";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final long MAX_APK = 50L * 1024 * 1024;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private final Activity activity;

    public UpdateManager(Activity activity) { this.activity = activity; }

    private static final class Release {
        String tag, version, notes, apkUrl, scriptsUrl, sha256;
        int code;
        long size;
    }

    private void ui(Runnable action) {
        UI.post(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) action.run(); });
    }

    private void notifyUser(String title, String text) {
        ui(() -> new AlertDialog.Builder(activity).setTitle(title)
                .setMessage(text).setPositiveButton("确定", null).show());
    }

    private static byte[] getBytes(String url, int limit) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/octet-stream");
        conn.setRequestProperty("User-Agent", "codex-cover-ota/1.0");
        try {
            if (conn.getResponseCode() != 200)
                throw new Exception("GitHub Release 下载失败 HTTP " + conn.getResponseCode()
                        + "（请检查网络或 Release 是否已公开）");
            try (InputStream in = conn.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > limit) throw new Exception("更新元数据过大");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally { conn.disconnect(); }
    }

    private static Release load() throws Exception {
        // Public release assets are not subject to GitHub's unauthenticated
        // REST API 60 requests/hour shared egress-IP limit.
        JSONObject meta = new JSONObject(new String(getBytes(LATEST_MANIFEST, 65536), "UTF-8"));
        Release result = new Release();
        result.tag = meta.getString("tag");
        result.version = meta.getString("versionName");
        result.code = meta.getInt("versionCode");
        result.notes = meta.optString("notes", "");
        if (result.notes.length() > 550) result.notes = result.notes.substring(0, 550) + "…";
        if (meta.getInt("schema") != 1
                || !"to-o/codex-cover-remote".equals(meta.getString("repository"))
                || !result.tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")
                || !result.tag.equals("v" + result.version)
                || meta.getInt("minSdk") > android.os.Build.VERSION.SDK_INT)
            throw new Exception("更新元数据与当前设备不兼容");
        JSONObject apk = meta.getJSONObject("apk");
        if (!"codex-cover.apk".equals(apk.getString("name")))
            throw new Exception("APK 名称错误");
        result.sha256 = apk.getString("sha256");
        result.size = apk.getLong("size");
        if (!result.sha256.matches("[a-f0-9]{64}") || result.size <= 0 || result.size > MAX_APK)
            throw new Exception("APK 校验信息不合法");
        // Construct asset URLs only from strictly validated tag and known filenames.
        result.apkUrl = ASSET_PREFIX + result.tag + "/codex-cover.apk";
        result.scriptsUrl = ASSET_PREFIX + result.tag + "/termux-scripts.zip";
        return result;
    }

    private long installedCode() throws Exception {
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0)
                .getLongVersionCode();
    }

    public void check() {
        AlertDialog progress = new AlertDialog.Builder(activity).setTitle("检查更新")
                .setMessage("正在读取 GitHub 正式发布版本…").setCancelable(false).show();
        new Thread(() -> {
            try {
                Release release = load();
                long current = installedCode();
                ui(() -> {
                    progress.dismiss();
                    String text = "当前版本：" + getInstalledVersion()
                            + "\n最新版本：" + release.version
                            + "\n\n" + (release.notes.isEmpty() ? "已发布新的安装文件。" : release.notes);
                    AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                            .setTitle(release.code > current ? "发现新版本" : "已是最新 APK 版本")
                            .setMessage(text)
                            .setNegativeButton("关闭", null)
                            .setNeutralButton("更新 Termux 脚本", (d, which) -> updateScripts(release));
                    if (release.code > current)
                        dialog.setPositiveButton("下载并安装 APK", (d, which) -> downloadApk(release));
                    dialog.show();
                });
            } catch (Exception error) {
                ui(() -> progress.dismiss());
                notifyUser("检查更新失败", error.getMessage());
            }
        }, "github-ota-check").start();
    }

    private String getInstalledVersion() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) { return "未知"; }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private void checkSigning(File apk, long expectedCode) throws Exception {
        PackageManager pm = activity.getPackageManager();
        PackageInfo incoming = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo current = pm.getPackageInfo(activity.getPackageName(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (incoming == null || !activity.getPackageName().equals(incoming.packageName)
                || incoming.getLongVersionCode() != expectedCode)
            throw new Exception("APK 包名或版本不匹配");
        if (incoming.signingInfo == null || current.signingInfo == null)
            throw new Exception("无法验证 APK 证书");
        Signature[] a = incoming.signingInfo.getApkContentsSigners();
        Signature[] b = current.signingInfo.getApkContentsSigners();
        if (a.length != 1 || b.length != 1 || !Arrays.equals(a[0].toByteArray(), b[0].toByteArray()))
            throw new Exception("APK 签名与已安装版本不一致，拒绝升级");
    }

    private void downloadApk(Release release) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AlertDialog progress = new AlertDialog.Builder(activity).setTitle("下载 APK")
                .setMessage("准备下载…")
                .setNegativeButton("取消", (d, which) -> cancelled.set(true))
                .setCancelable(false).show();
        new Thread(() -> {
            HttpURLConnection conn = null;
            File part = null;
            try {
                File directory = new File(activity.getCacheDir(), "updates");
                if (!directory.exists() && !directory.mkdirs())
                    throw new Exception("无法创建更新目录");
                File file = new File(directory, "codex-cover.apk");
                part = new File(directory, "codex-cover.apk.part");
                conn = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "codex-cover-ota/1.0");
                if (conn.getResponseCode() != 200) throw new Exception("下载失败 HTTP " + conn.getResponseCode());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0, lastShown = 0;
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[32768];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        if (cancelled.get()) throw new Exception("已取消下载");
                        total += len;
                        if (total > release.size || total > MAX_APK) throw new Exception("APK 大小异常");
                        digest.update(buf, 0, len);
                        out.write(buf, 0, len);
                        if (total - lastShown > 262144) {
                            long copied = total;
                            ui(() -> progress.setMessage("正在下载：" + (copied * 100 / release.size) + "%"));
                            lastShown = total;
                        }
                    }
                    out.getFD().sync();
                }
                if (total != release.size || !hex(digest.digest()).equals(release.sha256))
                    throw new Exception("APK SHA-256 校验失败");
                if (file.exists() && !file.delete()) throw new Exception("无法替换旧安装包");
                if (!part.renameTo(file)) throw new Exception("无法准备 APK");
                checkSigning(file, release.code);
                ui(() -> { progress.dismiss(); openInstaller(); });
            } catch (Exception error) {
                if (part != null) part.delete();
                ui(() -> progress.dismiss());
                if (!cancelled.get()) notifyUser("下载或校验失败", error.getMessage());
            } finally { if (conn != null) conn.disconnect(); }
        }, "github-ota-download").start();
    }

    private void openInstaller() {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity).setTitle("允许安装更新")
                    .setMessage("需要先允许“Codex 远程”安装未知应用。授权后重新点击“检查更新”即可升级。")
                    .setPositiveButton("打开设置", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(intent);
                    }).setNegativeButton("取消", null).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setDataAndType(UpdateApkProvider.URI, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, false);
        try { activity.startActivity(intent); }
        catch (Exception e) { notifyUser("安装失败", "无法启动 Android 安装器：" + e.getMessage()); }
    }

    private void updateScripts(Release release) {
        if (activity.checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            notifyUser("需要权限", "请先在主界面授予 Termux RUN_COMMAND 权限。");
            return;
        }
        final String action = "local.codex.cover.UPDATE_SCRIPTS_RESULT";
        final Context context = activity.getApplicationContext();
        final AlertDialog progress = new AlertDialog.Builder(activity).setTitle("升级 Termux 脚本")
                .setMessage("正在从 GitHub 下载、校验并更新脚本…")
                .setCancelable(false).show();
        final int generation = (int) (System.nanoTime() & 0x7fffffff);
        final BroadcastReceiver[] receiver = new BroadcastReceiver[1];
        final Runnable[] timeout = new Runnable[1];
        final boolean[] finished = {false};
        final Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            if (timeout[0] != null) UI.removeCallbacks(timeout[0]);
            try { context.unregisterReceiver(receiver[0]); } catch (Exception ignored) {}
            ui(progress::dismiss);
        };
        receiver[0] = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (intent.getIntExtra("generation", -1) != generation) return;
                finish.run();
                Bundle value = intent.getBundleExtra("result");
                int exit = value == null ? -1 : value.getInt("exitCode", -1);
                String text = value == null ? "Termux 未返回结果" : value.getString("stdout", "");
                if (exit != 0) {
                    String err = value == null ? "" : value.getString("stderr", "");
                    notifyUser("脚本升级失败", (text + "\n" + err).trim()
                            + "\n\n首次使用时需先将 codex-release-update.py 安装到 Termux ~/bin。");
                } else {
                    notifyUser("脚本更新完成", text + "\n请在当前任务结束后重启远程/语音服务。");
                }
            }
        };
        context.registerReceiver(receiver[0], new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        timeout[0] = () -> {
            finish.run();
            notifyUser("脚本更新超时", "请通过 Termux 查看 codex-release-update.py 的执行结果。");
        };
        UI.postDelayed(timeout[0], 120000);
        Intent callbackIntent = new Intent(action).setPackage(activity.getPackageName())
                .putExtra("generation", generation);
        PendingIntent callback = PendingIntent.getBroadcast(context, generation, callbackIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
        Intent command = new Intent("com.termux.RUN_COMMAND");
        command.setClassName("com.termux", "com.termux.app.RunCommandService");
        command.putExtra("com.termux.RUN_COMMAND_PATH",
                "/data/data/com.termux/files/home/bin/codex-release-update.py");
        command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"apply", "--tag", release.tag});
        command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback);
        try { activity.startService(command); }
        catch (Exception e) {
            finish.run();
            notifyUser("无法启动 Termux", e.getMessage());
        }
    }
}
