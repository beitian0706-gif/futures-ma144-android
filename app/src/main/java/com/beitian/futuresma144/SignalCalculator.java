package com.beitian.futuresma144;

import java.time.*;
import java.util.*;

/** Pure Java MA crossing and strategy-status calculator. */
public final class SignalCalculator {
    public record Bar(LocalDateTime time, double open, double high, double low, double close, double volume) {}
    public record Contract(String code, String name, String root) {}
    public record Signal(
        String code, String name, LocalDateTime time, String direction,
        double close, double ma, double atr, double distancePoints,
        double distanceAtr, String distanceStatus, String signalStatus
    ) {}
    public record ScanResult(List<Signal> signals, LocalDateTime newestCompleted) {}

    private static final int ATR_PERIOD = 14;
    private static final int HOURLY_SLOPE_LOOKBACK = 12;
    private static final int DAILY_MA = 60;
    private static final int DAILY_SLOPE_LOOKBACK = 5;
    private static final double MIN_DISTANCE_ATR = 0.1;
    private static final double IDEAL_DISTANCE_ATR = 0.3;
    private static final double MAX_DISTANCE_ATR = 0.5;

    private SignalCalculator() {}

    public static LocalDateTime beijingNow() {
        return LocalDateTime.now(ZoneOffset.ofHours(8));
    }

    public static List<Bar> completedBars(List<Bar> bars, LocalDateTime now, int graceSeconds) {
        LocalDateTime cutoff = now.minusSeconds(Math.max(0, graceSeconds));
        List<Bar> out = new ArrayList<>();
        for (Bar bar : bars) if (!bar.time().isAfter(cutoff)) out.add(bar);
        out.sort(Comparator.comparing(Bar::time));
        return out;
    }

    public static ScanResult scanNew(
        List<Bar> source, Contract contract, int maPeriod,
        LocalDateTime lastChecked, LocalDateTime now
    ) {
        List<Bar> bars = completedBars(source, now, 15);
        if (bars.isEmpty()) return new ScanResult(List.of(), lastChecked);
        LocalDateTime newest = bars.get(bars.size() - 1).time();
        List<Signal> all = crosses(bars, contract, maPeriod);
        List<Signal> found = new ArrayList<>();
        if (lastChecked == null) {
            for (Signal signal : all) if (signal.time().equals(newest)) found.add(signal);
        } else {
            for (Signal signal : all) if (signal.time().isAfter(lastChecked)) found.add(signal);
        }
        return new ScanResult(found, newest);
    }

    public static List<Signal> currentTradingDay(
        List<Bar> source, Contract contract, int maPeriod, LocalDateTime now
    ) {
        List<Bar> bars = completedBars(source, now, 15);
        LocalDate day = tradingDay(now);
        List<Signal> out = new ArrayList<>();
        for (Signal signal : crosses(bars, contract, maPeriod)) {
            if (tradingDay(signal.time()).equals(day)) out.add(signal);
        }
        return out;
    }

    public static List<Signal> replay(
        List<Bar> source, Contract contract, int maPeriod, int tradingDays, LocalDateTime now
    ) {
        List<Bar> bars = completedBars(source, now, 15);
        List<Signal> all = crosses(bars, contract, maPeriod);
        LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
        for (Bar bar : bars) dates.add(tradingDay(bar.time()));
        List<LocalDate> ordered = new ArrayList<>(dates);
        Set<LocalDate> allowed = new HashSet<>(ordered.subList(Math.max(0, ordered.size() - tradingDays), ordered.size()));
        List<Signal> out = new ArrayList<>();
        for (Signal signal : all) if (allowed.contains(tradingDay(signal.time()))) out.add(signal);
        return out;
    }

