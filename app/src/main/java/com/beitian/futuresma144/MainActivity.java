package com.beitian.futuresma144;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout contractsBox;
    private TextView status;
    private EditText period, interval;
    private Button monitor;
    private List<SignalCalculator.Contract> contracts = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        loadPrefs();
        buildUi();
        requestNotificationPermission();
        refresh();
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(30, 41, 59));
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        return view;
    }
    private Button button(String value, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(value); button.setOnClickListener(listener); return button;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(14), dp(18), dp(14), dp(30));
        scroll.addView(root); setContentView(scroll);

        TextView title = text("期货 MA144 60分钟收线提醒 v1.6", 24);
        title.setTypeface(null, 1); root.addView(title);
        status = text("正在获取实际主力合约…", 14); status.setTextColor(Color.rgb(37, 99, 235)); root.addView(status);

        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); root.addView(row);
        row.addView(text("均线", 14));
        period = new EditText(this); period.setInputType(2); period.setText(getPreferences(0).getString("period", "144"));
        row.addView(period, new LinearLayout.LayoutParams(dp(70), dp(55)));
        row.addView(text("刷新(秒)", 14));
        interval = new EditText(this); interval.setInputType(2); interval.setText(getPreferences(0).getString("interval", "60"));
        row.addView(interval, new LinearLayout.LayoutParams(dp(75), dp(55)));

        TextView rule = text("固定规则：仅在60分钟K线收盘后15秒确认；任何上穿/下穿都会通知，并同时标注是否满足日线MA60、MA144方向和0.1—0.5ATR幅度条件。", 13);
        rule.setTextColor(Color.rgb(71, 85, 105)); root.addView(rule);

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.VERTICAL); root.addView(actions);
        LinearLayout first = new LinearLayout(this); actions.addView(first);
        monitor = button("开始后台监控", view -> toggleMonitor());
        first.addView(monitor, new LinearLayout.LayoutParams(0, dp(52), 1));
        first.addView(button("立即扫描", view -> openResults(false)), new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout second = new LinearLayout(this); actions.addView(second);
        second.addView(button("刷新主力", view -> refresh()), new LinearLayout.LayoutParams(0, dp(52), 1));
        second.addView(button("历史回放30日", view -> openResults(true)), new LinearLayout.LayoutParams(0, dp(52), 1));

        root.addView(text("实际主力合约（非连续）", 19));
        LinearLayout choose = new LinearLayout(this); root.addView(choose);
        choose.addView(button("全选", view -> selectAll(true)));
        choose.addView(button("清空", view -> selectAll(false)));
        contractsBox = new LinearLayout(this); contractsBox.setOrientation(LinearLayout.VERTICAL); root.addView(contractsBox);

        TextView warning = text("只提供行情提醒，不连接账户、不自动下单。公开行情可能延迟；系统通知权限必须开启。监控启动后可熄屏或切换到其他应用，但不要在手机管家中强制结束本应用。", 12);
        warning.setTextColor(Color.GRAY); root.addView(warning);
        updateMonitorUi();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
    }

    private void refresh() {
        status.setText("正在按持仓量刷新实际主力合约…");
        io.submit(() -> {
            try {
                List<SignalCalculator.Contract> value = MarketClient.mainContracts();
                runOnUiThread(() -> showContracts(value));
            } catch (Exception exception) {
                error("刷新失败：" + exception.getMessage());
            }
        });
    }

    private void showContracts(List<SignalCalculator.Contract> value) {
        contracts = value; contractsBox.removeAllViews();
        for (SignalCalculator.Contract contract : value) {
            CheckBox box = new CheckBox(this);
            box.setText(contract.code() + "  " + contract.name());
            box.setChecked(selected.isEmpty() || selected.contains(contract.root()));
            box.setTag(contract);
            box.setOnCheckedChangeListener((view, checked) -> {
                if (checked) selected.add(contract.root()); else selected.remove(contract.root());
                savePrefs();
            });
            contractsBox.addView(box);
        }
        status.setText("已加载 " + value.size() + " 个实际交割月主力合约");
    }

    private List<SignalCalculator.Contract> chosen() {
        List<SignalCalculator.Contract> value = new ArrayList<>();
        for (int i = 0; i < contractsBox.getChildCount(); i++) {
            CheckBox box = (CheckBox) contractsBox.getChildAt(i);
            if (box.isChecked()) value.add((SignalCalculator.Contract) box.getTag());
        }
        return value;
    }

    private void selectAll(boolean checked) {
        for (int i = 0; i < contractsBox.getChildCount(); i++)
            ((CheckBox) contractsBox.getChildAt(i)).setChecked(checked);
    }

    private int number(EditText field, int fallback) {
        try { return Integer.parseInt(field.getText().toString()); } catch (Exception ignored) { return fallback; }
    }

    private void openResults(boolean replay) {
        List<SignalCalculator.Contract> value = chosen();
        if (value.isEmpty()) { toast("请先选择合约"); return; }
        savePrefs();
        ArrayList<String> codes = new ArrayList<>(), names = new ArrayList<>();
        for (SignalCalculator.Contract contract : value) { codes.add(contract.code()); names.add(contract.name()); }
        Intent intent = new Intent(this, ResultActivity.class);
        intent.putStringArrayListExtra("codes", codes); intent.putStringArrayListExtra("names", names);
        intent.putExtra("replay", replay); intent.putExtra("period", Math.max(2, number(period, 144)));
        startActivity(intent);
    }

    private void toggleMonitor() {
        if (MonitorService.running) {
            stopService(new Intent(this, MonitorService.class));
            status.setText("后台监控已停止");
            monitor.setText("开始后台监控");
            return;
        }
        List<SignalCalculator.Contract> value = chosen();
        if (value.isEmpty()) { toast("请先选择合约"); return; }
        int seconds = number(interval, 60);
        if (seconds < 30 || seconds > 3600) { toast("刷新秒数应在30—3600之间，建议60秒"); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission(); toast("请先允许通知权限，然后再次点击开始监控"); return;
        }
        ArrayList<String> codes = new ArrayList<>(), names = new ArrayList<>();
        for (SignalCalculator.Contract contract : value) { codes.add(contract.code()); names.add(contract.name()); }
        Intent service = new Intent(this, MonitorService.class);
        service.putStringArrayListExtra(MonitorService.EXTRA_CODES, codes);
        service.putStringArrayListExtra(MonitorService.EXTRA_NAMES, names);
        service.putExtra(MonitorService.EXTRA_PERIOD, Math.max(2, number(period, 144)));
        service.putExtra(MonitorService.EXTRA_INTERVAL_SECONDS, seconds);
        startForegroundService(service);
        savePrefs();
        status.setText("后台监控已启动：" + value.size() + "个合约，每" + seconds + "秒刷新");
        monitor.setText("停止后台监控");
    }

    private void updateMonitorUi() {
        if (monitor == null) return;
        monitor.setText(MonitorService.running ? "停止后台监控" : "开始后台监控");
    }

    private void loadPrefs() {
        String roots = getPreferences(0).getString("roots", "");
        if (!roots.isEmpty()) selected.addAll(Arrays.asList(roots.split(",")));
    }
    private void savePrefs() {
        getPreferences(0).edit()
            .putString("period", period.getText().toString())
            .putString("interval", interval.getText().toString())
            .putString("roots", String.join(",", selected)).apply();
    }
    private void error(String value) { runOnUiThread(() -> { status.setText(value); toast(value); }); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    @Override protected void onResume() { super.onResume(); updateMonitorUi(); }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}
