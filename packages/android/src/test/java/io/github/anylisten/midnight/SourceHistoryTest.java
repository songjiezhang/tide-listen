package io.github.anylisten.midnight;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.NetworkCapabilities;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNetworkCapabilities;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class SourceHistoryTest {
  private final Context context = RuntimeEnvironment.getApplication();
  private final SharedPreferences prefs = context.getSharedPreferences("test-source-health", 0);
  private long now = TimeUnit.DAYS.toMillis(100);
  private boolean online = true;
  private static final String FAST = "fast::tx::original";
  private static final String SLOW = "slow::tx::original";
  private static final String UNKNOWN = "unknown::tx::original";

  private SourceHealthTracker restart() {
    return new SourceHealthTracker(() -> 0, () -> now, prefs, () -> online);
  }

  private List<String> rank(SourceHealthTracker health, String... keys) {
    return health.rankCandidates(Arrays.asList(keys), k -> k);
  }

  @Test public void foregroundEvidenceSurvivesRestartAndExpiresToUserOrder() {
    SourceHealthTracker h = restart();
    h.recordSuccess(SLOW, 1300);
    h.recordSuccess(FAST, 80);
    for (int i = 0; i < 4; i++) h.recordFailure(SLOW);
    h = restart();
    assertEquals(Arrays.asList(FAST, UNKNOWN, SLOW), rank(h, SLOW, UNKNOWN, FAST));
    assertEquals("tx", h.lastPlatform());
    assertEquals(Arrays.asList("slow::wy::original", "fast::wy::original"),
        rank(h, "slow::wy::original", "fast::wy::original"));
    assertEquals(Arrays.asList(UNKNOWN, "fast::tx::replacement"), rank(h, UNKNOWN, "fast::tx::replacement"));
    now += TimeUnit.DAYS.toMillis(4);
    assertEquals(Arrays.asList(SLOW, UNKNOWN, FAST), rank(h, SLOW, UNKNOWN, FAST));
    assertEquals("", restart().lastPlatform());
    assertTrue(prefs.getAll().isEmpty());
  }

  @Test public void offlineFailuresPreloadsAndInvalidatedSamplesDoNotTrainHistory() {
    SourceHealthTracker h = restart();
    h.track(SLOW, false).success(1);
    h.track(SLOW, false).failure();
    online = false;
    h.track(FAST).failure();
    assertTrue(prefs.getAll().isEmpty());
    h.track(FAST).success(90);
    SourceHealthTracker.Sample stale = h.track(SLOW);
    h.invalidateSource("slow");
    online = true;
    stale.failure();
    assertEquals(1, prefs.getAll().size());
    h.invalidateSource("fast");
    assertEquals(FAST, rank(restart(), UNKNOWN, FAST).get(0));
    h.forgetSource("fast");
    assertTrue(prefs.getAll().isEmpty());
  }

  @Test public void ignoresBadOrFutureRecordsAndRestoresHealthyOnes() {
    restart().recordSuccess(FAST, 100);
    prefs.edit().putString(SLOW, "[1,1,-1," + (now + 1000) + ",0]")
        .putString(UNKNOWN, "not-json").putInt("bad-type", 12).commit();
    assertEquals(FAST, rank(restart(), SLOW, UNKNOWN, FAST).get(0));
    assertEquals(1, prefs.getAll().size());
  }

  @Test public void internetWithoutSystemValidationCanTrainButCaptivePortalCannot() {
    NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
    assertFalse(MusicStore.canReachMusicNetwork(null));
    Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    assertTrue(MusicStore.canReachMusicNetwork(caps));
    Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    assertFalse(MusicStore.canReachMusicNetwork(caps));
  }

  @Test(timeout = 7000) public void networkLookupFailureCannotStrandTimedOutRace() {
    SourceHealthTracker h = new SourceHealthTracker(() -> 0, () -> now, prefs, () -> {
      throw new SecurityException("network state unavailable");
    });
    CountDownLatch blocked = new CountDownLatch(1);
    SourceRace<String, String> race = new SourceRace<>(Collections.singletonList(SLOW), h, k -> k,
        (k, control) -> { blocked.await(); return k; }, ignored -> {}, 50, 100);
    try {
      assertThrows(SourceRace.Failure.class, race::await);
    } finally {
      blocked.countDown();
    }
    assertEquals(Arrays.asList(UNKNOWN, SLOW), rank(h, SLOW, UNKNOWN));
    assertTrue(prefs.getAll().isEmpty());
  }

  @Test public void prewarmPicksRecentPlatformWinnerAcrossSyncAndToggle() throws Exception {
    SourceHealthTracker h = restart();
    SharedPreferences library = context.getSharedPreferences("health-library", 0);
    LxSources sources = new LxSources(library, null, h);
    JSONArray scripts = new JSONArray();
    for (String name : Arrays.asList("first", "second", "best")) {
      scripts.put(new JSONObject().put("name", name).put("enabled", true).put("script", "// " + name));
    }
    sources.installTideSources(scripts);
    LxSources.Candidate best = sources.candidates("tx").get(2);
    h.recordSuccess(best.healthKey("tx"), 100);
    sources.installTideSources(scripts);
    sources.change(best.id, "enableSource");
    sources.change(best.id, "enableSource");
    sources = new LxSources(library, null, restart());
    assertEquals(2, sources.prewarmOrder(2).size());
    assertEquals(best.id, sources.prewarmOrder(2).get(0).id);
    sources.change(best.id, "removeSource");
    assertTrue(prefs.getAll().isEmpty());
    assertEquals("first", sources.prewarmOrder(2).get(0).name);
  }
}
