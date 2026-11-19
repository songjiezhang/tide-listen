package io.github.anylisten.midnight;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

final class DavSync {
  private static final String KEY = "any-listen-dav";
  private static final OkHttpClient CLIENT =
      LxEngine.HTTP.newBuilder().followRedirects(false).build();

  private static synchronized SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (ks.containsAlias(KEY)) return (SecretKey) ks.getKey(KEY, null);
    KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    gen.init(
        new KeyGenParameterSpec.Builder(
                KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return gen.generateKey();
  }

  private static String protect(String text) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key());
    return Base64.encodeToString(c.getIV(), Base64.NO_WRAP)
        + "."
        + Base64.encodeToString(c.doFinal(text.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
  }

  private static String reveal(String text) throws Exception {
    if (text.isEmpty()) return "";
    String[] s = text.split("\\.", 2);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(s[0], Base64.DEFAULT)));
    return new String(c.doFinal(Base64.decode(s[1], Base64.DEFAULT)), StandardCharsets.UTF_8);
  }

  static synchronized void configure(MusicStore s, String url, String user, String password)
      throws Exception {
    HttpUrl base = HttpUrl.parse(url);
    if (base == null
        || !base.username().isEmpty()
        || !base.password().isEmpty()
        || base.query() != null
        || base.fragment() != null) throw new IOException("请输入 WebDAV 目录地址，账号和密码请单独填写");
    String path = base.toString();
    if (!path.endsWith("/")) path += "/";
    s.desktop.loadCachedOnce();
    android.content.SharedPreferences.Editor editor = s.prefs.edit();
    if (!path.equals(s.prefs.getString("dav", "")) || !user.equals(s.prefs.getString("user", "")))
      editor.remove("lastSync");
    editor
        .putString("dav", path)
        .putString("user", user)
        .putString("password", protect(password))
        .apply();
    s.desktop.loadCachedOnce();
  }

  static String password(MusicStore s) throws Exception {
    return reveal(s.prefs.getString("password", ""));
  }

  static synchronized String sync(MusicStore store) throws Exception {
    String base = store.prefs.getString("dav", ""), user = store.prefs.getString("user", "");
    if (base.isEmpty()) throw new IOException("请先配置 WebDAV");
    DesktopPlaylists playlists = store.desktop;
    playlists.loadCachedOnce();
    DavSync dav =
        new DavSync(
            HttpUrl.get(base),
            user.isEmpty() ? "" : Credentials.basic(user, password(store), StandardCharsets.UTF_8));
    int count = playlists.pendingCount();
    if (count == 0) {
      Remote remote = dav.remote(playlists);
      if (remote == null) {
        if (playlists.hasRemoteBase()) throw new IOException("远端快照暂时不可用，已保留上次歌单");
        return "等待首次同步";
      }
      playlists.acceptRemote(remote.raw, remote.key, 0);
    } else {
      try {
        dav.writeChanges(store, count);
      } catch (Exception writeError) {
        try {
          Remote remote = dav.remote(playlists);
          if (remote != null) playlists.acceptRemote(remote.raw, remote.key, 0);
        } catch (Exception readError) {
          writeError.addSuppressed(readError);
        }
        throw writeError;
      }
    }
    store.prefs.edit().putLong("lastSync", System.currentTimeMillis()).apply();
    return "WebDAV 已同步";
  }

  private static final class Remote {
    final JSONObject info;
    final String raw, key, etag;

    Remote(JSONObject info, String raw, String key, String etag) {
      this.info = info;
      this.raw = raw;
      this.key = key;
      this.etag = etag;
    }
  }

  private final HttpUrl base;
  private final String auth;

  private DavSync(HttpUrl base, String auth) {
    this.base = base;
    this.auth = auth;
  }

  private Request.Builder request(String file) {
    Request.Builder request = new Request.Builder().url(base.resolve(file));
    if (!auth.isEmpty()) request.header("Authorization", auth);
    return request;
  }

  private static String decoded(Response res) throws IOException {
    check(res);
    String raw = MusicStore.text(res.body().byteStream(), MusicStore.LIMIT);
    if (!raw.startsWith("cg_")) return raw;
    byte[] gzip = Base64.decode(raw.substring(3), Base64.DEFAULT);
    return MusicStore.text(
        new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gzip)),
        MusicStore.LIMIT);
  }

  private static String encoded(String raw) throws IOException {
    byte[] data = raw.getBytes(StandardCharsets.UTF_8);
    if (data.length > MusicStore.LIMIT) throw new IOException("歌单快照超过大小限制");
    if (data.length <= 1024) return raw;
    java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
    try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(result)) {
      gzip.write(data);
    }
    String compressed = "cg_" + Base64.encodeToString(result.toByteArray(), Base64.NO_WRAP);
    return compressed.length() < data.length ? compressed : raw;
  }

  private static String digest(String raw) {
    return okio.ByteString.encodeUtf8(raw).md5().hex();
  }

  private Remote remote(DesktopPlaylists playlists) throws Exception {
    JSONObject info;
    String etag;
    try (Response res = CLIENT.newCall(request("list/snapshot-info").build()).execute()) {
      if (res.code() == 404) return null;
      info = new JSONObject(decoded(res));
      etag = res.header("ETag", "");
    }
    String key = info.getString("latest");
    if (info.getInt("version") != 1
        || !key.matches("[0-9a-f]{32}")
        || info.optJSONObject("clients") == null) throw new IOException("WebDAV 歌单索引格式无法识别");
    String raw = playlists.base(key);
    if (raw.isEmpty()) {
      try (Response res = CLIENT.newCall(request("list/snapshots/" + key).build()).execute()) {
        raw = decoded(res);
      }
      if (!digest(raw).equals(key)) throw new IOException("WebDAV 歌单快照校验失败");
    }
    return new Remote(info, raw, key, etag);
  }

  private void writeChanges(MusicStore store, int count) throws Exception {
    String lock = lock();
    try {
      DesktopPlaylists playlists = store.desktop;
      Remote remote = remote(playlists);
      if (remote == null && playlists.hasRemoteBase()) throw new IOException("远端快照暂时不可用，修改已保留");
      String old = remote == null ? "" : remote.raw;
      String next = playlists.staged(old, count);
      if (remote != null
          && new JSONObject(next).toString().equals(new JSONObject(old).toString())) {
        playlists.acceptRemote(old, remote.key, count);
        return;
      }
      if (remote == null) {
        mkdir("list");
        mkdir("list/snapshots");
      }
      String key = digest(next);
      JSONArray history;
      try (Response res = CLIENT.newCall(request("list/snapshot-list").build()).execute()) {
        history = res.code() == 404 ? new JSONArray() : new JSONArray(decoded(res));
      }
      if (remote != null && history.length() == 0) history.put(remote.key);
      JSONObject info =
          remote == null ? MusicStore.obj("version", 1, "clients", new JSONObject()) : remote.info;
      String device = store.prefs.getString("device", "");
      info.getJSONObject("clients")
          .put(
              device,
              MusicStore.obj("snapshotKey", key, "lastSyncTime", System.currentTimeMillis()));
      info.put("latest", key);
      put("list/snapshots/" + key, encoded(next), "", false);
      JSONArray updated = new JSONArray().put(key);
      for (int i = 0; i < history.length(); i++) {
        String item = history.getString(i);
        if (!key.equals(item)) updated.put(item);
      }
      put("list/snapshot-list", encoded(updated.toString()), "", false);
      put(
          "list/snapshot-info",
          encoded(DesktopPlaylists.serialized(info)),
          remote == null ? "" : remote.etag,
          remote == null);
      playlists.acceptRemote(next, key, count);
    } finally {
      try (Response res = CLIENT.newCall(request(lock).delete().build()).execute()) {
        // A disconnected device's lock will expire under the desktop protocol.
      } catch (IOException ignored) {
      }
    }
  }

  private void mkdir(String path) throws IOException {
    try (Response res = CLIENT.newCall(request(path).method("MKCOL", null).build()).execute()) {
      if (res.code() != 405) check(res);
    }
  }

  private void put(String path, String raw, String etag, boolean create) throws IOException {
    Request.Builder builder =
        request(path).put(RequestBody.create(raw, MediaType.get("application/octet-stream")));
    if (create) builder.header("If-None-Match", "*");
    else if (!etag.isEmpty() && !etag.startsWith("W/")) builder.header("If-Match", etag);
    try (Response res = CLIENT.newCall(builder.build()).execute()) {
      check(res);
    }
  }

  private java.util.List<String> locks() throws Exception {
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><D:propfind"
            + " xmlns:D=\"DAV:\"><D:prop><D:resourcetype/></D:prop></D:propfind>";
    Request request =
        request("")
            .header("Depth", "1")
            .method(
                "PROPFIND",
                RequestBody.create(xml, MediaType.get("application/xml; charset=utf-8")))
            .build();
    try (Response res = CLIENT.newCall(request).execute()) {
      check(res);
      String body = MusicStore.text(res.body().byteStream(), 2 * 1024 * 1024);
      org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser();
      parser.setInput(new java.io.StringReader(body));
      java.util.List<String> locks = new java.util.ArrayList<>();
      for (int event = parser.getEventType();
          event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
          event = parser.next()) {
        if (event != org.xmlpull.v1.XmlPullParser.START_TAG || !"href".equals(parser.getName()))
          continue;
        HttpUrl entry = base.resolve(parser.nextText().trim());
        if (entry == null
            || !base.host().equals(entry.host())
            || !base.scheme().equals(entry.scheme())
            || base.port() != entry.port()
            || !entry.encodedPath().startsWith(base.encodedPath())) continue;
        String file = entry.encodedPath().substring(base.encodedPath().length());
        if (file.matches("lock-[0-9]{10,16}-[A-Za-z0-9_-]+")) locks.add(file);
      }
      return locks;
    }
  }

  private static long lockTime(String file) {
    return Long.parseLong(file.split("-", 3)[1]);
  }

  private String lock() throws Exception {
    long now = System.currentTimeMillis();
    for (String file : locks()) {
      if (now - lockTime(file) <= 120_000) throw new IOException("其他设备正在同步，稍后自动重试");
      try (Response res = CLIENT.newCall(request(file).delete().build()).execute()) {
        if (res.code() != 404) check(res);
      }
    }
    String lock = "lock-" + now + "-" + java.util.UUID.randomUUID();
    put(lock, "", "", true);
    boolean owned = false;
    try {
      java.util.List<String> candidates = locks();
      if (!candidates.contains(lock)) throw new IOException("WebDAV 同步锁暂不可见");
      for (String file : candidates)
        if (file.compareTo(lock) < 0 && now - lockTime(file) <= 120_000)
          throw new IOException("其他设备正在同步，稍后自动重试");
      owned = true;
      return lock;
    } finally {
      if (!owned) {
        try (Response ignored = CLIENT.newCall(request(lock).delete().build()).execute()) {
        } catch (IOException ignored) {
        }
      }
    }
  }

  static void check(Response response) throws IOException {
    if (!response.isSuccessful()) throw new IOException("WebDAV 返回 HTTP " + response.code());
  }
}
