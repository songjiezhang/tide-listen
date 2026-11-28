package io.github.anylisten.midnight;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.json.*;

final class MusicStore {
  static final int LIMIT = 16 * 1024 * 1024;
  static final Set<String> PLATFORMS = new HashSet<>(Arrays.asList("wy", "tx", "kw", "kg", "mg"));
  private static MusicStore instance;

  static synchronized MusicStore get(Context c) {
    if (instance == null) instance = new MusicStore(c.getApplicationContext());
    return instance;
  }

  final SharedPreferences prefs;
  final LxSources sources;
  final DesktopPlaylists desktop;
  private final String device;
  private JSONObject tracks;
  private final Map<String, JSONObject> local = new HashMap<>();
  private final Map<String, JSONObject> active = new HashMap<>();
  private final Map<String, JSONObject> catalog =
      new LinkedHashMap<String, JSONObject>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JSONObject> entry) {
          return size() > 12000;
        }
      };

  private MusicStore(Context c) {
    prefs = c.getSharedPreferences("library", Context.MODE_PRIVATE);
    desktop = new DesktopPlaylists(c, this);
    device = prefs.getString("device", UUID.randomUUID().toString());
    prefs.edit().putString("device", device).apply();
    try {
      ConnectivityManager network = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
      SourceHealthTracker health = new SourceHealthTracker(SystemClock::elapsedRealtime,
          System::currentTimeMillis, c.getSharedPreferences("source-health-v1", Context.MODE_PRIVATE),
          () -> {
            NetworkCapabilities caps = network == null ? null : network.getNetworkCapabilities(network.getActiveNetwork());
            return canReachMusicNetwork(caps);
          });
      sources = new LxSources(prefs, c.getSharedPreferences("resolved-url-cache", Context.MODE_PRIVATE), health);
      tracks = new JSONObject(prefs.getString("tracks", "{}"));
      for (JSONObject track : legacyTracks())
        if ("local".equals(track.optString("source"))) local.put(localId(track), track);
    } catch (JSONException e) {
      throw new IllegalStateException("曲库无法读取", e);
    }
  }

  static boolean canReachMusicNetwork(NetworkCapabilities caps) {
    return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
  }

  static JSONObject obj(Object... pairs) throws JSONException {
    JSONObject o = new JSONObject();
    for (int i = 0; i < pairs.length; i += 2) o.put((String) pairs[i], pairs[i + 1]);
    return o;
  }

  static byte[] read(InputStream in, int limit) throws IOException {
    if (in == null) throw new IOException("文件无法读取");
    try (InputStream stream = in;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = stream.read(buf)) != -1) {
        if (out.size() + n > limit) throw new IOException("文件超过大小限制");
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  static String text(InputStream in, int limit) throws IOException {
    return new String(read(in, limit), StandardCharsets.UTF_8);
  }

  static String platformName(String p) {
    switch (p) {
      case "wy":
        return "网易云音乐";
      case "kg":
        return "酷狗音乐";
      case "kw":
        return "酷我音乐";
      case "tx":
        return "QQ 音乐";
      case "mg":
        return "咪咕音乐";
      case "local":
        return "本地文件";
      default:
        return p;
    }
  }

  private void save() {
    prefs.edit().putString("tracks", tracks.toString()).apply();
  }

  private void stamp(JSONObject t) throws JSONException {
    t.put("time", Math.max(System.currentTimeMillis(), t.optLong("time") + 1));
    t.put("device", device);
  }

  synchronized List<JSONObject> legacyTracks() throws JSONException {
    List<JSONObject> out = new ArrayList<>();
    for (Iterator<String> it = tracks.keys(); it.hasNext(); ) {
      JSONObject track = tracks.getJSONObject(it.next());
      if (!track.optBoolean("deleted")) out.add(track);
    }
    return out;
  }

  private static String localId(JSONObject track) {
    return "android_local_"
        + UUID.nameUUIDFromBytes(track.optString("id").getBytes(StandardCharsets.UTF_8));
  }

  synchronized JSONObject deviceLocal(String id) {
    return local.get(id);
  }

  synchronized JSONObject track(String id) throws JSONException {
    if (id.startsWith("shared:")) {
      try {
        return desktop.track(id);
      } catch (JSONException missing) {
        if (!active.containsKey(id)) throw missing;
      }
    }
    JSONObject track = catalog.get(id);
    if (track != null) return track;
    if (active.containsKey(id)) return active.get(id);
    return tracks.getJSONObject(id);
  }

  synchronized void rememberQueue(Map<String, JSONObject> queue) {
    active.clear();
    active.putAll(queue);
  }

  synchronized JSONObject queuedTrack(String id) {
    return active.get(id);
  }

  JSONObject summary(JSONObject track) throws JSONException {
    JSONObject row = sharedRow(track);
    return obj(
        "id",
        track.getString("id"),
        "key",
        row.getString("id"),
        "name",
        track.getString("name"),
        "artist",
        track.optString("artist"),
        "platform",
        track.optString("platform", platformName(track.optString("source"))),
        "cover",
        CatalogEngine.art(DesktopPlaylists.picture(row)),
        "duration",
        track.optString("duration"),
        "fav",
        desktop.loved(row),
        "playable",
        track.optBoolean("playable", true));
  }

  synchronized JSONArray catalogSongs(JSONArray rows) throws JSONException {
    JSONArray result = new JSONArray();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      String id = "catalog:" + row.getString("id");
      JSONObject track = DesktopPlaylists.convert(id, row);
      catalog.put(id, track);
      result.put(summary(track));
    }
    return result;
  }

  JSONObject sharedRow(JSONObject track) throws JSONException {
    JSONObject existing = track.optJSONObject("desktopRow");
    if (existing != null) {
      JSONObject music = track.optJSONObject("music");
      JSONObject meta = existing.optJSONObject("meta");
      if (music == null || meta == null) return existing;
      JSONObject qualities = mergedQualities(meta, music);
      if (qualities.length() == 0) return existing;
      JSONObject copy = new JSONObject(existing.toString());
      copy.getJSONObject("meta").put("qualitys", qualities);
      return copy;
    }
    String platform = track.getString("source");
    boolean onDevice = "local".equals(platform);
    String id;
    JSONObject meta =
        obj("createTime", track.optLong("time"), "updateTime", 0, "posTime", 0, "albumName", "");
    if (onDevice) {
      id = localId(track);
      meta.put("filePath", "")
          .put("ext", "")
          .put("sizeStr", "")
          .put("deviceId", device)
          .put("musicId", id)
          .put("bitrateLabel", JSONObject.NULL);
    } else {
      JSONObject info = track.getJSONObject("music");
      String song = info.optString("songmid");
      id = info.optString("id");
      if (!id.startsWith(platform + "_")) id = platform + "_" + song;
      meta.put("source", platform).put("musicId", song);
      for (String field :
          Arrays.asList(
              "albumName",
              "albumId",
              "albumMid",
              "songId",
              "strMediaMid",
              "hash",
              "copyrightId",
              "lrcUrl",
              "mrcUrl",
              "trcUrl",
              "picUrl",
              "qualitys",
              "_qualitys")) if (!info.isNull(field)) meta.put(field, info.get(field));
      if (!meta.has("picUrl") && !info.isNull("img")) meta.put("picUrl", info.get("img"));
      JSONObject qualities = mergedQualities(info.optJSONObject("meta"), info);
      if (qualities.length() > 0) meta.put("qualitys", qualities);
    }
    String duration = track.optString("duration");
    return obj(
        "id",
        id,
        "name",
        track.getString("name"),
        "singer",
        track.optString("artist"),
        "interval",
        duration.isEmpty() ? JSONObject.NULL : duration,
        "isLocal",
        onDevice,
        "meta",
        meta);
  }

  private static JSONObject mergedQualities(JSONObject... records) throws JSONException {
    JSONObject merged = new JSONObject();
    for (JSONObject record : records) {
      if (record == null) continue;
      for (String field : Arrays.asList("types", "qualitys", "_qualitys", "_types")) {
        Object value = record.opt(field);
        if (value instanceof JSONObject) {
          JSONObject map = (JSONObject) value;
          for (Iterator<String> it = map.keys(); it.hasNext(); ) {
            String type = it.next();
            if (!map.isNull(type)) mergeQuality(merged, type, map.optJSONObject(type));
          }
        } else if (value instanceof JSONArray) {
          JSONArray list = (JSONArray) value;
          for (int i = 0; i < list.length(); i++) {
            JSONObject details = list.optJSONObject(i);
            if (!list.isNull(i)) mergeQuality(merged,
                details == null ? list.optString(i) : details.optString("type"), details);
          }
        }
      }
    }
    return merged;
  }

  private static void mergeQuality(JSONObject merged, String type, JSONObject details) throws JSONException {
    if (type.isEmpty()) return;
    JSONObject entry = merged.optJSONObject(type);
    if (entry == null) {
      entry = new JSONObject();
      merged.put(type, entry);
    }
    if (details == null) return;
    for (Iterator<String> it = details.keys(); it.hasNext(); ) {
      String key = it.next();
      if (!details.isNull(key) && !"".equals(details.opt(key))) entry.put(key, details.get(key));
    }
    if (!entry.has("size") && entry.has("sizeStr")) entry.put("size", entry.get("sizeStr"));
    if (!entry.has("sizeStr") && entry.opt("size") instanceof String) entry.put("sizeStr", entry.get("size"));
  }

  static JSONObject fromLx(JSONObject raw) throws JSONException {
    String p = raw.getString("source");
    if (!PLATFORMS.contains(p)) throw new JSONException("不支持的歌曲平台");
    JSONObject info = new JSONObject(raw.toString());
    JSONObject meta = info.optJSONObject("meta");
    if (meta == null) meta = new JSONObject(raw.toString());
    String sid =
        raw.has("songmid")
            ? raw.getString("songmid")
            : meta.optString("songId", raw.optString("id"));
    if (sid.isEmpty()) sid = meta.optString("hash");
    if (sid.isEmpty()) throw new JSONException("歌曲缺少 ID");
    for (Iterator<String> it = meta.keys(); it.hasNext(); ) {
      String k = it.next();
      if (!info.has(k)) info.put(k, meta.get(k));
    }
    if (!meta.has("songId")) meta.put("songId", sid);
    if (!info.has("songmid")) info.put("songmid", meta.get("songId"));
    if (!info.has("img")) info.put("img", meta.optString("picUrl"));
    JSONObject qualities = mergedQualities(meta, raw);
    JSONArray types = new JSONArray();
    for (Iterator<String> it = qualities.keys(); it.hasNext(); ) {
      String type = it.next();
      types.put(new JSONObject(qualities.getJSONObject(type).toString()).put("type", type));
    }
    info.put("_types", qualities).put("types", types);
    if (!info.has("typeUrl")) info.put("typeUrl", new JSONObject());
    if (p.equals("tx") && raw.has("meta") && meta.has("id")) info.put("songId", meta.get("id"));
    info.put("meta", meta);
    String stable = raw.optString("id", p + "_" + sid);
    info.put("id", stable);
    return obj(
        "id",
        "lx:" + p + ":" + sid,
        "source",
        p,
        "name",
        raw.optString("name", sid),
        "artist",
        raw.optString("singer"),
        "duration",
        raw.optString("interval"),
        "music",
        info);
  }

  private void insert(JSONObject t) throws JSONException {
    String id = t.getString("id");
    JSONObject prev = tracks.optJSONObject(id);
    if (prev != null) {
      t.put("fav", prev.optBoolean("fav") || t.optBoolean("fav"));
      t.put("time", prev.optLong("time"));
    }
    stamp(t);
    tracks.put(id, t);
    if ("local".equals(t.optString("source"))) local.put(localId(t), t);
  }

  synchronized void add(JSONObject t) throws JSONException {
    insert(t);
    save();
  }

  JSONArray importList(InputStream in) throws Exception {
    BufferedInputStream buf = new BufferedInputStream(in);
    buf.mark(2);
    boolean gzip = buf.read() == 31 && buf.read() == 139;
    buf.reset();
    Object root = new JSONTokener(text(gzip ? new GZIPInputStream(buf) : buf, LIMIT)).nextValue();
    if (root instanceof String) root = new JSONTokener((String) root).nextValue();
    ArrayList<JSONObject> found = new ArrayList<>();
    collect(root, found, false, 0);
    if (found.isEmpty()) throw new IOException("文件中没有支持的在线歌曲");
    JSONArray rows = new JSONArray();
    for (JSONObject t : found) rows.put(sharedRow(t));
    return rows;
  }

  private static void collect(Object v, List<JSONObject> out, boolean fav, int depth)
      throws JSONException {
    if (depth > 12) return;
    if (v instanceof JSONArray) {
      JSONArray a = (JSONArray) v;
      for (int i = 0; i < a.length(); i++) collect(a.opt(i), out, fav, depth + 1);
    } else if (v instanceof JSONObject) {
      JSONObject o = (JSONObject) v;
      if (o.has("name") && PLATFORMS.contains(o.optString("source")) && !o.has("list")) {
        try {
          out.add(fromLx(o).put("fav", fav));
        } catch (JSONException ignored) {
        }
        return;
      }
      boolean loved = fav || "love".equals(o.optString("id"));
      for (String k :
          Arrays.asList("data", "list", "lists", "playList", "userList", "defaultList", "loveList"))
        if (o.has(k)) collect(o.opt(k), out, loved || k.equals("loveList"), depth + 1);
    }
  }
}
