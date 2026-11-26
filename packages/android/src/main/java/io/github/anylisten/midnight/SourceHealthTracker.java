package io.github.anylisten.midnight;

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

  private static final long[] COOLDOWNS_MS = {0, 0, 60_000, 180_000, 300_000};
  private static final double EWMA_ALPHA = 0.4;
  private static final int LATENCY_SWAP_THRESHOLD_MS = 300;
  private final Map<String, Health> sources = new HashMap<>();
  private final Clock clock;

  SourceHealthTracker() {
    this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
  }

  SourceHealthTracker(Clock clock) {
    this.clock = clock;
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
    Health health = entry(key);
    health.latency = health.latency < 0 ? latencyMs :
        health.latency * (1 - EWMA_ALPHA) + latencyMs * EWMA_ALPHA;
    health.failures = 0;
    health.cooldownUntil = 0;
  }

  synchronized void recordFailure(String key) {
    Health health = entry(key);
    health.failures++;
    health.cooldownUntil = clock.now() + COOLDOWNS_MS[Math.min(health.failures, COOLDOWNS_MS.length - 1)];
  }

  final class Sample {
    private final String key;
    private final Health health;

    Sample(String key, Health health) {
      this.key = key;
      this.health = health;
    }

    void success(long latencyMs) {
      synchronized (SourceHealthTracker.this) {
        if (sources.get(key) == health) recordSuccess(key, latencyMs);
      }
    }

    void failure() {
      synchronized (SourceHealthTracker.this) {
        if (sources.get(key) == health) recordFailure(key);
      }
    }
  }

  synchronized Sample track(String key) {
    return new Sample(key, entry(key));
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
    Collections.sort(cooling, (a, b) ->
        Long.compare(sources.get(key.get(a)).cooldownUntil, sources.get(key.get(b)).cooldownUntil));
    healthy.addAll(degraded);
    healthy.addAll(cooling);
    return healthy;
  }
}
