package io.github.anylisten.midnight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.*;
import java.math.BigInteger;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

/** Manual updates for the installed TideListen flavor. */
final class AppUpdater {
  static final int INSTALL_PERMISSION = 42, INSTALL_RESULT = 43;
  static final String REPOSITORY = "https://github.com/songjiezhang/tide-listen";
  private static final Pattern TAG = Pattern.compile("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-beta\\.[0-9]+)?");
  private static final int PAGE_SIZE = 100;
  private static final String API =
      "https://api.github.com/repos/songjiezhang/tide-listen/releases";
  private static final long MAX_APK = 150L * 1024 * 1024;
  private static final OkHttpClient HTTP =
      new OkHttpClient.Builder().followSslRedirects(false).callTimeout(5, TimeUnit.MINUTES).build();
  private final Activity activity;
  private Call active;
  private File pending;
  private boolean checking;

  AppUpdater(Activity activity, String savedName) {
    this.activity = activity;
    pending = UpdatePackages.open(activity, savedName);
  }

  void finishInstall() {
    if (pending != null) pending.delete();
    pending = null;
  }

  String pendingName() {
    return pending == null ? null : pending.getName();
  }

  void close() {
    if (active != null) active.cancel();
  }

  private void message(String text) {
    Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
  }

  void cancelCheck() {
    if (checking && active != null) {
      active.cancel();
      active = null;
    }
  }

  void check(boolean includeBeta) {
    if (active != null || pending != null) {
      message("正在检查或下载安装包");
      return;
    }
    message("正在检查汐听更新…");
    if (includeBeta) {
      checkPage(1, null);
    } else {
      fetch(API + "/latest", null, body -> {
        JSONObject latest = new JSONObject(body.string());
        if (!eligible(latest, false)) throw new IOException("正式版发布信息异常");
        return updatePrompt(latest);
      });
    }
  }

  private void checkPage(int page, JSONObject previous) {
    fetch(API + "?per_page=" + PAGE_SIZE + "&page=" + page, null, body -> {
      JSONArray releases = new JSONArray(body.string());
      JSONObject best = newestForEdition(releases, previous, BuildConfig.EDITION);
      if (releases.length() == PAGE_SIZE) return () -> checkPage(page + 1, best);
      if (best == null) throw new IOException("未找到适用于当前设备的发布版本");
      return updatePrompt(best);
    });
  }

  static boolean eligible(JSONObject release, boolean includeBeta) {
    String tag = release.optString("tag_name");
    if (release.optBoolean("draft") || !TAG.matcher(tag).matches()) return false;
    boolean beta = tag.contains("-beta.");
    return beta == release.optBoolean("prerelease") && (includeBeta || !beta);
  }

  static JSONObject newestForEdition(JSONArray releases, JSONObject best, String edition) {
    for (int i = 0; i < releases.length(); i++) {
      JSONObject release = releases.optJSONObject(i);
      if (release == null || !eligible(release, true) || assetFor(release, edition) == null) continue;
      if (best == null || compareVersions(version(release), version(best)) > 0) best = release;
    }
    return best;
  }

  private static String version(JSONObject release) {
    return release.optString("tag_name").substring(1);
  }

  private static JSONObject assetFor(JSONObject release, String edition) {
    String tag = release.optString("tag_name");
    String name = "TideListen-" + edition + "-" + version(release) + ".apk";
    String url = REPOSITORY + "/releases/download/" + tag + "/" + name;
    JSONArray assets = release.optJSONArray("assets");
    for (int i = 0; assets != null && i < assets.length(); i++) {
      JSONObject asset = assets.optJSONObject(i);
      if (asset != null && name.equals(asset.optString("name"))
          && url.equals(asset.optString("browser_download_url"))) return asset;
    }
    return null;
  }

