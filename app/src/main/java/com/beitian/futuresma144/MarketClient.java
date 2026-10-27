package com.beitian.futuresma144;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

public final class MarketClient {
    private MarketClient() {}

    private static String get(String address, Charset charset) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/120 Safari/537.36");
        connection.setRequestProperty("Referer", "https://vip.stock.finance.sina.com.cn/");
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), charset);
        } finally {
            connection.disconnect();
        }
    }

    public static List<SignalCalculator.Contract> mainContracts() throws Exception {
        String js = get("https://vip.stock.finance.sina.com.cn/quotes_service/view/js/qihuohangqing.js", Charset.forName("GBK"));
        Pattern pattern = Pattern.compile("\\[\\s*['\\\"]([^'\\\"]{1,30})['\\\"]\\s*,\\s*['\\\"]([A-Za-z0-9_]+_qh)['\\\"]");
        Matcher matcher = pattern.matcher(js);
        LinkedHashMap<String, String> nodes = new LinkedHashMap<>();
        while (matcher.find()) nodes.putIfAbsent(matcher.group(2), matcher.group(1));
        if (nodes.isEmpty()) throw new IOException("新浪品种目录解析失败");

        List<SignalCalculator.Contract> result = new ArrayList<>();
        for (var entry : nodes.entrySet()) {
            try {
                String url = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQFuturesData?page=1&num=40&sort=position&asc=0&base=futures&node=" + URLEncoder.encode(entry.getKey(), "UTF-8");
                JSONArray array = new JSONArray(get(url, Charset.forName("UTF-8")));
                JSONObject best = null; double maximum = -1;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    String symbol = item.optString("symbol", "").toUpperCase(Locale.ROOT);
                    if (!symbol.matches("[A-Z]+[0-9]{3,4}") || symbol.endsWith("0")) continue;
                    double position = parse(item.optString("position", "0"));
                    if (position > maximum) { maximum = position; best = item; }
                }
                if (best != null) {
                    String code = best.optString("symbol").toUpperCase(Locale.ROOT);
                    String root = code.replaceAll("[0-9]+$", "");
                    result.add(new SignalCalculator.Contract(code, entry.getValue() + code.substring(root.length()), root));
                }
            } catch (Exception ignored) {}
        }
        result.sort(Comparator.comparing(SignalCalculator.Contract::root));
        if (result.isEmpty()) throw new IOException("未取得实际主力合约，请稍后重试");
        return result;
    }

    public static List<SignalCalculator.Bar> bars(String code) throws Exception {
        String url = "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/=/InnerFuturesNewService.getFewMinLine?symbol=" + URLEncoder.encode(code, "UTF-8") + "&type=60";
        String raw = get(url, Charset.forName("UTF-8"));
        int left = raw.indexOf("=("), right = raw.lastIndexOf(");");
        if (left < 0 || right < 0) throw new IOException("K线返回格式异常");
        JSONArray array = new JSONArray(raw.substring(left + 2, right));
        List<SignalCalculator.Bar> result = new ArrayList<>();
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (int i = 0; i < array.length(); i++) {
            try {
                Object item = array.get(i);
                String time, open, high, low, close, volume;
                if (item instanceof JSONObject object) {
                    time = object.getString("d"); open = object.get("o").toString();
                    high = object.get("h").toString(); low = object.get("l").toString();
                    close = object.get("c").toString(); volume = object.optString("v", "0");
                } else {
                    JSONArray row = (JSONArray) item;
                    time = row.getString(0); open = row.get(1).toString(); high = row.get(2).toString();
                    low = row.get(3).toString(); close = row.get(4).toString();
                    volume = row.length() > 5 ? row.get(5).toString() : "0";
                }
                result.add(new SignalCalculator.Bar(
                    LocalDateTime.parse(time, format), parse(open), parse(high), parse(low),
                    parse(close), parse(volume)
                ));
            } catch (Exception ignored) {}
        }
        if (result.isEmpty()) throw new IOException("K线数据为空或字段无法识别");
        result.sort(Comparator.comparing(SignalCalculator.Bar::time));
        return result;
    }

    private static double parse(String value) {
        try { return Double.parseDouble(value); } catch (Exception ignored) { return 0; }
    }
}
