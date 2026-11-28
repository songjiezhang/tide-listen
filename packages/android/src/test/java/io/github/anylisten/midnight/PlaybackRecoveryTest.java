package io.github.anylisten.midnight;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache.CacheException;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class PlaybackRecoveryTest {
  private static final String TAG = "\"revision-A\"";
  private final MockWebServer server = new MockWebServer();
  private Context context;
  private MusicStore store;
  private JSONObject track;
  private String key, url;
  private LxDataSource data;
  private DataSpec song;

  @Before public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    store = MusicStore.get(context);
    JSONObject row = MusicStore.obj("id", "a", "name", "source A", "enabled", true, "revision", 1);
    store.prefs.edit().putString("lxSources", new org.json.JSONArray().put(row).toString()).commit();
    track = MusicStore.fromLx(MusicStore.obj("source", "tx", "songmid", "123", "name", "Song"));
    store.rememberQueue(Collections.singletonMap(track.getString("id"), track));
    key = ResolvedUrlCache.cacheKey(track, "auto");
    url = server.url("/audio").toString();
    store.sources.urlCache.put(key, "a", 1, "tx", "123", "128k", url);
    song = new DataSpec(Uri.parse("midnight://track/" + track.getString("id")));
    data = new LxDataSource(context, store, () -> { throw new AssertionError(); }, new OkHttpClient());
  }

  @After public void tearDown() throws Exception {
    data.close();
    server.shutdown();
    SimpleCache cache = ReflectionHelpers.getStaticField(MediaCache.class, "instance");
    if (cache != null) cache.release();
    ReflectionHelpers.setStaticField(MediaCache.class, "instance", null);
    ReflectionHelpers.setStaticField(MediaCache.class, "failed", false);
    ReflectionHelpers.setStaticField(MusicStore.class, "instance", null);
  }

  private String readAll() throws IOException {
    byte[] buffer = new byte[5];
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int count;
    while ((count = data.read(buffer, 1, 4)) != -1) bytes.write(buffer, 1, count);
    return bytes.toString("UTF-8");
  }

  private MockResponse audio(String body) {
    return new MockResponse().setBody(body).setHeader("Content-Type", "audio/mpeg").setHeader("ETag", TAG);
  }

  private void cachePrefix() throws Exception {
    server.enqueue(audio("abcdefghij"));
    assertEquals(10, data.open(song));
    byte[] bytes = new byte[3];
    assertEquals(1, data.read(bytes, 0, 3));
    assertEquals(2, data.read(bytes, 1, 2));
    assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), bytes);
    data.close();
    assertEquals(TAG, store.sources.urlCache.get(key).etag);
    assertEquals(3, MediaCache.get(context).getCachedLength(store.sources.urlCache.get(key).mediaCacheKey(), 0, 10));
    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
  }

  @Test public void cachedChunkedBodyLivesPastSixSecondOpenBudget() throws Exception {
    server.enqueue(new MockResponse().setChunkedBody("abcdefghijkl", 12)
        .throttleBody(8, 3250, TimeUnit.MILLISECONDS).setHeader("Content-Type", "audio/mpeg"));
    long start = System.nanoTime();
    data.open(song);
    assertEquals(0, data.read(new byte[1], 0, 0));
    assertEquals("abcdefghijkl", readAll());
    assertTrue(System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(6));
    assertEquals(1, server.getRequestCount());
  }

  @Test public void cachedOpenStillBoundsTimeToFirstByte() throws Exception {
    cachePrefix();
    server.enqueue(audio("fghij").setResponseCode(206).setHeader("Content-Range", "bytes 5-9/10")
        .setBodyDelay(10, TimeUnit.SECONDS));
    long start = System.nanoTime();
    assertThrows(LxDataSource.ReprepareException.class, () -> data.open(song.subrange(5)));
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue("open took " + elapsed + " ms", elapsed >= 5500 && elapsed < 9500);
  }

  @Test public void unvalidatedPartialDownloadCannotBeSplicedIntoReplacementBody() throws Exception {
    server.enqueue(new MockResponse().setBody("old music"));
    data.open(song);
    byte[] first = new byte[1];
    assertEquals(1, data.read(first, 0, 1));
    data.close();
    String previous = store.sources.urlCache.get(key).mediaCacheKey();
    server.enqueue(new MockResponse().setBody("new music"));
    data.open(song);
    assertEquals("new music", readAll());
    assertNotEquals(previous, store.sources.urlCache.get(key).mediaCacheKey());
  }

  @Test public void responseHeadersAloneCannotWin() throws Exception {
    server.enqueue(audio("AB").setBodyDelay(800, TimeUnit.MILLISECONDS));
    FutureTask<Long> open = new FutureTask<>(() -> data.open(song));
    new Thread(open).start();
    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
    assertThrows(TimeoutException.class, () -> open.get(100, TimeUnit.MILLISECONDS));
    assertEquals(2, (long) open.get(3, TimeUnit.SECONDS));
    assertEquals("AB", readAll());
  }

  @Test public void failedBodyRequiresTimeBasedRecoveryAndCannotResumeFromAnotherUrl() throws Exception {
    server.enqueue(audio("AB").setHeader("Content-Length", 20).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
    data.open(song);
    assertThrows(LxDataSource.ReprepareException.class, this::readAll);
    data.close();
    assertTrue(store.sources.urlCache.recovering(key, "a", 1, "tx"));
    store.sources.urlCache.put(key, "b", 1, "kw", "other", "320k", server.url("/other").toString());
    assertThrows(LxDataSource.ReprepareException.class, () -> data.open(song.subrange(2)));
    assertEquals(1, server.getRequestCount());
    assertEquals("b", store.sources.urlCache.get(key).sourceId);
  }

  @Test public void seekUsesPreviouslyOpenedRepresentationEvenIfGlobalUrlChanges() throws Exception {
    cachePrefix();
    store.sources.urlCache.put(key, "b", 1, "kw", "999", "flac", server.url("/new").toString());
    server.enqueue(audio("fghij").setResponseCode(206).setHeader("Content-Range", "bytes 5-9/10"));
    data.open(song.subrange(5));
    assertEquals("fghij", readAll());
    RecordedRequest range = server.takeRequest(2, TimeUnit.SECONDS);
    assertEquals("/audio", range.getPath());
    assertEquals(TAG, range.getHeader("If-Match"));
    assertEquals("bytes=5-9", range.getHeader("Range"));
    assertEquals("b", store.sources.urlCache.get(key).sourceId);
  }

  @Test public void holeRejectsErrorPageBeforeAnyBytesReachDisk() throws Exception {
    cachePrefix();
    ResolvedUrlCache.Entry entry = store.sources.urlCache.get(key);
    server.enqueue(audio("invalid").setResponseCode(206).setHeader("Content-Range", "bytes 3-9/10")
        .setHeader("Content-Type", "application/json; charset=utf-8"));
    data.open(song);
    assertThrows(LxDataSource.ReprepareException.class, this::readAll);
    data.close();
    assertEquals(3, MediaCache.get(context).getCachedLength(entry.mediaCacheKey(), 0, 10));
    assertFalse(MediaCache.get(context).isCached(entry.mediaCacheKey(), 3, 1));
    assertEquals(TAG, server.takeRequest(2, TimeUnit.SECONDS).getHeader("If-Match"));
  }

  @Test public void unchangedUrlWithNewEtagCannotFillOldSpans() throws Exception {
    cachePrefix();
    ResolvedUrlCache.Entry entry = store.sources.urlCache.get(key);
    server.enqueue(audio("NEWFILE").setResponseCode(206).setHeader("Content-Range", "bytes 3-9/10")
        .setHeader("ETag", "\"revision-B\""));
    data.open(song);
    assertThrows(LxDataSource.ReprepareException.class, this::readAll);
    data.close();
    assertFalse(MediaCache.get(context).isCached(entry.mediaCacheKey(), 3, 1));
  }

  @Test public void disablingSourceDuringCachedPrefixBlocksHoleAndLateProbeCommit() throws Exception {
    cachePrefix();
    ResolvedUrlCache.Entry entry = store.sources.urlCache.get(key);
    data.open(song);
    store.sources.change("a", "enableSource");
    assertThrows(LxDataSource.ReprepareException.class, this::readAll);
    assertFalse(store.sources.cacheIfCurrent(key, entry));
    store.sources.change("a", "enableSource");
    assertFalse(store.sources.cacheIfCurrent(key, entry));
    assertNull(store.sources.urlCache.indexedFile(key));
    assertEquals(1, server.getRequestCount());
  }

  @Test public void completedDiskFileSurvivesUrlExpiryAndProcessRestart() throws Exception {
    server.enqueue(audio("disk-only"));
    data.open(song);
    assertEquals("disk-only", readAll());
    data.close();
    android.content.SharedPreferences prefs = context.getSharedPreferences("resolved-url-cache", Context.MODE_PRIVATE);
    org.json.JSONArray entries = new org.json.JSONArray(prefs.getString("entries", ""));
    entries.getJSONObject(0).put("e", 1L);
    prefs.edit().putString("entries", entries.toString()).commit();
    ReflectionHelpers.setStaticField(MusicStore.class, "instance", null);
    store = MusicStore.get(context);
    store.rememberQueue(Collections.singletonMap(track.getString("id"), track));
    assertNull(store.sources.urlCache.get(key));
    assertNotNull(store.sources.urlCache.indexedFile(key));
    data = new LxDataSource(context, store, () -> { throw new AssertionError(); }, new OkHttpClient());
    data.open(song);
    assertEquals("disk-only", readAll());
    assertEquals(1, server.getRequestCount());
  }

  @Test public void initializationFailureFallsBackToHttp() throws Exception {
    assertTrue(new File(context.getCacheDir(), "media-cache").createNewFile());
    server.enqueue(audio("direct"));
    data.open(song);
    assertEquals("direct", readAll());
    assertNull(MediaCache.get(context));
    assertFalse(store.sources.urlCache.recovering(key, "a", 1, "tx"));
  }

  @Test public void diskFullDuringReadResumesAfterLastDeliveredByte() throws Exception {
    byte[] body = {0, 1, 2, 3, 4};
    DataSource disk = new DataSource() {
      boolean delivered;
      @Override public long open(DataSpec spec) { return body.length - spec.position; }
      @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (delivered) throw new CacheException("ENOSPC");
        delivered = true;
        buffer[offset] = 2;
        return 1;
      }
      @Override public Uri getUri() { return null; }
      @Override public void close() {}
      @Override public void addTransferListener(TransferListener listener) {}
    };
    DataSource recovering = new MediaCache.WithDiskFallback(disk, new ByteArrayDataSource(body));
    byte[] actual = new byte[3];
    assertEquals(3, recovering.open(song.subrange(2)));
    assertEquals(1, recovering.read(actual, 0, 3));
    assertEquals(2, recovering.read(actual, 1, 2));
    assertArrayEquals(new byte[]{2, 3, 4}, actual);
    recovering.close();
  }

  @Test public void persistedEntriesHintsAndRecoveryAreScopedToActualSourceRevision() throws Exception {
    AtomicLong time = new AtomicLong(100);
    Map<String, String> disk = new HashMap<>();
    ResolvedUrlCache.Storage storage = new ResolvedUrlCache.Storage() {
      public String read(String k) { return disk.get(k); }
      public void write(String k, String value) { disk.put(k, value); }
    };
    ResolvedUrlCache cache = new ResolvedUrlCache(time::get, 10, storage);
    cache.put(key, "a", 1, "kw", "alt", "320k", url);
    cache.rememberQuality("kw:alt", "320k");
    String fileKey = cache.get(key).mediaCacheKey();
    time.addAndGet(11);
    cache = new ResolvedUrlCache(time::get, 10, storage);
    assertNull(cache.get(key));
    assertEquals(fileKey, cache.indexedFile(key).mediaCacheKey());
    assertEquals("", cache.rememberedQuality("tx:123"));
    assertEquals("320k", cache.rememberedQuality("kw:alt"));
    cache.bodyFailed(key, cache.indexedFile(key));
    assertTrue(cache.recovering(key, "a", 1, "kw"));
    assertFalse(cache.recovering(key, "a", 2, "kw"));
    time.addAndGet(60_000);
    assertFalse(cache.recovering(key, "a", 1, "kw"));
  }

  @Test public void legacyAndDesktopQualityAliasesRoundTripWithoutDroppingHashes() throws Exception {
    JSONObject lx = MusicStore.obj("source", "kg", "songmid", "one", "hash", "song-hash", "name", "Live",
        "types", MusicStore.obj("flac", MusicStore.obj("hash", "flac-hash")),
        "meta", MusicStore.obj("_qualitys", MusicStore.obj("128k", MusicStore.obj("hash", "mp3-hash")),
            "qualitys", MusicStore.obj("flac", MusicStore.obj("sizeStr", "21 MB"))));
    JSONObject row = store.sharedRow(MusicStore.fromLx(lx));
    JSONObject converted = DesktopPlaylists.convert("test", row);
    JSONObject info = converted.getJSONObject("music");
    assertEquals("song-hash", info.getString("hash"));
    assertEquals(2, info.getJSONArray("types").length());
    assertEquals("21 MB", info.getJSONObject("_types").getJSONObject("flac").getString("size"));
    JSONObject exported = store.sharedRow(converted).getJSONObject("meta").getJSONObject("qualitys");
    assertEquals("flac-hash", exported.getJSONObject("flac").getString("hash"));
    assertEquals("mp3-hash", exported.getJSONObject("128k").getString("hash"));
  }

  @Test public void fallbackKeysSeparateRecordingsEvenWithoutPlatformIds() throws Exception {
    JSONObject other = new JSONObject(track.toString());
    other.getJSONObject("music").put("songmid", "456");
    assertNotEquals(LxDataSource.fallbackKey(track), LxDataSource.fallbackKey(other));
    track.getJSONObject("music").remove("songmid");
    other.getJSONObject("music").remove("songmid");
    other.getJSONObject("music").remove("id");
    track.getJSONObject("music").remove("id");
    other.getJSONObject("music").put("albumName", "Concert");
    assertNotEquals(LxDataSource.fallbackKey(track), LxDataSource.fallbackKey(other));
    track.getJSONObject("music").put("albumName", "Concert");
    track.put("duration", "04:35");
    assertNotEquals(LxDataSource.fallbackKey(track), LxDataSource.fallbackKey(other));
  }
}
