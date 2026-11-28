package io.github.anylisten.midnight;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;

/** Device-local playback history. SourceHealthTracker serializes access to this store. */
final class RecentSourceHealth {
  private static final long DAY = TimeUnit.DAYS.toMillis(1);
  private static final int LIMIT = 64;
  private static final long MAX_LATENCY_MS = TimeUnit.MINUTES.toMillis(5);
  private final SharedPreferences prefs;
  private final Map<String, Record> records = new HashMap<>();

  private static final class Record {
    double wins, attempts, latency = -1;
    long updated, lastSuccess;

    double weight(long now) {
      long age = now - updated;
      if (age < 0 || age >= 3 * DAY) return 0;
      return Math.pow(0.5, (double) age / DAY);
    }
  }

  RecentSourceHealth(SharedPreferences prefs, long now) {
    this.prefs = prefs;
    SharedPreferences.Editor cleanup = prefs.edit();
    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
      try {
        JSONArray a = new JSONArray((String) entry.getValue());
        Record r = new Record();
        r.wins = a.getDouble(0);
        r.attempts = a.getDouble(1);
        r.latency = a.getDouble(2);
        r.updated = a.getLong(3);
        r.lastSuccess = a.getLong(4);
        if (r.weight(now) > 0 && r.wins >= 0 && r.wins <= r.attempts && r.attempts > 0
            && r.attempts <= 64 && r.latency >= -1 && r.latency <= MAX_LATENCY_MS
            && r.lastSuccess >= 0 && r.lastSuccess <= r.updated) {
          records.put(entry.getKey(), r);
          continue;
        }
      } catch (JSONException | ClassCastException ignored) {
        // A malformed preference cannot prevent playback or discard other sources.
      }
      cleanup.remove(entry.getKey());
    }
    trim(cleanup);
    cleanup.apply();
  }

  void record(String key, boolean success, long latencyMs, long now) {
    Record r = records.get(key);
    if (r == null || r.weight(now) == 0) r = new Record();
    double decay = Math.min(r.weight(now), 15.0 / Math.max(1, r.attempts));
    r.wins = r.wins * decay + (success ? 1 : 0);
    r.attempts = r.attempts * decay + 1;
    if (success) {
      r.lastSuccess = now;
      if (latencyMs >= 0) {
        latencyMs = Math.min(latencyMs, MAX_LATENCY_MS);
        r.latency = r.latency < 0 ? latencyMs : 0.6 * r.latency + 0.4 * latencyMs;
      }
    }
    r.updated = now;
    records.put(key, r);
    SharedPreferences.Editor edit = prefs.edit();
    try {
      edit.putString(key, new JSONArray().put(r.wins).put(r.attempts).put(r.latency)
          .put(r.updated).put(r.lastSuccess).toString());
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    trim(edit);
    edit.apply();
  }

  private void trim(SharedPreferences.Editor edit) {
    while (records.size() > LIMIT) {
      String oldest = null;
      for (String key : records.keySet()) {
        if (oldest == null || records.get(key).updated < records.get(oldest).updated) oldest = key;
      }
      records.remove(oldest);
      edit.remove(oldest);
    }
  }

  double score(String key, long now) {
    Record r = records.get(key);
    if (r == null) return 0;
    double weight = r.weight(now);
    if (weight == 0) return 0;
    // Unknown sources are neutral. Sparse or old evidence has less influence.
    double reliability = (2 * r.wins - r.attempts) * weight / (2 + r.attempts * weight);
    double speed = r.latency < 0 ? 0 : (1000 - r.latency) / (1000 + r.latency);
    return reliability + 0.2 * speed * (r.wins * weight / (2 + r.wins * weight));
  }

  String lastPlatform(long now) {
    long latest = 0;
    String platform = "";
    for (Map.Entry<String, Record> entry : records.entrySet()) {
      Record r = entry.getValue();
      if (r.weight(now) == 0 || now - r.lastSuccess >= 3 * DAY || r.lastSuccess <= latest) continue;
      String[] parts = entry.getKey().split("::", -1);
      if (parts.length == 3 && !parts[1].isEmpty()) {
        platform = parts[1];
        latest = r.lastSuccess;
      }
    }
    return platform;
  }

  void forget(String id) {
    SharedPreferences.Editor edit = prefs.edit();
    Iterator<String> keys = records.keySet().iterator();
    while (keys.hasNext()) {
      String key = keys.next();
      if (key.startsWith(id + "::")) {
        keys.remove();
        edit.remove(key);
      }
    }
    edit.apply();
  }
}
