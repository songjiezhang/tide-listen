package io.github.anylisten.midnight;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache.CacheException;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import okio.ByteString;

/** Process-wide bounded cache of versioned audio representations. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
final class MediaCache {
  private static final String TAG = "LxTiming";
  private static final long MAX_CACHE_BYTES = 256L * 1024L * 1024L;
  private static final long FRAGMENT_SIZE_BYTES = 5L * 1024L * 1024L;
  private static SimpleCache instance;
  private static boolean failed;

  private MediaCache() {}

  static String cacheKey(String sourceId, int revision, String platform, String songMid,
      String quality, String url, String generation) {
    StringBuilder identity = new StringBuilder();
    for (String part : new String[] {sourceId, String.valueOf(revision), platform, songMid, quality, url, generation}) {
      identity.append(part.length()).append(':').append(part);
    }
    return "v2:" + ByteString.encodeUtf8(identity.toString()).sha256().hex();
  }

  static synchronized SimpleCache get(Context context) {
    if (failed || context == null) return null;
    if (instance != null) return instance;
    try {
      Context app = context.getApplicationContext();
      File dir = new File(app.getCacheDir(), "media-cache");
      SimpleCache cache =
          new SimpleCache(
              dir,
              new LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
              new StandaloneDatabaseProvider(app));
      try {
        cache.checkInitialization();
        instance = cache;
      } catch (CacheException error) {
        cache.release();
        throw error;
      }
    } catch (CacheException | RuntimeException error) {
      failed = true;
      Log.w(TAG, "media disk cache unavailable: " + error.getMessage());
    }
    return instance;
  }

  static DataSource wrap(Context context, DataSource upstream) {
    SimpleCache cache = get(context);
    if (cache == null) return upstream;
    DataSource cached = new CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(() -> upstream)
        .setCacheReadDataSourceFactory(new FileDataSource.Factory())
        .setCacheWriteDataSinkFactory(
            new CacheDataSink.Factory().setCache(cache).setFragmentSize(FRAGMENT_SIZE_BYTES))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .createDataSource();
    return new WithDiskFallback(cached, upstream);
  }

  static boolean complete(Context context, String key) {
    SimpleCache cache = get(context);
    if (cache == null) return false;
    long size = ContentMetadata.getContentLength(cache.getContentMetadata(key));
    return size > 0 && cache.isCached(key, 0, size);
  }

  // CacheDataSource's ignore-on-error flag only applies to its next open(). Resume the
  // same HTTP representation immediately, at the last byte actually given to our caller.
  static final class WithDiskFallback implements DataSource {
    private final DataSource cached, upstream;
    private DataSource current;
    private DataSpec spec;
    private long delivered;

    WithDiskFallback(DataSource cached, DataSource upstream) {
      this.cached = cached;
      this.upstream = upstream;
      this.current = cached;
    }

    @Override public long open(DataSpec spec) throws IOException {
      this.spec = spec;
      delivered = 0;
      try {
        return current.open(spec);
      } catch (IOException failure) {
        return bypass(failure);
      }
    }

    private long bypass(IOException failure) throws IOException {
      if (current != cached || !diskError(failure)) throw failure;
      disable(failure);
      try {
        cached.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      current = upstream;
      return upstream.open(spec.subrange(delivered));
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
      int count;
      try {
        count = current.read(buffer, offset, length);
      } catch (IOException failure) {
        bypass(failure);
        count = current.read(buffer, offset, length);
      }
      if (count > 0) delivered += count;
      return count;
    }

    @Override public Uri getUri() { return current.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return current.getResponseHeaders(); }
    @Override public void addTransferListener(TransferListener listener) { cached.addTransferListener(listener); }
    @Override public void close() throws IOException {
      try {
        current.close();
      } catch (IOException failure) {
        if (current != cached || !diskError(failure)) throw failure;
        disable(failure);
      }
    }
  }

  private static synchronized void disable(IOException failure) {
    if (!failed) Log.w(TAG, "Bypassing failed media disk cache", failure);
    failed = true;
  }

  static boolean diskError(Throwable error) {
    for (; error != null; error = error.getCause()) {
      if (error instanceof CacheException || error instanceof FileDataSource.FileDataSourceException) return true;
    }
    return false;
  }
}
