package io.github.anylisten.midnight;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import org.json.*;

public class SyncTest extends Instrumentation {
  Bundle args;
  public void onCreate(Bundle b) { args=b; start(); }
  private void check(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
  @Override public void onStart() {
    Bundle result=new Bundle();
    try {
      Context context=getTargetContext();
      MusicStore store=MusicStore.get(context);
      if (TideSync.enabled(store)) TideSync.disconnect(store);
      TideSync.pair(store, "http://127.0.0.1:8472", args.getString("code"));
      TideSync.sync(store,context);
      String role=args.getString("role");
      String keys=store.desktop.lists().toString();
      check(keys.contains("汐听通勤歌单"),"identical playlist name");
      check(!TideSync.status(store).getBoolean("sources"),"sources default off");
      if ("phone".equals(role)) {
        JSONObject row=MusicStore.obj("id","tx_013","name","手机离线收藏","singer","测试","interval","00:12","isLocal",false,
            "meta",MusicStore.obj("musicId","013","source","tx","createTime",1,"posTime",1,"updateTime",1));
        store.desktop.add("love",new JSONArray().put(row));
        check(store.desktop.pendingCount()==1,"local journal pending before sync");
        TideSync.sync(store,context);
        check(store.desktop.pendingCount()==0,"local journal ack");
      } else check(store.desktop.songs("love").toString().contains("手机离线收藏"),"car received phone favorite");
      TideSync.setSources(store,true);
      TideSync.sync(store,context);
      check(TideSync.status(store).getBoolean("hasReview"),"require explicit review");
      JSONArray pending=TideSync.pending(store);
      check(pending.length()==3,"two sources available");
      TideSync.approve(store,context,pending.getInt(0));
      JSONArray sources=store.sources.exportTideSources();
      check(sources.length()==2 && sources.getJSONObject(0).getString("name").equals("示例音源一"),"source priority transferred");
      check(sources.getJSONObject(0).getBoolean("enabled") && !sources.getJSONObject(1).getBoolean("enabled"),"source enabled states");
      check(!TideSync.status(store).getBoolean("hasReview"),"review cleared");
      result.putString("stream","PASS "+getTargetContext().getPackageName()+" 配对、歌单、离线队列、音源审核和优先级");
      finish(-1,result);
    } catch(Throwable error) {
      result.putString("stream",android.util.Log.getStackTraceString(error));
      finish(0,result);
    }
  }
}
