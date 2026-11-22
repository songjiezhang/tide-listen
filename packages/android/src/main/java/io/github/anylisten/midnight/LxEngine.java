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
final class LxEngine {
  static final OkHttpClient HTTP =
      new OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build();
  static final int SCRIPT_LIMIT = 2 * 1024 * 1024;
  private final HandlerThread thread = new HandlerThread("LX Music");
  private final String key = UUID.randomUUID().toString();
  private final CountDownLatch done = new CountDownLatch(1);
  private final Map<String, Call> calls = new ConcurrentHashMap<>();
  private String preferredQuality;
  private Handler handler;
  private QuickJSContext js;
  private JSONObject result;
  private Exception error;
  private volatile boolean ended;

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
    if (script.isEmpty()) throw new IOException("请先在音源设置中导入洛雪 JS 音源");
    LxEngine engine = new LxEngine();
    engine.preferredQuality = quality;
    return engine.execute(c, script, song);
  }

  private JSONObject execute(Context c, String script, JSONObject song) throws Exception {
    String preload = MusicStore.text(c.getAssets().open("lx-preload.js"), SCRIPT_LIMIT);
    thread.start();
    handler = new Handler(thread.getLooper());
    handler.post(
        () -> {
          try {
            QuickJSLoader.init();
            js = QuickJSContext.create();
            js.setMemoryLimit(48 * 1024 * 1024);
            js.evaluate(
                "globalThis.console = { log(){}, info(){}, warn(){}, error(){}, debug(){} }");
            bind(song);
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
    try {
      if (!done.await(35, TimeUnit.SECONDS)) throw new IOException("音源请求超时");
      if (error instanceof QuickJSException) {
        String detail = error.getMessage() == null ? "" : error.getMessage().split("[\r\n]", 2)[0]
            .replaceFirst("^(?:UnhandledPromiseRejectionException:\\s*)?(?:Error:\\s*)?", "").trim();
        throw new IOException(detail.isEmpty() ? "音源脚本运行失败" : detail, error);
      }
      if (error != null) throw error;
      return result;
    } finally {
      ended = true;
      for (Call call : calls.values()) call.cancel();
      handler.removeCallbacksAndMessages(null);
      handler.post(
          () -> {
            try {
              if (js != null) js.destroy();
            } finally {
              thread.quit();
            }
          });
    }
  }

  private void fail(Exception e) {
    if (!ended) {
      error = e;
      done.countDown();
    }
  }

  private void send(String action, Object data) {
    if (ended) return;
    try {
      js.getGlobalObject().getJSFunction("__lx_native__").call(key, action, data.toString());
    } catch (Exception e) {
      fail(e);
    }
  }

  private void bind(JSONObject song) {
    js.getGlobalObject()
        .setProperty(
            "__lx_native_call__",
            args -> {
              if (!key.equals(args[0])) return null;
              String action = (String) args[1], data = (String) args[2];
              handler.post(
                  () -> {
                    try {
                      if (!ended) receive(action, data, song);
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

  private void receive(String action, String data, JSONObject song) throws Exception {
    if (action.equals("cancelRequest")) {
      Call call = calls.remove(String.valueOf(new JSONTokener(data).nextValue()));
      if (call != null) call.cancel();
      return;
    }
    JSONObject msg = new JSONObject(data);
    switch (action) {
      case "init":
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
        result = obj("platforms", supported, "qualities", qualityMap);
        if (song == null) {
          done.countDown();
          return;
        }
        String p = song.getString("source");
        if (!supported.toString().contains("\"" + p + "\""))
          throw new IOException("当前音源不支持" + MusicStore.platformName(p));
        JSONArray qualities = sources.getJSONObject(p).getJSONArray("qualitys");
        String quality =
            "auto".equals(preferredQuality) ? qualities.getString(0) : preferredQuality;
        if (!LxSources.includes(qualities, quality)) throw new IOException("音源不支持此音质");
        send(
            "request",
            obj(
                "requestKey",
                key,
                "data",
                obj(
                    "source",
                    p,
                    "action",
                    "musicUrl",
                    "info",
                    obj("type", quality, "musicInfo", song))));
        break;
      case "request":
        request(msg);
        break;
      case "response":
        if (!key.equals(msg.optString("requestKey"))) return;
        if (!msg.optBoolean("status"))
          throw new IOException(msg.optString("errorMessage", "音源解析失败"));
        String url = msg.getJSONObject("result").getJSONObject("data").getString("url");
        if (HttpUrl.parse(url) == null) throw new IOException("音源返回了无效的播放地址");
        result.put("url", url);
        done.countDown();
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
      Call call = HTTP.newCall(req.method(method, body).build());
      call.timeout()
          .timeout(
              Math.max(1, Math.min(60000, o.optLong("timeout", 15000))), TimeUnit.MILLISECONDS);
      calls.put(id, call);
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
