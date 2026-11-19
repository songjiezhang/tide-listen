package io.github.anylisten.midnight;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.*;
import java.io.IOException;
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
      List<LxSources.Candidate> candidates = store.sources.candidates(track.getString("source"));
      if (candidates.isEmpty())
        throw new IOException("请启用支持" + MusicStore.platformName(track.getString("source")) + "的音源");
      String last = "";
      for (LxSources.Candidate candidate : candidates) {
        checkNetwork();
        try {
          String url =
              LxEngine.run(context, candidate.script, track.getJSONObject("music"))
                  .getString("url");
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
          last = candidate.name + " · " + e.getMessage();
        }
      }
      throw new IOException("已尝试 " + candidates.size() + " 个音源，均未能播放。最后一次错误 · " + last);
    } catch (JSONException e) {
      throw new IOException("歌曲信息无效", e);
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
