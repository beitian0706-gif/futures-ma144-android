package com.beitian.futuresma144;

import android.app.*;
import android.content.*;
import android.os.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class MonitorService extends Service {
    public static final String ACTION_STOP = "com.beitian.futuresma144.STOP_MONITOR";
    public static final String EXTRA_CODES = "codes";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_PERIOD = "period";
    public static final String EXTRA_INTERVAL_SECONDS = "interval_seconds";
    public static volatile boolean running = false;

    private static final String STATUS_CHANNEL = "monitor_status_v16";
    private static final String SIGNAL_CHANNEL = "signals_v16";
    private static final int FOREGROUND_ID = 144;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private ScheduledExecutorService scheduler;
    private final Map<String, LocalDateTime> lastChecked = new ConcurrentHashMap<>();
    private List<SignalCalculator.Contract> contracts = List.of();
    private int period = 144;
    private int intervalSeconds = 60;

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ArrayList<String> codes = intent == null ? null : intent.getStringArrayListExtra(EXTRA_CODES);
        ArrayList<String> names = intent == null ? null : intent.getStringArrayListExtra(EXTRA_NAMES);
        if (codes == null || codes.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        period = Math.max(2, intent.getIntExtra(EXTRA_PERIOD, 144));
        intervalSeconds = Math.max(30, Math.min(3600, intent.getIntExtra(EXTRA_INTERVAL_SECONDS, 60)));
        List<SignalCalculator.Contract> selected = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            String name = names != null && i < names.size() ? names.get(i) : code;
            selected.add(new SignalCalculator.Contract(code, name, code.replaceAll("\\d+$", "")));
        }
        contracts = selected;
        lastChecked.clear();
        startForeground(FOREGROUND_ID, statusNotification("正在启动，监控 " + contracts.size() + " 个合约"));
        running = true;
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::scan, 0, intervalSeconds, TimeUnit.SECONDS);
        return START_NOT_STICKY;
    }

    private void scan() {
        int success = 0, errors = 0, signals = 0;
        for (SignalCalculator.Contract contract : contracts) {
            try {
                SignalCalculator.ScanResult result = SignalCalculator.scanNew(
                    MarketClient.bars(contract.code()), contract, period,
                    lastChecked.get(contract.code()), SignalCalculator.beijingNow()
                );
                if (result.newestCompleted() != null) lastChecked.put(contract.code(), result.newestCompleted());
                for (SignalCalculator.Signal signal : result.signals()) {
                    if (isFresh(signal)) {
                        notifySignal(signal);
                        signals++;
                    }
                }
                success++;
            } catch (Exception ignored) {
                errors++;
            }
        }
        String time = SignalCalculator.beijingNow().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String summary = time + " 已刷新 " + success + "/" + contracts.size() + " 个合约";
        if (signals > 0) summary += "，发现 " + signals + " 个穿越";
        if (errors > 0) summary += "，失败 " + errors + " 个";
        getSystemService(NotificationManager.class).notify(FOREGROUND_ID, statusNotification(summary));
    }

    private boolean isFresh(SignalCalculator.Signal signal) {
        String key = signal.code() + "|" + signal.time() + "|" + signal.direction();
        String preferenceKey = "last_alert_" + signal.code();
        SharedPreferences preferences = getSharedPreferences("alerts", MODE_PRIVATE);
        if (key.equals(preferences.getString(preferenceKey, ""))) return false;
        preferences.edit().putString(preferenceKey, key).apply();
        return true;
    }

    private void notifySignal(SignalCalculator.Signal signal) {
        String atr = Double.isFinite(signal.distanceAtr())
            ? String.format(Locale.CHINA, "%.3f ATR", signal.distanceAtr()) : "ATR不足";
        String detail = signal.time().format(TIME) + " 收盘 " + format(signal.close())
            + "，MA" + period + " " + format(signal.ma())
            + "\n偏离 " + String.format(Locale.CHINA, "%+.2f", signal.distancePoints()) + " 点（" + atr + "）"
            + "\n" + signal.distanceStatus() + "｜" + signal.signalStatus();
        PendingIntent open = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new Notification.Builder(this, SIGNAL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(signal.code() + " 60分钟收线" + signal.direction() + " MA" + period)
            .setContentText(signal.signalStatus())
            .setStyle(new Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build();
        getSystemService(NotificationManager.class).notify(Math.abs((signal.code() + signal.time()).hashCode()), notification);
    }

    private Notification statusNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stopIntent = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("MA144收线监控运行中")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止监控", stop)
            .setOngoing(true)
            .build();
    }

    private void createChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(STATUS_CHANNEL, "监控运行状态", NotificationManager.IMPORTANCE_LOW);
        NotificationChannel signals = new NotificationChannel(SIGNAL_CHANNEL, "MA144收线穿越提醒", NotificationManager.IMPORTANCE_HIGH);
        signals.enableVibration(true);
        manager.createNotificationChannel(status);
        manager.createNotificationChannel(signals);
    }

    private static String format(double value) { return String.format(Locale.CHINA, "%.2f", value); }

    @Override public void onDestroy() {
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
