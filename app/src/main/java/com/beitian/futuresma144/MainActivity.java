package com.beitian.futuresma144;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private ScheduledExecutorService timer;
    private LinearLayout contractsBox; private TextView status;
    private EditText period, interval; private CheckBox closed, sound;
    private Button monitor; private List<MarketClient.Contract> contracts=new ArrayList<>();
    private final Set<String> selected=new HashSet<>(), notified=new HashSet<>();
    private static final DateTimeFormatter TF=DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Override public void onCreate(Bundle b){super.onCreate(b);createChannel();loadPrefs();buildUi();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7);refresh();}
    private int dp(int x){return(int)(x*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int sp){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(Color.rgb(30,41,59));v.setPadding(dp(8),dp(8),dp(8),dp(8));return v;}
    private Button button(String s,View.OnClickListener l){Button b=new Button(this);b.setText(s);b.setOnClickListener(l);return b;}
    private void buildUi(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(18),dp(14),dp(30));scroll.addView(root);setContentView(scroll);
        TextView title=text("期货 MA144 穿越提醒",25);title.setTypeface(null,1);root.addView(title);
        status=text("正在获取实际主力合约…",14);status.setTextColor(Color.rgb(37,99,235));root.addView(status);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);root.addView(row);
        row.addView(text("周期",14));period=new EditText(this);period.setInputType(2);period.setText(getPreferences(0).getString("period","144"));row.addView(period,new LinearLayout.LayoutParams(dp(70),dp(55)));
        row.addView(text("间隔(分)",14));interval=new EditText(this);interval.setInputType(2);interval.setText(getPreferences(0).getString("interval","5"));row.addView(interval,new LinearLayout.LayoutParams(dp(65),dp(55)));
        closed=new CheckBox(this);closed.setText("仅完成K线");closed.setChecked(true);root.addView(closed);sound=new CheckBox(this);sound.setText("声音/通知提醒");sound.setChecked(true);root.addView(sound);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);root.addView(actions);
        LinearLayout a1=new LinearLayout(this);actions.addView(a1);monitor=button("开始监控",v->toggleMonitor());a1.addView(monitor,new LinearLayout.LayoutParams(0,dp(52),1));a1.addView(button("立即扫描",v->openResults(false)),new LinearLayout.LayoutParams(0,dp(52),1));
        LinearLayout a2=new LinearLayout(this);actions.addView(a2);a2.addView(button("刷新主力",v->refresh()),new LinearLayout.LayoutParams(0,dp(52),1));a2.addView(button("历史回放30日",v->openResults(true)),new LinearLayout.LayoutParams(0,dp(52),1));
        root.addView(text("实际主力合约（非连续）",19));LinearLayout choose=new LinearLayout(this);root.addView(choose);choose.addView(button("全选",v->selectAll(true)));choose.addView(button("清空",v->selectAll(false)));
        contractsBox=new LinearLayout(this);contractsBox.setOrientation(LinearLayout.VERTICAL);root.addView(contractsBox);
        TextView warn=text("仅用于行情提醒，不提供下单功能；公开行情可能延迟。监控需保持应用开启。",12);warn.setTextColor(Color.GRAY);root.addView(warn);
    }
    private void refresh(){status.setText("正在按持仓量刷新实际主力合约…");io.submit(()->{try{List<MarketClient.Contract>x=MarketClient.mainContracts();runOnUiThread(()->showContracts(x));}catch(Exception e){error("刷新失败："+e.getMessage());}});}
    private void showContracts(List<MarketClient.Contract>x){contracts=x;contractsBox.removeAllViews();for(var c:x){CheckBox cb=new CheckBox(this);cb.setText(c.code()+"  "+c.name());cb.setChecked(selected.isEmpty()||selected.contains(c.root()));cb.setTag(c);cb.setOnCheckedChangeListener((v,on)->{if(on)selected.add(c.root());else selected.remove(c.root());savePrefs();});contractsBox.addView(cb);}status.setText("已加载 "+x.size()+" 个实际交割月主力合约");}
    private List<MarketClient.Contract> chosen(){List<MarketClient.Contract>x=new ArrayList<>();for(int i=0;i<contractsBox.getChildCount();i++){CheckBox b=(CheckBox)contractsBox.getChildAt(i);if(b.isChecked())x.add((MarketClient.Contract)b.getTag());}return x;}
    private void selectAll(boolean on){for(int i=0;i<contractsBox.getChildCount();i++)((CheckBox)contractsBox.getChildAt(i)).setChecked(on);}
    private int num(EditText e,int d){try{return Math.max(1,Integer.parseInt(e.getText().toString()));}catch(Exception x){return d;}}
    private void openResults(boolean replay){List<MarketClient.Contract>x=chosen();if(x.isEmpty()){toast("请先选择合约");return;}savePrefs();ArrayList<String>codes=new ArrayList<>(),names=new ArrayList<>();for(var c:x){codes.add(c.code());names.add(c.name());}Intent i=new Intent(this,ResultActivity.class);i.putStringArrayListExtra("codes",codes);i.putStringArrayListExtra("names",names);i.putExtra("replay",replay);i.putExtra("period",num(period,144));i.putExtra("closed",closed.isChecked());startActivity(i);}
    private void monitorScan(){List<MarketClient.Contract>x=chosen();if(x.isEmpty())return;io.submit(()->{int count=0,errors=0;for(var c:x){try{var s=MarketClient.latestCross(MarketClient.bars(c.code()),c,num(period,144),closed.isChecked());if(s!=null){count++;String key=s.code()+s.time()+s.direction();if(sound.isChecked()&&notified.add(key))runOnUiThread(()->notifySignal(s));}}catch(Exception e){errors++;}}int total=count,err=errors;runOnUiThread(()->status.setText("监控扫描完成："+total+" 个信号，"+err+" 个失败"));});}
    private void toggleMonitor(){if(timer!=null){timer.shutdownNow();timer=null;monitor.setText("开始监控");status.setText("监控已停止");return;}int mins=num(interval,5);timer=Executors.newSingleThreadScheduledExecutor();timer.scheduleWithFixedDelay(this::monitorScan,0,mins,TimeUnit.MINUTES);monitor.setText("停止监控");status.setText("监控已启动，每 "+mins+" 分钟扫描");}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("signals","穿越提醒",NotificationManager.IMPORTANCE_HIGH));}
    private void notifySignal(MarketClient.Signal s){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"signals"):new Notification.Builder(this);b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(s.code()+" "+s.direction()+" MA"+num(period,144)).setContentText(s.time().format(TF)+" 收盘 "+s.close()+" MA "+String.format(Locale.CHINA,"%.2f",s.ma())).setAutoCancel(true);getSystemService(NotificationManager.class).notify(Math.abs((s.code()+s.time()).hashCode()),b.build());}
    private void loadPrefs(){String csv=getPreferences(0).getString("roots","");if(!csv.isEmpty())selected.addAll(Arrays.asList(csv.split(",")));}
    private void savePrefs(){getPreferences(0).edit().putString("period",period.getText().toString()).putString("interval",interval.getText().toString()).putString("roots",String.join(",",selected)).apply();}
    private void error(String s){runOnUiThread(()->{status.setText(s);toast(s);});}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){if(timer!=null)timer.shutdownNow();io.shutdownNow();super.onDestroy();}
}
