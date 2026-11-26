package io.github.anylisten.midnight;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class SourceRaceTest {
  private static String key(String source) {
    return source + "::wy";
  }

  @Test(timeout = 2000)
  public void fastFirstSourceAvoidsHedge() throws Exception {
    AtomicInteger started = new AtomicInteger();
    SourceRace<String, String> race = new SourceRace<>(Arrays.asList("first", "second"),
        new SourceHealthTracker(), SourceRaceTest::key, (source, control) -> {
          started.incrementAndGet();
          return source;
        }, result -> {});
    assertEquals("first", race.await());
    assertEquals(1, started.get());
  }

  @Test(timeout = 3000)
  public void hedgedWinnerClosesLateLoserWithNoHealthPenalty() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch disposed = new CountDownLatch(1);
    SourceHealthTracker health = new SourceHealthTracker();
    SourceRace<String, String> race = new SourceRace<>(Arrays.asList("slow", "fast"), health,
        SourceRaceTest::key, (source, control) -> {
          if (source.equals("slow")) {
            started.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException ignored) {
              // Imitate a transport that still returns a successfully opened stream after cancellation.
            }
          } else assertTrue(started.await(1, TimeUnit.SECONDS));
          return source;
        }, result -> {
          if (result.equals("slow")) disposed.countDown();
        }, 40, 2000);
    assertEquals("fast", race.await());
    assertTrue(disposed.await(1, TimeUnit.SECONDS));
    assertEquals(Arrays.asList("slow", "fast"),
        health.rankCandidates(Arrays.asList("slow", "fast"), SourceRaceTest::key));
  }

  @Test(timeout = 2000)
  public void allFailuresAdvanceImmediately() throws Exception {
    SourceRace<String, String> race = new SourceRace<>(Arrays.asList("first", "last"),
        new SourceHealthTracker(), SourceRaceTest::key, (source, control) -> {
          throw new IOException(source);
        }, result -> {}, 60_000, 1000);
    try {
      race.await();
      fail("expected every source to fail");
    } catch (SourceRace.Failure error) {
      assertEquals(2, error.attempts);
      assertEquals("last", error.getMessage());
    }
  }

  @Test(timeout = 2000)
  public void timeoutCancelsAndRecordsFailure() throws Exception {
    SourceHealthTracker health = new SourceHealthTracker();
    CountDownLatch canceled = new CountDownLatch(1);
    SourceRace<String, String> race = new SourceRace<>(Collections.singletonList("slow"), health,
        SourceRaceTest::key, (source, control) -> {
          control.onCancel(timedOut -> {
            if (timedOut) canceled.countDown();
          });
          canceled.await();
          return source;
        }, result -> {}, 10, 150);
    try {
      race.await();
      fail("expected timeout");
    } catch (SourceRace.Failure error) {
      assertEquals(1, error.attempts);
    }
    assertEquals(0, canceled.getCount());
    assertEquals(Arrays.asList("new", "slow"),
        health.rankCandidates(Arrays.asList("slow", "new"), SourceRaceTest::key));
  }

  @Test(timeout = 3000)
  public void raceCapsConcurrencyAndReleasesAttemptsOnCancellation() throws Exception {
    CountDownLatch started = new CountDownLatch(3);
    CountDownLatch canceled = new CountDownLatch(3);
    AtomicInteger count = new AtomicInteger();
    SourceRace<String, String> race = new SourceRace<>(Arrays.asList("one", "two", "three", "four"),
        new SourceHealthTracker(), SourceRaceTest::key, (source, control) -> {
          count.incrementAndGet();
          started.countDown();
          try {
            new CountDownLatch(1).await();
          } finally {
            canceled.countDown();
          }
          return source;
        }, result -> {}, 10, 2000);
    FutureTask<String> waiting = new FutureTask<>(race::await);
    new Thread(waiting).start();
    try {
      assertTrue(started.await(1, TimeUnit.SECONDS));
      assertEquals(3, count.get());
    } finally {
      race.cancel();
    }
    assertTrue(canceled.await(1, TimeUnit.SECONDS));
    try {
      waiting.get(1, TimeUnit.SECONDS);
      fail("expected interruption");
    } catch (ExecutionException error) {
      assertTrue(error.getCause() instanceof InterruptedException);
    }
  }

  @Test
  public void healthRanksLatencyAndCooldownAndResetsOnSuccess() {
    AtomicLong clock = new AtomicLong();
    SourceHealthTracker health = new SourceHealthTracker(clock::get);
    List<String> original = Arrays.asList("slow", "new", "fast");
    health.recordSuccess(key("slow"), 900);
    health.recordSuccess(key("fast"), 250);
    assertEquals(Arrays.asList("fast", "new", "slow"), health.rankCandidates(original, SourceRaceTest::key));
    health.recordFailure(key("fast"));
    assertEquals(Arrays.asList("slow", "new", "fast"), health.rankCandidates(original, SourceRaceTest::key));
    health.recordFailure(key("fast"));
    health.recordFailure(key("fast"));
    health.recordFailure(key("slow"));
    health.recordFailure(key("slow"));
    assertEquals(Arrays.asList("new", "slow", "fast"), health.rankCandidates(original, SourceRaceTest::key));
    clock.addAndGet(60_000);
    assertEquals(Arrays.asList("new", "slow", "fast"), health.rankCandidates(original, SourceRaceTest::key));
    health.recordSuccess(key("fast"), 300);
    health.recordFailure(key("fast"));
    assertEquals(Arrays.asList("new", "slow", "fast"), health.rankCandidates(original, SourceRaceTest::key));
    SourceHealthTracker.Sample stale = health.track(key("slow"));
    health.invalidateSource("slow");
    stale.failure();
    assertEquals(original, health.rankCandidates(original, SourceRaceTest::key));
  }

  private static final class TestEngine implements LxEnginePool.Engine {
    final CountDownLatch closed = new CountDownLatch(1);

    @Override public void close() { closed.countDown(); }
    @Override public boolean isBroken() { return closed.getCount() == 0; }
  }

  @Test(timeout = 3000)
  public void poolSharesOpeningAndReapsOnlyIdleEngines() throws Exception {
    LxEnginePool<TestEngine> pool = new LxEnginePool<>(new SourceHealthTracker(), 30);
    AtomicInteger opened = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(1);
    LxEnginePool.Open<TestEngine> open = onBroken -> {
      opened.incrementAndGet();
      ready.await();
      return new TestEngine();
    };
    LxEnginePool<TestEngine>.Lease first = pool.acquire("one", 1, open);
    LxEnginePool<TestEngine>.Lease second = pool.acquire("one", 1, open);
    ready.countDown();
    TestEngine engine = first.await();
    assertSame(engine, second.await());
    first.close();
    assertFalse(engine.closed.await(70, TimeUnit.MILLISECONDS));
    second.close();
    assertTrue(engine.closed.await(1, TimeUnit.SECONDS));
    assertEquals(1, opened.get());
  }

  @Test(timeout = 3000)
  public void revisionFatalErrorsAndRepeatedTimeoutsInvalidateOnlyCurrentEngine() throws Exception {
    SourceHealthTracker health = new SourceHealthTracker();
    LxEnginePool<TestEngine> pool = new LxEnginePool<>(health);
    java.util.concurrent.atomic.AtomicReference<Runnable> fatal = new java.util.concurrent.atomic.AtomicReference<>();
    LxEnginePool.Open<TestEngine> open = onBroken -> {
      fatal.set(onBroken);
      return new TestEngine();
    };
    try (LxEnginePool<TestEngine>.Lease old = pool.acquire("one", 1, open)) {
      TestEngine previous = old.await();
      Runnable stale = fatal.get();
      try (LxEnginePool<TestEngine>.Lease current = pool.acquire("one", 2, open)) {
        TestEngine engine = current.await();
        assertTrue(previous.isBroken());
        stale.run();
        assertFalse(engine.isBroken());
        current.recordResult(true);
        current.recordResult(false);
        current.recordResult(true);
        assertFalse(engine.isBroken());
        current.recordResult(true);
        assertTrue(engine.isBroken());
      }
      try (LxEnginePool<TestEngine>.Lease rebuilt = pool.acquire("one", 2, open)) {
        TestEngine engine = rebuilt.await();
        fatal.get().run();
        assertTrue(engine.isBroken());
      }
    }
  }

  @Test(timeout = 3000)
  public void abandonedInitializationClosesLateResultWithoutTouchingReplacement() throws Exception {
    LxEnginePool<TestEngine> pool = new LxEnginePool<>(new SourceHealthTracker());
    CountDownLatch started = new CountDownLatch(1);
    TestEngine late = new TestEngine();
    LxEnginePool<TestEngine>.Lease abandoned = pool.acquire("one", 1, onBroken -> {
      started.countDown();
      try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) {}
      return late;
    });
    assertTrue(started.await(1, TimeUnit.SECONDS));
    abandoned.close();
    try (LxEnginePool<TestEngine>.Lease next = pool.acquire("one", 1, onBroken -> new TestEngine())) {
      assertTrue(late.closed.await(1, TimeUnit.SECONDS));
      assertFalse(next.await().isBroken());
      pool.invalidate("one");
    }
  }

  @Test(timeout = 2000)
  public void skippedCandidatesPreserveHealthAndQualityLadder() throws Exception {
    SourceHealthTracker health = new SourceHealthTracker();
    SourceRace<String, String> race = new SourceRace<>(Arrays.asList("skip", "ok"), health,
        SourceRaceTest::key, (source, control) -> {
          if (source.equals("skip")) throw new SourceRace.Skipped("quality");
          return source;
        }, result -> {});
    assertEquals("ok", race.await());
    assertEquals(Arrays.asList("skip", "ok"),
        health.rankCandidates(Arrays.asList("skip", "ok"), SourceRaceTest::key));
    assertEquals(Arrays.asList("flac24bit", "flac", "wav", "320k", "192k", "128k"),
        LxSources.playbackQualities("flac24bit"));
    assertEquals(Collections.singletonList("auto"), LxSources.playbackQualities("auto"));
  }

  @Test(timeout = 2000)
  public void runtimeErrorFailsAttemptImmediatelyWithoutWaitingForTimeout() throws Exception {
    SourceHealthTracker health = new SourceHealthTracker();
    SourceRace<String, String> race = new SourceRace<>(Arrays.asList("broken", "ok"), health,
        SourceRaceTest::key, (source, control) -> {
          if (source.equals("broken")) throw new LinkageError("native init failed");
          return source;
        }, result -> {}, 60_000, 60_000);
    assertEquals("ok", race.await());
  }

  @Test(timeout = 2000)
  public void poolReportsReadyStateOnlyForLiveCurrentRevision() throws Exception {
    LxEnginePool<TestEngine> pool = new LxEnginePool<>(new SourceHealthTracker());
    assertFalse(pool.isReady("one", 1));
    try (LxEnginePool<TestEngine>.Lease lease = pool.acquire("one", 1, onBroken -> new TestEngine())) {
      lease.await();
      assertTrue(pool.isReady("one", 1));
      assertFalse(pool.isReady("one", 2));
    }
    pool.invalidate("one");
    assertFalse(pool.isReady("one", 1));
  }

  @Test
  public void preferIpv4OrdersIpv4AheadOfIpv6WithoutDroppingIpv6() throws Exception {
    java.net.InetAddress v6 = java.net.InetAddress.getByName("2001:db8::1");
    java.net.InetAddress v4 = java.net.InetAddress.getByName("198.51.100.1");
    List<java.net.InetAddress> ordered = PreferIpv4Dns.order(Arrays.asList(v6, v4));
    assertEquals(Arrays.asList(v4, v6), ordered);
    assertEquals(Collections.singletonList(v6), PreferIpv4Dns.order(Collections.singletonList(v6)));
  }

  @Test
  public void resolvedUrlCacheHonorsTtlAndSourceInvalidation() {
    AtomicLong clock = new AtomicLong(1000);
    ResolvedUrlCache cache = new ResolvedUrlCache(clock::get, 5000);
    cache.put("song1::320k", "srcA", "https://example.com/a.mp3");
    cache.put("song2::320k", "srcB", "https://example.com/b.mp3");
    assertEquals("https://example.com/a.mp3", cache.get("song1::320k").url);
    cache.invalidateSource("srcA");
    assertNull(cache.get("song1::320k"));
    assertEquals("https://example.com/b.mp3", cache.get("song2::320k").url);
    clock.addAndGet(5000);
    assertNull(cache.get("song2::320k"));
  }
}
