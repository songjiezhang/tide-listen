package io.github.anylisten.midnight;

import static org.junit.Assert.*;

import android.content.Context;
import android.util.LruCache;
import android.webkit.WebResourceResponse;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class PlaybackServiceTest {
  private final Context context = RuntimeEnvironment.getApplication();
  private final MediaItem item = new MediaItem.Builder().setMediaId("song").build();
  private int state = Player.STATE_READY;

  private Player player() {
    return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "getCurrentMediaItem":
            case "getMediaItemAt": return item;
            case "getPlaybackState": return state;
            case "getNextMediaItemIndex": return 1;
            case "isPlaying": return state == Player.STATE_READY;
            case "getPlayWhenReady": return true;
            default: throw new AssertionError(method.getName());
          }
        });
  }

  @Test public void gaplessTransitionRecordsStartupAndSeekDoesNotCountAsRebuffer() {
    PlaybackService service = new PlaybackService();
    Player player = player();
    service.resetTiming("song");
    service.updateTiming(player);
    service.resetTiming("gapless");
    service.updateTiming(player);
    assertTrue(ReflectionHelpers.<Boolean>getField(service, "timingReadyLogged"));
    assertTrue(ReflectionHelpers.<Boolean>getField(service, "timingPlayingLogged"));
    service.recordDiscontinuity(Player.DISCONTINUITY_REASON_SEEK);
    state = Player.STATE_BUFFERING;
    service.updateTiming(player);
    state = Player.STATE_READY;
    service.updateTiming(player);
    assertEquals(0, (int) ReflectionHelpers.getField(service, "rebufferCount"));
    state = Player.STATE_BUFFERING;
    service.updateTiming(player);
    state = Player.STATE_READY;
    service.updateTiming(player);
    assertEquals(1, (int) ReflectionHelpers.getField(service, "rebufferCount"));
  }

  @Test public void repeatedCheckpointReusesPreloadAndQualityChangeReplacesIt() throws Exception {
    PlaybackService service = new PlaybackService();
    MusicStore store = MusicStore.get(context);
    JSONObject track = MusicStore.fromLx(MusicStore.obj("songmid", "unique", "source", "tx"));
    store.rememberQueue(Collections.singletonMap("song", track));
    ExecutorService worker = ReflectionHelpers.getField(service, "preloadWorker");
    CountDownLatch hold = new CountDownLatch(1);
    worker.submit(() -> { hold.await(); return null; });
    try {
      Player player = player();
      service.preloadNextTrack(player, store);
      Future<?> first = ReflectionHelpers.getField(service, "preloadRequest");
      assertNotNull(first);
      service.preloadNextTrack(player, store);
      assertSame(first, ReflectionHelpers.getField(service, "preloadRequest"));
      assertFalse(first.isCancelled());
      store.prefs.edit().putString("quality", "320k").commit();
      service.preloadNextTrack(player, store);
      assertTrue(first.isCancelled());
      assertNotSame(first, ReflectionHelpers.getField(service, "preloadRequest"));
    } finally {
      ReflectionHelpers.callInstanceMethod(service, "cancelPreload");
      hold.countDown();
      worker.shutdownNow();
    }
  }

  @Test public void expiredCoverRefreshesOnceAndPreservesConcurrentReplacement() throws Exception {
    MusicStore store = MusicStore.get(context);
    JSONObject track = MusicStore.fromLx(MusicStore.obj("songmid", "art", "source", "tx"));
    String id = track.getString("id");
    store.rememberQueue(Collections.singletonMap(id, track));
    String key = "tx:art";
    String old = "https://qq.com/old", fresh = "https://qq.com/new";
    LruCache<String, String> pictures = ReflectionHelpers.getStaticField(CatalogEngine.class, "PICTURE_URL_CACHE");
    java.util.Map<String, FutureTask<String>> pending =
        ReflectionHelpers.getStaticField(CatalogEngine.class, "PICTURE_REQUESTS");
    List<String> requests = new ArrayList<>();
    AtomicInteger resolutions = new AtomicInteger();
    OkHttpClient transport = new OkHttpClient.Builder().addInterceptor(chain -> {
      String url = chain.request().url().toString();
      requests.add(url);
      return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
          .code(old.equals(url) ? 403 : 404).message("expired")
          .body(ResponseBody.create(null, new byte[0])).build();
    }).build();
    ReflectionHelpers.setStaticField(CatalogEngine.class, "artworkHttp", transport);
    try {
      pictures.put(key, old);
      String proxy = CatalogEngine.trackArt(context, id, store.sharedRow(track));
      pending.put(key, new FutureTask<>(() -> {
        resolutions.incrementAndGet();
        pictures.put(key, fresh);
        return fresh;
      }));
      WebResourceResponse response = CatalogEngine.cover(context, proxy);
      assertEquals(404, response.getStatusCode());
      assertEquals(java.util.Arrays.asList(old, fresh), requests);
      assertEquals(1, resolutions.get());
      assertNull(pictures.get(key));
      // A concurrent resolution already replaced the expired URL before its old request failed.
      pictures.put(key, fresh);
      pending.remove(key);
      requests.clear();
      CatalogEngine.cover(context, proxy);
      assertEquals(java.util.Arrays.asList(old, fresh), requests);
    } finally {
      pictures.remove(key);
      pending.remove(key);
      ReflectionHelpers.setStaticField(CatalogEngine.class, "artworkHttp", null);
    }
  }
}
