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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.*;

/** Passes the first valid media stream at the selected quality to ExoPlayer. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
final class LxDataSource implements DataSource {
  private static final String TAG = "LxTiming";
  static final String STREAM_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36";
  private static final long WARM_ATTEMPT_TIMEOUT_MS = 12_000;
  private static final long COLD_ATTEMPT_TIMEOUT_MS = 16_000;
  private static final long FALLBACK_SEARCH_TIMEOUT_MS = 8_000;
  private static final ExecutorService FALLBACK_SEARCH_POOL =
      Executors.newCachedThreadPool(
          task -> {
            Thread thread = new Thread(task, "LX fallback search");
            thread.setDaemon(true);
            return thread;
          });

  static final class ExhaustedException extends IOException {
    ExhaustedException(String message) {
      super(message);
    }
  }

  private final Context context;
  private final MusicStore store;
  private final DataSource.Factory upstreamFactory;
  private final OkHttpClient onlineHttp;
  private final List<TransferListener> transferListeners = new ArrayList<>();
  private volatile DataSource activeUpstream;
  private Stream activeStream;
  private OpenRequest opening;
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
    SourceRace<LxSources.Candidate, Stream> race;
    StreamCall cachedCall;
    final List<CancellationSignal> searchSignals = new ArrayList<>();
    boolean canceled;

    synchronized void addSearchSignal(CancellationSignal signal) {
      if (canceled) signal.cancel();
      else searchSignals.add(signal);
    }

    void cancelSearches() {
      List<CancellationSignal> snapshot;
      synchronized (this) {
        canceled = true;
        snapshot = new ArrayList<>(searchSignals);
        searchSignals.clear();
      }
      for (CancellationSignal signal : snapshot) signal.cancel();
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
    final String sourceId;
    final String url;
    final List<TransferListener> listeners = new ArrayList<>();

    Stream(DataSource source, StreamCall call, DataSpec spec, long length, String sourceId, String url) {
      this.call = call;
      this.spec = spec;
      this.source = source;
      this.length = length;
      this.sourceId = sourceId;
      this.url = url;
    }
  }

  private static final class StreamCall implements Call.Factory {
    private final OkHttpClient http;
    private Call call;
    private boolean canceled;

    StreamCall(OkHttpClient http) {
      this.http = http;
    }

    @Override
    public synchronized Call newCall(Request request) {
      call = http.newCall(request);
      if (canceled) call.cancel();
      return call;
    }

    synchronized void cancel() {
      canceled = true;
      if (call != null) call.cancel();
    }
  }

  LxDataSource(Context context, MusicStore store, DataSource.Factory upstreamFactory, OkHttpClient onlineHttp) {
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
      if (Thread.currentThread().isInterrupted() || isOpening(request)) Thread.currentThread().interrupt();
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

  private long openDirect(OpenRequest request, DataSpec spec) throws IOException, InterruptedException {
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

  private static String urlCacheKey(JSONObject track, String preferredQuality) throws JSONException {
    return track.getString("id") + "::" + preferredQuality;
  }

  private long attachStream(OpenRequest request, Stream stream) throws InterruptedException {
    synchronized (this) {
      if (isOpening(request) && !Thread.currentThread().isInterrupted()) {
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

  private Stream tryOpenCachedUrl(OpenRequest request, DataSpec spec, String cacheKey)
      throws InterruptedException {
    ResolvedUrlCache.Entry cached = store.sources.urlCache.get(cacheKey);
    if (cached == null) return null;
    long startedAt = SystemClock.elapsedRealtime();
    StreamCall call = new StreamCall(onlineHttp);
    synchronized (this) {
      if (!isOpening(request)) throw new InterruptedException();
      request.cachedCall = call;
    }
    DataSource source = null;
    boolean valid = false;
    try {
      DataSpec remote = spec.withUri(Uri.parse(cached.url));
      source = new OkHttpDataSource.Factory(call).setUserAgent(STREAM_USER_AGENT).createDataSource();
      long length = source.open(remote);
      if (length == 0 || isErrorPage(source.getResponseHeaders())) {
        throw new IOException("缓存的播放地址已失效");
      }
      valid = true;
      Log.d(
          TAG,
          "cache hit open pos=" + spec.position + " in " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
      return new Stream(source, call, remote, length, cached.sourceId, cached.url);
    } catch (Exception error) {
      if (!isOpening(request) || Thread.currentThread().isInterrupted()) {
        throw new InterruptedException();
      }
      store.sources.urlCache.remove(cacheKey, cached.url);
      Log.d(TAG, "cache miss after error: " + error.getMessage());
      return null;
    } finally {
      synchronized (this) {
        if (request.cachedCall == call) request.cachedCall = null;
      }
      if (!valid && source != null) closeQuietly(source);
    }
  }

  private PlatformAttemptResult tryPlatform(
      OpenRequest request,
      DataSpec spec,
      String platform,
      JSONObject music,
      String preferred,
      Set<String> failureNotes)
      throws JSONException, InterruptedException {
    List<LxSources.Candidate> remaining = store.sources.candidates(platform);
    if (remaining.isEmpty()) return new PlatformAttemptResult(null, 0, false);
    int tried = 0;
    Set<String> unavailable = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    SourceHealthTracker health = store.sources.health;
    SourceHealthTracker.Key<LxSources.Candidate> key = candidate -> candidate.id + "::" + platform;
    for (String quality : LxSources.resolveQualities(preferred, music)) {
      List<LxSources.Candidate> supported = new ArrayList<>();
      for (LxSources.Candidate candidate : remaining) {
        if (!unavailable.contains(candidate.id) && candidate.maySupport(platform, quality)) {
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
                      spec, music, quality, candidate, unavailable, control, attemptTimeoutMs),
              stream -> closeQuietly(stream.source),
              SourceRace.HEDGE_DELAY_MS,
              attemptTimeoutMs);
      synchronized (this) {
        if (!isOpening(request)) throw new InterruptedException();
        request.race = race;
      }
      try {
        Stream stream = race.await();
        return new PlatformAttemptResult(stream, tried + 1, true);
      } catch (SourceRace.Failure error) {
        tried += error.attempts;
        if (error.attempts > 0) {
          for (String detail : error.errors) {
            if (failureNotes.size() < 4) failureNotes.add(detail);
          }
          if (error.errors.isEmpty() && error.getMessage() != null && !error.getMessage().isEmpty()) {
            if (failureNotes.size() < 4) failureNotes.add(error.getMessage());
          }
        }
      } finally {
        synchronized (this) {
          request.race = null;
        }
      }
    }
    return new PlatformAttemptResult(null, tried, true);
  }

  private Stream tryCrossPlatformFallback(
      OpenRequest request,
      DataSpec spec,
      JSONObject track,
      String originalPlatform,
      String preferred,
      int[] totalTried,
      boolean[] anyCandidates,
      boolean[] triedFallback,
      Set<String> failureNotes)
      throws JSONException, InterruptedException {
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
    triedFallback[0] = true;

    ExecutorCompletionService<FallbackMatch> searches =
        new ExecutorCompletionService<>(FALLBACK_SEARCH_POOL);
    List<CancellationSignal> localSignals = new ArrayList<>(eligiblePlatforms.size());
    List<Future<FallbackMatch>> futures = new ArrayList<>(eligiblePlatforms.size());
    for (String altPlatform : eligiblePlatforms) {
      CancellationSignal signal = new CancellationSignal();
      localSignals.add(signal);
      request.addSearchSignal(signal);
      futures.add(
          searches.submit(
              () -> {
                try {
                  JSONObject result =
                      CatalogEngine.run(
                          context,
                          MusicStore.obj(
                              "action", "search",
                              "platform", altPlatform,
                              "query", query,
                              "page", 1),
                          signal);
                  JSONArray rows = result.optJSONArray("list");
                  JSONObject best = TrackMatcher.pickBest(name, artist, album, duration, rows);
                  if (best == null) return null;
                  JSONObject converted =
                      DesktopPlaylists.convert("fallback:" + best.optString("id"), best);
                  if (!converted.optBoolean("playable")) return null;
                  JSONObject altMusic = converted.optJSONObject("music");
                  if (altMusic == null) return null;
                  return new FallbackMatch(altPlatform, altMusic);
                } catch (Exception ignored) {
                  return null;
                }
              }));
    }

    long deadline = SystemClock.elapsedRealtime() + FALLBACK_SEARCH_TIMEOUT_MS;
    try {
      for (int i = 0; i < eligiblePlatforms.size(); i++) {
        if (!isOpening(request) || Thread.currentThread().isInterrupted()) {
          throw new InterruptedException();
        }
        long remainingMs = deadline - SystemClock.elapsedRealtime();
        if (remainingMs <= 0) break;
        Future<FallbackMatch> completed = searches.poll(remainingMs, TimeUnit.MILLISECONDS);
        if (completed == null) break;
        FallbackMatch match;
        try {
          match = completed.get();
        } catch (Exception ignored) {
          continue;
        }
        if (match == null) continue;
        try {
          checkNetwork();
        } catch (IOException ignored) {
          return null;
        }
        PlatformAttemptResult attempt =
            tryPlatform(request, spec, match.platform, match.music, preferred, failureNotes);
        totalTried[0] += attempt.tried;
        anyCandidates[0] = anyCandidates[0] || attempt.hadCandidates;
        if (attempt.stream != null) {
          Log.d(TAG, "cross-platform fallback " + originalPlatform + " -> " + match.platform);
          return attempt.stream;
        }
      }
      return null;
    } finally {
      for (CancellationSignal signal : localSignals) signal.cancel();
      for (Future<FallbackMatch> future : futures) future.cancel(true);
    }
  }

  private static String summarizeFailures(Set<String> failureNotes) {
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

  private long openOnline(OpenRequest request, DataSpec spec, JSONObject track)
      throws IOException, JSONException, InterruptedException {
    long totalStart = SystemClock.elapsedRealtime();
    String preferred = store.prefs.getString("quality", "auto");
    String cacheKey = urlCacheKey(track, preferred);
    Stream cachedStream = tryOpenCachedUrl(request, spec, cacheKey);
    if (cachedStream != null) {
      return attachStream(request, cachedStream);
    }
    String platform = track.getString("source");
    JSONObject music = track.getJSONObject("music");
    Set<String> failureNotes = new LinkedHashSet<>();
    int[] totalTried = {0};
    boolean[] anyCandidates = {false};

    PlatformAttemptResult primary =
        tryPlatform(request, spec, platform, music, preferred, failureNotes);
    totalTried[0] += primary.tried;
    anyCandidates[0] = primary.hadCandidates;
    if (primary.stream != null) {
      store.sources.urlCache.put(cacheKey, primary.stream.sourceId, primary.stream.url);
      Log.d(
          TAG,
          "resolved+opened platform="
              + platform
              + " pos="
              + spec.position
              + " in "
              + (SystemClock.elapsedRealtime() - totalStart)
              + "ms");
      return attachStream(request, primary.stream);
    }

    if (!isOpening(request)) throw new InterruptedException();
    checkNetwork();

    boolean[] triedFallback = {false};
    Stream fallbackStream =
        tryCrossPlatformFallback(
            request,
            spec,
            track,
            platform,
            preferred,
            totalTried,
            anyCandidates,
            triedFallback,
            failureNotes);
    if (fallbackStream != null) {
      store.sources.urlCache.put(cacheKey, fallbackStream.sourceId, fallbackStream.url);
      Log.d(
          TAG,
          "fallback resolved+opened orig="
              + platform
              + " pos="
              + spec.position
              + " in "
              + (SystemClock.elapsedRealtime() - totalStart)
              + "ms");
      return attachStream(request, fallbackStream);
    }

    if (!isOpening(request)) throw new InterruptedException();
    checkNetwork();
    if (!anyCandidates[0])
      throw new ExhaustedException("请启用支持" + MusicStore.platformName(platform) + "的音源");
    if (totalTried[0] == 0) throw new ExhaustedException("已启用的音源不支持所选音质或更低音质");
    throw new ExhaustedException(
        "已尝试 "
            + totalTried[0]
            + " 个音源"
            + (triedFallback[0] ? "（含跨平台换源）" : "")
            + "，均未能播放。原因 · "
            + summarizeFailures(failureNotes));
  }

  /** Pre-resolves and probes the next track URL into urlCache without downloading the full stream. */
  static SourceRace<LxSources.Candidate, String[]> startPreload(
      Context context, MusicStore store, OkHttpClient onlineHttp, JSONObject track)
      throws JSONException {
    if ("local".equals(track.optString("source"))) return null;
    if (store.prefs.getBoolean("unmetered", false)) {
      android.net.ConnectivityManager network =
          (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (network == null || network.isActiveNetworkMetered()) return null;
    }
    String preferred = store.prefs.getString("quality", "auto");
    String cacheKey = urlCacheKey(track, preferred);
    if (store.sources.urlCache.get(cacheKey) != null) return null;
    String platform = track.getString("source");
    JSONObject music = track.getJSONObject("music");
    List<LxSources.Candidate> remaining = store.sources.candidates(platform);
    if (remaining.isEmpty()) return null;
    List<String> qualities = LxSources.resolveQualities(preferred, music);
    String quality = qualities.get(0);
    List<LxSources.Candidate> supported = new ArrayList<>();
    for (LxSources.Candidate candidate : remaining) {
      if (candidate.maySupport(platform, quality)) supported.add(candidate);
    }
    if (supported.isEmpty()) return null;
    SourceHealthTracker health = store.sources.health;
    SourceHealthTracker.Key<LxSources.Candidate> key = candidate -> candidate.id + "::" + platform;
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
            candidate.cacheCapabilities(engine.capabilities());
            String url = engine.resolve(music, quality, timeoutMs);
            control.onCancel(timedOut -> probeCall.cancel());
            control.checkCanceled();
            Request probe =
                new Request.Builder()
                    .url(url)
                    .header("User-Agent", STREAM_USER_AGENT)
                    .header("Range", "bytes=0-1")
                    .build();
            try (Response response = probeCall.newCall(probe).execute()) {
              if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
              String contentType = response.header("Content-Type", "");
              String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
              if (mime.startsWith("text/html") || mime.startsWith("application/json")) {
                throw new IOException("音源返回了无效的音频响应");
              }
              String finalUrl = response.request().url().toString();
              candidate.ensureCurrent();
              control.checkCanceled();
              return new String[] {candidate.id, finalUrl, cacheKey};
            }
          } catch (LxEngine.UnsupportedSource error) {
            throw new SourceRace.Skipped(error.getMessage());
          }
        },
        result -> {},
        SourceRace.HEDGE_DELAY_MS,
        timeoutMs);
  }

  private Stream openCandidate(DataSpec spec, JSONObject music, String quality, LxSources.Candidate candidate,
      Set<String> unavailable, SourceRace.Control control, long attemptTimeoutMs) throws Exception {
    long attemptStart = SystemClock.elapsedRealtime();
    StreamCall call = new StreamCall(onlineHttp);
    DataSource source = null;
    boolean valid = false;
    checkCandidateNetwork();
    try (LxEnginePool<LxEngine>.Lease lease = candidate.acquire(context)) {
      control.onResult(timedOut -> {
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
      try {
        candidate.cacheCapabilities(engine.capabilities());
        String url = engine.resolve(music, quality, attemptTimeoutMs);
        long afterResolve = SystemClock.elapsedRealtime();
        checkCandidateNetwork();
        control.onCancel(timedOut -> call.cancel());
        control.checkCanceled();
        DataSpec remote = spec.withUri(Uri.parse(url));
        source = new OkHttpDataSource.Factory(call).setUserAgent(STREAM_USER_AGENT).createDataSource();
        long length = source.open(remote);
        if (length == 0 || isErrorPage(source.getResponseHeaders()))
          throw new IOException("音源返回了无效的音频响应");
        candidate.ensureCurrent();
        control.checkCanceled();
        Uri resolvedUri = source.getUri();
        String finalUrl = resolvedUri != null ? resolvedUri.toString() : url;
        valid = true;
        Log.d(
            TAG,
            candidate.name
                + " ok init=" + (afterInit - attemptStart) + "ms"
                + " resolve=" + (afterResolve - afterInit) + "ms"
                + " open=" + (SystemClock.elapsedRealtime() - afterResolve) + "ms");
        return new Stream(source, call, remote, length, candidate.id, finalUrl);
      } catch (LxEngine.UnsupportedSource error) {
        if (error.platform) unavailable.add(candidate.id);
        throw new SourceRace.Skipped(error.getMessage());
      } catch (InterruptedException | SourceRace.Skipped error) {
        throw error;
      } catch (java.util.concurrent.TimeoutException error) {
        unavailable.add(candidate.id);
        throw error;
      } catch (Exception error) {
        control.checkCanceled();
        candidate.ensureCurrent();
        if (engine.isBroken()) unavailable.add(candidate.id);
        throw error;
      }
    } catch (InterruptedException | SourceRace.Skipped | java.util.concurrent.TimeoutException error) {
      throw error;
    } catch (Exception error) {
      throw new IOException(candidate.name + " · " + error.getMessage(), error);
    } finally {
      if (!valid && source != null) closeQuietly(source);
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

  private static boolean isErrorPage(Map<String, List<String>> headers) {
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      if (!"Content-Type".equalsIgnoreCase(header.getKey())) continue;
      for (String type : header.getValue()) {
        String mime = type.toLowerCase(Locale.ROOT);
        if (mime.startsWith("text/html") || mime.startsWith("application/json")) return true;
      }
    }
    return false;
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
      int count = source.read(buffer, offset, length);
      if (count > 0) {
        synchronized (this) {
          for (TransferListener listener : stream.listeners)
            listener.onBytesTransferred(this, stream.spec, true, count);
        }
      }
      return count;
    }
  }

  @Override
  public void close() throws IOException {
    OpenRequest closingRequest;
    SourceRace<LxSources.Candidate, Stream> race;
    StreamCall cachedCall;
    DataSource source;
    Stream stream;
    synchronized (this) {
      stream = activeStream;
      activeStream = null;
      closingRequest = opening;
      race = opening == null ? null : opening.race;
      cachedCall = opening == null ? null : opening.cachedCall;
      opening = null;
      source = activeUpstream;
      activeUpstream = null;
    }
    if (closingRequest != null) closingRequest.cancelSearches();
    if (race != null) race.cancel();
    if (cachedCall != null) cachedCall.cancel();
    if (stream != null) {
      stream.call.cancel();
      synchronized (stream) {
        try {
          stream.source.close();
        } finally {
          synchronized (this) {
            for (TransferListener listener : stream.listeners) listener.onTransferEnd(this, stream.spec, true);
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
