package com.beitian.futuresma144;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class ResultActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout results;
    private TextView status;
    private boolean replay;
    private int period;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Override public void onCreate(Bundle state) { super.onCreate(state); buildUi(); runScan(); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(30, 41, 59)); view.setPadding(dp(10), dp(10), dp(10), dp(10)); return view;
    }
    private Button button(String value, android.view.View.OnClickListener listener) {
        Button button = new Button(this); button.setText(value); button.setOnClickListener(listener); return button;
    }

    private void buildUi() {
        replay = getIntent().getBooleanExtra("replay", false);
        period = getIntent().getIntExtra("period", 144);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(12)); setContentView(root);
        TextView title = text(replay ? "最近30个交易日历史回放" : "当前交易日收线穿越", 24);
        title.setTypeface(null, 1); root.addView(title);
        status = text("正在获取60分钟K线并计算 MA" + period + "…", 14);
        status.setTextColor(Color.rgb(37, 99, 235)); root.addView(status);
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER); root.addView(actions);
        actions.addView(button("返回设置", view -> finish()), new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(button("清除结果", view -> clearResults()), new LinearLayout.LayoutParams(0, dp(52), 1));
        ScrollView scroll = new ScrollView(this); results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL); scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void runScan() {
        ArrayList<String> codes = getIntent().getStringArrayListExtra("codes");
        ArrayList<String> names = getIntent().getStringArrayListExtra("names");
        if (codes == null || codes.isEmpty()) { status.setText("没有选择合约"); return; }
        io.submit(() -> {
            int count = 0, errors = 0; String firstError = "";
            for (int i = 0; i < codes.size(); i++) {
                String code = codes.get(i), name = names != null && i < names.size() ? names.get(i) : code;
                SignalCalculator.Contract contract = new SignalCalculator.Contract(code, name, code.replaceAll("\\d+$", ""));
                try {
                    List<SignalCalculator.Bar> bars = MarketClient.bars(code);
                    List<SignalCalculator.Signal> signals = replay
                        ? SignalCalculator.replay(bars, contract, period, 30, SignalCalculator.beijingNow())
                        : SignalCalculator.currentTradingDay(bars, contract, period, SignalCalculator.beijingNow());
                    count += signals.size();
                    for (SignalCalculator.Signal signal : signals) addSignal(signal, name);
                } catch (Exception exception) {
                    errors++; if (firstError.isEmpty()) firstError = code + ": " + exception.getMessage();
                }
                int done = i + 1, total = codes.size(), found = count;
                runOnUiThread(() -> status.setText("正在处理 " + done + "/" + total + "，已发现 " + found + " 个穿越"));
            }
            int total = count, failed = errors; String detail = firstError;
            runOnUiThread(() -> {
                status.setText("完成：共 " + total + " 个穿越，" + failed + " 个失败");
                if (total == 0) addEmpty();
                if (failed > 0) Toast.makeText(this, "首个错误：" + detail, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void addSignal(SignalCalculator.Signal signal, String name) {
        runOnUiThread(() -> {
            String atr = Double.isFinite(signal.distanceAtr())
                ? String.format(Locale.CHINA, "%.3f ATR", signal.distanceAtr()) : "ATR不足";
            TextView view = text(
                signal.code() + "  " + name + "\n"
                    + signal.time().format(TIME) + "  " + signal.direction() + "\n"
                    + "收盘 " + format(signal.close()) + "    MA" + period + " " + format(signal.ma()) + "\n"
                    + "偏离 " + String.format(Locale.CHINA, "%+.2f", signal.distancePoints()) + " 点（" + atr + "）\n"
                    + "幅度：" + signal.distanceStatus() + "\n策略：" + signal.signalStatus(), 16
            );
            boolean up = signal.direction().equals("上穿");
            view.setTextColor(up ? Color.rgb(185, 28, 28) : Color.rgb(21, 128, 61));
            GradientDrawable background = new GradientDrawable();
            background.setColor(up ? Color.rgb(254, 242, 242) : Color.rgb(240, 253, 244));
            background.setCornerRadius(dp(10));
            background.setStroke(dp(1), up ? Color.rgb(254, 202, 202) : Color.rgb(187, 247, 208));
            view.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, dp(6), 0, dp(6)); results.addView(view, 0, params);
        });
    }

    private void addEmpty() {
        TextView view = text(replay ? "最近30个交易日没有发现收线穿越" : "当前交易日没有发现收线穿越", 16);
        view.setGravity(Gravity.CENTER); view.setTextColor(Color.GRAY); results.addView(view);
    }
    private void clearResults() { results.removeAllViews(); status.setText("结果已清除"); }
    private static String format(double value) { return String.format(Locale.CHINA, "%.2f", value); }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}
