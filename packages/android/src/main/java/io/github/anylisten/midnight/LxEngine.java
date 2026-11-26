package io.github.anylisten.midnight;

import static io.github.anylisten.midnight.MusicStore.obj;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.QuickJSContext;
import com.whl.quickjs.wrapper.QuickJSException;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import okhttp3.*;
import org.json.*;

/** One LX mobile protocol session, with its own JS thread and cancellable HTTP calls. */
final class LxEngine implements LxEnginePool.Engine {
  static final OkHttpClient HTTP =
      new OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build();
  private static final OkHttpClient SCRIPT_HTTP = HTTP.newBuilder().retryOnConnectionFailure(false).build();
  static final int SCRIPT_LIMIT = 2 * 1024 * 1024;
  private static final long INIT_TIMEOUT_MS = 12_000;
  private final HandlerThread thread = new HandlerThread("LX Music");
  private final String key = UUID.randomUUID().toString();
  private final CountDownLatch initialized = new CountDownLatch(1);
  private final Map<String, Call> calls = new ConcurrentHashMap<>();
  private final Map<String, PendingCall> pendingRequests = new ConcurrentHashMap<>();
  private final Handler handler;
  private QuickJSContext js;
  private JSONObject capabilities;
  private volatile Exception fatalError;
  private volatile boolean ended;
  private final Runnable onBroken;

  private static final class PendingCall {
    final CountDownLatch done = new CountDownLatch(1);
    String url;
    Exception error;

    synchronized void complete(String url, Exception error) {
      if (done.getCount() == 0) return;
      this.url = url;
      this.error = error;
      done.countDown();
    }
  }

  static final class UnsupportedSource extends IOException {
    final boolean platform;

    UnsupportedSource(String message, boolean platform) {
      super(message);
      this.platform = platform;
    }
  }

  static String tag(String js, String key, String fallback) {
    Matcher m =
        Pattern.compile("(?m)^\\s*(?:/\\*+|\\*|//)?\\s*@" + key + "[ \\t]+([^\\r\\n]+)")
            .matcher(js);
    return m.find() ? m.group(1).replaceFirst("\\s*\\*/\\s*$", "").trim() : fallback;
  }

  static HttpUrl sourceUrl(String text) throws IOException {
    HttpUrl url = HttpUrl.parse(text.trim());
    if (url == null || !url.username().isEmpty() || !url.password().isEmpty())
      throw new IOException("请输入 JS 文件的 HTTP 或 HTTPS 直链");
    return url.newBuilder().fragment(null).build();
  }

  static String download(HttpUrl url) throws IOException {
    Request req = new Request.Builder().url(url).header("Cache-Control", "no-cache").build();
    try (Response res = HTTP.newCall(req).execute()) {
      if (res.code() != 200) throw new IOException("音源服务器返回 HTTP " + res.code());
      return MusicStore.text(res.body().byteStream(), SCRIPT_LIMIT);
    }
  }

  static JSONObject run(Context c, String script, JSONObject song, String quality)
      throws Exception {
    LxEngine engine = open(c, script, () -> {});
    try {
      JSONObject result = new JSONObject(engine.capabilities().toString());
      if (song != null) result.put("url", engine.resolve(song, quality, SourceRace.ATTEMPT_TIMEOUT_MS));
      return result;
    } finally {
      engine.close();
    }
  }

