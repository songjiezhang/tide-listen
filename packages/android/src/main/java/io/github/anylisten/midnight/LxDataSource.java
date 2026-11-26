package io.github.anylisten.midnight;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.*;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.json.*;

/** Passes the first valid media stream at the selected quality to ExoPlayer. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
final class LxDataSource implements DataSource {
  static final String STREAM_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36";
  private static final long COLD_ATTEMPT_TIMEOUT_MS = 14_000;

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
  }

  private static final class Stream {
    final DataSource source;
    final StreamCall call;
    final DataSpec spec;
    final long length;
    final List<TransferListener> listeners = new ArrayList<>();

    Stream(DataSource source, StreamCall call, DataSpec spec, long length) {
      this.call = call;
      this.spec = spec;
      this.source = source;
      this.length = length;
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

  private long openOnline(OpenRequest request, DataSpec spec, JSONObject track)
      throws IOException, JSONException, InterruptedException {
    String platform = track.getString("source");
    JSONObject music = track.getJSONObject("music");
    List<LxSources.Candidate> remaining = store.sources.candidates(platform);
    if (remaining.isEmpty())
      throw new ExhaustedException("请启用支持" + MusicStore.platformName(platform) + "的音源");
    String preferred = store.prefs.getString("quality", "auto");
    String lastError = "";
    int tried = 0;
    Set<String> unavailable = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    SourceHealthTracker health = store.sources.health;
    SourceHealthTracker.Key<LxSources.Candidate> key = candidate -> candidate.id + "::" + platform;
    for (String quality : LxSources.playbackQualities(preferred)) {
      List<LxSources.Candidate> supported = new ArrayList<>();
      boolean hasCold = false;
      for (LxSources.Candidate candidate : remaining) {
        if (!unavailable.contains(candidate.id) && candidate.maySupport(platform, quality)) {
          supported.add(candidate);
          if (!candidate.isWarm()) hasCold = true;
        }
      }
      if (supported.isEmpty()) continue;
      long attemptTimeoutMs = hasCold ? COLD_ATTEMPT_TIMEOUT_MS : SourceRace.ATTEMPT_TIMEOUT_MS;
      SourceRace<LxSources.Candidate, Stream> race = new SourceRace<>(
          health.rankCandidates(supported, key), health, key,
          (candidate, control) -> openCandidate(spec, music, quality, candidate, unavailable, control, attemptTimeoutMs),
          stream -> closeQuietly(stream.source),
          SourceRace.HEDGE_DELAY_MS,
          attemptTimeoutMs);
      synchronized (this) {
        if (!isOpening(request)) throw new InterruptedException();
        request.race = race;
      }
      try {
        Stream stream = race.await();
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
      } catch (SourceRace.Failure error) {
        tried += error.attempts;
        if (error.attempts > 0) lastError = error.getMessage();
      } finally {
        synchronized (this) {
          request.race = null;
        }
      }
    }
    if (!isOpening(request)) throw new InterruptedException();
    checkNetwork();
    if (tried == 0) throw new ExhaustedException("已启用的音源不支持所选音质或更低音质");
    throw new ExhaustedException("已尝试 " + tried + " 个音源，均未能播放。最后一次错误 · " + lastError);
  }

  private Stream openCandidate(DataSpec spec, JSONObject music, String quality, LxSources.Candidate candidate,
      Set<String> unavailable, SourceRace.Control control, long attemptTimeoutMs) throws Exception {
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
      try {
        candidate.cacheCapabilities(engine.capabilities());
        String url = engine.resolve(music, quality, attemptTimeoutMs);
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
        valid = true;
        return new Stream(source, call, remote, length);
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
    SourceRace<LxSources.Candidate, Stream> race;
    DataSource source;
    Stream stream;
    synchronized (this) {
      stream = activeStream;
      activeStream = null;
      race = opening == null ? null : opening.race;
      opening = null;
      source = activeUpstream;
      activeUpstream = null;
    }
    if (race != null) race.cancel();
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
