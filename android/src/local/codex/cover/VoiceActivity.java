package local.codex.cover;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

public final class VoiceActivity extends Activity {
    private static final String RESULT = "local.codex.cover.VOICE_RESULT";
    private static final String TERMUX = "com.termux.permission.RUN_COMMAND";
    private final Handler handler = new Handler();
    private TextView message;
    private WebView web;
    private String origin;
    private boolean loading;
    private boolean foreground;
    private int generation;
    private PendingIntent callback;
    private final Runnable timeout = () -> {
        loading = false;
        if (callback != null) callback.cancel();
        message.setText("启动超时 · 返回后重试");
    };
    private final BroadcastReceiver result = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!loading || intent.getIntExtra("generation", -1) != generation) return;
            loading = false;
            handler.removeCallbacks(timeout);
            Bundle value = intent.getBundleExtra("result");
            if (value == null || value.getInt("err", 0) > 0 || value.getInt("exitCode", -1) != 0) {
                android.util.Log.w("CodexVoice", "launcher err=" + (value == null ? -1 : value.getInt("err", 0))
                        + " exit=" + (value == null ? -1 : value.getInt("exitCode", -1))
                        + " stderr=" + (value == null ? "" : value.getString("stderr", "")));
                message.setText("语音服务启动失败 · 点此重试");
                return;
            }
            try {
                JSONObject info = new JSONObject(value.getString("stdout", ""));
                Uri url = Uri.parse(info.getString("url"));
                if (!"http".equals(url.getScheme()) || !"127.0.0.1".equals(url.getHost())
                        || url.getPort() < 1 || !"/".equals(url.getPath())
                        || url.getUserInfo() != null || url.getQuery() != null) throw new Exception();
                String token = info.getString("token");
                if (!token.matches("[A-Za-z0-9_-]{32,100}")) throw new Exception();
                origin = "http://127.0.0.1:" + url.getPort();
                open(origin + "/#" + token);
            } catch (Exception error) {
                message.setText("无法读取语音服务地址 · 返回后重试");
            }
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        registerReceiver(result, new IntentFilter(RESULT), Context.RECEIVER_NOT_EXPORTED);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(24, 24, 24, 24);
        body.setBackgroundColor(0xFF0E1724);
        message = new TextView(this);
        message.setTextColor(0xFFEDF5FA);
        message.setTextSize(18);
        message.setText("正在准备实时语音…");
        message.setOnClickListener(view -> launch());
        body.addView(message);
        Button back = new Button(this);
        back.setText("返回控制页");
        back.setOnClickListener(view -> finish());
        body.addView(back);
        setContentView(body);
        if (checkSelfPermission(TERMUX) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{TERMUX, Manifest.permission.RECORD_AUDIO}, 10);
        } else launch();
    }

    private void launch() {
        if (loading) return;
        loading = true;
        Intent returnIntent = new Intent(RESULT).setPackage(getPackageName());
        returnIntent.putExtra("generation", ++generation);
        callback = PendingIntent.getBroadcast(this, generation, returnIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
        Intent intent = new Intent("com.termux.RUN_COMMAND");
        intent.setClassName("com.termux", "com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/home/bin/codex-voice-start");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{});
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback);
        try {
            startService(intent);
            handler.postDelayed(timeout, 30000);
        } catch (RuntimeException error) {
            callback.cancel();
            loading = false;
            message.setText("无法调用 Termux · 返回后重试");
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != 10) return;
        if (checkSelfPermission(TERMUX) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launch();
        else message.setText("实时语音需要麦克风和 Termux 命令权限");
    }

    private void open(String url) {
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0E1724);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setSaveFormData(false);
        web.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                return !"http".equals(target.getScheme()) || !"127.0.0.1".equals(target.getHost())
                        || !origin.equals("http://127.0.0.1:" + target.getPort());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                Uri source = request.getOrigin();
                if (foreground && "http".equals(source.getScheme()) && "127.0.0.1".equals(source.getHost())
                        && origin.equals("http://127.0.0.1:" + source.getPort())
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    for (String resource : request.getResources()) {
                        if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) { request.deny(); return; }
                    }
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else request.deny();
            }
        });
        setContentView(web);
        web.loadUrl(url);
        if (!foreground) web.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (web != null) web.onResume();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onPause() {
        foreground = false;
        if (web != null) {
            web.evaluateJavascript("window.stopVoice && window.stopVoice('已离开语音页面')", null);
            web.onPause();
        }
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (callback != null) callback.cancel();
        unregisterReceiver(result);
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }
}
