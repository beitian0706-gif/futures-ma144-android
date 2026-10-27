import com.beitian.futuresma144.SignalCalculator;
import java.time.LocalDateTime;
import java.util.*;

public class SignalCalculatorTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static List<SignalCalculator.Bar> crossingBars() {
        List<SignalCalculator.Bar> bars = new ArrayList<>();
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 9, 0);
        for (int i = 0; i < 144; i++)
            bars.add(new SignalCalculator.Bar(start.plusHours(i), 100, 101, 99, 100, 1000));
        bars.add(new SignalCalculator.Bar(start.plusHours(144), 101, 102, 100, 101, 1000));
        bars.add(new SignalCalculator.Bar(start.plusHours(145), 99, 100, 98, 99, 1000));
        return bars;
    }

    public static void main(String[] args) {
        List<SignalCalculator.Bar> bars = crossingBars();
        SignalCalculator.Contract contract = new SignalCalculator.Contract("RB2701", "螺纹2701", "RB");
        LocalDateTime firstCross = bars.get(144).time();
        LocalDateTime secondCross = bars.get(145).time();

        var tooEarly = SignalCalculator.scanNew(
            bars.subList(0, 145), contract, 144, null, firstCross.plusSeconds(5)
        );
        check(tooEarly.signals().isEmpty(), "未到15秒收线确认时间不应提醒");
        check(tooEarly.newestCompleted().equals(bars.get(143).time()), "未完成K线不应成为最新已检查K线");

        var first = SignalCalculator.scanNew(
            bars.subList(0, 145), contract, 144, null, firstCross.plusSeconds(20)
        );
        check(first.signals().size() == 1, "启动时应检查最新已完成K线");
        check(first.signals().get(0).direction().equals("上穿"), "第一根信号应为上穿");

        var second = SignalCalculator.scanNew(
            bars, contract, 144, firstCross, secondCross.plusSeconds(20)
        );
        check(second.signals().size() == 1, "新收线后应产生一个新提醒");
        check(second.signals().get(0).direction().equals("下穿"), "第二根信号应为下穿");

        var current = SignalCalculator.currentTradingDay(
            bars, contract, 144, secondCross.plusSeconds(20)
        );
        check(!current.isEmpty(), "立即扫描应返回当前交易日收线穿越");
        System.out.println("SignalCalculatorTest: PASS");
    }
}
