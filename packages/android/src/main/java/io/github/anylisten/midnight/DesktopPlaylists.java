package io.github.anylisten.midnight;

import static io.github.anylisten.midnight.MusicStore.obj;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Materializes the latest Any Listen snapshot with unsynced edits overlaid. */
final class DesktopPlaylists {
  private final SharedPreferences cache, legacy, settings;
  private final MusicStore store;
  private boolean loaded;
  private volatile View view;
  private String scope;
  private String revision = "", raw = "";
  private JSONArray pending = new JSONArray();

  private static final class View {
    final JSONObject full;
    final JSONArray lists = new JSONArray();
    final Map<String, JSONArray> members = new LinkedHashMap<>();
    final Map<String, JSONObject> tracks = new HashMap<>();
    final Set<String> loved = new HashSet<>();

    View(MusicStore store, JSONObject full) throws JSONException {
      this.full = full;
      add(store, full.getJSONObject("loveList"), "我喜欢");
      add(store, full.getJSONObject("defaultList"), "试听列表");
      JSONArray users = full.getJSONArray("userList");
      for (int i = 0; i < users.length(); i++) add(store, users.getJSONObject(i), null);
    }

    private void add(MusicStore store, JSONObject list, String builtIn) throws JSONException {
      String id = list.getString("id");
      if (id.isEmpty() || members.containsKey(id)) throw new JSONException("歌单 ID 无效或重复");
      JSONArray input = list.getJSONArray("list"), ids = new JSONArray();
      JSONObject listMeta = list.optJSONObject("meta");
      String cover =
          listMeta == null || listMeta.isNull("pic")
              ? ""
              : CatalogEngine.art(listMeta.optString("pic"));
      String songCover = "";
      for (int i = 0; i < input.length(); i++) {
        JSONObject row = input.getJSONObject(i);
        String song = row.getString("id");
        String trackId = "shared:" + song;
        if (!tracks.containsKey(trackId)) {
          JSONObject deviceLocal = row.optBoolean("isLocal") ? store.deviceLocal(song) : null;
          JSONObject track =
              deviceLocal == null
                  ? convert(trackId, row)
                  : new JSONObject(deviceLocal.toString())
                      .put("id", trackId)
                      .put("desktopRow", row)
                      .put("platform", "本地文件")
                      .put("playable", true);
          tracks.put(trackId, track);
        }
        if ("love".equals(id)) loved.add(song);
        ids.put(trackId);
        if (songCover.isEmpty() && !"love".equals(id)) {
          String candidate = CatalogEngine.art(picture(row));
          if (!candidate.equals(cover)) songCover = candidate;
        }
      }
      members.put(id, ids);
      lists.put(
          obj(
              "id",
              id,
              "name",
              builtIn == null ? list.getString("name") : builtIn,
              "count",
              ids.length(),
              "cover",
              cover.isEmpty() ? songCover : cover,
              "coverFallback",
              cover.isEmpty() ? "" : songCover,
              "editable",
              "general".equals(list.optString("type"))));
    }
  }

  DesktopPlaylists(Context context, MusicStore store) {
    this.store = store;
    this.settings = store.prefs;
    cache = context.getSharedPreferences("playlist-library", Context.MODE_PRIVATE);
    legacy = context.getSharedPreferences("desktop-playlists", Context.MODE_PRIVATE);
  }

  private String account() {
    String tide = TideSync.scope(settings);
    if (!tide.isEmpty()) return tide;
    String address = settings.getString("dav", "");
    if (address.isEmpty()) return "";
    return address + "\n" + settings.getString("user", "");
  }

  static String serialized(JSONObject source) {
    return source.toString().replace("\\/", "/");
  }

  static String picture(JSONObject row) {
    JSONObject meta = row == null ? null : row.optJSONObject("meta");
    return meta == null || meta.isNull("picUrl") ? "" : meta.optString("picUrl");
  }

  private static String key(String scope) {
    return "account-" + okio.ByteString.encodeUtf8(scope).sha256().hex();
  }

