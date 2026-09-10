package com.beitian.futuresma144;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

public final class MarketClient {
    public record Contract(String code, String name, String root) {}
    public record Bar(LocalDateTime time, double close) {}
    public record Signal(String code, String name, LocalDateTime time, String direction, double close, double ma) {}

    private static String get(String address, Charset charset) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/120 Safari/537.36");
        c.setRequestProperty("Referer","https://vip.stock.finance.sina.com.cn/");
        try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            in.transferTo(out); return out.toString(charset);
        } finally { c.disconnect(); }
    }

    public static List<Contract> mainContracts() throws Exception {
        String js=get("https://vip.stock.finance.sina.com.cn/quotes_service/view/js/qihuohangqing.js",Charset.forName("GBK"));
        // The catalogue contains [Chinese product name, Sina node]. Use each real product node once.
        // Catalogue rows contain at least three values: [name, node, column-count, ...].
        // Match the first two values and deliberately do not require an immediate closing bracket.
        Pattern p=Pattern.compile("\\[\\s*['\\\"]([^'\\\"]{1,30})['\\\"]\\s*,\\s*['\\\"]([A-Za-z0-9_]+_qh)['\\\"]");
        Matcher m=p.matcher(js); LinkedHashMap<String,String> nodes=new LinkedHashMap<>();
        while(m.find()) nodes.putIfAbsent(m.group(2),m.group(1));
        if(nodes.isEmpty()) throw new IOException("新浪品种目录解析失败");
        List<Contract> result=new ArrayList<>();
        for(var e:nodes.entrySet()) {
            try {
                String u="https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQFuturesData?page=1&num=40&sort=position&asc=0&base=futures&node="+URLEncoder.encode(e.getKey(),"UTF-8");
                JSONArray a=new JSONArray(get(u,Charset.forName("UTF-8")));
                JSONObject best=null; double max=-1;
                for(int i=0;i<a.length();i++) {
                    JSONObject o=a.getJSONObject(i); String s=o.optString("symbol","").toUpperCase(Locale.ROOT);
                    if(!s.matches("[A-Z]+[0-9]{3,4}") || s.endsWith("0")) continue;
                    double pos=parse(o.optString("position","0"));
                    if(pos>max){max=pos;best=o;}
                }
                if(best!=null) {
                    String code=best.optString("symbol").toUpperCase(Locale.ROOT);
                    String root=code.replaceAll("[0-9]+$","");
                    String label=e.getValue()+code.substring(root.length());
                    result.add(new Contract(code,label,root));
                }
            } catch(Exception ignored) {}
        }
        result.sort(Comparator.comparing(Contract::root));
        if(result.isEmpty()) throw new IOException("未取得实际主力合约（休盘时也应能读取，请稍后重试）");
        return result;
    }

    public static List<Bar> bars(String code) throws Exception {
        String u="https://stock2.finance.sina.com.cn/futures/api/jsonp.php/=/InnerFuturesNewService.getFewMinLine?symbol="+URLEncoder.encode(code,"UTF-8")+"&type=60";
        String raw=get(u,Charset.forName("UTF-8"));
        int left=raw.indexOf("=("), right=raw.lastIndexOf(");");
        if(left<0||right<0) throw new IOException("K线返回格式异常");
        JSONArray a=new JSONArray(raw.substring(left+2,right)); List<Bar> out=new ArrayList<>();
        DateTimeFormatter f=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for(int i=0;i<a.length();i++) {
            try {
                Object item=a.get(i);
                String timeText, closeText;
                if(item instanceof JSONObject) {
                    JSONObject x=(JSONObject)item;
                    timeText=x.getString("d"); closeText=x.get("c").toString();
                } else {
                    JSONArray x=(JSONArray)item;
                    timeText=x.getString(0); closeText=x.get(4).toString();
                }
                out.add(new Bar(LocalDateTime.parse(timeText,f),parse(closeText)));
            } catch(Exception ignored) {}
        }
        if(out.isEmpty()) throw new IOException("K线数据为空或字段无法识别");
        out.sort(Comparator.comparing(Bar::time)); return out;
    }

    public static Signal latestCross(List<Bar> b, Contract c, int n, boolean closedOnly) {
        List<Bar> x=new ArrayList<>(b);
        if(closedOnly&&!x.isEmpty()&&x.get(x.size()-1).time().isAfter(LocalDateTime.now().minusMinutes(60))) x.remove(x.size()-1);
        List<Signal> all=crosses(x,c,n,0); return all.isEmpty()?null:all.get(all.size()-1);
    }

    public static List<Signal> replay(List<Bar> b, Contract c, int n, int days) { return crosses(b,c,n,days); }

    private static List<Signal> crosses(List<Bar> b, Contract c, int n, int days) {
        List<Signal> out=new ArrayList<>(); if(b.size()<n+1)return out;
        Set<LocalDate> allowed=new HashSet<>();
        if(days>0){LinkedHashSet<LocalDate>d=new LinkedHashSet<>();for(Bar z:b)d.add(z.time().plusHours(4).toLocalDate());List<LocalDate>l=new ArrayList<>(d);allowed.addAll(l.subList(Math.max(0,l.size()-days),l.size()));}
        double sum=0; double[] ma=new double[b.size()]; Arrays.fill(ma,Double.NaN);
        for(int i=0;i<b.size();i++){sum+=b.get(i).close();if(i>=n)sum-=b.get(i-n).close();if(i>=n-1)ma[i]=sum/n;}
        for(int i=n;i<b.size();i++){
            if(days>0&&!allowed.contains(b.get(i).time().plusHours(4).toLocalDate()))continue;
            double prev=b.get(i-1).close()-ma[i-1], cur=b.get(i).close()-ma[i]; String dir=null;
            if(prev<=0&&cur>0)dir="上穿"; else if(prev>=0&&cur<0)dir="下穿";
            if(dir!=null)out.add(new Signal(c.code(),c.name(),b.get(i).time(),dir,b.get(i).close(),ma[i]));
        } return out;
    }
    private static double parse(String s){try{return Double.parseDouble(s);}catch(Exception e){return 0;}}
}