  private Runnable updatePrompt(JSONObject release) throws Exception {
    String version = version(release);
    if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0)
      return () -> message("当前已是最新版本 " + BuildConfig.VERSION_NAME);
    JSONObject asset = assetFor(release, BuildConfig.EDITION);
    if (asset == null) throw new IOException("这个版本尚未提供当前设备的安装包");
    String url = asset.getString("browser_download_url");
    String digest = asset.getString("digest");
    long size = asset.getLong("size");
    if (!digest.matches("sha256:[a-fA-F0-9]{64}") || size <= 0 || size > MAX_APK)
      throw new IOException("安装包校验信息异常");
    return () -> new AlertDialog.Builder(activity)
        .setTitle("发现新版本 " + version)
        .setMessage("当前版本 " + BuildConfig.VERSION_NAME + "\n下载大小 " + ((size + 1048575) / 1048576) + " MB")
        .setNegativeButton("稍后", null)
        .setPositiveButton("下载并安装", (dialog, which) -> download(url, size, digest.substring(7), version))
        .show();
  }

  private interface ReadResponse {
    Runnable read(ResponseBody body) throws Exception;
  }

  private void fetch(String url, File temporary, ReadResponse reader) {
    Call call =
        HTTP.newCall(
            new Request.Builder()
                .url(url)
                .header("User-Agent", "TideListen/" + BuildConfig.VERSION_NAME)
                .build());
    active = call;
    checking = temporary == null;
    call.enqueue(
        new Callback() {
          private void finish(Runnable result) {
            activity.runOnUiThread(
                () -> {
                  try {
                    if (active != call) return;
                    active = null;
                    if (!activity.isDestroyed() && !activity.isFinishing()) result.run();
                  } finally {
                    if (temporary != null) temporary.delete();
                  }
                });
          }

          public void onFailure(Call call, IOException e) {
            finish(() -> message("更新失败 · " + e.getMessage()));
          }

          public void onResponse(Call call, Response response) {
            try (Response resource = response) {
              if (!resource.isSuccessful() || resource.body() == null)
                throw new IOException("GitHub HTTP " + resource.code());
              finish(reader.read(resource.body()));
            } catch (Exception e) {
              finish(() -> message("更新失败 · " + e.getMessage()));
            }
          }
        });
  }

  static int compareVersions(String left, String right) {
    String[] a = left.split("\\.|-beta\\.");
    String[] b = right.split("\\.|-beta\\.");
    for (int i = 0; i < Math.min(a.length, b.length); i++) {
      int order = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
      if (order != 0) return order;
    }
    // For the same numeric version, a final release follows every beta.
    return Integer.compare(b.length, a.length);
  }

  private void download(String url, long expected, String hash, String version) {
    if (active != null || pending != null) return;
    File tmp;
    try {
      tmp = UpdatePackages.create(activity);
    } catch (IOException e) {
      message("更新失败 · " + e.getMessage());
      return;
    }
    message("正在下载安装包，完成后将打开系统安装界面");
    fetch(
        url, tmp,
        body -> {
          MessageDigest digest = MessageDigest.getInstance("SHA-256");
          long total = 0;
          try (InputStream in = body.byteStream();
              OutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
              total += n;
              if (total > expected) throw new IOException("安装包长度异常");
              out.write(buffer, 0, n);
              digest.update(buffer, 0, n);
            }
          }
          if (total != expected
              || !okio.ByteString.of(digest.digest()).hex().equalsIgnoreCase(hash))
            throw new IOException("安装包完整性校验失败");
          PackageInfo info =
              activity.getPackageManager().getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
          if (info == null
              || !activity.getPackageName().equals(info.packageName)
              || !version.equals(info.versionName)
              || info.versionCode <= BuildConfig.VERSION_CODE)
            throw new IOException("安装包与当前应用或版本不匹配");
          return () -> {
            try {
              pending = UpdatePackages.handOff(activity, tmp);
              install(true);
            } catch (IOException e) {
              message("更新失败 · " + e.getMessage());
            }
          };
        });
  }

  void install(boolean askPermission) {
    if (pending == null || !pending.isFile()) {
      finishInstall();
      return;
    }
    try {
      if (Build.VERSION.SDK_INT >= 26
          && !activity.getPackageManager().canRequestPackageInstalls()) {
        if (askPermission) {
          activity.startActivityForResult(
              new Intent(
                  Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                  Uri.parse("package:" + activity.getPackageName())),
              INSTALL_PERMISSION);
        } else {
          finishInstall();
          message("需要允许此应用安装更新");
        }
        return;
      }
      Uri apk =
          FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", pending);
      activity.startActivityForResult(
          new Intent(Intent.ACTION_INSTALL_PACKAGE)
              .setDataAndType(apk, "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              .putExtra(Intent.EXTRA_RETURN_RESULT, true),
          INSTALL_RESULT);
    } catch (RuntimeException e) {
      finishInstall();
      message("无法打开系统安装器 · " + e.getMessage());
    }
  }
}
