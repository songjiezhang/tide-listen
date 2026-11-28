package io.github.anylisten.midnight;

import android.content.SharedPreferences;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded LRU cache of resolved playable track URLs with optional persistence across restarts.
 * Entries are keyed by stable track identity + preferred quality and record the concrete
 * source revision, platform, songMid, and quality used to construct safe media disk cache keys.
 */
final class ResolvedUrlCache {
  static final long DEFAULT_TTL_MS = TimeUnit.HOURS.toMillis(2);
  private static final int MAX_ENTRIES = 160;
  private static final int MAX_QUALITY_HINTS = 256;
  private static final String PREF_ENTRIES = "entries";
  private static final String PREF_QUALITIES = "lastQualities";

  interface Clock {
    long now();
  }

  interface Storage {
    String read(String key);

    void write(String key, String value);
  }

  static final class Entry {
    final String sourceId;
    final int sourceRevision;
    final String actualPlatform;
    final String actualSongMid;
    final String actualQuality;
    final String url;
    final long expiresAt;
    final String generation;
    final String resourceUrl;
    final String etag;

    Entry(
        String sourceId,
        int sourceRevision,
        String actualPlatform,
        String actualSongMid,
        String actualQuality,
        String url,
        long expiresAt) {
      this(sourceId, sourceRevision, actualPlatform, actualSongMid, actualQuality, url, expiresAt,
          java.util.UUID.randomUUID().toString(), "", "");
    }

    private Entry(String sourceId, int sourceRevision, String actualPlatform, String actualSongMid,
        String actualQuality, String url, long expiresAt, String generation, String resourceUrl, String etag) {
      this.generation = generation;
      this.resourceUrl = resourceUrl;
      this.etag = etag;
      this.sourceId = sourceId == null ? "" : sourceId;
      this.sourceRevision = sourceRevision;
      this.actualPlatform = actualPlatform == null ? "" : actualPlatform;
      this.actualSongMid = actualSongMid == null ? "" : actualSongMid;
      this.actualQuality = actualQuality == null ? "" : actualQuality;
      this.url = url == null ? "" : url;
      this.expiresAt = expiresAt;
    }

    Entry validated(String resourceUrl, String etag) {
      return new Entry(sourceId, sourceRevision, actualPlatform, actualSongMid, actualQuality, url,
          expiresAt, generation, resourceUrl, etag);
    }

    Entry freshFile() {
      return new Entry(sourceId, sourceRevision, actualPlatform, actualSongMid, actualQuality, url, expiresAt);
    }

    String mediaCacheKey() {
      return MediaCache.cacheKey(sourceId, sourceRevision, actualPlatform, actualSongMid, actualQuality, url, generation);
    }
  }

