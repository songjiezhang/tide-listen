package io.github.anylisten.midnight;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.*;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.*;

/** Passes the first valid media stream at the selected quality to ExoPlayer. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
final class LxDataSource implements DataSource {
  private static final String TAG = "LxTiming";
  // Match lx-music-mobile playList.ts Android mobile UA for media stream GETs (separate from script API UA).
  static final String MEDIA_USER_AGENT =
      "Mozilla/5.0 (Linux; Android 10; Pixel 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.79 Mobile Safari/537.36";
  private static final long WARM_ATTEMPT_TIMEOUT_MS = 12_000;
  private static final long COLD_ATTEMPT_TIMEOUT_MS = 16_000;
  private static final long CACHED_URL_TIMEOUT_MS = 6_000;
  private static final long FALLBACK_SEARCH_TIMEOUT_MS = 6_000;
  private static final long FALLBACK_TOTAL_TIMEOUT_MS = 18_000;
  private static final long FALLBACK_HEDGE_STAGGER_MS = 1_500;
  private static final int FALLBACK_MAX_CONCURRENT_PLATFORMS = 2;
  private static final long FALLBACK_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(15);
  private static final int FALLBACK_CACHE_MAX = 64;

  private static final ExecutorService FALLBACK_SEARCH_POOL =
      Executors.newCachedThreadPool(
          task -> {
            Thread thread = new Thread(task, "LX fallback search");
            thread.setDaemon(true);
            return thread;
          });
  private static final ExecutorService FALLBACK_OPEN_POOL =
      Executors.newCachedThreadPool(
          task -> {
            Thread thread = new Thread(task, "LX fallback open");
            thread.setDaemon(true);
            return thread;
          });

  private static final class CachedFallbackList {
    final List<FallbackMatch> matches;
    final long expiresAt;

    CachedFallbackList(List<FallbackMatch> matches, long expiresAt) {
      this.matches = matches;
      this.expiresAt = expiresAt;
    }
  }

  private static final LinkedHashMap<String, CachedFallbackList> FALLBACK_CACHE =
      new LinkedHashMap<String, CachedFallbackList>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedFallbackList> eldest) {
          return size() > FALLBACK_CACHE_MAX;
        }
      };

  static final class ExhaustedException extends IOException {
    ExhaustedException(String message) {
      super(message);
    }

    static ExhaustedException find(Throwable error) {
      for (; error != null; error = error.getCause()) {
        if (error instanceof ExhaustedException) return (ExhaustedException) error;
      }
      return null;
    }
  }

  private static final class CacheMiss extends IOException {
    static boolean caused(Throwable error) {
      for (; error != null; error = error.getCause()) if (error instanceof CacheMiss) return true;
      return false;
    }
  }

  static final class ReprepareException extends IOException {
    ReprepareException() { super("音频文件已变更，需按播放时间重新加载"); }
  }

  private final Context context;
  private final MusicStore store;
  private final DataSource.Factory upstreamFactory;
  private final OkHttpClient onlineHttp;
  private final List<TransferListener> transferListeners = new ArrayList<>();
  private volatile DataSource activeUpstream;
  private Stream activeStream;
  private OpenRequest opening;
  // ProgressiveMediaPeriod reuses this DataSource for seeks and byte-range retries.
  private ResolvedUrlCache.Entry pinnedFile;
  private Uri pinnedTrack;
  private String pinnedKey;
  private boolean online;
  private long nextNetworkCheck;

  private void checkNetwork() throws IOException {
    if (!store.prefs.getBoolean("unmetered", false)) return;
    android.net.ConnectivityManager network =
        (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (network == null || network.isActiveNetworkMetered())
      throw new ExhaustedException("已开启「仅在非计费网络播放」，请连接 Wi-Fi 或调整网络设置");
  }

  private static final class OpenRequest {
    final List<SourceRace<LxSources.Candidate, Stream>> races = new ArrayList<>();
    private StreamCall cachedCall;

    synchronized void setCachedCall(StreamCall call) {
      if (canceled) call.cancel();
      else cachedCall = call;
    }

    synchronized void clearCachedCall(StreamCall call) {
      if (cachedCall == call) cachedCall = null;
    }

    FallbackRace<FallbackMatch, Stream> fallback;
    synchronized void setFallback(FallbackRace<FallbackMatch, Stream> race) {
      if (canceled) race.close();
      else fallback = race;
    }

    final List<CancellationSignal> searchSignals = new ArrayList<>();
    boolean canceled;

    synchronized void addRace(SourceRace<LxSources.Candidate, Stream> race) {
      if (canceled) race.cancel();
      else races.add(race);
    }

    synchronized void removeRace(SourceRace<LxSources.Candidate, Stream> race) {
      races.remove(race);
    }

    synchronized void addSearchSignal(CancellationSignal signal) {
      if (canceled) signal.cancel();
      else searchSignals.add(signal);
    }

    void cancelAll() {
      List<SourceRace<LxSources.Candidate, Stream>> raceSnapshot;
      List<CancellationSignal> signalSnapshot;
      StreamCall callSnapshot;
      synchronized (this) {
        canceled = true;
        if (fallback != null) fallback.close();
        raceSnapshot = new ArrayList<>(races);
        races.clear();
        signalSnapshot = new ArrayList<>(searchSignals);
        searchSignals.clear();
        callSnapshot = cachedCall;
        cachedCall = null;
      }
      for (CancellationSignal signal : signalSnapshot) signal.cancel();
      for (SourceRace<LxSources.Candidate, Stream> race : raceSnapshot) race.cancel();
      if (callSnapshot != null) callSnapshot.cancel();
    }
  }

  private static final class PlatformAttemptResult {
    final Stream stream;
    final int tried;
    final boolean hadCandidates;

    PlatformAttemptResult(Stream stream, int tried, boolean hadCandidates) {
      this.stream = stream;
      this.tried = tried;
      this.hadCandidates = hadCandidates;
    }
  }

  private static final class FallbackMatch {
    final String platform;
    final JSONObject music;

    FallbackMatch(String platform, JSONObject music) {
      this.platform = platform;
      this.music = music;
    }
  }

  private static final class Stream {
    final DataSource source;
    final StreamCall call;
    final DataSpec spec;
    final long length;
    final ResolvedUrlCache.Entry file;
    final String sourceName, urlCacheKey;
    final byte firstByte;
    boolean firstPending;
    long delivered;
    final List<TransferListener> listeners = new ArrayList<>();

    Stream(DataSource source, StreamCall call, DataSpec spec, long length,
        ResolvedUrlCache.Entry file, String sourceName, String urlCacheKey, byte firstByte) {
      this.firstByte = firstByte;
      this.firstPending = length != 0;
      this.source = source;
      this.call = call;
      this.spec = spec;
      this.length = length;
      this.file = file;
      this.sourceName = sourceName;
      this.urlCacheKey = urlCacheKey;
    }

    int read(byte[] buffer, int offset, int size) throws IOException {
      if (size == 0) return 0;
      int count;
      if (firstPending) {
        firstPending = false;
        buffer[offset] = firstByte;
        count = 1;
      } else {
        count = source.read(buffer, offset, size);
      }
      if (count < 0 && length >= 0 && delivered < length) throw new java.io.EOFException("音频正文提前结束");
      if (count > 0) delivered += count;
      return count;
    }
  }

  static final class StreamCall implements Call.Factory {
    private final OkHttpClient http;
    private Call call;
    private boolean canceled;
    private ScheduledFuture<?> deadline;

    StreamCall(OkHttpClient http) { this.http = http; }

    synchronized void limitOpen(long timeoutMs) {
      if (canceled) return;
      deadline = SourceRace.after(timeoutMs, () -> {
        synchronized (this) {
          if (deadline != null) cancel();
        }
      });
    }

    synchronized void finishOpen() throws IOException {
      if (deadline != null) deadline.cancel(false);
      deadline = null;
      if (canceled) throw new InterruptedIOException("开流超时或请求已取消");
    }

    synchronized boolean usedNetwork() {
      return call != null;
    }

    @Override public synchronized Call newCall(Request request) {
      call = http.newCall(request);
      if (canceled) call.cancel();
      return call;
    }

    synchronized void cancel() {
      if (deadline != null) deadline.cancel(false);
      deadline = null;
      canceled = true;
      if (call != null) call.cancel();
    }
  }

  LxDataSource(
      Context context,
      MusicStore store,
      DataSource.Factory upstreamFactory,
      OkHttpClient onlineHttp) {
    this.context = context;
    this.store = store;
    this.upstreamFactory = upstreamFactory;
    this.onlineHttp = onlineHttp;
  }

  @Override
  public long open(DataSpec spec) throws IOException {
    OpenRequest request = new OpenRequest();
    synchronized (this) {
      opening = request;
    }
    online = false;
    nextNetworkCheck = 0;
    try {
      if (!"midnight".equals(spec.uri.getScheme())) return openDirect(request, spec);
      JSONObject track = store.track(spec.uri.getLastPathSegment());
      if ("local".equals(track.getString("source")))
        return openDirect(request, spec.withUri(Uri.parse(track.getString("uri"))));
      online = true;
      checkNetwork();
      return openOnline(request, spec, track);
    } catch (JSONException e) {
      throw new IOException("歌曲信息无效", e);
    } catch (InterruptedException e) {
      if (Thread.currentThread().isInterrupted() || isOpening(request))
        Thread.currentThread().interrupt();
      throw new InterruptedIOException("播放请求已取消");
    } finally {
      synchronized (this) {
        if (opening == request) opening = null;
      }
    }
  }

  private synchronized boolean isOpening(OpenRequest request) {
    return opening == request;
  }

  private long openDirect(OpenRequest request, DataSpec spec)
      throws IOException, InterruptedException {
    DataSource source;
    synchronized (this) {
      if (!isOpening(request)) throw new InterruptedException();
      source = upstreamFactory.createDataSource();
      for (TransferListener listener : transferListeners) source.addTransferListener(listener);
      activeUpstream = source;
    }
    synchronized (source) {
      if (activeUpstream != source) throw new InterruptedException();
      long length = source.open(spec);
      if (activeUpstream != source) throw new InterruptedException();
      return length;
    }
  }

  private static String songMidOf(JSONObject music) {
    if (music == null) return "";
    String mid = music.optString("songmid", "");
    if (mid.isEmpty()) mid = music.optString("hash", "");
    if (mid.isEmpty()) mid = music.optString("id", "");
    return mid;
  }

  private long attachStream(OpenRequest request, Stream stream) throws InterruptedException {
    synchronized (this) {
      if (isOpening(request) && !Thread.currentThread().isInterrupted()) {
        pinnedFile = stream.file;
        pinnedKey = stream.urlCacheKey;
        activeStream = stream;
        activeUpstream = stream.source;
        stream.listeners.addAll(transferListeners);
        for (TransferListener listener : stream.listeners) {
          listener.onTransferInitializing(this, stream.spec, true);
          listener.onTransferStart(this, stream.spec, true);
        }
        return stream.length;
      }
    }
    closeQuietly(stream.source);
    throw new InterruptedException();
  }

  private Stream tryOpenCachedUrl(OpenRequest request, DataSpec spec, String key,
      ResolvedUrlCache.Entry entry) throws InterruptedException, ExhaustedException {
    if (entry == null || !store.sources.isEnabledAtRevision(entry.sourceId, entry.sourceRevision)
        || store.sources.urlCache.recovering(key, entry.sourceId, entry.sourceRevision, entry.actualPlatform)) return null;
    boolean complete = MediaCache.complete(context, entry.mediaCacheKey());
    boolean expired = System.currentTimeMillis() >= entry.expiresAt;
    if (expired && !complete) return null;
    // Without a strong validator, concurrently downloaded spans cannot establish byte identity.
    // Give each incomplete, unvalidated download its own disk generation.
    boolean diskOnly = complete && (expired || entry.etag.isEmpty());
    ResolvedUrlCache.Entry indexed = entry;
    if (!complete && entry.etag.isEmpty()) {
      entry = entry.freshFile().validated(entry.resourceUrl, "");
    }
    try {
      try {
        return openCachedFile(request, spec, key, entry, diskOnly);
      } catch (IOException error) {
        if (!diskOnly || expired || !CacheMiss.caused(error)) throw error;
        // The LRU evicted a complete, unvalidated file between checking it and opening it.
        return openCachedFile(request, spec, key, entry.freshFile().validated(entry.resourceUrl, ""), false);
      }
    } catch (IOException error) {
      if (!isOpening(request) || Thread.currentThread().isInterrupted()) throw new InterruptedException();
      ExhaustedException policy = ExhaustedException.find(error);
      if (policy != null) throw policy;
      if (spec.position > 0 && !diskOnly && !MediaCache.diskError(error)) {
        store.sources.urlCache.bodyFailed(key, indexed);
      }
      Log.d(TAG, "cached open failed pos=" + spec.position, error);
      return null;
    }
  }

  private Stream openCachedFile(OpenRequest request, DataSpec spec, String key,
      ResolvedUrlCache.Entry entry, boolean diskOnly) throws IOException, InterruptedException {
    if (!isOpening(request)) throw new InterruptedException();
    StreamCall call = fileCall(entry, diskOnly);
    request.setCachedCall(call);
    long start = SystemClock.elapsedRealtime();
    boolean opened = false;
    try {
      call.limitOpen(CACHED_URL_TIMEOUT_MS);
      Stream stream = openFile(spec, key, entry, "cache", call);
      opened = true;
      return stream;
    } finally {
      request.clearCachedCall(call);
      if (spec.position == 0 && call.usedNetwork()) {
        Log.d(TAG, "cached network open " + (opened ? "ok" : "failed")
            + " in " + (SystemClock.elapsedRealtime() - start) + "ms");
      }
    }
  }

  private StreamCall fileCall(ResolvedUrlCache.Entry file, boolean diskOnly) {
    // This guard also runs when CacheDataSource opens a network hole mid-read.
    okhttp3.Interceptor validate = new okhttp3.Interceptor() {
      private String resourceUrl = file.resourceUrl;
      private String etag = file.etag;

      @Override public Response intercept(Chain chain) throws IOException {
        if (diskOnly) throw new CacheMiss();
        if (!store.sources.isEnabledAtRevision(file.sourceId, file.sourceRevision)) {
          throw new IOException("音源已变更");
        }
        checkNetwork();
        Request request = chain.request();
        if (!etag.isEmpty()) request = request.newBuilder().header("If-Match", etag).build();
        Response response = chain.proceed(request);
        String finalUrl = response.request().url().toString();
        String currentTag = strongEtag(response.header("ETag", ""));
        if ((!resourceUrl.isEmpty() && !resourceUrl.equals(finalUrl))
            || (!etag.isEmpty() && !etag.equals(currentTag))) {
          response.close();
          throw new IOException("音频文件版本已变更");
        }
        if (response.isSuccessful()) {
          resourceUrl = finalUrl;
          etag = currentTag;
        }
        return response;
      }
    };
    return new StreamCall(onlineHttp.newBuilder().addInterceptor(validate).build());
  }

  private static String strongEtag(String tag) {
    return tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"") ? tag : "";
  }

  static boolean audioContentType(String type) {
    String mime = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    return !mime.startsWith("text/html") && !mime.startsWith("application/json") && !mime.contains("+json");
  }

  private Stream openFile(DataSpec spec, String key, ResolvedUrlCache.Entry file,
      String name, StreamCall call) throws IOException {
    DataSpec remote = spec.buildUpon().setUri(Uri.parse(file.url)).setKey(file.mediaCacheKey()).build();
    OkHttpDataSource http = new OkHttpDataSource.Factory(call).setUserAgent(MEDIA_USER_AGENT)
        .setContentTypePredicate(LxDataSource::audioContentType).createDataSource();
    DataSource source = MediaCache.wrap(context, http);
    boolean ready = false;
    try {
      long length = source.open(remote);
      byte[] first = new byte[1];
      if (length == 0 ? spec.position == 0 : source.read(first, 0, 1) != 1) {
        throw new java.io.EOFException("音频正文为空");
      }
      call.finishOpen();
      Uri uri = http.getUri();
      if (uri != null) {
        String tag = "";
        for (Map.Entry<String, List<String>> header : http.getResponseHeaders().entrySet()) {
          if ("ETag".equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
            tag = header.getValue().get(0);
          }
        }
        file = file.validated(uri.toString(), strongEtag(tag));
      }
      Stream stream = new Stream(source, call, remote, length, file, name, key, first[0]);
      ready = true;
      return stream;
    } finally {
      if (!ready) {
        call.cancel();
        closeQuietly(source);
      }
    }
  }

  private PlatformAttemptResult tryPlatform(
      OpenRequest request,
      DataSpec spec,
      String platform,
      JSONObject music,
      String preferred,
      String rememberedQuality,
      String urlCacheKey,
      Set<String> failureNotes)
      throws JSONException, InterruptedException {
    List<LxSources.Candidate> remaining = store.sources.candidates(platform);
    if (remaining.isEmpty()) return new PlatformAttemptResult(null, 0, false);
    int tried = 0;
    Set<String> unavailable = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    SourceHealthTracker health = store.sources.health;
    SourceHealthTracker.Key<LxSources.Candidate> key = candidate -> candidate.healthKey(platform);
    for (String quality : LxSources.resolveQualities(preferred, music, rememberedQuality)) {
      List<LxSources.Candidate> supported = new ArrayList<>();
      for (LxSources.Candidate candidate : remaining) {
        if (!unavailable.contains(candidate.id) && candidate.maySupport(platform, quality)
            && !store.sources.urlCache.recovering(urlCacheKey, candidate.id, candidate.revision, platform)) {
          supported.add(candidate);
        }
      }
      if (supported.isEmpty()) continue;
      List<LxSources.Candidate> ranked = health.rankCandidates(supported, key);
      long attemptTimeoutMs =
          ranked.get(0).isWarm() ? WARM_ATTEMPT_TIMEOUT_MS : COLD_ATTEMPT_TIMEOUT_MS;
      SourceRace<LxSources.Candidate, Stream> race =
          new SourceRace<>(
              ranked,
              health,
              key,
              (candidate, control) ->
                  openCandidate(
                      spec,
                      platform,
                      music,
                      quality,
                      urlCacheKey,
                      candidate,
                      unavailable,
                      control,
                      attemptTimeoutMs),
              stream -> closeQuietly(stream.source),
              SourceRace.HEDGE_DELAY_MS,
              attemptTimeoutMs);
      if (!isOpening(request)) throw new InterruptedException();
      request.addRace(race);
      try {
        Stream stream = race.await();
        return new PlatformAttemptResult(stream, tried + 1, true);
      } catch (SourceRace.Failure error) {
        tried += error.attempts;
        if (error.attempts > 0) {
          synchronized (failureNotes) {
            for (String detail : error.errors) {
              if (failureNotes.size() < 4) failureNotes.add(detail);
            }
            if (error.errors.isEmpty()
                && error.getMessage() != null
                && !error.getMessage().isEmpty()
                && failureNotes.size() < 4) {
              failureNotes.add(error.getMessage());
            }
          }
        }
      } finally {
        request.removeRace(race);
      }
    }
    return new PlatformAttemptResult(null, tried, true);
  }

  private static List<FallbackMatch> readFallbackCache(String key) {
    long now = SystemClock.elapsedRealtime();
    synchronized (FALLBACK_CACHE) {
      CachedFallbackList entry = FALLBACK_CACHE.get(key);
      if (entry == null) return null;
      if (now >= entry.expiresAt) {
        FALLBACK_CACHE.remove(key);
        return null;
      }
      return new ArrayList<>(entry.matches);
    }
  }

  private static void saveFallbackCache(String key, List<FallbackMatch> matches) {
    if (matches == null || matches.isEmpty()) return;
    synchronized (FALLBACK_CACHE) {
      FALLBACK_CACHE.put(
          key,
          new CachedFallbackList(
              new ArrayList<>(matches), SystemClock.elapsedRealtime() + FALLBACK_CACHE_TTL_MS));
    }
  }

  static String fallbackKey(JSONObject track) {
    String platform = track.optString("source");
    JSONObject music = track.optJSONObject("music");
    String id = songMidOf(music);
    if (!id.isEmpty()) return new JSONArray().put(platform).put(id).toString();
    return new JSONArray().put(platform).put(track.optString("name")).put(track.optString("artist"))
        .put(music == null ? "" : music.optString("albumName")).put(track.optString("duration")).toString();
  }

  private Stream tryCrossPlatformFallback(
      OpenRequest request,
      DataSpec spec,
      JSONObject track,
      String originalPlatform,
      String preferred,
      String urlCacheKey,
      AtomicInteger totalTried,
      AtomicBoolean anyCandidates,
      AtomicBoolean triedFallback,
      Set<String> failureNotes)
      throws InterruptedException {
    String name = track.optString("name", "");
    String artist = track.optString("artist", "");
    String query = TrackMatcher.searchQuery(name, artist);
    if (query.isEmpty()) return null;
    JSONObject origMusic = track.optJSONObject("music");
    String album = origMusic == null ? "" : origMusic.optString("albumName", "");
    String duration = track.optString("duration", "");

    List<String> eligiblePlatforms = new ArrayList<>();
    for (String altPlatform : TrackMatcher.fallbackPlatforms(originalPlatform)) {
      try {
        if (!store.sources.candidates(altPlatform).isEmpty()) {
          eligiblePlatforms.add(altPlatform);
        }
      } catch (JSONException ignored) {
      }
    }
    if (eligiblePlatforms.isEmpty()) return null;
    triedFallback.set(true);

    String cacheKey = fallbackKey(track);
    List<FallbackMatch> cached = readFallbackCache(cacheKey);
    List<FallbackMatch> matches = Collections.synchronizedList(new ArrayList<>());
    if (cached != null) {
      for (FallbackMatch match : cached) {
        if (eligiblePlatforms.remove(match.platform)) matches.add(match);
      }
    }
    FallbackRace<FallbackMatch, Stream> race = new FallbackRace<>(eligiblePlatforms.size(),
        FALLBACK_MAX_CONCURRENT_PLATFORMS, FALLBACK_SEARCH_TIMEOUT_MS, FALLBACK_TOTAL_TIMEOUT_MS,
        FALLBACK_HEDGE_STAGGER_MS, stream -> closeQuietly(stream.source));
    request.setFallback(race);
    List<Future<?>> workers = new ArrayList<>();
    List<CancellationSignal> signals = new ArrayList<>();
    try {
      for (FallbackMatch match : matches) race.offer(match);
      for (String altPlatform : eligiblePlatforms) {
        CancellationSignal signal = new CancellationSignal();
        signals.add(signal);
        request.addSearchSignal(signal);
        workers.add(FALLBACK_SEARCH_POOL.submit(() -> {
          try {
            JSONObject result = CatalogEngine.run(context, MusicStore.obj(
                "action", "search", "platform", altPlatform, "query", query, "page", 1), signal);
            JSONObject best = TrackMatcher.pickBest(name, artist, album, duration, result.optJSONArray("list"));
            if (best == null) return;
            JSONObject converted = DesktopPlaylists.convert("fallback:" + best.optString("id"), best);
            JSONObject music = converted.optJSONObject("music");
            if (converted.optBoolean("playable") && music != null) {
              FallbackMatch match = new FallbackMatch(altPlatform, music);
              if (race.offer(match)) matches.add(match);
            }
          } catch (Exception ignored) {
          } finally {
            race.searchFinished();
          }
        }));
      }
      while (isOpening(request)) {
        FallbackMatch match = race.next();
        if (match == null) break;
        workers.add(FALLBACK_OPEN_POOL.submit(() -> {
          Stream result = null;
          try {
            checkNetwork();
            String songKey = ResolvedUrlCache.stableSongKey(match.platform, songMidOf(match.music), "");
            PlatformAttemptResult attempt = tryPlatform(request, spec, match.platform, match.music,
                preferred, store.sources.urlCache.rememberedQuality(songKey), urlCacheKey, failureNotes);
            result = attempt.stream;
            totalTried.addAndGet(attempt.tried);
            if (attempt.hadCandidates) anyCandidates.set(true);
          } catch (Exception ignored) {
          } finally {
            race.finished(result);
          }
        }));
      }
      return race.takeWinner();
    } finally {
      race.close();
      for (CancellationSignal signal : signals) signal.cancel();
      for (Future<?> worker : workers) worker.cancel(true);
      saveFallbackCache(cacheKey, matches);
    }
  }

  private static String summarizeFailures(Set<String> failureNotes) {
    synchronized (failureNotes) {
      if (failureNotes.isEmpty()) return "未知错误";
      StringBuilder sb = new StringBuilder();
      int count = 0;
      for (String note : failureNotes) {
        if (count >= 3) break;
        if (count > 0) sb.append("；");
        sb.append(note);
        count++;
      }
      return sb.toString();
    }
  }

  private long openOnline(OpenRequest request, DataSpec spec, JSONObject track)
      throws IOException, JSONException, InterruptedException {
    String preferred = store.prefs.getString("quality", "auto");
    String stableKey = ResolvedUrlCache.stableSongKey(track);
    String urlCacheKey = ResolvedUrlCache.cacheKey(track, preferred);
    boolean continuing = spec.position > 0;
    if (continuing && spec.uri.equals(pinnedTrack) && pinnedKey != null) urlCacheKey = pinnedKey;
    ResolvedUrlCache.Entry file = continuing
        ? (spec.uri.equals(pinnedTrack) ? pinnedFile : null)
        : store.sources.urlCache.indexedFile(urlCacheKey);
    if (!spec.uri.equals(pinnedTrack)) pinnedFile = null;
    pinnedTrack = spec.uri;
    Stream cachedStream = tryOpenCachedUrl(request, spec, urlCacheKey, file);
    if (cachedStream != null) {
      long length = acceptStream(request, cachedStream, file);
      if (!continuing && cachedStream.call.usedNetwork()) store.sources.recordPlayback(file, true);
      return length;
    }
    if (continuing) throw new ReprepareException();
    if (file != null) store.sources.urlCache.removeIfCurrent(urlCacheKey, file);
    String platform = track.getString("source");
    JSONObject music = track.getJSONObject("music");
    String rememberedQuality =
        "auto".equals(preferred) ? store.sources.urlCache.rememberedQuality(stableKey) : "";
    Set<String> failureNotes = new LinkedHashSet<>();
    AtomicInteger totalTried = new AtomicInteger(0);
    AtomicBoolean anyCandidates = new AtomicBoolean(false);

    PlatformAttemptResult primary =
        tryPlatform(
            request, spec, platform, music, preferred, rememberedQuality, urlCacheKey, failureNotes);
    totalTried.addAndGet(primary.tried);
    if (primary.hadCandidates) anyCandidates.set(true);
    if (primary.stream != null) {
      return acceptStream(request, primary.stream, null);
    }

    if (!isOpening(request)) throw new InterruptedException();
    checkNetwork();

    AtomicBoolean triedFallback = new AtomicBoolean(false);
    Stream fallbackStream =
        tryCrossPlatformFallback(
            request,
            spec,
            track,
            platform,
            preferred,
            urlCacheKey,
            totalTried,
            anyCandidates,
            triedFallback,
            failureNotes);
    if (fallbackStream != null) {
      return acceptStream(request, fallbackStream, null);
    }

    if (!isOpening(request)) throw new InterruptedException();
    checkNetwork();
    if (!anyCandidates.get())
      throw new ExhaustedException("请启用支持" + MusicStore.platformName(platform) + "的音源");
    if (totalTried.get() == 0) throw new ExhaustedException("已启用的音源不支持所选音质或更低音质");
    throw new ExhaustedException(
        "已尝试 "
            + totalTried.get()
            + " 个音源"
            + (triedFallback.get() ? "（含跨平台换源）" : "")
            + "，均未能播放。原因 · "
            + summarizeFailures(failureNotes));
  }

  private long acceptStream(OpenRequest request, Stream stream, ResolvedUrlCache.Entry previous)
      throws IOException, InterruptedException {
    if (!store.sources.cacheIfCurrent(stream.urlCacheKey, stream.file, previous)) {
      closeQuietly(stream.source);
      throw new IOException("音源已变更");
    }
    ResolvedUrlCache.Entry file = stream.file;
    store.sources.urlCache.rememberQuality(
        ResolvedUrlCache.stableSongKey(file.actualPlatform, file.actualSongMid, ""), file.actualQuality);
    Log.d(TAG, "opened " + file.actualPlatform + "/" + file.actualQuality + " src=" + stream.sourceName);
    return attachStream(request, stream);
  }

  /** Pre-resolves and probes the next track URL into urlCache without downloading the full stream. */
  static SourceRace<LxSources.Candidate, ResolvedUrlCache.Entry> startPreload(
      Context context, MusicStore store, OkHttpClient onlineHttp, JSONObject track, String preferred)
      throws JSONException {
    if ("local".equals(track.optString("source"))) return null;
    if (store.prefs.getBoolean("unmetered", false)) {
      android.net.ConnectivityManager network =
          (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (network == null || network.isActiveNetworkMetered()) return null;
    }
    String stableKey = ResolvedUrlCache.stableSongKey(track);
    String urlCacheKey = ResolvedUrlCache.cacheKey(track, preferred);
    ResolvedUrlCache.Entry indexed = store.sources.urlCache.indexedFile(urlCacheKey);
    if (indexed != null && (store.sources.urlCache.get(urlCacheKey) != null
        || MediaCache.complete(context, indexed.mediaCacheKey()))) return null;
    String platform = track.getString("source");
    JSONObject music = track.getJSONObject("music");
    String songMid = songMidOf(music);
    List<LxSources.Candidate> remaining = store.sources.candidates(platform);
    if (remaining.isEmpty()) return null;
    String remembered =
        "auto".equals(preferred) ? store.sources.urlCache.rememberedQuality(stableKey) : "";
    String chosenQuality = null;
    List<LxSources.Candidate> supported = new ArrayList<>();
    for (String q : LxSources.resolveQualities(preferred, music, remembered)) {
      supported.clear();
      for (LxSources.Candidate candidate : remaining) {
        if (candidate.maySupport(platform, q)
            && !store.sources.urlCache.recovering(urlCacheKey, candidate.id, candidate.revision, platform)) supported.add(candidate);
      }
      if (!supported.isEmpty()) {
        chosenQuality = q;
        break;
      }
    }
    if (chosenQuality == null || supported.isEmpty()) return null;
    final String quality = chosenQuality;
    SourceHealthTracker health = store.sources.health;
    SourceHealthTracker.Key<LxSources.Candidate> key = candidate -> candidate.healthKey(platform);
    List<LxSources.Candidate> ranked = health.rankCandidates(supported, key);
    long timeoutMs = ranked.get(0).isWarm() ? WARM_ATTEMPT_TIMEOUT_MS : COLD_ATTEMPT_TIMEOUT_MS;
    return new SourceRace<>(
        ranked,
        health,
        key,
        (candidate, control) -> {
          StreamCall probeCall = new StreamCall(onlineHttp);
          try (LxEnginePool<LxEngine>.Lease lease = candidate.acquire(context)) {
            control.onResult(lease::recordResult);
            control.checkCanceled();
            LxEngine engine = lease.await();
            control.markReady();
            candidate.cacheCapabilities(engine.capabilities());
            String url = engine.resolve(music, quality, timeoutMs);
            control.onCancel(timedOut -> probeCall.cancel());
            control.checkCanceled();
            Request probe =
                new Request.Builder()
                    .url(url)
                    .header("User-Agent", MEDIA_USER_AGENT)
                    .header("Range", "bytes=0-1")
                    .build();
            try (Response response = probeCall.newCall(probe).execute()) {
              if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
              if (!audioContentType(response.header("Content-Type")) || response.body() == null
                  || response.body().source().exhausted()) throw new IOException("音源返回了无效的音频响应");
              String finalUrl = response.request().url().toString();
              candidate.ensureCurrent();
              control.checkCanceled();
              return new ResolvedUrlCache.Entry(
                  candidate.id,
                  candidate.revision,
                  platform,
                  songMid,
                  quality,
                  finalUrl,
                  System.currentTimeMillis() + ResolvedUrlCache.DEFAULT_TTL_MS);
            }
          } catch (LxEngine.UnsupportedSource error) {
            throw new SourceRace.Skipped(error.getMessage());
          }
        },
        result -> {},
        SourceRace.HEDGE_DELAY_MS,
        timeoutMs, false);
  }

  private Stream openCandidate(
      DataSpec spec,
      String platform,
      JSONObject music,
      String quality,
      String urlCacheKey,
      LxSources.Candidate candidate,
      Set<String> unavailable,
      SourceRace.Control control,
      long attemptTimeoutMs)
      throws Exception {
    long attemptStart = SystemClock.elapsedRealtime();
    Stream stream = null;
    boolean valid = false;
    String stage = "init";
    String songMid = songMidOf(music);
    checkCandidateNetwork();
    try (LxEnginePool<LxEngine>.Lease lease = candidate.acquire(context)) {
      control.onResult(
          timedOut -> {
            if (timedOut) unavailable.add(candidate.id);
            lease.recordResult(timedOut);
          });
      control.checkCanceled();
      LxEngine engine;
      try {
        engine = lease.await();
      } catch (InterruptedException | SourceRace.Skipped error) {
        throw error;
      } catch (Exception error) {
        unavailable.add(candidate.id);
        throw error;
      }
      long afterInit = SystemClock.elapsedRealtime();
      control.markReady();
      stage = "resolve";
      try {
        candidate.cacheCapabilities(engine.capabilities());
        String url = engine.resolve(music, quality, attemptTimeoutMs);
        long afterResolve = SystemClock.elapsedRealtime();
        stage = "open";
        checkCandidateNetwork();
        ResolvedUrlCache.Entry file = new ResolvedUrlCache.Entry(candidate.id, candidate.revision,
            platform, songMid, quality, url, System.currentTimeMillis() + ResolvedUrlCache.DEFAULT_TTL_MS);
        StreamCall call = fileCall(file, false);
        control.onCancel(timedOut -> call.cancel());
        control.checkCanceled();
        stream = openFile(spec, urlCacheKey, file, candidate.name, call);
        candidate.ensureCurrent();
        control.checkCanceled();
        valid = true;
        Log.d(TAG, candidate.name + " first byte " + platform + "/" + quality
            + " init=" + (afterInit - attemptStart) + "ms resolve=" + (afterResolve - afterInit)
            + "ms open=" + (SystemClock.elapsedRealtime() - afterResolve) + "ms");
        return stream;
      } catch (LxEngine.UnsupportedSource error) {
        if (error.platform) unavailable.add(candidate.id);
        throw new SourceRace.Skipped(error.getMessage());
      } catch (InterruptedException | SourceRace.Skipped error) {
        throw error;
      } catch (java.util.concurrent.TimeoutException error) {
        unavailable.add(candidate.id);
        Log.d(
            TAG,
            candidate.name
                + " fail stage="
                + stage
                + " "
                + platform
                + "/"
                + quality
                + " elapsed="
                + (SystemClock.elapsedRealtime() - attemptStart)
                + "ms err=timeout");
        throw error;
      } catch (Exception error) {
        control.checkCanceled();
        candidate.ensureCurrent();
        ExhaustedException policy = ExhaustedException.find(error);
        if (policy != null) throw new SourceRace.Skipped(policy.getMessage());
        if (engine.isBroken()) unavailable.add(candidate.id);
        Log.d(
            TAG,
            candidate.name
                + " fail stage="
                + stage
                + " "
                + platform
                + "/"
                + quality
                + " elapsed="
                + (SystemClock.elapsedRealtime() - attemptStart)
                + "ms err="
                + error.getMessage());
        throw error;
      }
    } catch (InterruptedException | SourceRace.Skipped | java.util.concurrent.TimeoutException error) {
      throw error;
    } catch (Exception error) {
      throw new IOException(candidate.name + " · " + error.getMessage(), error);
    } finally {
      if (!valid && stream != null) closeQuietly(stream.source);
    }
  }

  private void checkCandidateNetwork() throws SourceRace.Skipped {
    try {
      checkNetwork();
    } catch (IOException error) {
      throw new SourceRace.Skipped(error.getMessage());
    }
  }

  private static void closeQuietly(DataSource source) {
    try {
      source.close();
    } catch (IOException ignored) {
    }
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    long now = android.os.SystemClock.elapsedRealtime();
    if (online && now >= nextNetworkCheck) {
      checkNetwork();
      nextNetworkCheck = now + 1000;
    }
    DataSource source;
    Stream stream;
    synchronized (this) {
      source = activeUpstream;
      stream = activeStream;
    }
    if (source == null) throw new IOException("播放请求已关闭");
    if (stream == null) {
      synchronized (source) {
        if (source != activeUpstream) throw new InterruptedIOException("播放请求已关闭");
        return source.read(buffer, offset, length);
      }
    }
    synchronized (stream) {
      if (source != activeUpstream) throw new InterruptedIOException("播放请求已关闭");
      try {
        int count = stream.read(buffer, offset, length);
        if (count > 0) {
          synchronized (this) {
            for (TransferListener listener : stream.listeners)
              listener.onBytesTransferred(this, stream.spec, true, count);
          }
        }
        return count;
      } catch (IOException error) {
        // Media3 may wrap the network policy exception while opening the next cache hole.
        ExhaustedException policy = ExhaustedException.find(error);
        if (policy != null) throw policy;
        if (source == activeUpstream && !Thread.currentThread().isInterrupted()) {
          ResolvedUrlCache.Entry file = stream.file;
          if (CacheMiss.caused(error)) {
            store.sources.cacheIfCurrent(stream.urlCacheKey, file.freshFile(), file);
            throw new ReprepareException();
          }
          store.sources.urlCache.bodyFailed(stream.urlCacheKey, file);
          if (!MediaCache.diskError(error)) store.sources.recordPlayback(file, false);
          Log.d(TAG, "body failed " + file.actualPlatform + "/" + file.actualQuality, error);
          throw new ReprepareException();
        }
        throw error;
      }
    }
  }

  @Override
  public void close() throws IOException {
    OpenRequest closingRequest;
    DataSource source;
    Stream stream;
    synchronized (this) {
      stream = activeStream;
      activeStream = null;
      closingRequest = opening;
      opening = null;
      source = activeUpstream;
      activeUpstream = null;
    }
    if (closingRequest != null) closingRequest.cancelAll();
    if (stream != null) {
      stream.call.cancel();
      synchronized (stream) {
        try {
          stream.source.close();
        } finally {
          synchronized (this) {
            for (TransferListener listener : stream.listeners)
              listener.onTransferEnd(this, stream.spec, true);
          }
        }
      }
    } else if (source != null) {
      synchronized (source) {
        source.close();
      }
    }
  }

  @Override
  public Uri getUri() {
    DataSource source = activeUpstream;
    return source == null ? null : source.getUri();
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    DataSource source = activeUpstream;
    return source == null ? Collections.emptyMap() : source.getResponseHeaders();
  }

  @Override
  public synchronized void addTransferListener(TransferListener listener) {
    if (transferListeners.contains(listener)) return;
    transferListeners.add(listener);
    if (activeStream != null) {
      activeStream.listeners.add(listener);
      listener.onTransferInitializing(this, activeStream.spec, true);
      listener.onTransferStart(this, activeStream.spec, true);
    } else if (activeUpstream != null) activeUpstream.addTransferListener(listener);
  }
}
