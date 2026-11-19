package io.github.anylisten.midnight;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import okio.ByteString;

/** A bounded cache for online catalog pages and rendered cover images. */
final class CatalogCache {
  private final File directory;
  private final int maxEntries, maxBytes;
  private final long maxAge;

  CatalogCache(Context context, String name, int maxEntries, int maxBytes, long maxAge) {
    directory = new File(context.getCacheDir(), name);
    this.maxEntries = maxEntries;
    this.maxBytes = maxBytes;
    this.maxAge = maxAge;
  }

  private File path(String key) {
    return new File(directory, ByteString.encodeUtf8(key).sha256().hex() + ".cache");
  }

  synchronized byte[] read(String key) {
    File file = path(key);
    if (maxAge > 0 && System.currentTimeMillis() - file.lastModified() > maxAge) return null;
    try {
      return MusicStore.read(new AtomicFile(file).openRead(), maxBytes);
    } catch (IOException ignored) {
      return null;
    }
  }

  synchronized void write(String key, byte[] bytes) {
    if (bytes.length > maxBytes) return;
    AtomicFile file = new AtomicFile(path(key));
    FileOutputStream stream = null;
    try {
      stream = file.startWrite();
      stream.write(bytes);
      file.finishWrite(stream);
    } catch (IOException ignored) {
      if (stream != null) file.failWrite(stream);
      return;
    }
    File[] entries = directory.listFiles((dir, name) -> name.endsWith(".cache"));
    if (entries == null || entries.length <= maxEntries) return;
    Arrays.sort(entries, (left, right) -> Long.compare(left.lastModified(), right.lastModified()));
    for (int i = 0; i < entries.length - maxEntries; i++) entries[i].delete();
  }
}
