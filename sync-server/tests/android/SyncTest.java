package io.github.anylisten.midnight;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
      check(keys.contains("通勤歌单"),"identical playlist name");
      check(!TideSync.status(store).getBoolean("sources"),"sources default off");
      if ("phone".equals(role)) {
        JSONObject row=MusicStore.obj("id","tx_013","name","手机离线收藏","singer","测试","interval","00:12","isLocal",false,
            "meta",MusicStore.obj("musicId","013","source","tx","createTime",1,"posTime",1,"updateTime",1));
        store.desktop.add("love",new JSONArray().put(row));
        check(store.desktop.pendingCount()==1,"local journal pending before sync");
        CountDownLatch notified = new CountDownLatch(1);
        AtomicBoolean listChanged = new AtomicBoolean();
        okhttp3.Call watch = TideSync.watch(store, (call, lists, sources, error) -> {
          listChanged.set(lists && !sources && error == null);
          notified.countDown();
        });
        try {
          TideSync.sync(store,context);
          check(notified.await(5, TimeUnit.SECONDS) && listChanged.get(), "list revision notification");
        } finally {
          watch.cancel();
        }
        check(store.desktop.pendingCount()==0,"local journal ack");
        JSONObject ref = MusicStore.obj("extensionId", "online-metadata", "source", "tx",
            "sourceType", "songlist", "syncId", "qa-source");
        DesktopPlaylists library = store.desktop;
        library.properties("qa-list", "已关联的通勤歌单", ref, false);
        check(library.songs("qa-list").length() == 1, "binding keeps existing songs");
        JSONObject next = new JSONObject(row.toString()).put("id", "tx_second");
        JSONArray songs = new JSONArray().put(row).put(next);
        library.saveOnline(library.scopeId(), "qa-list", "", "", ref, songs, System.currentTimeMillis());
        library.move("qa-list", "tx_013", "tx_second", true);
        JSONObject chart = new JSONObject(ref.toString()).put("sourceType", "topSongs");
        library.saveOnline(library.scopeId(), "", "通勤排行榜", "", chart, songs, System.currentTimeMillis());
        JSONArray lists = library.lists();
        String chartId = lists.getJSONObject(lists.length() - 1).getString("id");
        library.move("", chartId, "qa-list", false);
        boolean rejected = false;
        try {
          library.saveOnline(library.scopeId(), "qa-list", "", "", chart, new JSONArray(), System.currentTimeMillis());
        } catch (java.io.IOException expected) { rejected = true; }
        check(rejected && library.songs("qa-list").length() == 2, "stale refresh preserves songs");
        TideSync.sync(store, context);
        check(library.pendingCount() == 0, "playlist changes acknowledged");
      } else {
        check(store.desktop.songs("love").toString().contains("手机离线收藏"), "car received phone favorite");
        JSONArray lists = store.desktop.lists();
        JSONObject chart = lists.getJSONObject(2), saved = lists.getJSONObject(3);
        check(chart.getString("name").equals("通勤排行榜") && chart.getJSONObject("online").getString("sourceType").equals("topSongs"),
            "chart binding and playlist order survived transfer");
        check(saved.getString("id").equals("qa-list") && saved.getString("name").equals("已关联的通勤歌单") &&
            saved.getJSONObject("online").getString("syncId").equals("qa-source"), "converted in place");
        JSONArray rows = store.desktop.songs("qa-list");
        check(rows.length() == 2 && rows.getJSONObject(0).getString("key").equals("tx_second") &&
            rows.getJSONObject(1).getString("key").equals("tx_013"), "song order survived transfer");
      }
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
