package io.github.anylisten.midnight;

import static io.github.anylisten.midnight.MusicStore.obj;
import static io.github.anylisten.midnight.DesktopPlaylists.serialized;

import android.content.Context;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

final class TideSync {
  private static final OkHttpClient CLIENT =
      LxEngine.HTTP.newBuilder().followRedirects(false).followSslRedirects(false).build();
  // The server can hold a change request for 20 seconds when revisions match.
  private static final OkHttpClient WATCH_CLIENT =
      CLIENT.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build();
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final String ADDRESS = "tide.address";
  private static final String TOKEN = "tide.token";
  private static final String ACCOUNT = "tide.account";
  private static final String LIBRARY = "tide.library";
  private static final String SOURCES = "tide.sources";
  private static final String SOURCE_REV = "tide.sourceRev";
  private static final String SOURCE_HASH = "tide.sourceHash";
  private static final String SOURCE_TRUSTED = "tide.sourcesTrusted";
  private static final String PENDING = "tide.sourcePending";

  private static final class Conflict extends IOException {}

  interface ChangeListener {
    void changed(Call call, boolean lists, boolean sources, String error);
  }

  private final MusicStore store;
  private final String address, token;

  private TideSync(MusicStore store) throws Exception {
    this.store = store;
    address = store.prefs.getString(ADDRESS, "");
    token = DavSync.reveal(store.prefs.getString(TOKEN, ""));
    if (address.isEmpty() || token.isEmpty()) throw new IOException("请先配对汐听同步服务");
  }

  static boolean enabled(MusicStore store) {
    return !store.prefs.getString(ADDRESS, "").isEmpty();
  }

  static boolean configured(MusicStore store) {
    return enabled(store) || !store.prefs.getString("dav", "").isEmpty();
  }

  static String scope(android.content.SharedPreferences prefs) {
    String address = prefs.getString(ADDRESS, "");
    return address.isEmpty() ? "" : "tide\n" + address + "\n" + prefs.getString(LIBRARY, "");
  }

  static boolean sourcesEnabled(MusicStore store) {
    return store.prefs.getBoolean(SOURCES, false);
  }

  private static boolean sourcesTrusted(MusicStore store) {
    // Older clients saved a revision after syncing, but had no separate first-review flag.
    return store.prefs.getBoolean(SOURCE_TRUSTED, sourcesEnabled(store)
        && (store.prefs.contains(SOURCE_REV) || !store.prefs.contains(PENDING)));
  }

  static boolean hasLocalSourceChanges(MusicStore store) throws Exception {
    return sourcesEnabled(store) && !digest(store.sources.exportTideSources())
        .equals(store.prefs.getString(SOURCE_HASH, ""));
  }

  static JSONObject status(MusicStore store) throws JSONException {
    boolean review = pending(store).length() > 0;
    return obj(
        "address", store.prefs.getString(ADDRESS, ""),
        "account", store.prefs.getString(ACCOUNT, ""),
        "sources", store.prefs.getBoolean(SOURCES, false),
        "pending", review,
        "hasReview", review);
  }

  private static String serverUrl(String input) throws IOException {
    HttpUrl url = HttpUrl.parse(input.trim());
    if (url == null
        || (!url.isHttps() && !url.host().equals("127.0.0.1") && !url.host().equals("localhost"))
        || !url.username().isEmpty() || !url.password().isEmpty()
        || url.fragment() != null || url.query() != null
        || !url.encodedPath().matches("/(?:(?:admin|login)/?)?")) {
      throw new IOException("请输入同步服务的 HTTPS 地址");
    }
    return url.newBuilder().encodedPath("/").build().toString();
  }

  private static JSONObject request(String address, String token, String kind, String method, JSONObject body)
      throws Exception {
    Request.Builder builder = new Request.Builder().url(address + "api/v1/" + kind);
    if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
    if (!"GET".equals(method)) builder.method(method, RequestBody.create(serialized(body), JSON));
    try (Response response = CLIENT.newCall(builder.build()).execute()) {
      if (response.code() == 409) throw new Conflict();
      String text = MusicStore.text(response.body().byteStream(), 24 * 1024 * 1024);
      JSONObject reply;
      try { reply = new JSONObject(text); }
      catch (JSONException error) { throw new IOException("同步服务响应无效"); }
      if (!response.isSuccessful()) throw new IOException(reply.optString("error", "同步请求失败"));
      return reply;
    }
  }

