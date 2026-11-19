package io.github.anylisten.midnight;

import static io.github.anylisten.midnight.MusicStore.obj;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.io.IOException;
import java.util.ArrayList;
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
    JSONArray platforms = unchanged ? expected.optJSONArray("platforms") : null;
    if (platforms == null)
      platforms = LxEngine.run(context, script, null).getJSONArray("platforms");
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
      if (unchanged && row.has("platforms") && link.equals(row.optString("url")))
        return new InstallResult("音源已是最新内容 · " + row.getString("name"), false);
      String name = LxEngine.tag(script, "name", row.optString("name", fallback));
      row.put("name", name)
          .put("version", LxEngine.tag(script, "version", ""))
          .put("platforms", platforms)
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
        break;
      case "removeSource":
        list.remove(i);
        removedKey = key(id);
        break;
      case "raiseSource":
      case "lowerSource":
        int dest = action.equals("raiseSource") ? i - 1 : i + 1;
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

  static final class Candidate {
    final String name, script;

    Candidate(String name, String script) {
      this.name = name;
      this.script = script;
    }
  }

  synchronized List<Candidate> candidates(String platform) throws JSONException {
    List<Candidate> result = new ArrayList<>();
    JSONArray list = rows();
    for (int i = 0; i < list.length(); i++) {
      JSONObject row = list.getJSONObject(i);
      if (!row.getBoolean("enabled")) continue;
      JSONArray platforms = row.optJSONArray("platforms");
      if (platforms != null) {
        boolean supports = false;
        for (int k = 0; k < platforms.length(); k++)
          if (platform.equals(platforms.getString(k))) supports = true;
        if (!supports) continue;
      }
      result.add(
          new Candidate(row.getString("name"), prefs.getString(key(row.getString("id")), "")));
    }
    return result;
  }
}