  private final Clock clock;
  private final long ttlMs;
  private final Storage storage;
  private final LinkedHashMap<String, ResolvedUrlCache.Entry> entries =
      new LinkedHashMap<String, ResolvedUrlCache.Entry>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ResolvedUrlCache.Entry> eldest) {
          return size() > MAX_ENTRIES;
        }
      };
  private final LinkedHashMap<String, String> lastQualities =
      new LinkedHashMap<String, String>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
          return size() > MAX_QUALITY_HINTS;
        }
      };

  // A body failure must outlive ExoPlayer's replacement media period.
  private final LinkedHashMap<String, Long> bodyFailures = new LinkedHashMap<>();

  synchronized void bodyFailed(String key, Entry file) {
    removeIfCurrent(key, file);
    String failure = failureKey(key, file.sourceId, file.sourceRevision, file.actualPlatform);
    bodyFailures.remove(failure);
    bodyFailures.put(failure, clock.now() + TimeUnit.MINUTES.toMillis(1));
    if (bodyFailures.size() > MAX_ENTRIES) bodyFailures.remove(bodyFailures.keySet().iterator().next());
  }

  synchronized boolean recovering(String key, String id, int revision, String platform) {
    String failure = failureKey(key, id, revision, platform);
    Long until = bodyFailures.get(failure);
    if (until == null) return false;
    if (clock.now() < until) return true;
    bodyFailures.remove(failure);
    return false;
  }

  private static String failureKey(String key, String id, int revision, String platform) {
    return key + "::" + id + "::" + revision + "::" + platform;
  }

  ResolvedUrlCache() {
    this(System::currentTimeMillis, DEFAULT_TTL_MS, null);
  }

  ResolvedUrlCache(SharedPreferences prefs) {
    this(
        System::currentTimeMillis,
        DEFAULT_TTL_MS,
        prefs == null
            ? null
            : new Storage() {
              @Override
              public String read(String key) {
                return prefs.getString(key, "");
              }

              @Override
              public void write(String key, String value) {
                prefs.edit().putString(key, value).apply();
              }
            });
  }

  ResolvedUrlCache(Clock clock, long ttlMs) {
    this(clock, ttlMs, null);
  }

  ResolvedUrlCache(Clock clock, long ttlMs, Storage storage) {
    this.clock = clock;
    this.ttlMs = ttlMs;
    this.storage = storage;
    loadFromStorage();
  }

  static String stableSongKey(String platform, String songMid, String fallbackId) {
    String p = platform == null ? "" : platform.trim();
    String mid = songMid == null ? "" : songMid.trim();
    if (!p.isEmpty() && !mid.isEmpty()) return p + ":" + mid;
    return fallbackId == null ? "" : fallbackId.trim();
  }

  static String stableSongKey(JSONObject track) {
    if (track == null) return "";
    String platform = track.optString("source", "");
    JSONObject music = track.optJSONObject("music");
    String songMid = music == null ? "" : music.optString("songmid", "");
    if (songMid.isEmpty() && music != null) songMid = music.optString("hash", "");
    return stableSongKey(platform, songMid, track.optString("id", ""));
  }

  static String cacheKey(JSONObject track, String preferredQuality) {
    return stableSongKey(track) + "::" + (preferredQuality == null ? "auto" : preferredQuality);
  }

  synchronized Entry get(String key) {
    Entry entry = entries.get(key);
    if (entry == null) return null;
    return clock.now() < entry.expiresAt ? entry : null;
  }

  // The disk LRU owns the audio lifetime. Its bounded index survives URL expiration.
  synchronized Entry indexedFile(String key) {
    return entries.get(key);
  }

  synchronized void put(String key, Entry entry) {
    entries.put(key, entry);
    persistEntries();
  }

  synchronized void removeIfCurrent(String key, Entry expected) {
    Entry current = entries.get(key);
    if (current != null && current.generation.equals(expected.generation)) {
      entries.remove(key);
      persistEntries();
    }
  }

  synchronized void put(
      String key,
      String sourceId,
      int sourceRevision,
      String actualPlatform,
      String actualSongMid,
      String actualQuality,
      String url) {
    put(key, new Entry(sourceId, sourceRevision, actualPlatform, actualSongMid, actualQuality,
        url, clock.now() + ttlMs));
  }

  synchronized void rememberQuality(String stableSongKey, String quality) {
    if (stableSongKey == null
        || stableSongKey.isEmpty()
        || quality == null
        || quality.isEmpty()
        || "auto".equals(quality)) return;
    String previous = lastQualities.get(stableSongKey);
    lastQualities.put(stableSongKey, quality);
    if (!quality.equals(previous)) persistQualities();
  }

  synchronized String rememberedQuality(String stableSongKey) {
    if (stableSongKey == null || stableSongKey.isEmpty()) return "";
    String quality = lastQualities.get(stableSongKey);
    return quality == null ? "" : quality;
  }

  synchronized void invalidateSource(String sourceId) {
    boolean changed = false;
    Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
    while (it.hasNext()) {
      if (sourceId.equals(it.next().getValue().sourceId)) {
        it.remove();
        changed = true;
      }
    }
    if (changed) persistEntries();
  }

  synchronized void clear() {
    entries.clear();
    bodyFailures.clear();
    lastQualities.clear();
    persistEntries();
    persistQualities();
  }

  private void loadFromStorage() {
    if (storage == null) return;
    try {
      String raw = storage.read(PREF_ENTRIES);
      if (raw != null && !raw.isEmpty()) {
        JSONArray arr = new JSONArray(raw);
        for (int i = 0; i < arr.length(); i++) {
          JSONObject row = arr.optJSONObject(i);
          if (row == null) continue;
          long expiresAt = row.optLong("e", 0);
          String k = row.optString("k", "");
          String u = row.optString("u", "");
          if (k.isEmpty() || u.isEmpty()) continue;
          entries.put(
              k,
              new Entry(
                  row.optString("s", ""),
                  row.optInt("r", 0),
                  row.optString("p", ""),
                  row.optString("m", ""),
                  row.optString("q", ""),
                  u,
                  expiresAt,
                  row.optString("g", java.util.UUID.randomUUID().toString()),
                  row.optString("f", ""),
                  row.optString("t", "")));
        }
      }
    } catch (Exception ignored) {
    }
    try {
      String rawQ = storage.read(PREF_QUALITIES);
      if (rawQ != null && !rawQ.isEmpty()) {
        JSONObject obj = new JSONObject(rawQ);
        for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
          String k = it.next();
          String q = obj.optString(k, "");
          if (!q.isEmpty()) lastQualities.put(k, q);
        }
      }
    } catch (Exception ignored) {
    }
  }

  private void persistEntries() {
    if (storage == null) return;
    try {
      JSONArray arr = new JSONArray();
      for (Map.Entry<String, Entry> item : entries.entrySet()) {
        Entry e = item.getValue();
        arr.put(
            new JSONObject()
                .put("k", item.getKey())
                .put("s", e.sourceId)
                .put("r", e.sourceRevision)
                .put("p", e.actualPlatform)
                .put("m", e.actualSongMid)
                .put("q", e.actualQuality)
                .put("u", e.url)
                .put("g", e.generation)
                .put("f", e.resourceUrl)
                .put("t", e.etag)
                .put("e", e.expiresAt));
      }
      storage.write(PREF_ENTRIES, arr.toString());
    } catch (Exception ignored) {
    }
  }

  private void persistQualities() {
    if (storage == null) return;
    try {
      JSONObject obj = new JSONObject();
      for (Map.Entry<String, String> item : lastQualities.entrySet()) {
        obj.put(item.getKey(), item.getValue());
      }
      storage.write(PREF_QUALITIES, obj.toString());
    } catch (Exception ignored) {
    }
  }
}
