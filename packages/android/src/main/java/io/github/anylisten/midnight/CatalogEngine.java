package io.github.anylisten.midnight;

import static io.github.anylisten.midnight.MusicStore.obj;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.CancellationSignal;
import android.util.Base64;
import android.util.LruCache;
import android.webkit.WebResourceResponse;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.QuickJSContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.*;
import org.json.*;

/**
 * Runs the playlist and lyric modules of the bundled Online Metadata extension on the catalog
 * worker.
 */
final class CatalogEngine {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String[] PLATFORM_HOSTS = {
    "kugou.com", "kuwo.cn", "163.com", "126.net", "qq.com", "gtimg.cn", "migu.cn", "cmvideo.cn"
  };
  private static final OkHttpClient HTTP =
      LxEngine.HTTP
          .newBuilder()
          .callTimeout(12, TimeUnit.SECONDS)
          .followRedirects(false)
          .followSslRedirects(false)
          .build();
  private static OkHttpClient artworkHttp;
  private static CatalogCache cachedPages, cachedCovers;
  private static final ConcurrentHashMap<String, FutureTask<Cover>> COVER_REQUESTS =
      new ConcurrentHashMap<>();
  private static final ThreadPoolExecutor COVER_PREFETCH =
      new ThreadPoolExecutor(
          4,
          4,
          20,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(12),
          new ThreadPoolExecutor.DiscardPolicy());

  static synchronized CatalogCache pages(Context context) {
    if (cachedPages == null)
      cachedPages = new CatalogCache(context, "online-catalog-v1", 48, 512 * 1024, 0);
    return cachedPages;
  }

  private static synchronized CatalogCache covers(Context context) {
    if (cachedCovers == null)
      cachedCovers =
          new CatalogCache(
              context, "cover-thumbnails-v1", 160, 400 * 1024, TimeUnit.DAYS.toMillis(14));
    return cachedCovers;
  }

  static void prefetchCovers(Context context, JSONArray rows) {
    if (rows == null) return;
    Context app = context.getApplicationContext();
    for (int i = 0; i < Math.min(4, rows.length()); i++) {
      JSONObject row = rows.optJSONObject(i);
      if (row == null) continue;
      HttpUrl proxy = HttpUrl.parse(row.optString("cover"));
      if (proxy == null) continue;
      String target = proxy.queryParameter("url");
      HttpUrl url = target == null ? null : publicUrl(target, true);
      if (url == null || COVERS.get(url.toString()) != null) continue;
      COVER_PREFETCH.execute(
          () -> {
            try {
              loadCover(app, url);
            } catch (Exception ignored) {
              // The view keeps its placeholder when the cover is unavailable.
            }
          });
    }
  }

  private static synchronized OkHttpClient artworkHttp(Context context) {
    if (artworkHttp == null)
      artworkHttp =
          HTTP.newBuilder()
              .cache(
                  new Cache(new File(context.getCacheDir(), "playlist-covers"), 32L * 1024 * 1024))
              .build();
    return artworkHttp;
  }

