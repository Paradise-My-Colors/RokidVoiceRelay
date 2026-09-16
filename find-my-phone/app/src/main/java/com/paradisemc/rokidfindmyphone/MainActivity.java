package com.paradisemc.rokidfindmyphone;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private TextView status;
    @Override protected void onCreate(Bundle savedInstanceState) { super.onCreate(savedInstanceState); setContentView(buildUi()); requestRuntimePermissions(); PhoneFinderService.start(this); }
    @Override protected void onResume() { super.onResume(); refreshStatus(); }
    private View buildUi() {
        int pad=dp(24); LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(pad,pad,pad,pad); root.setBackgroundColor(Color.WHITE);
        TextView title=text("Rokid Find My Phone",28,true); root.addView(title);
        TextView subtitle=text("Companion for the Rokid AIUI Find My Phone agent. Keep Bluetooth on and this service enabled.",16,false); subtitle.setPadding(0,dp(10),0,dp(18)); root.addView(subtitle);
        status=text("Checking setup…",16,true); status.setPadding(0,0,0,dp(18)); root.addView(status);
        root.addView(button("Enable / restart Phone Finder",v->{ requestRuntimePermissions(); PhoneFinderService.start(this); refreshStatus(); }));
        root.addView(button("Allow Do Not Disturb override",v->startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))));
        if(Build.VERSION.SDK_INT>=34) root.addView(button("Allow full-screen alarm",v->{ try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:"+getPackageName()))); } catch(Exception ignored){} }));
        root.addView(button("TEST: Ring this phone",v->AlarmController.start(this))); root.addView(button("STOP RINGING",v->AlarmController.stop(this)));
        TextView note=text("One-time setup: grant Nearby devices/Bluetooth, notifications, and Do Not Disturb access. The service starts again after reboot. When the Rokid agent sends RING, the phone temporarily forces audible mode and maximum alarm/ring volume. Press STOP RINGING to restore your previous settings.",14,false); note.setPadding(0,dp(18),0,0); root.addView(note); return root;
    }
    private void refreshStatus(){ boolean bt=hasBtPermission(); boolean dnd=((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted(); BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter(); boolean enabled=adapter!=null&&adapter.isEnabled(); status.setText("Bluetooth permission: "+(bt?"OK":"NEEDED")+"\nBluetooth: "+(enabled?"ON":"OFF")+"\nDND override: "+(dnd?"OK":"NEEDED for DND")); }
    private boolean hasBtPermission(){ return Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED; }
    private void requestRuntimePermissions(){ if(Build.VERSION.SDK_INT<23)return; List<String> needed=new ArrayList<>(); if(Build.VERSION.SDK_INT>=31){ if(checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.BLUETOOTH_ADVERTISE); if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.BLUETOOTH_CONNECT); } if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.POST_NOTIFICATIONS); if(!needed.isEmpty())requestPermissions(needed.toArray(new String[0]),100); }
    private Button button(String label,View.OnClickListener listener){ Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(16); b.setOnClickListener(listener); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54)); lp.setMargins(0,dp(6),0,dp(6)); b.setLayoutParams(lp); return b; }
    private TextView text(String s,int sp,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.rgb(25,25,25)); if(bold)t.setTypeface(t.getTypeface(),android.graphics.Typeface.BOLD); return t; }
    private int dp(int value){ return Math.round(value*getResources().getDisplayMetrics().density); }
}