  synchronized void loadCachedOnce() throws Exception {
    if (!loaded || !account().equals(scope)) {
      String next = account();
      JSONObject record = new JSONObject(cache.getString(key(next), "{}"));
      String snapshot = record.optString("base");
      String snapshotKey = record.optString("revision");
      if (snapshot.isEmpty() && !next.isEmpty() && next.equals(legacy.getString("origin", ""))) {
        snapshot = legacy.getString("snapshot", "");
        snapshotKey = legacy.getString("revision", "");
      }
      JSONArray edits = record.optJSONArray("pending");
      if (edits == null) edits = new JSONArray();
      View parsed = materialize(snapshot, edits);
      scope = next;
      raw = snapshot;
      revision = snapshotKey;
      pending = edits;
      view = parsed;
      loaded = true;
    }
    if (!cache.getBoolean("migrated-legacy", false)) {
      JSONArray all = new JSONArray(), loved = new JSONArray();
      for (JSONObject t : store.legacyTracks()) {
        JSONObject row = store.sharedRow(t);
        all.put(row);
        if (t.optBoolean("fav")) loved.put(row);
      }
      JSONArray edits = new JSONArray(pending.toString());
      if (all.length() != 0) edits.put(obj("action", "add", "listId", "default", "rows", all));
      if (loved.length() != 0) edits.put(obj("action", "add", "listId", "love", "rows", loved));
      save(raw, revision, edits, cache.edit().putBoolean("migrated-legacy", true));
    }
    if (!scope.isEmpty() && !scope.startsWith("tide\n") && !cache.getBoolean("adopted-device-journal", false)) {
      String offlineKey = key("");
      JSONObject offline = new JSONObject(cache.getString(offlineKey, "{}"));
      JSONArray deviceEdits = offline.optJSONArray("pending");
      JSONArray edits = new JSONArray(pending.toString());
      if (deviceEdits != null)
        for (int i = 0; i < deviceEdits.length(); i++) edits.put(deviceEdits.get(i));
      save(
          raw,
          revision,
          edits,
          cache.edit().remove(offlineKey).putBoolean("adopted-device-journal", true));
    }
  }

  private static JSONObject newList(String id, String name, String cover, boolean builtIn)
      throws JSONException {
    long now = System.currentTimeMillis();
    JSONObject meta =
        obj("playCount", 0, "playTime", 0, "createTime", now, "updateTime", 0, "posTime", 0);
    if (!builtIn) meta.put("pic", cover).put("desc", "").put("songCount", 0);
    return obj(
        "id",
        id,
        "name",
        name,
        "type",
        builtIn ? "default" : "general",
        "meta",
        meta,
        "parentId",
        JSONObject.NULL,
        "list",
        new JSONArray());
  }

  static JSONObject blank() throws JSONException {
    return obj(
        "defaultList",
        newList("default", "default", "", true),
        "loveList",
        newList("love", "love", "", true),
        "userList",
        new JSONArray());
  }

  private View materialize(String base, JSONArray edits) throws JSONException {
    JSONObject full = base.isEmpty() ? blank() : new JSONObject(base);
    for (int i = 0; i < edits.length(); i++) apply(full, edits.getJSONObject(i));
    return new View(store, full);
  }

  private static JSONObject find(JSONObject full, String id) throws JSONException {
    if ("default".equals(id)) return full.getJSONObject("defaultList");
    if ("love".equals(id)) return full.getJSONObject("loveList");
    JSONArray users = full.getJSONArray("userList");
    for (int i = 0; i < users.length(); i++) {
      JSONObject list = users.getJSONObject(i);
      if (id.equals(list.getString("id"))) return list;
    }
    return null;
  }