  private static final String ART_HOST = "art.midnight.invalid";
  private static final int COVER_SOURCE_LIMIT = 8 * 1024 * 1024;
  private static final int COVER_IMAGE_LIMIT = 768 * 1024;
  private static final int COVER_SIDE = 640;
  private static final LruCache<String, Cover> COVERS =
      new LruCache<String, Cover>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Cover cover) {
          return cover.bytes.length;
        }
      };

  private static final class Cover {
    final String type;
    final byte[] bytes;

    Cover(String type, byte[] bytes) {
      this.type = type;
      this.bytes = bytes;
    }
  }

  static HttpUrl publicUrl(String link) {
    return publicUrl(link, false);
  }

  private static HttpUrl publicUrl(String link, boolean artwork) {
    link = link.trim();
    HttpUrl parsed = HttpUrl.parse(link.startsWith("//") ? "https:" + link : link);
    if (parsed == null
        || !parsed.username().isEmpty()
        || !parsed.password().isEmpty()
        || (parsed.port() != 80 && parsed.port() != 443)) return null;
    if (artwork && (parsed.host().equals("c1.kgimg.com") || parsed.host().equals("imge.kugou.com")))
      return parsed.newBuilder().scheme("https").port(443).build();
    for (String domain : PLATFORM_HOSTS)
      if (parsed.host().equals(domain) || parsed.host().endsWith("." + domain)) return parsed;
    return null;
  }

  static String art(String link) {
    HttpUrl url = publicUrl(link, true);
    if (url == null) return "";
    return new HttpUrl.Builder()
        .scheme("https")
        .host(ART_HOST)
        .addPathSegment("cover")
        .addQueryParameter("url", url.toString())
        .build()
        .toString();
  }

  static WebResourceResponse cover(Context context, String link) {
    HttpUrl request = HttpUrl.parse(link);
    if (request == null || !ART_HOST.equals(request.host())) return null;
    String target = request.queryParameter("url");
    try {
      HttpUrl url = target == null ? null : publicUrl(target, true);
      if (url == null) throw new IOException();
      Cover cover = loadCover(context, url);
      return new WebResourceResponse(
          cover.type,
          null,
          200,
          "OK",
          java.util.Collections.singletonMap("Cache-Control", "private, max-age=86400"),
          new ByteArrayInputStream(cover.bytes));
    } catch (Exception e) {
      return new WebResourceResponse(
          "text/plain",
          "UTF-8",
          404,
          "Not Found",
          java.util.Collections.emptyMap(),
          new ByteArrayInputStream(new byte[0]));
    }
  }

  private static Cover loadCover(Context context, HttpUrl url) throws Exception {
    String key = url.toString();
    Cover ready = COVERS.get(key);
    if (ready != null) return ready;
    FutureTask<Cover> load =
        new FutureTask<>(
            () -> {
              byte[] disk = covers(context).read(key);
              if (disk != null) {
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(disk))) {
                  String type = input.readUTF();
                  return new Cover(type, MusicStore.read(input, COVER_IMAGE_LIMIT));
                } catch (IOException ignored) {
                  // Re-download incomplete cache entries.
                }
              }
              try (Response res =
                  execute(
                      artworkHttp(context),
                      new Request.Builder().url(url).build(),
                      4,
                      null,
                      true)) {
                ResponseBody body = res.body();
                if (!res.isSuccessful()
                    || body == null
                    || body.contentLength() > COVER_SOURCE_LIMIT) throw new IOException();
                boolean migu = url.host().equals("d.musicapp.migu.cn");
                Cover cover =
                    decodeCover(MusicStore.read(body.byteStream(), COVER_SOURCE_LIMIT), migu);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream output = new DataOutputStream(bytes)) {
                  output.writeUTF(cover.type);
                  output.write(cover.bytes);
                }
                covers(context).write(key, bytes.toByteArray());
                return cover;
              }
            });
    FutureTask<Cover> pending = COVER_REQUESTS.putIfAbsent(key, load);
    if (pending != null) return pending.get();
    try {
      load.run();
      Cover cover = load.get();
      COVERS.put(key, cover);
      return cover;
    } finally {
      COVER_REQUESTS.remove(key, load);
    }
  }

  static String embeddedCover(Context context, android.net.Uri uri) throws IOException {
    android.media.MediaMetadataRetriever media = new android.media.MediaMetadataRetriever();
    try {
      media.setDataSource(context, uri);
      byte[] bytes = media.getEmbeddedPicture();
      if (bytes == null || bytes.length > COVER_SOURCE_LIMIT) return "";
      Cover image = decodeCover(bytes, false);
      return "data:" + image.type + ";base64," +
          android.util.Base64.encodeToString(image.bytes, android.util.Base64.NO_WRAP);
    } finally {
      media.release();
    }
  }

  private static Cover decodeCover(byte[] bytes, boolean migu) throws IOException {
    int outputSide = migu ? 420 : COVER_SIDE;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    String mime = options.outMimeType;
    // Older Android versions rely on WebView to decode AVIF.
    if (mime == null && bytes.length >= 16 && bytes.length <= COVER_IMAGE_LIMIT) {
      String brand = new String(bytes, 4, 8, StandardCharsets.US_ASCII);
      if ("ftypavif".equals(brand) || "ftypavis".equals(brand))
        return new Cover("image/avif", bytes);
    }
    if (!Arrays.asList("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif")
            .contains(mime)
        || options.outWidth <= 0
        || options.outHeight <= 0) throw new IOException();
    int side = Math.max(options.outWidth, options.outHeight);
    if (side <= outputSide && bytes.length <= (migu ? 96 * 1024 : 360 * 1024))
      return new Cover(mime, bytes);

    options.inJustDecodeBounds = false;
    options.inSampleSize = 1;
    while (side / options.inSampleSize > 2 * outputSide) options.inSampleSize *= 2;
    Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (image == null) throw new IOException();
    try {
      int decodedSide = Math.max(image.getWidth(), image.getHeight());
      if (decodedSide > outputSide) {
        double scale = outputSide / (double) decodedSide;
        Bitmap scaled =
            Bitmap.createScaledBitmap(
                image,
                Math.max(1, (int) Math.round(image.getWidth() * scale)),
                Math.max(1, (int) Math.round(image.getHeight() * scale)),
                true);
        image.recycle();
        image = scaled;
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      boolean alpha = image.hasAlpha() && !migu;
      if (!image.compress(
          alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 85, output))
        throw new IOException();
      return new Cover(alpha ? "image/png" : "image/jpeg", output.toByteArray());
    } finally {
      image.recycle();
    }
  }

  static JSONObject run(Context context, JSONObject arg) throws Exception {
    return run(context, arg, null);
  }

  static JSONObject run(Context context, JSONObject arg, CancellationSignal cancellation)
      throws Exception {
    if (!MusicStore.PLATFORMS.contains(arg.getString("platform")))
      throw new IOException("在线资源来源无效");
    QuickJSLoader.init();
    QuickJSContext vm = QuickJSContext.create();
    String[] output = new String[1];
    try {
      vm.setMemoryLimit(64 * 1024 * 1024);
      vm.getGlobalObject()
          .setProperty(
              "__catalog_native",
              args -> {
                try {
                  if (cancellation != null) cancellation.throwIfCanceled();
                  return obj(
                          "value",
                          bridge((String) args[0], new JSONArray((String) args[1]), cancellation))
                      .toString();
                } catch (Exception e) {
                  try {
                    return obj("error", message(e)).toString();
                  } catch (JSONException ignored) {
                    return "{\"error\":\"在线资源请求失败\"}";
                  }
                }
              });
      vm.getGlobalObject()
          .setProperty(
              "__catalog_done",
              args -> {
                output[0] = (String) args[0];
                return null;
              });
      vm.evaluate(MusicStore.text(context.getAssets().open("catalog-host.js"), MusicStore.LIMIT));
      vm.evaluate(
          MusicStore.text(context.getAssets().open("online-metadata.js"), MusicStore.LIMIT));
      vm.evaluate(
          "OnlineMetadata.catalog(JSON.parse("
              + JSONObject.quote(arg.toString())
              + "))"
              + ".then(value => __catalog_done(JSON.stringify({value})), error => "
              + "__catalog_done(JSON.stringify({error: String(error.message || error)})))");
      if (output[0] == null) throw new IOException("在线资源插件未返回数据");
      JSONObject result = new JSONObject(output[0]);
      if (result.has("error")) throw new IOException(result.getString("error"));
      return result.getJSONObject("value");
    } finally {
      vm.destroy();
    }
  }

  private static String message(Exception e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private static byte[] input(Object data, String encoding) throws Exception {
    if (data instanceof JSONArray) {
      JSONArray a = (JSONArray) data;
      byte[] bytes = new byte[a.length()];
      for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) a.getInt(i);
      return bytes;
    }
    String text = (String) data;
    if ("base64".equals(encoding)) return Base64.decode(text, Base64.DEFAULT);
    if ("hex".equals(encoding)) return okio.ByteString.decodeHex(text).toByteArray();
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static Object convert(byte[] data, String encoding) {
    switch (encoding) {
      case "base64":
        return Base64.encodeToString(data, Base64.NO_WRAP);
      case "hex":
        return okio.ByteString.of(data).hex();
      case "binary":
        JSONArray out = new JSONArray();
        for (byte b : data) out.put(b & 255);
        return out;
      default:
        return new String(data, StandardCharsets.UTF_8);
    }
  }

  private static byte[] inflate(byte[] bytes, boolean allowPartial) throws Exception {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(bytes);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      while (!inflater.finished()) {
        int count = inflater.inflate(buffer);
        if (count == 0) {
          if (inflater.finished() || (allowPartial && inflater.needsInput())) break;
          throw new IOException("压缩歌词不完整");
        }
        if (out.size() + count > 1024 * 1024) throw new IOException("歌词超过大小限制");
        out.write(buffer, 0, count);
      }
      return out.toByteArray();
    } finally {
      inflater.end();
    }
  }

  private static Object bridge(String name, JSONArray a, CancellationSignal cancellation)
      throws Exception {
    switch (name) {
      case "request":
        return request(a.getString(0), a.getJSONObject(1), cancellation);
      case "decode":
        String charset = a.getString(1);
        return new String(
            input(a.get(0), "utf-8"),
            "utf16le".equalsIgnoreCase(charset)
                ? StandardCharsets.UTF_16LE
                : Charset.forName(charset));
      case "inflate":
        byte[] inflated = inflate(input(a.get(0), "base64"), "Z_SYNC_FLUSH".equals(a.optString(2)));
        return convert(inflated, "binary".equals(a.getString(1)) ? "base64" : "utf-8");
      case "convert":
        return convert(input(a.get(0), a.getString(1)), a.getString(2));
      case "randomHex":
        byte[] nonce = new byte[8];
        RANDOM.nextBytes(nonce);
        return okio.ByteString.of(nonce).hex();
      case "digest":
        return convert(
            MessageDigest.getInstance(a.getString(0)).digest(input(a.get(1), "utf-8")), "hex");
      case "aes":
        boolean cbc = "CBC_128_PKCS7Padding".equals(a.getString(0));
        int mode = a.getBoolean(5) ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE;
        Cipher aes = Cipher.getInstance(cbc ? "AES/CBC/PKCS5Padding" : "AES/ECB/PKCS5Padding");
        SecretKeySpec key = new SecretKeySpec(input(a.get(2), "utf-8"), "AES");
        if (cbc) aes.init(mode, key, new IvParameterSpec(input(a.get(3), "utf-8")));
        else aes.init(mode, key);
        return convert(aes.doFinal(input(a.get(1), "utf-8")), a.getString(4));
      case "rsa":
        String pem =
            new String(input(a.get(2), "utf-8"), StandardCharsets.UTF_8)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .trim();
        Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
        rsa.init(
            Cipher.ENCRYPT_MODE,
            KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.decode(pem, Base64.DEFAULT))));
        return convert(rsa.doFinal(input(a.get(1), "utf-8")), "base64");
      default:
        throw new IOException("在线资源插件请求了未知操作");
    }
  }

  private static Response execute(
      OkHttpClient client,
      Request request,
      int redirects,
      CancellationSignal cancellation,
      boolean artwork)
      throws IOException {
    if (publicUrl(request.url().toString(), artwork) == null) throw new IOException("在线资源地址无效");
    while (true) {
      Call call = client.newCall(request);
      if (cancellation != null) {
        cancellation.throwIfCanceled();
        cancellation.setOnCancelListener(call::cancel);
      }
      Response response = call.execute();
      if (!response.isRedirect() || redirects-- <= 0 || response.header("Location") == null)
        return response;
      try (Response redir = response) {
        HttpUrl resolved = request.url().resolve(redir.header("Location"));
        HttpUrl next = resolved == null ? null : publicUrl(resolved.toString(), artwork);
        if (next == null) throw new IOException("在线资源重定向地址无效");
        Request.Builder builder = request.newBuilder().url(next);
        if (!next.host().equals(request.url().host()))
          builder.removeHeader("Cookie").removeHeader("Authorization");
        if (redir.code() != 307 && redir.code() != 308 && !request.method().equals("HEAD"))
          builder.get().removeHeader("Content-Type");
        request = builder.build();
      }
    }
  }

  private static JSONObject request(
      String link, JSONObject options, CancellationSignal cancellation) throws Exception {
    HttpUrl safe = publicUrl(link);
    if (safe == null) throw new IOException("在线资源地址无效");
    HttpUrl.Builder url = safe.newBuilder();
    JSONObject query = options.optJSONObject("query");
    if (query != null)
      for (Iterator<String> it = query.keys(); it.hasNext(); ) {
        String key = it.next();
        if (!query.isNull(key)) url.addQueryParameter(key, query.getString(key));
      }
    Request.Builder req = new Request.Builder().url(url.build());
    JSONObject headers = options.optJSONObject("headers");
    if (headers != null)
      for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
        String key = it.next();
        req.header(key, headers.getString(key));
      }
    RequestBody body = null;
    String method = options.optString("method", "GET");
    JSONObject form = options.optJSONObject("form");
    if (form != null) {
      FormBody.Builder fields = new FormBody.Builder();
      for (Iterator<String> it = form.keys(); it.hasNext(); ) {
        String key = it.next();
        if (!form.isNull(key)) fields.add(key, form.getString(key));
      }
      body = fields.build();
    } else if (options.has("json")) {
      body = RequestBody.create(options.get("json").toString(), MediaType.get("application/json"));
    } else if ("POST".equals(method)) {
      body = RequestBody.create(options.optString("text"), MediaType.get("text/plain"));
    }
    try (Response response =
        execute(
            HTTP,
            req.method(method, body).build(),
            options.optInt("maxRedirect", 4),
            cancellation,
            false)) {
      ResponseBody responseBody = response.body();
      JSONObject payload;
      if (options.optBoolean("needRaw")) {
        byte[] bytes =
            responseBody == null
                ? new byte[0]
                : MusicStore.read(responseBody.byteStream(), 1024 * 1024);
        payload = obj("raw", convert(bytes, "base64"));
      } else {
        String text =
            responseBody == null ? "" : MusicStore.text(responseBody.byteStream(), 8 * 1024 * 1024);
        Object value = text;
        String start = text.trim();
        if (start.startsWith("{") || start.startsWith("["))
          try {
            value = new JSONTokener(text).nextValue();
          } catch (JSONException ignored) {
          }
        payload = obj("body", value);
      }
      JSONObject resultHeaders = new JSONObject();
      for (String key : response.headers().names())
        resultHeaders.put(key.toLowerCase(Locale.ROOT), response.header(key));
      return payload
          .put("statusCode", response.code())
          .put("headers", resultHeaders)
          .put("history", new JSONArray().put(link).put(response.request().url().toString()));
    } finally {
      if (cancellation != null) cancellation.setOnCancelListener(null);
    }
  }
}
