package io.github.anylisten.midnight;

import static io.github.anylisten.midnight.MusicStore.obj;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import okhttp3.HttpUrl;
import org.json.*;

/**
 * Ordered source metadata and script bodies are committed together, without entering the WebDAV
 * library.
 */
final class LxSources {
  private static final String ROWS = "lxSources";
  private final SharedPreferences prefs;

  LxSources(SharedPreferences prefs) throws JSONException {
    this.prefs = prefs;
    String script = prefs.getString("script", "");
    if (prefs.contains(ROWS) || script.isEmpty()) return;
    String id = UUID.randomUUID().toString();
    JSONObject first =
        obj(
            "id",
            id,
            "name",
            prefs.getString("scriptName", "自定义音源"),
            "version",
            LxEngine.tag(script, "version", ""),
            "enabled",
            true,
            "revision",
            1);
    prefs
        .edit()
        .putString(ROWS, new JSONArray().put(first).toString())
        .putString(key(id), script)
        .remove("script")
        .remove("scriptName")
        .apply();
  }

  private static String key(String id) {
    return "lx.js." + id;
  }

  private JSONArray rows() throws JSONException {
    return new JSONArray(prefs.getString(ROWS, "[]"));
  }

  private static int find(JSONArray rows, String field, String value) throws JSONException {
    if (!value.isEmpty()) {
      for (int i = 0; i < rows.length(); i++)
        if (value.equals(rows.getJSONObject(i).optString(field))) return i;
    }
    return -1;
  }

  synchronized JSONObject target(String id, String link) throws Exception {
    JSONArray rows = rows();
    int i = id.isEmpty() ? find(rows, "url", link) : find(rows, "id", id);
    if (i < 0 && !id.isEmpty()) throw new IOException("音源已移除");
    return i < 0 ? null : rows.getJSONObject(i);
  }

  synchronized JSONArray summary() throws JSONException {
    JSONArray list = rows();
    for (int i = 0; i < list.length(); i++) {
      JSONObject row = list.getJSONObject(i);
      row.put("linked", !row.optString("url").isEmpty());
      row.remove("url");
    }
    return list;
  }

  synchronized JSONArray exportTideSources() throws JSONException {
    JSONArray local = rows();
    JSONArray result = new JSONArray();
    for (int i = 0; i < local.length(); i++) {
      JSONObject row = local.getJSONObject(i);
      String script = prefs.getString(key(row.getString("id")), "");
      if (!script.isEmpty())
        result.put(obj("name", row.getString("name"), "url", row.optString("url"),
            "enabled", row.optBoolean("enabled"), "script", script));
    }
    return result;
  }

  void installTideSources(Context context, JSONArray sources) throws Exception {
    JSONArray before = exportTideSources();
    JSONArray existing;
    synchronized (this) { existing = rows(); }
    JSONArray records = new JSONArray();
    android.content.SharedPreferences.Editor editor = prefs.edit();
    for (int i = 0; i < sources.length(); i++) {
      JSONObject remote = sources.getJSONObject(i);
      String script = remote.getString("script");
      String url = remote.optString("url");
      if (!url.isEmpty()) LxEngine.sourceUrl(url);
      JSONObject previous = null;
      for (int j = 0; j < existing.length(); j++) {
        JSONObject row = existing.getJSONObject(j);
        if (sameScript(script.trim(), row.getString("id"))) { previous = row; break; }
      }
      boolean enabled = remote.optBoolean("enabled");
      JSONObject record = previous == null
          ? obj("id", UUID.randomUUID().toString(), "revision", 1)
          : new JSONObject(previous.toString());
      if (enabled && !record.has("qualities")) {
        JSONObject info = LxEngine.run(context, script, null, "auto");
        record.put("platforms", info.getJSONArray("platforms")).put("qualities", info.getJSONObject("qualities"));
      }
      record.put("name", remote.getString("name")).put("version", LxEngine.tag(script, "version", ""))
          .put("enabled", enabled).put("revision", record.optInt("revision") + 1);
      if (url.isEmpty()) record.remove("url"); else record.put("url", url);
      records.put(record);
      editor.putString(key(record.getString("id")), script);
    }
    synchronized (this) {
      if (!before.toString().equals(exportTideSources().toString()))
        throw new IOException("本机音源已变更，请重新确认");
      for (int i = 0; i < existing.length(); i++) {
        String id = existing.getJSONObject(i).getString("id");
        if (find(records, "id", id) < 0) editor.remove(key(id));
      }
      if (!editor.putString(ROWS, records.toString()).commit())
        throw new IOException("未能保存服务器音源");
    }
  }

