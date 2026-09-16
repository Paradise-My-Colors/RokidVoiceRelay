package com.paradisemc.rokidfindmyphone;
import android.content.BroadcastReceiver;import android.content.Context;import android.content.Intent;
public class StopReceiver extends BroadcastReceiver {@Override public void onReceive(Context context,Intent intent){AlarmController.stop(context);}}
