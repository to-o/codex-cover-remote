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
        body.setPadding(dp(16), dp(12), dp(16), dp(16));
        body.setBackgroundColor(0xFF0A0F16);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("✦  Codex Live");
        title.setTextColor(0xFFF0F5FB);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        Button back = new Button(this);
        back.setAllCaps(false);
        back.setText("返回");
        back.setTextSize(15);
        back.setTextColor(0xFFD5E3ED);
        android.graphics.drawable.GradientDrawable backShape = new android.graphics.drawable.GradientDrawable();
        backShape.setColor(0xFF1B2835);
        backShape.setCornerRadius(dp(13));
        back.setBackground(backShape);
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(78), dp(48)));
        body.addView(header);

        LinearLayout loader = new LinearLayout(this);
        loader.setGravity(android.view.Gravity.CENTER);
        loader.setOrientation(LinearLayout.VERTICAL);
        TextView wave = new TextView(this);
        wave.setText("◉");
        wave.setTextSize(56);
        wave.setTextColor(0xFF72E5B5);
        wave.setGravity(android.view.Gravity.CENTER);
        loader.addView(wave, new LinearLayout.LayoutParams(dp(102), dp(102)));
        message = new TextView(this);
        message.setTextColor(0xFFF0F5FB);
        message.setTextSize(19);
        message.setGravity(android.view.Gravity.CENTER);
        message.setText("正在准备实时语音…");
        message.setOnClickListener(view -> launch());
        loader.addView(message, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = new TextView(this);
        hint.setText("正在连接本机语音服务");
        hint.setTextColor(0xFF91A5B8);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setTextSize(14);
        hint.setPadding(0, dp(8), 0, 0);
        loader.addView(hint);
        body.addView(loader, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(body);
        if (checkSelfPermission(TERMUX) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{TERMUX, Manifest.permission.RECORD_AUDIO}, 10);
        } else launch();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        web.setBackgroundColor(0xFF0A0F16);
        web.getSettings().setJavaScriptEnabled(true);
        // Honor Android's accessibility font scale in the embedded Live UI.
        float fontScale = getResources().getConfiguration().fontScale;
        web.getSettings().setTextZoom(Math.round(100f * Math.max(1f, fontScale)));
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