  String install(Context context, JSONObject expected, String script, String fallback, String link)
      throws Exception {
    return installScript(context, expected, script, fallback, link).message;
  }

  String installFromLink(Context context, String link, JSONObject expected) throws Exception {
    return download(context, link, expected).message;
  }

  String updateAll(Context context) throws Exception {
    JSONArray list = rows();
    int updated = 0, current = 0, unlinked = 0;
    List<String> failures = new ArrayList<>();
    for (int i = 0; i < list.length(); i++) {
      JSONObject row = list.getJSONObject(i);
      String link = row.optString("url");
      if (link.isEmpty()) {
        unlinked++;
        continue;
      }
      try {
        if (download(context, link, row).changed) updated++;
        else current++;
      } catch (Exception e) {
        failures.add(row.getString("name"));
      }
    }
    if (list.length() == 0) return "尚未导入音源";
    String result = "已更新 " + updated + " · 已是最新 " + current + " · 失败 " + failures.size();
    if (unlinked > 0) result += " · 未绑定链接 " + unlinked;
    if (!failures.isEmpty()) result += "\n更新失败，已保留原版本 · " + TextUtils.join("、", failures);
    return result;
  }

  private InstallResult download(Context context, String link, JSONObject expected)
      throws Exception {
    HttpUrl url = LxEngine.sourceUrl(link);
    String address = url.toString();
    if (expected == null) expected = target("", address);
    return installScript(context, expected, LxEngine.download(url), url.host(), address);
  }

  private static final class InstallResult {
    final String message;
    final boolean changed;

    InstallResult(String message, boolean changed) {
      this.message = message;
      this.changed = changed;
    }
  }

  private InstallResult installScript(
      Context context, JSONObject expected, String script, String fallback, String link)
      throws Exception {
    if (script.startsWith("\uFEFF")) script = script.substring(1);
    String comparable = script.trim();
    boolean unchanged = expected != null && sameScript(comparable, expected.getString("id"));
    JSONObject capabilities =
        unchanged && expected.has("qualities") && expected.has("platforms")
            ? expected
            : LxEngine.run(context, script, null, "auto");
    synchronized (this) {
      JSONArray list = rows();
      String id = expected == null ? "" : expected.getString("id");
      int index = find(list, "id", id), byUrl = find(list, "url", link);
      if (expected != null
          && (index < 0
              || list.getJSONObject(index).getInt("revision") != expected.getInt("revision")))
        throw new IOException("音源已变更，请重试");
      if (byUrl >= 0 && byUrl != index) throw new IOException("另一个音源已使用此地址");
      for (int i = 0; i < list.length(); i++) {
        JSONObject existing = list.getJSONObject(i);
        if (i == index || !sameScript(comparable, existing.getString("id"))) continue;
        if (expected != null
            || (!link.isEmpty() && existing.has("url") && !link.equals(existing.optString("url"))))
          throw new IOException("列表中已有相同的音源");
        if (link.isEmpty())
          return new InstallResult("已导入此音源 · " + existing.getString("name"), false);
        index = i;
        unchanged = true;
        break;
      }
      JSONObject row =
          index < 0
              ? obj("id", UUID.randomUUID().toString(), "enabled", true)
              : list.getJSONObject(index);
      if (unchanged && row.has("qualities") && link.equals(row.optString("url")))
        return new InstallResult("音源已是最新内容 · " + row.getString("name"), false);
      String name = LxEngine.tag(script, "name", row.optString("name", fallback));
      row.put("name", name)
          .put("version", LxEngine.tag(script, "version", ""))
          .put("platforms", capabilities.getJSONArray("platforms"))
          .put("qualities", capabilities.getJSONObject("qualities"))
          .put("url", link.isEmpty() ? null : link)
          .put("revision", row.optInt("revision") + 1);
      if (index < 0) list.put(row);
      save(list, key(row.getString("id")), script);
      if (index < 0) return new InstallResult("已添加并启用 · " + name, true);
      return new InstallResult((unchanged ? "已保存更新地址 · " : "音源已更新 · ") + name, true);
    }
  }

