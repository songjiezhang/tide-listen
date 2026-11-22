package io.github.anylisten.midnight;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns only updater-created APKs in the private update cache. */
public final class UpdatePackages extends BroadcastReceiver {
  private static final Pattern MANAGED =
      Pattern.compile("(?:update(?:-v\\d+)?-|install-v\\d+-(\\d+)-)-?\\d+\\.apk");
  private static final String DOWNLOAD = "update-v" + BuildConfig.VERSION_CODE + "-";
  private static final String INSTALL = "install-v" + BuildConfig.VERSION_CODE + "-";
  private static boolean opened;

  private static File directory(Context context) {
    return new File(context.getCacheDir(), "app-updates");
  }

  static File create(Context context) throws IOException {
    File dir = directory(context);
    if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
      throw new IOException("无法创建更新目录");
    return File.createTempFile(DOWNLOAD, ".apk", dir);
  }

  static File handOff(Activity activity, File file) throws IOException {
    File target = new File(directory(activity),
        INSTALL + activity.getTaskId() + "-" + file.getName().substring(DOWNLOAD.length()));
    if (!file.renameTo(target)) throw new IOException("无法准备安装包");
    return target;
  }

  static synchronized File open(Activity activity, String savedName) {
    File pending = null;
    if (savedName != null && savedName.startsWith(INSTALL) && MANAGED.matcher(savedName).matches()) {
      File saved = new File(directory(activity), savedName);
      if (saved.isFile()) pending = saved;
    }
    if (!opened && directory(activity).isDirectory()) {
      // Other tasks may still be in the installer or on the unknown-sources permission screen.
      ActivityManager tasks = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
      Set<String> owners = new HashSet<>();
      for (ActivityManager.AppTask task : tasks.getAppTasks()) {
        ActivityManager.RecentTaskInfo info;
        try {
          info = task.getTaskInfo();
        } catch (IllegalArgumentException removedTask) {
          continue;
        }
        int id = Build.VERSION.SDK_INT >= 29 ? info.taskId : info.persistentId;
        if (id != activity.getTaskId() || !activity.isTaskRoot()) owners.add(Integer.toString(id));
      }
      clean(activity, pending, owners);
    }
    opened = true;
    return pending;
  }

  private static void clean(Context context, File pending, Set<String> liveTasks) {
    File[] files = directory(context).listFiles();
    if (files == null) return;
    for (File file : files) {
      Matcher name = MANAGED.matcher(file.getName());
      if (!name.matches() || file.equals(pending) || !file.isFile()) continue;
      boolean downloading = file.getName().startsWith(DOWNLOAD);
      boolean installing = file.getName().startsWith(INSTALL);
      // A replacement broadcast can arrive after the new version has already started a download.
      if ((downloading || installing) && liveTasks == null) continue;
      if (installing && liveTasks.contains(name.group(1))) continue;
      file.delete();
    }
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) clean(context, null, null);
  }
}