  static Call watch(MusicStore store, ChangeListener listener) throws Exception {
    TideSync client = new TideSync(store);
    int lists = store.desktop.tideRevision();
    int sources = pending(store).optInt(0, store.prefs.getInt(SOURCE_REV, -1));
    boolean watchSources = sourcesEnabled(store);
    HttpUrl.Builder url = HttpUrl.get(client.address + "api/v1/changes").newBuilder()
        .addQueryParameter("lists", Integer.toString(lists));
    if (watchSources) url.addQueryParameter("sources", Integer.toString(sources));
    Request request = new Request.Builder().url(url.build())
        .header("Authorization", "Bearer " + client.token).build();
    Call call = WATCH_CLIENT.newCall(request);
    call.enqueue(new Callback() {
      @Override
      public void onFailure(Call active, IOException error) {
        listener.changed(active, false, false, "无法连接同步服务");
      }

      @Override
      public void onResponse(Call active, Response incoming) {
        try (Response response = incoming) {
          if (response.code() == 404) throw new IOException("请更新同步服务以接收远端变更");
          JSONObject result = new JSONObject(MusicStore.text(response.body().byteStream(), 8192));
          if (!response.isSuccessful()) throw new IOException(result.optString("error", "同步请求失败"));
          listener.changed(active, result.getInt("lists") != lists,
              watchSources && result.getInt("sources") != sources, null);
        } catch (IOException | JSONException error) {
          listener.changed(active, false, false, error.getMessage());
        }
      }
    });
    return call;
  }

  static String pair(MusicStore store, String input, String code) throws Exception {
    synchronized (DavSync.class) {
      if (enabled(store)) throw new IOException("请先断开当前同步账号");
      String address = serverUrl(input);
      String name = "汐听 " + (BuildConfig.FLAVOR.equals("dashboard") ? "车机" : "Android") + " · " + android.os.Build.MODEL;
      JSONObject paired = request(address, "", "pair", "POST", obj("code", code, "name", name));
      if (!store.prefs.edit()
          .putString(ADDRESS, address)
          .putString(TOKEN, DavSync.protect(paired.getString("token")))
          .putString(ACCOUNT, paired.getString("account"))
          .putString(LIBRARY, paired.getString("library"))
          .remove("lastSync")
          .commit()) throw new IOException("设备凭据未能保存");
      store.desktop.loadCachedOnce();
      return "配对成功";
    }
  }

  static String disconnect(MusicStore store) throws Exception {
    synchronized (DavSync.class) {
      if (!store.prefs.edit()
          .remove(ADDRESS).remove(TOKEN).remove(ACCOUNT).remove(LIBRARY)
          .remove(SOURCES).remove(SOURCE_TRUSTED).remove(SOURCE_REV).remove(SOURCE_HASH).remove(PENDING)
          .remove("lastSync").commit()) throw new IOException("未能移除本地凭据");
      store.desktop.loadCachedOnce();
      return "已断开汐听同步";
    }
  }

  static String setSources(MusicStore store, boolean value) throws Exception {
    synchronized (DavSync.class) {
      if (!enabled(store)) throw new IOException("请先配对汐听同步服务");
      if (value == sourcesEnabled(store)) return value ? "已开启音源同步" : "已关闭音源同步";
      if (!store.prefs.edit().putBoolean(SOURCE_TRUSTED, sourcesTrusted(store)).putBoolean(SOURCES, value)
          .remove(SOURCE_REV).remove(SOURCE_HASH).remove(PENDING).commit())
        throw new IOException("音源同步设置未能保存");
      return value ? "已开启音源同步" : "已关闭音源同步";
    }
  }

  static JSONArray pending(MusicStore store) throws JSONException {
    return sourcesTrusted(store) ? new JSONArray() : new JSONArray(store.prefs.getString(PENDING, "[]"));
  }

  static String approve(MusicStore store, Context context, int revision) throws Exception {
    synchronized (DavSync.class) {
      if (!store.prefs.getBoolean(SOURCES, false)) throw new IOException("请先启用音源同步");
      new TideSync(store).sources(context, revision);
      return "服务器音源已应用";
    }
  }