  private boolean sameScript(String comparable, String id) {
    String stored = prefs.getString(key(id), null);
    // The preferences XML writer can append indentation after a trailing newline.
    return stored != null && comparable.equals(stored.trim());
  }

  synchronized void change(String id, String action) throws Exception {
    JSONArray list = rows();
    int i = find(list, "id", id);
    if (i < 0) throw new IOException("音源已移除");
    JSONObject row = list.getJSONObject(i);
    String removedKey = null;
    switch (action) {
      case "enableSource":
        row.put("enabled", !row.getBoolean("enabled"));
        list.remove(i);
        list.put(row);
        break;
      case "removeSource":
        list.remove(i);
        removedKey = key(id);
        break;
      case "raiseSource":
      case "lowerSource":
        if (!row.getBoolean("enabled")) return;
        int step = action.equals("raiseSource") ? -1 : 1;
        int dest = i + step;
        while (dest >= 0 && dest < list.length() && !list.getJSONObject(dest).getBoolean("enabled"))
          dest += step;
        if (dest < 0 || dest >= list.length()) return;
        list.put(i, list.getJSONObject(dest)).put(dest, row);
        break;
      default:
        throw new IllegalArgumentException(action);
    }
    save(list, removedKey, null);
  }

  private void save(JSONArray list, String scriptKey, String script) throws IOException {
    String oldRows = prefs.getString(ROWS, null);
    String oldBody = scriptKey == null ? null : prefs.getString(scriptKey, null);
    SharedPreferences.Editor edit = prefs.edit().putString(ROWS, list.toString());
    if (scriptKey != null) edit.putString(scriptKey, script);
    if (edit.commit()) return;
    // A failed commit can still change SharedPreferences in memory.
    SharedPreferences.Editor restore = prefs.edit().putString(ROWS, oldRows);
    if (scriptKey != null) restore.putString(scriptKey, oldBody);
    restore.apply();
    throw new IOException("无法保存音源，请检查可用存储空间");
  }

  // Use the same quality ladder as the desktop LX source loader.
  private static final List<String> QUALITY_ORDER =
      Arrays.asList("128k", "192k", "320k", "wav", "flac", "flac24bit", "dolby", "master");

  static List<String> playbackQualities(String preferred) {
    int index = QUALITY_ORDER.indexOf(preferred);
    if (index < 0) return java.util.Collections.singletonList("auto");
    List<String> result = new ArrayList<>();
    for (int i = index; i >= 0; i--) result.add(QUALITY_ORDER.get(i));
    return result;
  }

  static boolean includes(JSONArray values, String value) {
    if (values == null) return false;
    for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) return true;
    return false;
  }

  final class Candidate {
    final String name, script;
    final JSONObject row;
    private JSONObject qualities;

    Candidate(JSONObject row) throws JSONException {
      this.row = row;
      name = row.getString("name");
      script = prefs.getString(key(row.getString("id")), "");
      qualities = row.optJSONObject("qualities");
    }

    boolean supports(Context context, String platform, String quality) throws Exception {
      if ("auto".equals(quality)) return true;
      if (qualities == null) {
        // Old installations only recorded platform names. Inspect on first use.
        JSONObject info = LxEngine.run(context, script, null, "auto");
        qualities = info.getJSONObject("qualities");
        synchronized (LxSources.this) {
          JSONArray latest = rows();
          int index = find(latest, "id", row.getString("id"));
          if (index >= 0
              && latest.getJSONObject(index).optInt("revision") == row.optInt("revision")) {
            latest
                .getJSONObject(index)
                .put("platforms", info.getJSONArray("platforms"))
                .put("qualities", qualities);
            prefs.edit().putString(ROWS, latest.toString()).apply();
          }
        }
      }
      return includes(qualities.optJSONArray(platform), quality);
    }
  }

  synchronized List<Candidate> candidates(String platform) throws JSONException {
    List<Candidate> result = new ArrayList<>();
    JSONArray list = rows();
    for (int i = 0; i < list.length(); i++) {
      JSONObject row = list.getJSONObject(i);
      JSONArray platforms = row.optJSONArray("platforms");
      if (row.getBoolean("enabled") && (platforms == null || includes(platforms, platform)))
        result.add(new Candidate(row));
    }
    return result;
  }
}
