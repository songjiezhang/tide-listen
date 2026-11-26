package io.github.anylisten.midnight;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Short-lived in-memory cache of resolved playable track URLs to avoid repeated script resolution. */
final class ResolvedUrlCache {
  static final long DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(10);
  private static final int MAX_ENTRIES = 128;

  interface Clock {
    long now();
  }

  static final class Entry {
    final String sourceId;
    final String url;
    final long expiresAt;

    Entry(String sourceId, String url, long expiresAt) {
      this.sourceId = sourceId;
      this.url = url;
      this.expiresAt = expiresAt;
    }
  }

  private final Clock clock;
  private final long ttlMs;
  private final LinkedHashMap<String, ResolvedUrlCache.Entry> entries =
      new LinkedHashMap<String, ResolvedUrlCache.Entry>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ResolvedUrlCache.Entry> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  ResolvedUrlCache() {
    this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), DEFAULT_TTL_MS);
  }

  ResolvedUrlCache(Clock clock, long ttlMs) {
    this.clock = clock;
    this.ttlMs = ttlMs;
  }

  synchronized Entry get(String key) {
    Entry entry = entries.get(key);
    if (entry == null) return null;
    if (clock.now() >= entry.expiresAt) {
      entries.remove(key);
      return null;
    }
    return entry;
  }

  synchronized void put(String key, String sourceId, String url) {
    entries.put(key, new Entry(sourceId, url, clock.now() + ttlMs));
  }

  synchronized void remove(String key, String expectedUrl) {
    Entry current = entries.get(key);
    if (current != null && (expectedUrl == null || expectedUrl.equals(current.url))) {
      entries.remove(key);
    }
  }

  synchronized void invalidateSource(String sourceId) {
    Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
    while (it.hasNext()) {
      if (sourceId.equals(it.next().getValue().sourceId)) it.remove();
    }
  }

  synchronized void clear() {
    entries.clear();
  }
}