  private static void apply(JSONObject full, JSONObject edit) throws JSONException {
    String id = edit.getString("listId"), action = edit.getString("action");
    JSONObject list = find(full, id);
    if ("create".equals(action)) {
      if (list == null)
        full.getJSONArray("userList").put(new JSONObject(edit.getJSONObject("created").toString()));
      return;
    }
    if (list == null) return;
    if ("delete".equals(action)) {
      JSONArray users = full.getJSONArray("userList");
      for (int i = 0; i < users.length(); i++)
        if (id.equals(users.getJSONObject(i).getString("id"))) {
          users.remove(i);
          break;
        }
      return;
    }
    if ("rename".equals(action)) {
      String name = edit.getString("name");
      if (!name.equals(list.getString("name"))) {
        list.put("name", name);
        JSONObject meta = list.getJSONObject("meta");
        meta.put("updateTime", Math.max(meta.optLong("updateTime"), edit.optLong("time")));
      }
      return;
    }
    JSONArray rows = list.getJSONArray("list");
    if ("remove".equals(action)) {
      for (int i = rows.length() - 1; i >= 0; i--)
        if (edit.getString("song").equals(rows.getJSONObject(i).getString("id"))) rows.remove(i);
    } else if ("add".equals(action)) {
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < rows.length(); i++) seen.add(rows.getJSONObject(i).getString("id"));
      JSONArray add = edit.getJSONArray("rows");
      boolean prepend = edit.optBoolean("prepend");
      JSONArray output = prepend ? new JSONArray() : rows;
      for (int i = 0; i < add.length(); i++) {
        JSONObject row = add.getJSONObject(i);
        if (seen.add(row.getString("id"))) output.put(row);
      }
      if (prepend && output.length() != 0) {
        for (int i = 0; i < rows.length(); i++) output.put(rows.getJSONObject(i));
        rows = output;
        list.put("list", rows);
      }
    }
    JSONObject meta = list.getJSONObject("meta");
    if ("general".equals(list.optString("type"))) meta.put("songCount", rows.length());
  }

  private void save(
      String snapshot, String snapshotKey, JSONArray edits, SharedPreferences.Editor write)
      throws Exception {
    View next = materialize(snapshot, edits);
    String data = obj("base", snapshot, "revision", snapshotKey, "pending", edits).toString();
    if (!write.putString(key(scope), data).commit()) throw new IOException("歌单未能保存到设备");
    raw = snapshot;
    revision = snapshotKey;
    pending = edits;
    view = next;
  }

  synchronized void acceptRemote(String snapshot, String snapshotKey, int acknowledged)
      throws Exception {
    JSONArray edits = new JSONArray();
    for (int i = acknowledged; i < pending.length(); i++) edits.put(pending.get(i));
    save(snapshot, snapshotKey, edits, cache.edit());
  }

  synchronized String base(String key) {
    return key.equals(revision) ? raw : "";
  }

  synchronized boolean hasRemoteBase() {
    return !raw.isEmpty();
  }

  synchronized int tideRevision() {
    try {
      return Integer.parseInt(revision);
    } catch (NumberFormatException error) {
      return -1;
    }
  }

  synchronized int pendingCount() {
    return pending.length();
  }

  synchronized String staged(String remote, int count) throws JSONException {
    JSONObject full = remote.isEmpty() ? blank() : new JSONObject(remote);
    for (int i = 0; i < count; i++) apply(full, pending.getJSONObject(i));
    new View(store, full);
    return serialized(full);
  }

  private synchronized void enqueue(JSONObject edit) throws Exception {
    loadCachedOnce();
    JSONArray next = new JSONArray(pending.toString()).put(edit);
    save(raw, revision, next, cache.edit());
  }

  synchronized String scopeId() throws Exception {
    loadCachedOnce();
    return scope;
  }

  synchronized String create(String name, String cover, JSONArray rows) throws Exception {
    String id = UUID.randomUUID().toString();
    JSONObject list = newList(id, name, cover, false);
    if (rows.length() > 0) {
      list.put("list", rows);
      list.getJSONObject("meta").put("songCount", rows.length());
    }
    enqueue(obj("action", "create", "listId", id, "created", list));
    return id;
  }

  synchronized void saveOnline(String expectedScope, String name, String cover, JSONArray rows)
      throws Exception {
    if (!expectedScope.equals(scopeId())) throw new IOException("同步账号已变更，请重新收藏");
    create(name, cover, rows);
  }

  synchronized void delete(String id) throws Exception {
    if ("love".equals(id) || "default".equals(id)) return;
    enqueue(obj("action", "delete", "listId", id));
  }

  synchronized void rename(String id, String name) throws Exception {
    loadCachedOnce();
    JSONObject list = find(view.full, id);
    if (list == null || "default".equals(list.optString("type"))) throw new IOException("此歌单无法重命名");
    enqueue(
        obj("action", "rename", "listId", id, "name", name, "time", System.currentTimeMillis()));
  }

  synchronized void add(String id, JSONArray rows) throws Exception {
    if (rows.length() == 0) return;
    loadCachedOnce();
    if (find(view.full, id) == null) throw new IOException("歌单已被移除");
    enqueue(obj("action", "add", "listId", id, "rows", rows, "prepend", true));
  }

  synchronized void remove(String id, String songId) throws Exception {
    enqueue(obj("action", "remove", "listId", id, "song", songId));
  }

  synchronized void toggleLoved(JSONObject row) throws Exception {
    loadCachedOnce();
    if (view.loved.contains(row.getString("id"))) remove("love", row.getString("id"));
    else add("love", new JSONArray().put(row));
  }

  JSONArray lists() {
    View v = view;
    return v == null ? new JSONArray() : v.lists;
  }

  JSONArray favoriteKeys() {
    View v = view;
    return v == null ? new JSONArray() : new JSONArray(v.loved);
  }

  boolean loved(JSONObject row) {
    View v = view;
    return v != null && v.loved.contains(row.optString("id"));
  }

  JSONArray songs(String listId) throws JSONException {
    View v = view;
    JSONArray result = new JSONArray();
    JSONArray ids = v == null ? null : v.members.get(listId);
    if (ids != null)
      for (int i = 0; i < ids.length(); i++) {
        JSONObject t = v.tracks.get(ids.getString(i));
        result.put(store.summary(t));
      }
    return result;
  }

  JSONObject track(String id) throws JSONException {
    View v = view;
    JSONObject t = v == null ? null : v.tracks.get(id);
    if (t == null) throw new JSONException("歌曲已从歌单移除");
    return t;
  }

  static JSONObject convert(String id, JSONObject row) throws JSONException {
    JSONObject meta = row.optJSONObject("meta");
    String platform = meta == null ? "" : meta.optString("source");
    boolean local = row.optBoolean("isLocal");
    String name = row.getString("name"),
        singer = row.isNull("singer") ? "" : row.getString("singer");
    if (!local
        && MusicStore.PLATFORMS.contains(platform)
        && !meta.isNull("musicId")
        && !meta.getString("musicId").isEmpty()) {
      JSONObject raw =
          obj(
              "id",
              row.getString("id"),
              "name",
              name,
              "singer",
              singer,
              "source",
              platform,
              "songmid",
              meta.get("musicId"),
              "interval",
              row.isNull("interval") ? "" : row.getString("interval"),
              "meta",
              meta);
      JSONObject t = MusicStore.fromLx(raw);
      JSONObject info = t.getJSONObject("music");
      if ("tx".equals(platform) && meta.has("songId")) info.put("songId", meta.get("songId"));
      JSONObject qualities = meta.optJSONObject("qualitys");
      if (qualities != null) {
        JSONObject types = new JSONObject(qualities.toString());
        for (Iterator<String> it = types.keys(); it.hasNext(); ) {
          JSONObject q = types.optJSONObject(it.next());
          if (q != null && q.has("sizeStr")) q.put("size", q.get("sizeStr"));
        }
        info.put("types", types);
      }
      return t.put("id", id)
          .put("desktopRow", row)
          .put("platform", MusicStore.platformName(platform))
          .put("playable", true);
    }
    return obj(
        "id",
        id,
        "name",
        name,
        "artist",
        singer,
        "platform",
        local ? "其他设备的本地文件" : "需要兼容的音源",
        "playable",
        false,
        "desktopRow",
        row);
  }
}
