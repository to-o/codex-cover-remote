package local.codex.cover;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String RESULT = "local.codex.cover.RESULT";
    private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = () -> runCommand("status-json");
    private final Runnable timeout = this::onTimeout;

    private void onTimeout() {
        if ("diagnose".equals(pending)) diagnosticError("Termux 响应超时");
        pending = null;
        setButtonsEnabled(true);
        status.setText("操作超时 · 点此刷新");
        scheduleRefresh(3000);
    }

    private TextView service;
    private TextView status;
    private TextView detail;
    private Button on;
    private Button off;
    private Button check;
    private Button retry;
    private Button recheck;
    private android.widget.ScrollView mainPage;
    private android.widget.ScrollView diagnosticPage;
    private LinearLayout diagnosticRows;
    private boolean visible;
    private int generation;
    private String pending;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (pending == null || intent.getIntExtra("generation", -1) != generation) return;
            String command = pending;
            pending = null;
            handler.removeCallbacks(timeout);
            setButtonsEnabled(true);

            Bundle result = intent.getBundleExtra("result");
            if (result == null || result.getInt("err") > 0 || result.getInt("exitCode", -1) != 0) {
                String errorDetail = result == null ? "Termux 没有返回结果" :
                        result.getString("stderr", "").trim();
                if (errorDetail.isEmpty() && result != null) errorDetail = result.getString("errmsg", "").trim();
                if (errorDetail.isEmpty()) errorDetail = "Termux 命令返回错误";
                android.util.Log.w("CodexCover", "action=" + command + ", err=" +
                        (result == null ? "-" : result.getInt("err", -1)) + ", exitCode=" +
                        (result == null ? "-" : result.getInt("exitCode", -1)) + ": " + errorDetail);
                status.setTextColor(0xFFFFA6A6);
                status.setText("执行失败 · 点此刷新");
                if (command.equals("diagnose")) diagnosticError("Termux 检查失败");
                if (result != null && result.containsKey("exitCode")) {
                    detail.setText("命令退出码 " + result.getInt("exitCode"));
                } else if (result != null && result.containsKey("err")) {
                    detail.setText("Termux 错误码 " + result.getInt("err"));
                }
                scheduleRefresh(3000);
                return;
            }
            if (command.equals("status-json")) {
                renderStatus(result.getString("stdout", ""));

            } else {
                if (command.equals("diagnose") && visible) showDiagnostics(result.getString("stdout", ""));
                if (command.equals("retry")) status.setText("已请求重新连接…");
                if (command.equals("on")) status.setText("服务已启动 · 正在连接");
                if (command.equals("off")) status.setText("远程已关闭");
                scheduleRefresh(120);
            }
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        registerReceiver(resultReceiver, new IntentFilter(RESULT), Context.RECEIVER_NOT_EXPORTED);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(18), dp(8), dp(18), dp(8));
        body.setBackgroundColor(0xFF0E1724);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF0E1724);
        scroll.addView(body);
        mainPage = scroll;
        setContentView(mainPage);

        TextView title = new TextView(this);
        title.setText("Codex 远程");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        body.addView(title);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(9));
        android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
        panel.setColor(0xFF192838);
        panel.setCornerRadius(dp(16));
        panel.setStroke(dp(1), 0xFF304657);
        card.setBackground(panel);
        LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(-1, -2);
        cardSize.setMargins(0, dp(8), 0, dp(8));
        body.addView(card, cardSize);

        service = new TextView(this);
        service.setText("本地服务  正在读取");
        service.setTextSize(15);
        service.setTextColor(0xFFDEE9F1);
        service.setPadding(0, 0, 0, 0);
        card.addView(service, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("远程连接  正在读取");
        status.setTextSize(15);
        status.setTextColor(0xFFBFD0DF);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setMinHeight(dp(36));
        status.setOnClickListener(view -> runCommand("status-json"));
        card.addView(status, new LinearLayout.LayoutParams(-1, -2));

        detail = new TextView(this);
        detail.setTextSize(12);
        detail.setTextColor(0xFFA4B8C9);
        detail.setPadding(0, 0, 0, dp(2));
        detail.setOnClickListener(view -> runCommand("status-json"));
        card.addView(detail, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        on = createButton("开启", "on", 0xFF296959);
        off = createButton("关闭", "off", 0xFF2B4054);
        LinearLayout.LayoutParams onSize = new LinearLayout.LayoutParams(0, dp(49), 1);
        onSize.setMarginEnd(dp(8));
        row.addView(on, onSize);
        row.addView(off, new LinearLayout.LayoutParams(0, dp(49), 1));
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(5), 0, 0);
        body.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        check = createButton("检查连接", "diagnose", 0xFF233644);
        check.setOnClickListener(view -> openDiagnostics());
        retry = createButton("重试连接", "retry", 0xFF3F392F);
        LinearLayout.LayoutParams checkSize = new LinearLayout.LayoutParams(0, dp(48), 1);
        actions.addView(check, checkSize);
        actions.addView(retry, new LinearLayout.LayoutParams(0, dp(48), 1));
        retry.setVisibility(android.view.View.GONE);

        Button voice = createButton("实时语音", "status-json", 0xFF315C79);
        voice.setOnClickListener(view -> startActivity(new Intent(this, VoiceActivity.class)));
        LinearLayout.LayoutParams voiceSize = new LinearLayout.LayoutParams(-1, dp(44));
        voiceSize.setMargins(0, dp(8), 0, 0);
        body.addView(voice, voiceSize);

        Button updates = createButton("检查更新", "status-json", 0xFF31445B);
        updates.setOnClickListener(view -> new UpdateManager(this).check());
        LinearLayout.LayoutParams updateSize = new LinearLayout.LayoutParams(-1, dp(44));
        updateSize.setMargins(0, dp(8), 0, 0);
        body.addView(updates, updateSize);

    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        runCommand("status-json");
    }

    @Override protected void onStop() {
        visible = false;
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(resultReceiver);
        super.onDestroy();
    }

    private Button createButton(String label, String command, int color) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, buttonShape(0xFF23303C));
        background.addState(new int[]{}, buttonShape(color));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x30FFFFFF), background, null));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(view -> runCommand(command));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void runCommand(String command) {
        if (!visible) return;
        if (pending != null && (!pending.equals("status-json") || command.equals("status-json"))) return;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(refresh);
        if (checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            status.setText("需要 Termux 命令权限");
            requestPermissions(new String[]{PERMISSION}, 1);
            return;
        }
        pending = command;
        if (!command.equals("status-json")) {
            setButtonsEnabled(false);
            status.setTextColor(0xFFF3CC82);
            switch (command) {
                case "on": status.setText("正在启动…"); break;
                case "off": status.setText("正在关闭…"); break;
                case "retry": status.setText("正在重试…"); break;
                default: status.setText("正在检查连接…"); break;
            }
            detail.setText("");
        }

        Intent returnIntent = new Intent(RESULT).setPackage(getPackageName());
        returnIntent.putExtra("generation", ++generation);
        PendingIntent callback = PendingIntent.getBroadcast(this, generation, returnIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);

        Intent intent = new Intent("com.termux.RUN_COMMAND");
        intent.setClassName("com.termux", "com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/home/bin/codex-remote-switch");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{command});
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback);
        try {
            startService(intent);
            handler.postDelayed(timeout, command.equals("diagnose") ? 12000 : 8000);
        } catch (RuntimeException error) {
            callback.cancel();
            pending = null;
            setButtonsEnabled(true);
            status.setText("无法调用 Termux · 点此重试");
            if (command.equals("diagnose")) diagnosticError("无法调用 Termux");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            runCommand("status-json");
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        on.setEnabled(enabled);
        off.setEnabled(enabled);
        check.setEnabled(enabled);
        retry.setEnabled(enabled);
        if (recheck != null) recheck.setEnabled(enabled);
    }

    private void scheduleRefresh(long delay) {
        if (visible) handler.postDelayed(refresh, delay);
    }

    private void openDiagnostics() {
        if (diagnosticPage == null) {
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPadding(dp(18), dp(12), dp(18), dp(12));
            body.setBackgroundColor(0xFF0E1724);

            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(this);
            title.setText("连接检查");
            title.setTextSize(23);
            title.setTextColor(Color.WHITE);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            Button back = createButton("返回", "status-json", 0xFF233644);
            back.setOnClickListener(view -> closeDiagnostics());
            header.addView(back, new LinearLayout.LayoutParams(dp(68), dp(44)));
            body.addView(header, new LinearLayout.LayoutParams(-1, -2));

            diagnosticRows = new LinearLayout(this);
            diagnosticRows.setOrientation(LinearLayout.VERTICAL);
            diagnosticRows.setPadding(0, dp(8), 0, dp(10));
            body.addView(diagnosticRows, new LinearLayout.LayoutParams(-1, -2));

            recheck = createButton("重新检查", "diagnose", 0xFF296959);
            recheck.setOnClickListener(view -> openDiagnostics());
            body.addView(recheck, new LinearLayout.LayoutParams(-1, dp(46)));

            diagnosticPage = new android.widget.ScrollView(this);
            diagnosticPage.setFillViewport(true);
            diagnosticPage.setBackgroundColor(0xFF0E1724);
            diagnosticPage.addView(body);
            setContentView(diagnosticPage);
        }
        diagnosticRows.removeAllViews();
        addDiagnosticRow("FlClash 代理", "检查中", 0xFFA6BCCE);
        addDiagnosticRow("OpenAI 接口", "等待检查", 0xFFA6BCCE);
        addDiagnosticRow("Codex 远程", "等待结果", 0xFFA6BCCE);
        recheck.setText("正在检查…");
        runCommand("diagnose");
    }

    private void closeDiagnostics() {
        diagnosticPage = null;
        diagnosticRows = null;
        recheck = null;
        setContentView(mainPage);
        runCommand("status-json");
    }

    @Override public void onBackPressed() {
        if (diagnosticPage != null) closeDiagnostics();
        else super.onBackPressed();
    }

    private void diagnosticError(String reason) {
        if (diagnosticPage == null) return;
        diagnosticRows.removeAllViews();
        addDiagnosticRow("连接检查", reason, 0xFFFFAAA5);
        recheck.setText("重新检查");
    }

    private void showDiagnostics(String output) {
        if (diagnosticPage == null) return;
        try {
            JSONObject result = new JSONObject(output);
            JSONObject codex = result.getJSONObject("codex");
            String proxy = result.getString("proxy");
            String endpoint = result.getString("endpoint");
            int good = 0xFF9AE2BE;
            int caution = 0xFFF3CC82;
            int neutral = 0xFFA6BCCE;

            String proxyText = proxy.equals("ready") ? "已启动" :
                    proxy.equals("closed") ? "未启动" : "无法访问";
            String endpointText;
            switch (endpoint) {
                case "reachable": endpointText = "可达 · " + result.optInt("elapsedMs") + " ms"; break;
                case "timeout": endpointText = "请求超时"; break;
                case "region": endpointText = "出口地区受限"; break;
                case "proxy_auth": endpointText = "代理需要认证"; break;
                case "rate_limited": endpointText = "接口限流"; break;
                case "server_error": endpointText = "服务异常"; break;
                case "forbidden": endpointText = "请求被拒绝"; break;
                case "unchecked": endpointText = "等待代理"; break;
                case "tls": endpointText = "TLS 连接失败"; break;
                default: endpointText = "请求失败"; break;
            }
            if (!endpoint.equals("reachable") && result.has("http")) {
                endpointText += " · HTTP " + result.getInt("http");
            }

            String remote = codex.isNull("status") ? "" : codex.getString("status");
            long age = System.currentTimeMillis() / 1000 - codex.optLong("confirmedAt", 0);
            String codexText;
            int codexColor = caution;
            if ("required".equals(result.optString("login"))) codexText = "需要登录";
            else if (codex.optString("service").equals("off")) {
                codexText = "已关闭";
                codexColor = neutral;
            } else if (age > 25 && !remote.isEmpty()) codexText = "状态待确认";
            else if (remote.equals("connected")) {
                codexText = "已连接";
                codexColor = good;
            } else if (remote.equals("errored")) codexText = "连接异常";
            else if (remote.equals("connecting")) codexText = "正在连接";
            else codexText = "等待状态";

            diagnosticRows.removeAllViews();
            addDiagnosticRow("FlClash 代理", proxyText, proxy.equals("ready") ? good : caution);
            addDiagnosticRow("OpenAI 接口", endpointText, endpoint.equals("reachable") ? good :
                    endpoint.equals("unchecked") ? neutral : caution);
            addDiagnosticRow("Codex 远程", codexText, codexColor);
            recheck.setText("重新检查");
        } catch (JSONException error) {
            diagnosticError("结果读取失败");
        }
    }

    private void addDiagnosticRow(String label, String value, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setMinimumHeight(dp(42));
        row.setBackground(buttonShape(0xFF192838));

        TextView heading = new TextView(this);
        heading.setText(label);
        heading.setTextSize(14);
        heading.setTextColor(0xFFD7E3EE);
        row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        TextView line = new TextView(this);
        line.setText(value);
        line.setTextSize(14);
        line.setTextColor(color);
        line.setGravity(Gravity.END);
        line.setMaxWidth(dp(178));
        row.addView(line, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.setMargins(0, 0, 0, dp(6));
        diagnosticRows.addView(row, size);
    }

    private android.graphics.drawable.GradientDrawable buttonShape(int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(13));
        return shape;
    }


    private String clock(long timestamp) {
        if (timestamp == 0) return "—";
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(timestamp * 1000));
    }


    private void renderStatus(String output) {
        try {
            JSONObject snapshot = new JSONObject(output);
            String serviceCode = snapshot.getString("service");
            String remoteCode = snapshot.isNull("status") ? "" : snapshot.getString("status");
            String serviceLabel;
            switch (serviceCode) {
                case "running": serviceLabel = "运行中"; break;
                case "starting": serviceLabel = "启动中"; break;
                case "restarting": serviceLabel = "等待重启"; break;
                case "off": serviceLabel = "已关闭"; break;
                case "errored": serviceLabel = "启动异常"; break;
                default: serviceLabel = "未运行"; break;
            }
            service.setText("本地服务  " + serviceLabel);

            String remoteLabel;
            int color;
            switch (remoteCode) {
                case "connected": remoteLabel = "已连接"; color = 0xFF92DFB5; break;
                case "connecting": remoteLabel = "正在连接"; color = 0xFFF3CC82; break;
                case "errored": remoteLabel = "连接异常"; color = 0xFFFFA6A6; break;
                case "disabled": remoteLabel = "已关闭"; color = 0xFFBFD0DF; break;
                default: remoteLabel = "等待状态"; color = 0xFFBFD0DF; break;
            }
            long confirmedAt = snapshot.optLong("confirmedAt", 0);
            long changedAt = snapshot.optLong("changedAt", 0);
            long now = System.currentTimeMillis() / 1000;
            boolean running = serviceCode.equals("running");
            boolean stale = running && confirmedAt > 0 && now - confirmedAt > 25;
            boolean retrying = snapshot.optBoolean("retrying");
            if (stale) {
                remoteLabel = "状态待确认";
                color = 0xFFF3CC82;
            }
            if (retrying) {
                remoteLabel = "正在重试";
                color = 0xFFF3CC82;
            }
            boolean slow = remoteCode.equals("connecting") && changedAt > 0 && now - changedAt > 15;
            boolean canRetry = running && (stale || slow || remoteCode.equals("errored")
                    || remoteCode.equals("disabled") || snapshot.has("retryErrorCode"));
            retry.setVisibility(canRetry || retrying ? android.view.View.VISIBLE : android.view.View.GONE);
            LinearLayout.LayoutParams checkSize = (LinearLayout.LayoutParams) check.getLayoutParams();
            int spacing = canRetry || retrying ? dp(8) : 0;
            if (checkSize.getMarginEnd() != spacing) {
                checkSize.setMarginEnd(spacing);
                check.setLayoutParams(checkSize);
            }
            retry.setEnabled(!retrying);
            status.setText("远程连接  " + remoteLabel + (remoteCode.isEmpty() ? "" : "   " + remoteCode));
            status.setTextColor(color);

            String details = "状态变更 " + clock(changedAt) + "   上次确认 " + clock(confirmedAt);
            if (!snapshot.isNull("retryErrorCode")) {
                String errorCode = snapshot.optString("retryErrorCode");
                details += errorCode.equals("timeout") ? "  ·  重试超时" : "  ·  重试码 " + errorCode;
            }            if (!snapshot.isNull("rpcErrorCode")) {
                details += "  ·  RPC " + snapshot.getInt("rpcErrorCode");
            }
            if (!snapshot.isNull("appServerExitCode")) {
                details += "  ·  退出码 " + snapshot.getInt("appServerExitCode");
            }
            detail.setText(details);
            scheduleRefresh(remoteCode.equals("connected") || remoteCode.equals("disabled") ? 3000 : 1200);
        } catch (JSONException error) {
            status.setText("无法读取状态");
            detail.setText("点状态刷新");
            scheduleRefresh(3000);
        }
    }

}
