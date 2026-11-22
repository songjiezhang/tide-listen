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
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

/** Manual updates for the installed TideListen flavor. */
final class AppUpdater {
  static final int INSTALL_PERMISSION = 42, INSTALL_RESULT = 43;
  static final String REPOSITORY = "https://github.com/songjiezhang/tide-listen";
  private static final String API =
      "https://api.github.com/repos/songjiezhang/tide-listen/releases/latest";
  private static final long MAX_APK = 150L * 1024 * 1024;
  private static final OkHttpClient HTTP =
      new OkHttpClient.Builder().followSslRedirects(false).callTimeout(5, TimeUnit.MINUTES).build();
  private final Activity activity;
  private Call active;
  private File pending;

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

  void check() {
    if (active != null || pending != null) {
      message("正在检查或下载安装包");
      return;
    }
    message("正在检查汐听更新…");
    fetch(
        API, null,
        response -> {
          JSONObject release = new JSONObject(response.string());
          String tag = release.getString("tag_name");
          if (release.optBoolean("draft")
              || release.optBoolean("prerelease")
              || !tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("发布版本格式异常");
          String version = tag.substring(1);
          if (compare(version, BuildConfig.VERSION_NAME) <= 0)
            return () -> message("当前已是最新版本 " + BuildConfig.VERSION_NAME);
          String name = "TideListen-" + BuildConfig.EDITION + "-" + version + ".apk";
          String url = REPOSITORY + "/releases/download/" + tag + "/" + name;
          JSONArray files = release.getJSONArray("assets");
          for (int i = 0; i < files.length(); i++) {
            JSONObject asset = files.getJSONObject(i);
            if (!name.equals(asset.optString("name"))
                || !url.equals(asset.optString("browser_download_url"))) continue;
            String digest = asset.getString("digest");
            long size = asset.getLong("size");
            if (!digest.matches("sha256:[a-fA-F0-9]{64}") || size <= 0 || size > MAX_APK)
              throw new IOException("安装包校验信息异常");
            return () ->
                new AlertDialog.Builder(activity)
                    .setTitle("发现新版本 " + version)
                    .setMessage(
                        "当前版本 "
                            + BuildConfig.VERSION_NAME
                            + "\n下载大小 "
                            + ((size + 1048575) / 1048576)
                            + " MB")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton(
                        "下载并安装",
                        (dialog, which) -> download(url, size, digest.substring(7), version))
                    .show();
          }
          throw new IOException("这个版本尚未提供当前设备的安装包");
        });
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

  static int compare(String left, String right) {
    String[] a = left.split("\\."), b = right.split("\\.");
    for (int i = 0; i < 3; i++) {
      int order = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
      if (order != 0) return order;
    }
    return 0;
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