    static List<Signal> crosses(List<Bar> bars, Contract contract, int maPeriod) {
        int size = bars.size();
        List<Signal> out = new ArrayList<>();
        if (maPeriod < 2 || size < maPeriod + 1) return out;

        double[] ma = filled(size);
        double[] atr = filled(size);
        double[] maSlope = filled(size);
        double sum = 0;
        for (int i = 0; i < size; i++) {
            sum += bars.get(i).close();
            if (i >= maPeriod) sum -= bars.get(i - maPeriod).close();
            if (i >= maPeriod - 1) ma[i] = sum / maPeriod;
        }
        for (int i = HOURLY_SLOPE_LOOKBACK; i < size; i++) {
            if (finite(ma[i]) && finite(ma[i - HOURLY_SLOPE_LOOKBACK]))
                maSlope[i] = ma[i] - ma[i - HOURLY_SLOPE_LOOKBACK];
        }
        double trSum = 0;
        double[] tr = new double[size];
        for (int i = 0; i < size; i++) {
            Bar bar = bars.get(i);
            double value = bar.high() - bar.low();
            if (i > 0) {
                double prev = bars.get(i - 1).close();
                value = Math.max(value, Math.max(Math.abs(bar.high() - prev), Math.abs(bar.low() - prev)));
            }
            tr[i] = value; trSum += value;
            if (i >= ATR_PERIOD) trSum -= tr[i - ATR_PERIOD];
            if (i >= ATR_PERIOD - 1) atr[i] = trSum / ATR_PERIOD;
        }

        DailyValues daily = dailyValues(bars);
        for (int i = maPeriod; i < size; i++) {
            double previous = bars.get(i - 1).close() - ma[i - 1];
            double current = bars.get(i).close() - ma[i];
            String direction = previous <= 0 && current > 0 ? "上穿" : previous >= 0 && current < 0 ? "下穿" : null;
            if (direction == null) continue;

            double points = current;
            double distanceAtr = finite(atr[i]) && atr[i] > 0 ? Math.abs(points) / atr[i] : Double.NaN;
            String distanceStatus = !finite(distanceAtr) ? "指标数据不足"
                : distanceAtr < MIN_DISTANCE_ATR ? "幅度太小，未确认"
                : distanceAtr <= IDEAL_DISTANCE_ATR ? "理想"
                : distanceAtr <= MAX_DISTANCE_ATR ? "可接受" : "偏离过大";

            LocalDate day = tradingDay(bars.get(i).time());
            DailyPoint d = daily.byDay.get(day);
            String status;
            if (!finite(maSlope[i]) || !finite(distanceAtr) || d == null || !d.ready()) {
                status = "指标数据不足";
            } else {
                boolean up = direction.equals("上穿");
                boolean dailyPosition = up ? d.previousClose > d.ma : d.previousClose < d.ma;
                boolean hourlyDirection = up ? maSlope[i] > 0 : maSlope[i] < 0;
                boolean dailySlopeAligned = up ? d.slope > 0 : d.slope < 0;
                if (!dailyPosition) status = "日线MA60位置不符";
                else if (!hourlyDirection) status = "MA144方向不符";
                else if (distanceAtr < MIN_DISTANCE_ATR) status = "穿越幅度太小，等待确认";
                else if (distanceAtr > MAX_DISTANCE_ATR) status = "偏离过大，不入场";
                else status = "符合收盘入场条件（" + (dailySlopeAligned ? "正常风险" : "半风险") + "）";
            }
            out.add(new Signal(
                contract.code(), contract.name(), bars.get(i).time(), direction,
                bars.get(i).close(), ma[i], atr[i], points, distanceAtr,
                distanceStatus, status
            ));
        }
        return out;
    }

    private record DailyPoint(double previousClose, double ma, double slope) {
        boolean ready() { return finite(previousClose) && finite(ma) && finite(slope); }
    }
    private record DailyValues(Map<LocalDate, DailyPoint> byDay) {}

    private static DailyValues dailyValues(List<Bar> bars) {
        LinkedHashMap<LocalDate, Double> closes = new LinkedHashMap<>();
        for (Bar bar : bars) closes.put(tradingDay(bar.time()), bar.close());
        List<LocalDate> dates = new ArrayList<>(closes.keySet());
        int size = dates.size();
        double[] values = new double[size], ma = filled(size), slope = filled(size);
        for (int i = 0; i < size; i++) values[i] = closes.get(dates.get(i));
        double sum = 0;
        for (int i = 0; i < size; i++) {
            sum += values[i];
            if (i >= DAILY_MA) sum -= values[i - DAILY_MA];
            if (i >= DAILY_MA - 1) ma[i] = sum / DAILY_MA;
            if (i >= DAILY_SLOPE_LOOKBACK && finite(ma[i]) && finite(ma[i - DAILY_SLOPE_LOOKBACK]))
                slope[i] = ma[i] - ma[i - DAILY_SLOPE_LOOKBACK];
        }
        Map<LocalDate, DailyPoint> result = new HashMap<>();
        for (int i = 0; i < size; i++) {
            if (i == 0) result.put(dates.get(i), new DailyPoint(Double.NaN, Double.NaN, Double.NaN));
            else result.put(dates.get(i), new DailyPoint(values[i - 1], ma[i - 1], slope[i - 1]));
        }
        return new DailyValues(result);
    }

    public static LocalDate tradingDay(LocalDateTime time) {
        LocalDate day = time.toLocalDate();
        if (time.getHour() >= 20) day = day.plusDays(1);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY)
            day = day.plusDays(1);
        return day;
    }

    private static double[] filled(int size) {
        double[] values = new double[size];
        Arrays.fill(values, Double.NaN);
        return values;
    }
    private static boolean finite(double value) { return Double.isFinite(value); }
}
