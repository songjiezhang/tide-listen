package io.github.anylisten.midnight;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class SourceHealthTracker {
  interface Key<T> {
    String get(T candidate);
  }

  interface Clock {
    long now();
  }

  interface NetworkState {
    boolean online();
  }

  private static final long[] COOLDOWNS_MS = {0, 0, 60_000, 180_000, 300_000};
  private static final double EWMA_ALPHA = 0.4;
  private static final int LATENCY_SWAP_THRESHOLD_MS = 300;
  private final Map<String, Health> sources = new HashMap<>();
  private final Clock clock;
  private final Clock wall;
  private final RecentSourceHealth recent;
  private final NetworkState network;

  SourceHealthTracker() {
    this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
  }

  SourceHealthTracker(Clock clock) {
    this(clock, System::currentTimeMillis, null, () -> true);
  }

  SourceHealthTracker(Clock clock, Clock wall, SharedPreferences prefs, NetworkState network) {
    this.clock = clock;
    this.wall = wall;
    this.network = network;
    recent = prefs == null ? null : new RecentSourceHealth(prefs, wall.now());
  }

  private static final class Health {
    double latency = -1;
    int failures;
    long cooldownUntil;
  }

  private Health entry(String key) {
    Health health = sources.get(key);
    if (health == null) {
      health = new Health();
      sources.put(key, health);
    }
    return health;
  }

  synchronized void recordSuccess(String key, long latencyMs) {
    recordSuccess(key, latencyMs, true);
  }

  private void recordSuccess(String key, long latencyMs, boolean playback) {
    Health health = entry(key);
    health.latency = health.latency < 0 ? latencyMs :
        health.latency * (1 - EWMA_ALPHA) + latencyMs * EWMA_ALPHA;
    health.failures = 0;
    health.cooldownUntil = 0;
    if (playback && recent != null) recent.record(key, true, latencyMs, wall.now());
  }

  synchronized void recordFailure(String key) {
    recordFailure(key, true);
  }

  private void recordFailure(String key, boolean playback) {
    Health health = entry(key);
    health.failures++;
    health.cooldownUntil = clock.now() + COOLDOWNS_MS[Math.min(health.failures, COOLDOWNS_MS.length - 1)];
    if (playback && recent != null && canRememberFailure()) recent.record(key, false, -1, wall.now());
  }

  private boolean canRememberFailure() {
    try {
      return network.online();
    } catch (RuntimeException unavailable) {
      // Some devices deny the capabilities lookup. Keep this failure local to this session.
      return false;
    }
  }

  synchronized void cachedPlayback(String key) {
    // The URL was already resolved, so its opening time is not a resolver latency sample.
    if (recent != null) recent.record(key, true, -1, wall.now());
  }

  final class Sample {
    private final String key;
    private final Health health;
    private final boolean playback;

    Sample(String key, Health health, boolean playback) {
      this.key = key;
      this.health = health;
      this.playback = playback;
    }

    void success(long latencyMs) {
      synchronized (SourceHealthTracker.this) {
        if (sources.get(key) == health) recordSuccess(key, latencyMs, playback);
      }
    }

    void failure() {
      synchronized (SourceHealthTracker.this) {
        if (sources.get(key) == health) recordFailure(key, playback);
      }
    }
  }

  synchronized Sample track(String key) {
    return track(key, true);
  }

  synchronized Sample track(String key, boolean playback) {
    return new Sample(key, entry(key), playback);
  }

  synchronized String lastPlatform() {
    return recent == null ? "" : recent.lastPlatform(wall.now());
  }

  synchronized void forgetSource(String id) {
    invalidateSource(id);
    if (recent != null) recent.forget(id);
  }

  synchronized void invalidateSource(String id) {
    Iterator<String> keys = sources.keySet().iterator();
    while (keys.hasNext()) {
      if (keys.next().startsWith(id + "::")) keys.remove();
    }
  }

  synchronized <T> List<T> rankCandidates(List<T> candidates, Key<T> key) {
    long now = clock.now();
    List<T> healthy = new ArrayList<>();
    List<T> degraded = new ArrayList<>();
    List<T> cooling = new ArrayList<>();
    List<T> sampled = new ArrayList<>();
    List<Integer> samplePositions = new ArrayList<>();
    for (T candidate : candidates) {
      Health health = sources.get(key.get(candidate));
      if (health != null && health.failures >= 2 && now < health.cooldownUntil) {
        cooling.add(candidate);
      } else if (health != null && health.failures > 0) {
        degraded.add(candidate);
      } else {
        if (health != null && health.latency >= 0) {
          int index = sampled.size();
          while (index > 0 && sources.get(key.get(sampled.get(index - 1))).latency - health.latency
              >= LATENCY_SWAP_THRESHOLD_MS) index--;
          sampled.add(index, candidate);
          samplePositions.add(healthy.size());
        }
        healthy.add(candidate);
      }
    }
    for (int i = 0; i < sampled.size(); i++) healthy.set(samplePositions.get(i), sampled.get(i));
    if (recent != null) {
      long time = wall.now();
      Collections.sort(healthy, (a, b) -> Double.compare(recent.score(key.get(b), time),
          recent.score(key.get(a), time)));
    }
    Collections.sort(cooling, (a, b) ->
        Long.compare(sources.get(key.get(a)).cooldownUntil, sources.get(key.get(b)).cooldownUntil));
    healthy.addAll(degraded);
    healthy.addAll(cooling);
    return healthy;
  }
}
