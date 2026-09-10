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
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout results;
    private TextView status;
    private boolean replay;
    private int period;
    private static final DateTimeFormatter TF=DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Override public void onCreate(Bundle b){super.onCreate(b);buildUi();runScan();}
    private int dp(int x){return(int)(x*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int sp){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(Color.rgb(30,41,59));v.setPadding(dp(10),dp(10),dp(10),dp(10));return v;}
    private Button button(String s,android.view.View.OnClickListener l){Button b=new Button(this);b.setText(s);b.setOnClickListener(l);return b;}
    private void buildUi(){
        replay=getIntent().getBooleanExtra("replay",false);period=getIntent().getIntExtra("period",144);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(16),dp(14),dp(12));setContentView(root);
        TextView title=text(replay?"30日历史回放结果":"实时扫描结果",24);title.setTypeface(null,1);root.addView(title);
        status=text("正在获取1小时K线并计算 MA"+period+"…",14);status.setTextColor(Color.rgb(37,99,235));root.addView(status);
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);root.addView(actions);
        actions.addView(button("返回设置",v->finish()),new LinearLayout.LayoutParams(0,dp(52),1));
        actions.addView(button("清除结果",v->clearResults()),new LinearLayout.LayoutParams(0,dp(52),1));
        ScrollView scroll=new ScrollView(this);results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);scroll.addView(results);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void runScan(){
        ArrayList<String>codes=getIntent().getStringArrayListExtra("codes"),names=getIntent().getStringArrayListExtra("names");boolean closed=getIntent().getBooleanExtra("closed",true);
        if(codes==null||codes.isEmpty()){status.setText("没有选择合约");return;}
        io.submit(()->{int count=0,errors=0;String firstError="";for(int i=0;i<codes.size();i++){String code=codes.get(i),name=names!=null&&i<names.size()?names.get(i):code;MarketClient.Contract c=new MarketClient.Contract(code,name,code.replaceAll("\\d+$",""));try{var bars=MarketClient.bars(code);if(replay){var ss=MarketClient.replay(bars,c,period,30);count+=ss.size();for(var s:ss)addSignal(s,name);}else{var s=MarketClient.latestCross(bars,c,period,closed);if(s!=null){count++;addSignal(s,name);}}}catch(Exception e){errors++;if(firstError.isEmpty())firstError=code+": "+e.getMessage();}int done=i+1,total=codes.size(),signals=count;runOnUiThread(()->status.setText("正在处理 "+done+"/"+total+"，已发现 "+signals+" 个信号"));}int total=count,err=errors;String detail=firstError;runOnUiThread(()->{status.setText("完成：共 "+total+" 个信号，"+err+" 个失败");if(total==0)addEmpty();if(err>0)Toast.makeText(this,"首个错误："+detail,Toast.LENGTH_LONG).show();});});
    }
    private void addSignal(MarketClient.Signal s,String name){runOnUiThread(()->{TextView v=text(s.code()+"  "+name+"\n"+s.time().format(TF)+"  "+s.direction()+"\n收盘 "+String.format(Locale.CHINA,"%.2f",s.close())+"    MA"+period+" "+String.format(Locale.CHINA,"%.2f",s.ma()),16);boolean up=s.direction().equals("上穿");v.setTextColor(up?Color.rgb(185,28,28):Color.rgb(21,128,61));GradientDrawable bg=new GradientDrawable();bg.setColor(up?Color.rgb(254,242,242):Color.rgb(240,253,244));bg.setCornerRadius(dp(10));bg.setStroke(dp(1),up?Color.rgb(254,202,202):Color.rgb(187,247,208));v.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(6));results.addView(v,0,lp);});}
    private void addEmpty(){TextView v=text(replay?"最近30个交易日内没有发现穿越信号":"本次扫描没有发现新的穿越信号",16);v.setGravity(Gravity.CENTER);v.setTextColor(Color.GRAY);results.addView(v);}
    private void clearResults(){results.removeAllViews();status.setText("结果已清除，不会保留历史数据");}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