  static String sync(MusicStore store, Context context) throws Exception {
    if (!enabled(store)) return DavSync.sync(store);
    syncLists(store);
    if (sourcesEnabled(store)) syncSources(store, context);
    return "汐听歌单已同步";
  }

  static void syncLists(MusicStore store) throws Exception {
    synchronized (DavSync.class) {
      new TideSync(store).lists();
      store.prefs.edit().putLong("lastSync", System.currentTimeMillis()).apply();
    }
  }

  static void syncSources(MusicStore store, Context context) throws Exception {
    synchronized (DavSync.class) {
      if (sourcesEnabled(store)) new TideSync(store).sources(context, null);
    }
  }

  private JSONObject get(String kind) throws Exception {
    return request(address, token, kind, "GET", null);
  }

  private int put(String kind, int revision, Object data) throws Exception {
    return request(address, token, kind, "PUT",
        obj("revision", revision, "operation", UUID.randomUUID().toString(), "data", data)).getInt("revision");
  }

  private void lists() throws Exception {
    DesktopPlaylists playlists = store.desktop;
    playlists.loadCachedOnce();
    for (int attempt = 0; attempt < 5; attempt++) {
      JSONObject snapshot = get("lists");
      int revision = snapshot.getInt("revision");
      JSONObject data = snapshot.optJSONObject("data");
      int count = playlists.pendingCount();
      String remote = data == null ? "" : serialized(data);
      if (count > 0) {
        String merged = playlists.staged(remote, count);
        try {
          if (!remote.equals(merged)) revision = put("lists", revision, new JSONObject(merged));
          playlists.acceptRemote(merged, Integer.toString(revision), count);
          return;
        } catch (Conflict conflict) {
          continue;
        }
      }
      if (playlists.tideRevision() != revision) {
        playlists.acceptRemote(remote, Integer.toString(revision), 0);
      }
      return;
    }
    throw new IOException("歌单正在被其他设备修改，请稍后重试");
  }

  private static String digest(JSONArray sources) throws JSONException {
    JSONArray flat = new JSONArray();
    for (int i = 0; i < sources.length(); i++) {
      JSONObject row = sources.getJSONObject(i);
      flat.put(new JSONArray().put(row.getString("script").trim()).put(row.getString("name"))
          .put(row.optString("url")).put(row.optBoolean("enabled")));
    }
    return okio.ByteString.encodeUtf8(flat.toString()).sha256().hex();
  }

  private void sources(Context context, Integer approve) throws Exception {
    JSONObject snapshot = get("sources");
    int revision = snapshot.getInt("revision");
    JSONArray remote = snapshot.optJSONArray("data");
    if (revision < 0 || (remote == null && !(revision == 0 && snapshot.has("data") && snapshot.isNull("data"))))
      throw new IOException("服务器音源列表格式无效");
    JSONArray local = store.sources.exportTideSources();
    String fingerprint = digest(local);
    if (approve != null) {
      JSONArray review = pending(store);
      if (approve != revision || review.length() == 0 || review.getInt(0) != revision)
        throw new IOException("服务器音源已变更，请重新查看");
    } else if (!sourcesTrusted(store) && revision > 0) {
      JSONArray review = new JSONArray().put(revision);
      for (int i = 0; remote != null && i < remote.length(); i++) {
        JSONObject row = remote.getJSONObject(i);
        review.put(row.getString("name") + (row.optBoolean("enabled") ? " · 启用" : " · 停用"));
      }
      store.prefs.edit().putString(PENDING, review.toString()).apply();
      return;
    }
    if (remote != null && fingerprint.equals(digest(remote))) {
      // Both endpoints already have this revision.
    } else if (approve == null && (revision == 0 || revision == store.prefs.getInt(SOURCE_REV, -1))) {
      if (!fingerprint.equals(store.prefs.getString(SOURCE_HASH, "")) && (local.length() > 0 || remote != null))
        revision = put("sources", revision, local);
    } else {
      store.sources.installTideSources(context, remote == null ? new JSONArray() : remote);
      fingerprint = digest(store.sources.exportTideSources());
    }
    store.prefs.edit().putBoolean(SOURCE_TRUSTED, true).putInt(SOURCE_REV, revision)
        .putString(SOURCE_HASH, fingerprint).remove(PENDING).apply();
  }
}