  static LxEngine open(Context c, String script, Runnable onBroken) throws Exception {
    if (script.isEmpty()) throw new IOException("请先在音源设置中导入洛雪 JS 音源");
    String preload = MusicStore.text(c.getAssets().open("lx-preload.js"), SCRIPT_LIMIT);
    LxEngine engine = new LxEngine(preload, script, onBroken);
    try {
      if (!engine.initialized.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        throw new IOException("音源初始化超时");
      if (engine.fatalError != null) throw engine.fatalError;
      return engine;
    } catch (Exception error) {
      engine.close();
      throw error;
    }
  }

  private LxEngine(String preload, String script, Runnable onBroken) {
    this.onBroken = onBroken;
    thread.start();
    handler = new Handler(thread.getLooper());
    handler.post(
        () -> {
          try {
            if (ended) return;
            QuickJSLoader.init();
            js = QuickJSContext.create();
            js.setMemoryLimit(48 * 1024 * 1024);
            js.evaluate(
                "globalThis.console = { log(){}, info(){}, warn(){}, error(){}, debug(){} }");
            bind();
            js.evaluate(preload);
            js.getGlobalObject()
                .getJSFunction("lx_setup")
                .call(
                    key,
                    "custom",
                    tag(script, "name", "自定义音源"),
                    tag(script, "description", ""),
                    tag(script, "version", ""),
                    tag(script, "author", ""),
                    tag(script, "homepage", ""),
                    script);
            js.evaluate(script);
          } catch (Exception e) {
            fail(e);
          }
        });
  }

  JSONObject capabilities() {
    return capabilities;
  }

  @Override
  public boolean isBroken() {
    return ended;
  }

  String resolve(JSONObject song, String quality, long timeoutMs) throws Exception {
    String platform = song.getString("source");
    JSONArray qualities = capabilities.getJSONObject("qualities").optJSONArray(platform);
    if (qualities == null)
      throw new UnsupportedSource("当前音源不支持" + MusicStore.platformName(platform), true);
    String type = "auto".equals(quality) ? qualities.getString(0) : quality;
    if (!LxSources.includes(qualities, type)) throw new UnsupportedSource("音源不支持此音质", false);
    String reqId = UUID.randomUUID().toString();
    JSONObject payload = obj("requestKey", reqId, "data",
        obj("source", platform, "action", "musicUrl", "info", obj("type", type, "musicInfo", song)));
    PendingCall pending = new PendingCall();
    synchronized (this) {
      if (ended) throw fatalError != null ? fatalError : new IOException("音源会话已关闭");
      pendingRequests.put(reqId, pending);
      handler.post(() -> {
        if (pendingRequests.containsKey(reqId)) send("request", payload);
      });
    }
    try {
      if (!pending.done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new TimeoutException("音源请求超时");
      if (pending.error != null) throw pending.error;
      return pending.url;
    } finally {
      synchronized (this) {
        pendingRequests.remove(reqId);
        if (pendingRequests.isEmpty()) {
          for (Call call : calls.values()) call.cancel();
        }
      }
    }
  }

  @Override
  public synchronized void close() {
    if (ended) return;
    ended = true;
    Exception cause = fatalError != null ? fatalError : new IOException("音源会话已关闭");
    initialized.countDown();
    for (PendingCall pending : pendingRequests.values()) pending.complete(null, cause);
    pendingRequests.clear();
    for (Call call : calls.values()) call.cancel();
    calls.clear();
    handler.removeCallbacksAndMessages(null);
    handler.post(() -> {
      try {
        if (js != null) js.destroy();
      } finally {
        thread.quitSafely();
      }
    });
  }

  private void fail(Exception error) {
    if (error instanceof QuickJSException) {
      String detail = error.getMessage() == null ? "" : error.getMessage().split("[\r\n]", 2)[0]
          .replaceFirst("^(?:UnhandledPromiseRejectionException:\\s*)?(?:Error:\\s*)?", "").trim();
      error = new IOException(detail.isEmpty() ? "音源脚本运行失败" : detail, error);
    }
    synchronized (this) {
      if (ended) return;
      fatalError = error;
      close();
    }
    // Pool eviction can close another engine. Never acquire the pool lock under the JS lock.
    onBroken.run();
  }

  private void send(String action, Object data) {
    if (ended) return;
    try {
      js.getGlobalObject().getJSFunction("__lx_native__").call(key, action, data.toString());
    } catch (Exception e) {
      fail(e);
    }
  }

  private void bind() {
    js.getGlobalObject()
        .setProperty(
            "__lx_native_call__",
            args -> {
              if (!key.equals(args[0])) return null;
              String action = (String) args[1], data = (String) args[2];
              handler.post(
                  () -> {
                    try {
                      if (!ended) receive(action, data);
                    } catch (Exception e) {
                      fail(e);
                    }
                  });
              return null;
            });
    js.getGlobalObject()
        .setProperty(
            "__lx_native_call__set_timeout",
            a -> {
              handler.postDelayed(() -> send("__set_timeout__", a[0]), ((Number) a[1]).longValue());
              return null;
            });
    js.getGlobalObject()
        .setProperty(
            "__lx_native_call__utils_str2b64",
            a ->
                Base64.encodeToString(
                    ((String) a[0]).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    js.getGlobalObject()
        .setProperty(
            "__lx_native_call__utils_b642buf",
            a -> {
              JSONArray b = new JSONArray();
              for (byte v : Base64.decode((String) a[0], Base64.DEFAULT)) b.put(v & 255);
              return b.toString();
            });
    js.getGlobalObject()
        .setProperty(
            "__lx_native_call__utils_str2md5",
            a -> {
              try {
                byte[] b =
                    MessageDigest.getInstance("MD5")
                        .digest(
                            URLDecoder.decode((String) a[0], "UTF-8")
                                .getBytes(StandardCharsets.UTF_8));
                StringBuilder s = new StringBuilder();
                for (byte v : b) s.append(String.format(Locale.ROOT, "%02x", v));
                return s.toString();
              } catch (Exception e) {
                return "";
              }
            });
    js.getGlobalObject()
        .setProperty(
            "__lx_native_call__utils_aes_encrypt",
            a -> AES.encrypt((String) a[0], (String) a[1], (String) a[2], (String) a[3]));
    js.getGlobalObject()
        .setProperty(
            "__lx_native_call__utils_rsa_encrypt",
            a -> RSA.encryptRSAToString((String) a[0], (String) a[1], (String) a[2]));
  }

  private void receive(String action, String data) throws Exception {
    if (action.equals("cancelRequest")) {
      Call call = calls.remove(String.valueOf(new JSONTokener(data).nextValue()));
      if (call != null) call.cancel();
      return;
    }
    JSONObject msg = new JSONObject(data);
    switch (action) {
      case "init":
        if (initialized.getCount() == 0) return;
        if (!msg.optBoolean("status"))
          throw new IOException(msg.optString("errorMessage", "音源初始化失败"));
        JSONObject sources = msg.getJSONObject("info").getJSONObject("sources");
        JSONArray supported = new JSONArray();
        JSONObject qualityMap = new JSONObject();
        for (String p : Arrays.asList("wy", "tx", "kg", "kw", "mg")) {
          JSONObject source = sources.optJSONObject(p);
          if (source != null
              && source.getJSONArray("actions").toString().contains("\"musicUrl\"")
              && source.getJSONArray("qualitys").length() > 0) {
            supported.put(p);
            qualityMap.put(p, source.getJSONArray("qualitys"));
          }
        }
        if (supported.length() == 0) throw new IOException("此脚本没有声明可播放的平台或音质");
        capabilities = obj("platforms", supported, "qualities", qualityMap);
        initialized.countDown();
        break;
      case "request":
        request(msg);
        break;
      case "response":
        PendingCall pending = pendingRequests.get(msg.optString("requestKey"));
        if (pending == null) return;
        try {
          if (!msg.optBoolean("status"))
            throw new IOException(msg.optString("errorMessage", "音源解析失败"));
          String url = msg.getJSONObject("result").getJSONObject("data").getString("url");
          HttpUrl parsed = HttpUrl.parse(url);
          if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty())
            throw new IOException("音源返回了无效的播放地址");
          pending.complete(url, null);
        } catch (Exception error) {
          pending.complete(null, error);
        }
        break;
    }
  }

  private void request(JSONObject msg) throws Exception {
    String id = msg.getString("requestKey");
    try {
      JSONObject o = msg.getJSONObject("options"),
          h = o.optJSONObject("headers"),
          form = o.optJSONObject("form"),
          multipart = o.optJSONObject("formData");
      Request.Builder req =
          new Request.Builder().url(msg.getString("url")).header("Accept", "application/json");
      if (h != null)
        for (Iterator<String> it = h.keys(); it.hasNext(); ) {
          String k = it.next();
          req.header(k, h.getString(k));
        }
      String method = o.optString("method", "GET").toUpperCase(Locale.ROOT);
      RequestBody body = null;
      if (form != null) {
        FormBody.Builder f = new FormBody.Builder();
        for (Iterator<String> it = form.keys(); it.hasNext(); ) {
          String k = it.next();
          f.add(k, form.getString(k));
        }
        body = f.build();
      } else if (multipart != null) {
        MultipartBody.Builder f = new MultipartBody.Builder().setType(MultipartBody.FORM);
        for (Iterator<String> it = multipart.keys(); it.hasNext(); ) {
          String k = it.next();
          f.addFormDataPart(k, multipart.getString(k));
        }
        body = f.build();
      } else if (!method.equals("GET")
          && !method.equals("HEAD")
          && (o.has("body") || Arrays.asList("POST", "PUT", "PATCH").contains(method))) {
        String type =
            h == null
                ? "application/json"
                : h.optString("Content-Type", h.optString("content-type", "application/json"));
        body = RequestBody.create(o.optString("body", ""), MediaType.parse(type));
      }
      Call call = SCRIPT_HTTP.newCall(req.method(method, body).build());
      call.timeout()
          .timeout(
              Math.max(1, Math.min(15000, o.optLong("timeout", SourceRace.ATTEMPT_TIMEOUT_MS))), TimeUnit.MILLISECONDS);
      synchronized (this) {
        if (ended) return;
        if (initialized.getCount() == 0 && pendingRequests.isEmpty()) {
          reply(id, null, "播放请求已结束");
          return;
        }
        calls.put(id, call);
      }
      call.enqueue(
          new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
              reply(id, null, e.getMessage());
            }

            @Override
            public void onResponse(Call c, Response resp) {
              try (Response res = resp) {
                JSONObject headers = new JSONObject();
                for (String k : res.headers().names())
                  headers.put(k.toLowerCase(Locale.ROOT), res.header(k));
                byte[] bytes = MusicStore.read(res.body().byteStream(), MusicStore.LIMIT);
                Object payload;
                if (o.optBoolean("binary")) {
                  JSONArray arr = new JSONArray();
                  for (byte b : bytes) arr.put(b & 255);
                  payload = obj("type", "Buffer", "data", arr);
                } else {
                  String s = new String(bytes, StandardCharsets.UTF_8);
                  payload = s;
                  String trimmed = s.trim();
                  if (trimmed.startsWith("{") || trimmed.startsWith("["))
                    try {
                      payload = new JSONTokener(s).nextValue();
                    } catch (JSONException ignored) {
                    }
                }
                reply(
                    id,
                    obj(
                        "statusCode",
                        res.code(),
                        "statusMessage",
                        res.message(),
                        "headers",
                        headers,
                        "body",
                        payload,
                        "url",
                        res.request().url().toString(),
                        "ok",
                        res.isSuccessful()),
                    null);
              } catch (Exception e) {
                reply(id, null, e.getMessage());
              }
            }
          });
    } catch (Exception e) {
      reply(id, null, e.getMessage());
    }
  }

  private void reply(String id, JSONObject response, String message) {
    calls.remove(id);
    if (ended) return;
    handler.post(
        () -> {
          try {
            send(
                "response",
                obj(
                    "requestKey",
                    id,
                    "error",
                    message == null ? JSONObject.NULL : message,
                    "response",
                    response));
          } catch (Exception e) {
            fail(e);
          }
        });
  }
}
