package io.github.anylisten.midnight;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.*;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.*;

/** Tries each enabled, compatible source until the actual media request opens. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
final class LxDataSource implements DataSource {
  private final Context context;
  private final MusicStore store;
  private final DataSource upstream;
  private boolean online;
  private long nextNetworkCheck;

  private void checkNetwork() throws IOException {
    if (!store.prefs.getBoolean("unmetered", false)) return;
    android.net.ConnectivityManager network =
        (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (network == null || network.isActiveNetworkMetered())
      throw new IOException("已开启「仅在非计费网络播放」，请连接 Wi-Fi 或调整网络设置");
  }

  LxDataSource(Context context, MusicStore store, DataSource upstream) {
    this.context = context;
    this.store = store;
    this.upstream = upstream;
  }

  @Override
  public long open(DataSpec spec) throws IOException {
    online = false;
    if (!"midnight".equals(spec.uri.getScheme())) return upstream.open(spec);
    try {
      JSONObject track = store.track(spec.uri.getLastPathSegment());
      if ("local".equals(track.getString("source")))
        return upstream.open(spec.withUri(Uri.parse(track.getString("uri"))));
      online = true;
      checkNetwork();
      return openOnline(spec, track);
    } catch (JSONException e) {
      throw new IOException("歌曲信息无效", e);
    }
  }

  private long openOnline(DataSpec spec, JSONObject track) throws IOException, JSONException {
    String platform = track.getString("source");
    JSONObject music = track.getJSONObject("music");
    List<LxSources.Candidate> remaining = store.sources.candidates(platform);
    if (remaining.isEmpty())
      throw new IOException("请启用支持" + MusicStore.platformName(platform) + "的音源");
    String preferred = store.prefs.getString("quality", "auto");
    String lastError = "";
    int tried = 0;
    for (String quality : LxSources.playbackQualities(preferred)) {
      for (Iterator<LxSources.Candidate> it = remaining.iterator(); it.hasNext(); ) {
        LxSources.Candidate candidate = it.next();
        checkNetwork();
        try {
          if (!candidate.supports(context, platform, quality)) continue;
          String url = candidate.resolve(context, music, quality);
          checkNetwork();
          long length = upstream.open(spec.withUri(Uri.parse(url)));
          if (length == 0 || isErrorPage(upstream.getResponseHeaders()))
            throw new IOException("音源返回了无效的音频响应");
          return length;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new java.io.InterruptedIOException("播放请求已取消");
        } catch (Exception e) {
          try {
            upstream.close();
          } catch (IOException closeError) {
            e.addSuppressed(closeError);
          }
          if (Thread.currentThread().isInterrupted())
            throw new java.io.InterruptedIOException("播放请求已取消");
          it.remove();
          tried++;
          lastError = candidate.name + " · " + e.getMessage();
        }
      }
      if (remaining.isEmpty()) break;
    }
    if (tried == 0) throw new IOException("已启用的音源不支持所选音质或更低音质");
    throw new IOException("已尝试 " + tried + " 个音源，均未能播放。最后一次错误 · " + lastError);
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
    return upstream.read(buffer, offset, length);
  }

  @Override
  public void close() throws IOException {
    upstream.close();
  }

  @Override
  public Uri getUri() {
    return upstream.getUri();
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return upstream.getResponseHeaders();
  }

  @Override
  public void addTransferListener(TransferListener listener) {
    upstream.addTransferListener(listener);
  }
}
