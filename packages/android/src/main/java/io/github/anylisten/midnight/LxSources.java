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
  final SourceHealthTracker health = new SourceHealthTracker();
  final ResolvedUrlCache urlCache;
  private final LxEnginePool<LxEngine> pool = new LxEnginePool<>(health);

  LxSources(SharedPreferences prefs) throws JSONException {
    this(prefs, null);
  }

  LxSources(SharedPreferences prefs, SharedPreferences urlCachePrefs) throws JSONException {
    this.prefs = prefs;
    this.urlCache = new ResolvedUrlCache(urlCachePrefs);
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

  synchronized void installTideSources(JSONArray sources) throws Exception {
    // Sync copies the reviewed scripts. Playback discovers their capabilities on this device.
    JSONArray existing = rows();
    JSONArray records = new JSONArray();
    android.content.SharedPreferences.Editor editor = prefs.edit();
    for (int i = 0; i < sources.length(); i++) {
      JSONObject remote = sources.getJSONObject(i);
      String script = remote.getString("script");
      String comparable = script.trim();
      String url = remote.optString("url");
      if (!url.isEmpty()) LxEngine.sourceUrl(url);
      JSONObject previous = null;
      for (int j = 0; j < existing.length(); j++) {
        JSONObject row = existing.getJSONObject(j);
        if (sameScript(comparable, row.getString("id"))) { previous = row; break; }
      }
      boolean enabled = remote.optBoolean("enabled");
      JSONObject record = previous == null
          ? obj("id", UUID.randomUUID().toString(), "revision", 1)
          : new JSONObject(previous.toString());
      record.put("name", remote.getString("name")).put("version", LxEngine.tag(script, "version", ""))
          .put("enabled", enabled).put("revision", record.optInt("revision") + 1);
      if (url.isEmpty()) record.remove("url"); else record.put("url", url);
      records.put(record);
      editor.putString(key(record.getString("id")), script);
    }
    for (int i = 0; i < existing.length(); i++) {
      String id = existing.getJSONObject(i).getString("id");
      if (find(records, "id", id) < 0) editor.remove(key(id));
    }
    if (!editor.putString(ROWS, records.toString()).commit())
      throw new IOException("未能保存服务器音源");
    for (int i = 0; i < existing.length(); i++) invalidate(existing.getJSONObject(i).getString("id"));
  }

  private void invalidate(String id) {
    pool.invalidate(id);
    health.invalidateSource(id);
    urlCache.invalidateSource(id);
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
      invalidate(row.getString("id"));
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
        row.put("enabled", !row.getBoolean("enabled")).put("revision", row.optInt("revision") + 1);
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
    if (action.equals("enableSource") || action.equals("removeSource")) invalidate(id);
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
  // Match lx-music-mobile default (128k first for fast startup & low bandwidth) with upward fallback.
  private static final List<String> AUTO_QUALITY_ORDER =
      Arrays.asList("128k", "320k", "flac", "flac24bit");

  static List<String> playbackQualities(String preferred) {
    int index = QUALITY_ORDER.indexOf(preferred);
    if (index < 0) return java.util.Collections.singletonList("auto");
    List<String> result = new ArrayList<>();
    for (int i = index; i >= 0; i--) result.add(QUALITY_ORDER.get(i));
    return result;
  }

  /**
   * Builds the concrete quality order for playback, prioritizing qualities declared by the song's
   * metadata (matching lx-music-mobile's getPlayQuality) while keeping fallbacks available.
   */
  static List<String> resolveQualities(String preferred, JSONObject music) {
    return resolveQualities(preferred, music, null);
  }

  static List<String> resolveQualities(
      String preferred, JSONObject music, String rememberedQuality) {
    return resolveQualitiesForDeclared(preferred, declaredQualities(music), rememberedQuality);
  }

  static List<String> resolveQualitiesForDeclared(
      String preferred, java.util.Set<String> declared) {
    return resolveQualitiesForDeclared(preferred, declared, null);
  }

  static List<String> resolveQualitiesForDeclared(
      String preferred, java.util.Set<String> declared, String rememberedQuality) {
    boolean auto = "auto".equals(preferred) || !QUALITY_ORDER.contains(preferred);
    List<String> base =
        auto ? new ArrayList<>(AUTO_QUALITY_ORDER) : playbackQualities(preferred);
    List<String> ordered;
    if (declared == null || declared.isEmpty()) {
      ordered = new ArrayList<>(base);
    } else {
      ordered = new ArrayList<>();
      for (String q : base) {
        if (declared.contains(q)) ordered.add(q);
      }
      // Always keep 128k as a final safety fallback even if metadata omitted it.
      if (!ordered.contains("128k")) ordered.add("128k");
      if (ordered.isEmpty()) ordered = new ArrayList<>(base);
    }
    if (auto
        && rememberedQuality != null
        && !rememberedQuality.isEmpty()
        && ordered.contains(rememberedQuality)) {
      ordered.remove(rememberedQuality);
      ordered.add(0, rememberedQuality);
    }
    return ordered;
  }

  private static java.util.Set<String> declaredQualities(JSONObject music) {
    java.util.Set<String> set = new java.util.LinkedHashSet<>();
    if (music == null) return set;
    collectQualityKeys(set, music.opt("_types"));
    collectQualityKeys(set, music.opt("types"));
    JSONObject meta = music.optJSONObject("meta");
    if (meta != null) {
      collectQualityKeys(set, meta.opt("_qualitys"));
      collectQualityKeys(set, meta.opt("qualitys"));
    }
    return set;
  }

  private static void collectQualityKeys(java.util.Set<String> out, Object raw) {
    if (raw instanceof JSONObject) {
      JSONObject obj = (JSONObject) raw;
      for (java.util.Iterator<String> it = obj.keys(); it.hasNext(); ) {
        String key = it.next();
        if (!obj.isNull(key) && QUALITY_ORDER.contains(key)) out.add(key);
      }
    } else if (raw instanceof JSONArray) {
      JSONArray arr = (JSONArray) raw;
      for (int i = 0; i < arr.length(); i++) {
        JSONObject item = arr.optJSONObject(i);
        if (item != null) {
          String type = item.optString("type");
          if (QUALITY_ORDER.contains(type)) out.add(type);
        } else {
          String type = arr.optString(i);
          if (QUALITY_ORDER.contains(type)) out.add(type);
        }
      }
    }
  }

  static boolean includes(JSONArray values, String value) {
    if (values == null) return false;
    for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) return true;
    return false;
  }

  final class Candidate {
    final String id, name, script;
    final int revision;
    private volatile JSONObject qualities;

    Candidate(JSONObject row) throws JSONException {
      id = row.getString("id");
      name = row.getString("name");
      revision = row.optInt("revision");
      script = prefs.getString(key(id), "");
      qualities = row.optJSONObject("qualities");
    }

    boolean maySupport(String platform, String quality) {
      JSONObject known = qualities;
      if (known == null) return true;
      JSONArray types = known.optJSONArray(platform);
      return types != null && ("auto".equals(quality) || includes(types, quality));
    }

    void ensureCurrent() throws JSONException, SourceRace.Skipped {
      JSONArray latest = rows();
      int index = find(latest, "id", id);
      if (index < 0 || latest.getJSONObject(index).optInt("revision") != revision
          || !latest.getJSONObject(index).optBoolean("enabled"))
        throw new SourceRace.Skipped("音源已变更");
    }

    boolean isWarm() {
      return pool.isReady(id, revision);
    }

    LxEnginePool<LxEngine>.Lease acquire(Context context) throws Exception {
      synchronized (LxSources.this) {
        ensureCurrent();
        return pool.acquire(id, revision, onBroken -> LxEngine.open(context.getApplicationContext(), script, onBroken));
      }
    }

    void cacheCapabilities(JSONObject info) throws JSONException {
      if (qualities == null) rememberCapabilities(info);
    }

    private void rememberCapabilities(JSONObject info) throws JSONException {
      qualities = info.getJSONObject("qualities");
      synchronized (LxSources.this) {
        JSONArray latest = rows();
        int index = find(latest, "id", id);
        if (index >= 0 && latest.getJSONObject(index).optInt("revision") == revision) {
          latest.getJSONObject(index).put("platforms", info.getJSONArray("platforms")).put("qualities", qualities);
          prefs.edit().putString(ROWS, latest.toString()).apply();
        }
      }
    }
  }

  String testSource(Context context, String id) throws Exception {
    Candidate candidate;
    synchronized (this) {
      JSONObject row = target(id, "");
      if (row == null) throw new IOException("音源已移除");
      candidate = new Candidate(row);
    }
    try {
      candidate.rememberCapabilities(LxEngine.run(context, candidate.script, null, "auto"));
      synchronized (this) {
        JSONArray latest = rows();
        int index = find(latest, "id", id);
        if (index >= 0 && latest.getJSONObject(index).optInt("revision") == candidate.revision) health.invalidateSource(id);
      }
      return candidate.name + " · 初始化成功";
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw error;
    } catch (Exception error) {
      return candidate.name + " · 初始化失败 · " +
          (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }
  }

  void prewarm(Context context, int limit) {
    List<Candidate> enabled = new ArrayList<>();
    synchronized (this) {
      try {
        JSONArray list = rows();
        for (int i = 0; i < list.length() && enabled.size() < limit; i++) {
          JSONObject row = list.getJSONObject(i);
          if (row.optBoolean("enabled")) enabled.add(new Candidate(row));
        }
      } catch (JSONException ignored) {
        return;
      }
    }
    for (Candidate candidate : enabled) {
      if (Thread.currentThread().isInterrupted()) return;
      if (candidate.isWarm()) continue;
      try (LxEnginePool<LxEngine>.Lease lease = candidate.acquire(context)) {
        LxEngine engine = lease.await();
        candidate.cacheCapabilities(engine.capabilities());
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception ignored) {
      }
    }
  }

  synchronized boolean cacheIfCurrent(String key, ResolvedUrlCache.Entry entry) {
    return cacheIfCurrent(key, entry, null);
  }

  synchronized boolean cacheIfCurrent(String key, ResolvedUrlCache.Entry entry, ResolvedUrlCache.Entry previous) {
    if (!isEnabledAtRevision(entry.sourceId, entry.sourceRevision)) return false;
    ResolvedUrlCache.Entry current = urlCache.indexedFile(key);
    if (previous == null || (current != null && previous.generation.equals(current.generation))) {
      urlCache.put(key, entry);
    }
    return true;
  }

  synchronized boolean isEnabledAtRevision(String id, int revision) {
    if (id == null || id.isEmpty()) return false;
    try {
      JSONArray list = rows();
      int index = find(list, "id", id);
      if (index < 0) return false;
      JSONObject row = list.getJSONObject(index);
      return row.optBoolean("enabled") && row.optInt("revision") == revision;
    } catch (JSONException ignored) {
      return false;
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
