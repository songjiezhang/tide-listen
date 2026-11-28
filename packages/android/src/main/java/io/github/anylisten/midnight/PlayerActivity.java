package io.github.anylisten.midnight;

import static io.github.anylisten.midnight.MusicStore.obj;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.session.*;
import androidx.media3.session.MediaController;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.Call;
import org.json.*;

public final class PlayerActivity extends Activity {
  private static final int SCRIPT = 11, BACKUP = 12, AUDIO = 13;
  private static final String PLAYER_PAGE = "file:///android_asset/index.html";
  private static final String PROCESS_INSTANCE = UUID.randomUUID().toString();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ExecutorService catalogWorker = Executors.newFixedThreadPool(2);
  private final ExecutorService lyricWorker = Executors.newSingleThreadExecutor();
  private final ExecutorService syncWorker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private WebView page;
  private MusicStore store;
  private AppUpdater appUpdater;
  private ListenableFuture<MediaController> future;
  private MediaController player;
  private CancellationSignal lyricRequest;
  private boolean ready, visible, busy, startupResume, choosingFile;
  private String status = "", syncStatus = "", changeWatchError = "", sourceUpdateStatus = "";
  private String importTarget = "default";
  private boolean syncRunning, playlistReady, savingCatalog;
  private boolean remoteListsChanged, remoteSourcesChanged, checkLocalSources;
  private Call changeWatch;
  private final Runnable autoSync = () -> sync(false);
  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (visible) {
            transport();
            main.postDelayed(this, 700);
          }
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = MusicStore.get(this);
    appUpdater = new AppUpdater(this, state == null ? null : state.getString("pendingUpdate"));
    if (state != null) {
      importTarget = state.getString("importTarget", "default");
      choosingFile = state.getBoolean("choosingFile");
    }
    startupResume =
        !choosingFile && (state == null || !PROCESS_INSTANCE.equals(state.getString("process")));
    page = new WebView(this);
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(0xff101117);
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets safe =
                insets.getInsets(
                    WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
          });
    }
    root.addView(
        page,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    setContentView(root);
    root.requestApplyInsets();
    page.setBackgroundColor(0xff101117);
    WebSettings w = page.getSettings();
    // The bundled player uses the App bridge. Direct network loads and navigation are blocked.
    //noinspection SetJavaScriptEnabled
    w.setJavaScriptEnabled(true);
    w.setAllowFileAccess(false);
    w.setAllowContentAccess(false);
    w.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    w.setBlockNetworkLoads(true);
    page.setWebViewClient(
        new WebViewClient() {
          @Override
          public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            WebResourceResponse art =
                CatalogEngine.cover(PlayerActivity.this, req.getUrl().toString());
            return art != null
                ? art
                : new WebResourceResponse(
                    "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return true;
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return true;
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            if (!PLAYER_PAGE.equals(url)) return;
            ready = true;
            refresh();
            transport();
          }
        });
    page.addJavascriptInterface(new Actions(), "App");
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
    page.loadUrl(PLAYER_PAGE);
    worker.execute(
        () -> {
          try {
            store.desktop.loadCachedOnce();
            main.post(
                () -> {
                  if (isDestroyed()) return;
                  playlistReady = true;
                  refresh();
                  if (visible) scheduleSync(1500);
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (!isDestroyed()) notice("读取本机歌单失败 · " + e.getMessage());
                });
          }
        });
    future =
        new MediaController.Builder(
                this, new SessionToken(this, new ComponentName(this, PlaybackService.class)))
            .buildAsync();
    future.addListener(
        () -> {
          if (isDestroyed()) return;
          try {
            player = future.get();
            player.addListener(
                new Player.Listener() {
                  @Override
                  public void onEvents(Player p, Player.Events e) {
                    transport();
                  }

                  @Override
                  public void onPlayerError(PlaybackException e) {
                    // The service may immediately prepare a replacement at the current play time.
                    main.postDelayed(() -> {
                      if (isDestroyed() || player == null || player.getPlayerError() != e) return;
                      Throwable cause = e;
                      while (cause.getCause() != null) cause = cause.getCause();
                      notice("播放失败 · " + (cause.getMessage() == null
                          ? e.getErrorCodeName() : cause.getMessage()));
                    }, 300);
                  }
                });
            resumeOnOpen();
            transport();
          } catch (Exception e) {
            notice("播放器启动失败 · " + e.getMessage());
          }
        },
        this::runOnUiThread);
  }

  @Override
  public void onStart() {
    super.onStart();
    visible = true;
    tick.run();
    checkLocalSources = true;
    if (playlistReady) scheduleSync(700);
  }

  @Override
  public void onResume() {
    super.onResume();
    resumeOnOpen();
  }

  @Override
  public void onStop() {
    visible = false;
    page.setKeepScreenOn(false);
    main.removeCallbacks(tick);
    cancelChangeWatch();
    if (store.desktop.pendingCount() == 0 && !checkLocalSources) main.removeCallbacks(autoSync);
    if (!isChangingConfigurations() && !isFinishing() && !choosingFile) startupResume = true;
    super.onStop();
  }

  private void resumeOnOpen() {
    if (!visible || player == null || !startupResume) return;
    startupResume = false;
    if (!BuildConfig.FLAVOR.equals("dashboard") || !store.prefs.getBoolean("resumeOnOpen", true))
      return;
    if (player.getCurrentMediaItem() != null || PlaybackService.hasSavedQueue(this)) player.play();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putString("importTarget", importTarget);
    state.putBoolean("choosingFile", choosingFile);
    state.putString("pendingUpdate", appUpdater.pendingName());
    state.putString("process", PROCESS_INSTANCE);
    super.onSaveInstanceState(state);
  }

  @Override
  public void onDestroy() {
    ready = false;
    appUpdater.close();
    worker.shutdown();
    cancelChangeWatch();
    syncWorker.shutdown();
    catalogWorker.shutdownNow();
    cancelLyrics();
    lyricWorker.shutdownNow();
    main.removeCallbacks(autoSync);
    MediaController.releaseFuture(future);
    page.removeJavascriptInterface("App");
    page.destroy();
    super.onDestroy();
  }

  private void emit(String name, JSONObject data) {
    if (ready && !isDestroyed())
      page.evaluateJavascript(
          "window."
              + name
              + "("
              + data.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
              + ")",
          null);
  }

  private void refresh() {
    try {
      emit(
          "library",
          obj(
              "playlists",
              store.desktop.lists(),
              "favoriteKeys",
              store.desktop.favoriteKeys(),
              "playlistReady",
              playlistReady,
              "carPage",
              store.prefs.getString("carPage", "{}"),
              "catalogSelection",
              store.prefs.getString("catalogSelection", "{}"),
              "showListeningList",
              store.prefs.getBoolean("showListeningList", false),
              "quality",
              store.prefs.getString("quality", "auto"),
              "playMode",
              store.prefs.getString("playMode", "order"),
              "includeBetaUpdates",
              store.prefs.getBoolean("includeBetaUpdates", false),
              "unmetered",
              store.prefs.getBoolean("unmetered", false),
              "keepScreen",
              keepScreen(),
              "largeControls",
              store.prefs.getBoolean("largeControls", false),
              "resumeOnOpen",
              store.prefs.getBoolean("resumeOnOpen", true),
              "syncState",
              syncRunning ? "正在同步…" : syncStatus,
              "changeWatchError",
              changeWatchError,
              "syncing",
              syncRunning,
              "pending",
              store.desktop.pendingCount(),
              "sources",
              store.sources.summary(),
              "appName",
              getString(R.string.app_name),
              "appVersion",
              BuildConfig.VERSION_NAME,
              "sourceUpdateStatus",
              sourceUpdateStatus,
              "dav",
              !store.prefs.getString("dav", "").isEmpty(),
              "tide", TideSync.status(store),
              "syncConfigured", TideSync.configured(store),
              "lastSync",
              store.prefs.getLong("lastSync", 0),
              "status",
              status,
              "busy",
              busy,
              "car",
              BuildConfig.FLAVOR.equals("dashboard")));
    } catch (Exception e) {
      Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
    }
  }

  private boolean keepScreen() {
    return store.prefs.getBoolean("keepScreen", BuildConfig.FLAVOR.equals("dashboard"));
  }

  private void transport() {
    page.setKeepScreenOn(visible && player != null && player.isPlaying() && keepScreen());
    if (!ready || player == null) return;
    try {
      MediaItem item = player.getCurrentMediaItem();
      JSONObject track = null;
      if (item != null) {
        try {
          track = store.summary(store.track(item.mediaId));
        } catch (JSONException ignored) {
        }
      }
      long duration = player.getDuration();
      if (duration == C.TIME_UNSET && item != null && item.mediaMetadata.durationMs != null)
        duration = item.mediaMetadata.durationMs;
      emit(
          "transport",
          obj(
              "id",
              item == null ? "" : item.mediaId,
              "name",
              item == null ? "" : item.mediaMetadata.title,
              "artist",
              item == null ? "" : item.mediaMetadata.artist,
              "cover",
              track == null ? "" : track.optString("cover"),
              "fav",
              track != null && track.optBoolean("fav"),
              "canFavorite",
              track != null,
              "playing",
              player.isPlaying(),
              "loading",
              player.getPlaybackState() == Player.STATE_BUFFERING,
              "position",
              Math.max(0, player.getCurrentPosition()),
              "duration",
              Math.max(0, duration),
              "prev",
              player.hasPreviousMediaItem(),
              "next",
              player.hasNextMediaItem()));
    } catch (Exception ignored) {
    }
  }

  private void notice(String s) {
    status = s;
    refresh();
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  private interface Job {
    String run() throws Exception;
  }

  private void runJob(String label, Job job) {
    runJob(label, job, false);
  }

  private void runJob(String label, Job job, boolean sourceBatch) {
    if (busy) {
      Toast.makeText(this, "正在处理上一项操作", Toast.LENGTH_SHORT).show();
      return;
    }
    busy = true;
    if (sourceBatch) sourceUpdateStatus = label;
    else status = label;
    refresh();
    worker.execute(
        () -> {
          String msg;
          try {
            msg = job.run();
          } catch (Exception e) {
            msg =
                "操作失败 · "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
          }
          String result = msg;
          main.post(
              () -> {
                if (!isDestroyed()) {
                  busy = false;
                  if (sourceBatch) {
                    sourceUpdateStatus = result;
                    refresh();
                  } else if (result.isEmpty()) {
                    status = "";
                    refresh();
                  } else notice(result);
                }
              });
        });
  }

  private void editSources(String label, Job edit) {
    editSources(label, edit, false);
  }

  private void editSources(String label, Job edit, boolean batch) {
    runJob(label, () -> {
      try {
        return edit.run();
      } finally {
        main.post(() -> {
          if (isDestroyed() || !TideSync.sourcesEnabled(store)) return;
          checkLocalSources = true;
          scheduleSync(400);
        });
      }
    }, batch);
  }

  private final class Actions {
    @JavascriptInterface
    public String songs(String listId) throws JSONException {
      return store.desktop.songs(listId).toString();
    }

    @JavascriptInterface
    public void action(String action, String arg) {
      runOnUiThread(
          () -> {
            if (isDestroyed()) return;
            try {
              dispatch(action, arg);
            } catch (Exception e) {
              notice("操作失败 · " + e.getMessage());
            }
          });
    }
  }

  private void dispatch(String action, String arg) throws Exception {
    switch (action) {
      case "checkAppUpdate":
        appUpdater.check(store.prefs.getBoolean("includeBetaUpdates", false));
        break;
      case "projectSource":
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdater.REPOSITORY)));
        break;
      case "quality":
        if (!Arrays.asList("auto", "128k", "320k", "flac").contains(arg)) return;
        store.prefs.edit().putString("quality", arg).apply();
        refresh();
        break;
      case "lyrics":
        JSONObject lyric = new JSONObject(arg);
        loadLyrics(lyric.getString("id"), lyric.getInt("token"));
        break;
      case "cancelLyrics":
        cancelLyrics();
        break;
      case "carPage":
        if (BuildConfig.FLAVOR.equals("dashboard") && arg.length() <= 8192)
          store.prefs.edit().putString("carPage", arg).apply();
        break;
      case "picture":
        if (arg.equals(playingId()) && !arg.isEmpty()) loadPicture(arg);
        break;
      case "catalogSelection":
        JSONObject selection = new JSONObject(arg);
        if (MusicStore.PLATFORMS.contains(selection.optString("platform")) && arg.length() < 512)
          store.prefs.edit().putString("catalogSelection", selection.toString()).apply();
        break;
      case "catalog":
        catalog(new JSONObject(arg));
        break;
      case "saveCatalog":
        saveCatalog(new JSONObject(arg));
        break;
      case "createList":
        editName();
        break;
      case "bindExisting":
        bindExisting(new JSONObject(arg));
        break;
      case "listProperties":
        JSONObject props = new JSONObject(arg);
        editPlaylist("正在保存列表属性", () -> {
          store.desktop.properties(props.getString("id"), props.getString("name").trim(),
              props.optJSONObject("online"), props.optBoolean("autoSync"));
          return "";
        });
        break;
      case "moveItem":
        JSONObject move = new JSONObject(arg);
        editPlaylist("正在保存顺序", () -> {
          store.desktop.move(move.getString("listId"), move.getString("moved"),
              move.getString("anchor"), move.getBoolean("after"));
          return "";
        });
        break;
      case "fav":
        editPlaylist(
            "正在保存收藏",
            () -> {
              store.desktop.toggleLoved(store.sharedRow(store.track(arg)));
              return "";
            });
        break;
      case "removeSong":
        JSONObject remove = new JSONObject(arg);
        editPlaylist(
            "正在移除歌曲",
            () -> {
              store.desktop.remove(
                  remove.getString("listId"),
                  store.sharedRow(store.track(remove.getString("song"))).getString("id"));
              return "";
            });
        break;
      case "deleteList":
        new AlertDialog.Builder(this)
            .setMessage("删除这个歌单？")
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "删除",
                (dialog, which) ->
                    editPlaylist(
                        "正在删除歌单",
                        () -> {
                          store.desktop.delete(arg);
                          return "";
                        }))
            .show();
        break;
      case "playMode":
        if (!Arrays.asList("order", "loop", "one", "shuffle").contains(arg)) return;
        store.prefs.edit().putString("playMode", arg).apply();
        refresh();
        break;
      case "resumeOnOpen":
        if (!BuildConfig.FLAVOR.equals("dashboard")) return;
        store.prefs.edit().putBoolean(action, "true".equals(arg)).apply();
        refresh();
        break;
      case "showListeningList":
        store.prefs.edit().putBoolean(action, "true".equals(arg)).apply();
        refresh();
        break;
      case "includeBetaUpdates":
        appUpdater.cancelCheck();
        store.prefs.edit().putBoolean(action, "true".equals(arg)).apply();
        refresh();
        break;
      case "unmetered":
      case "keepScreen":
      case "largeControls":
        store.prefs.edit().putBoolean(action, "true".equals(arg)).apply();
        refresh();
        transport();
        break;
      case "add":
        importTarget = arg.isEmpty() ? "default" : arg;
        menu(
            "添加音乐",
            new String[] {"导入洛雪歌单", "按平台和歌曲 ID 添加", "选择本地音频"},
            n -> {
              if (n == 0) pick(BACKUP);
              else if (n == 1) addOnline();
              else pick(AUDIO);
            });
        break;
      case "source":
        menu(
            "音源",
            new String[] {"选择本地 JS 文件", "从链接导入 JS"},
            n -> {
              if (n == 0) pick(SCRIPT);
              else sourceUrl(null);
            });
        break;
      case "updateSources":
        editSources("正在更新音源…", () -> store.sources.updateAll(this), true);
        break;
      case "editLink":
        sourceUrl(store.sources.target(arg, ""));
        break;
      case "testSource":
        runJob("正在测试音源初始化…", () -> store.sources.testSource(this, arg), true);
        break;
      case "updateSource":
        JSONObject target = store.sources.target(arg, "");
        if (target.optString("url").isEmpty()) sourceUrl(target);
        else downloadSource(target.optString("url"), target);
        break;
      case "enableSource":
      case "raiseSource":
      case "lowerSource":
        editSources(
            "正在保存音源设置",
            () -> {
              store.sources.change(arg, action);
              return "音源设置已保存";
            });
        break;
      case "removeSource":
        JSONObject row = store.sources.target(arg, "");
        new AlertDialog.Builder(this)
            .setMessage("移除音源「" + row.getString("name") + "」？")
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "移除",
                (dialog, which) ->
                    editSources(
                        "正在移除音源",
                        () -> {
                          store.sources.change(arg, action);
                          return "音源已移除";
                        }))
            .show();
        break;
      case "dav":
        if (TideSync.enabled(store)) notice("请先断开汐听同步服务");
        else davForm();
        break;
      case "tidePair":
        tidePairForm();
        break;
      case "tideDisconnect":
        new AlertDialog.Builder(this)
            .setMessage("断开本机的汐听同步？")
            .setNegativeButton("取消", null)
            .setPositiveButton("断开", (dialog, which) ->
                syncSettings("正在断开", () -> TideSync.disconnect(store)))
            .show();
        break;
      case "tideSources":
        syncSettings("正在保存", () -> TideSync.setSources(store, "true".equals(arg)));
        break;
      case "tideApprove":
        approveTideSources();
        break;
      case "sync":
        if (TideSync.configured(store)) sync(true);
        else tidePairForm();
        break;
      case "play":
        play(new JSONObject(arg));
        break;
      case "toggle":
        if (player != null && player.getCurrentMediaItem() != null) {
          if (player.getPlayWhenReady()
              && player.getPlaybackState() != Player.STATE_ENDED
              && player.getPlayerError() == null) player.pause();
          else {
            if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
            player.prepare();
            player.play();
          }
        }
        break;
      case "prev":
        if (player != null) player.seekToPreviousMediaItem();
        break;
      case "next":
        if (player != null) player.seekToNextMediaItem();
        break;
      case "seek":
        if (player != null) player.seekTo(Math.max(0, Long.parseLong(arg)));
        break;
    }
  }

  private void play(JSONObject params) throws Exception {
    if (player == null) throw new IOException("播放器正在启动");
    JSONArray ids = params.getJSONArray("ids");
    String target = params.getString("id");
    ArrayList<MediaItem> items = new ArrayList<>();
    int start = 0;
    Map<String, JSONObject> queue = new HashMap<>();
    for (int i = 0; i < ids.length(); i++) {
      String id = ids.getString(i);
      JSONObject t = store.track(id);
      if (t.optBoolean("deleted") || !t.optBoolean("playable", true)) continue;
      if (id.equals(target)) start = items.size();
      queue.put(id, t);
      items.add(PlaybackService.mediaItem(t));
    }
    if (items.isEmpty()) return;
    store.rememberQueue(queue);
    player.setMediaItems(items, start, 0);
    player.prepare();
    player.play();
  }

  private interface Choice {
    void accept(int n);
  }

  private void menu(String title, String[] options, Choice pick) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(options, (d, n) -> pick.accept(n))
        .setNegativeButton("取消", null)
        .show();
  }

  private void pick(int kind) {
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(kind == AUDIO ? "audio/*" : "*/*");
    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, kind == AUDIO);
    i.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(i, kind);
    choosingFile = true;
  }

  private String displayName(Uri uri) {
    try (Cursor c =
        getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) return c.getString(0);
    } catch (RuntimeException ignored) {
    }
    return "音频";
  }

  @Override
  protected void onActivityResult(int kind, int result, Intent data) {
    super.onActivityResult(kind, result, data);
    if (kind == AppUpdater.INSTALL_RESULT) {
      appUpdater.finishInstall();
      return;
    }
    if (kind == AppUpdater.INSTALL_PERMISSION) {
      appUpdater.install(false);
      return;
    }
    if (kind == SCRIPT || kind == BACKUP || kind == AUDIO) choosingFile = false;
    if (result != RESULT_OK || data == null) return;
    Uri uri = data.getData();
    if (kind == AUDIO) {
      List<Uri> files = new ArrayList<>();
      if (data.getClipData() != null)
        for (int i = 0; i < data.getClipData().getItemCount(); i++)
          files.add(data.getClipData().getItemAt(i).getUri());
      else if (uri != null) files.add(uri);
      String target = importTarget;
      editPlaylist(
          "正在导入音频",
          () -> {
            JSONArray rows = new JSONArray();
            for (Uri file : files) {
              getContentResolver()
                  .takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION);
              JSONObject track =
                  obj(
                      "id",
                      "local:" + file,
                      "source",
                      "local",
                      "name",
                      displayName(file),
                      "uri",
                      file.toString(),
                      "artist",
                      "");
              store.add(track);
              rows.put(store.sharedRow(track));
            }
            store.desktop.add(target, rows);
            return "已添加 " + rows.length() + " 首本地音频";
          });
    } else if (uri != null && kind == BACKUP) {
      String target = importTarget;
      editPlaylist(
          "正在读取洛雪歌单",
          () -> {
            JSONArray rows = store.importList(getContentResolver().openInputStream(uri));
            store.desktop.add(target, rows);
            return "已导入 " + rows.length() + " 首歌曲";
          });
    } else if (uri != null && kind == SCRIPT) {
      editSources(
          "正在验证音源",
          () ->
              store.sources.install(
                  this,
                  null,
                  MusicStore.text(getContentResolver().openInputStream(uri), LxEngine.SCRIPT_LIMIT),
                  displayName(uri),
                  ""));
    }
  }

  private LinearLayout form() {
    LinearLayout f = new LinearLayout(this);
    f.setOrientation(LinearLayout.VERTICAL);
    int p = (int) (22 * getResources().getDisplayMetrics().density);
    f.setPadding(p, p / 2, p, p / 3);
    return f;
  }

  private EditText field(LinearLayout form, String hint, String value, int type) {
    EditText input = new EditText(this);
    input.setSingleLine();
    input.setInputType(type);
    input.setHint(hint);
    input.setText(value);
    input.setTextSize(16);
    form.addView(input);
    return input;
  }

  private void dialog(String title, LinearLayout f, String action, Runnable confirm) {
    ScrollView sc = new ScrollView(this);
    sc.addView(f);
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(sc)
        .setNegativeButton("取消", null)
        .setPositiveButton(action, (d, w) -> confirm.run())
        .show();
  }

  private void sourceUrl(JSONObject target) {
    String current = target == null ? "" : target.optString("url");
    LinearLayout f = form();
    EditText url =
        field(
            f,
            "JS 文件的 HTTP 或 HTTPS 直链",
            current,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    dialog(
        target == null ? "从链接导入洛雪音源" : current.isEmpty() ? "绑定更新地址" : "修改更新地址",
        f,
        target == null ? "导入" : "验证并保存",
        () -> downloadSource(url.getText().toString(), target));
  }

  private void downloadSource(String link, JSONObject target) {
    editSources("正在下载并验证音源", () -> store.sources.installFromLink(this, link, target));
  }

  private void addOnline() {
    LinearLayout f = form();
    String[] codes = {"wy", "tx", "kg", "kw", "mg"};
    String[] labels = new String[codes.length];
    for (int i = 0; i < codes.length; i++) labels[i] = MusicStore.platformName(codes[i]);
    Spinner platform = new Spinner(this);
    platform.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
    f.addView(platform);
    EditText id = field(f, "平台歌曲 ID 或酷狗 hash", "", 1),
        name = field(f, "歌曲名称", "", 1),
        artist = field(f, "歌手（选填）", "", 1);
    dialog(
        "添加在线歌曲",
        f,
        "添加",
        () -> {
          String sid = id.getText().toString().trim(),
              p = codes[platform.getSelectedItemPosition()],
              n = name.getText().toString().trim();
          if (sid.isEmpty()) {
            notice("请输入歌曲 ID");
            return;
          }
          String target = importTarget;
          String performer = artist.getText().toString();
          editPlaylist(
              "正在添加歌曲",
              () -> {
                JSONObject meta = obj("songId", sid);
                if (p.equals("kg")) meta.put("hash", sid);
                JSONObject track =
                    MusicStore.fromLx(
                        obj(
                            "name",
                            n.isEmpty() ? sid : n,
                            "source",
                            p,
                            "singer",
                            performer,
                            "meta",
                            meta));
                store.desktop.add(target, new JSONArray().put(store.sharedRow(track)));
                return "歌曲已添加";
              });
        });
  }

  private void tidePairForm() {
    if (TideSync.enabled(store)) return;
    LinearLayout layout = form();
    EditText url = field(layout, "https://sync.example.com", "", InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
    EditText code = field(layout, "一次性配对码", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
    dialog("汐听同步服务", layout, "配对", () -> {
      String address = url.getText().toString(), pairingCode = code.getText().toString();
      syncSettings("正在配对", () -> TideSync.pair(store, address, pairingCode));
    });
  }

  private void syncSettings(String label, Job task) {
    runJob(label, () -> {
      String result = task.run();
      main.post(() -> {
        if (isDestroyed()) return;
        remoteListsChanged = false;
        remoteSourcesChanged = false;
        changeWatchError = "";
        checkLocalSources = TideSync.sourcesEnabled(store);
        scheduleSync(500);
      });
      return result;
    });
  }

  private void approveTideSources() throws JSONException {
    JSONArray pending = TideSync.pending(store);
    if (pending.length() == 0) return;
    StringBuilder message = new StringBuilder("信任此同步账号的音源？确认后会应用服务器列表，后续音源变更将自动同步。\n");
    for (int i = 1; i < pending.length(); i++) message.append("\n").append(pending.getString(i));
    if (pending.length() == 1) message.append("\n服务器音源列表为空");
    int revision = pending.getInt(0);
    new AlertDialog.Builder(this)
        .setTitle("确认服务器音源")
        .setMessage(message.toString())
        .setNegativeButton("取消", null)
        .setPositiveButton("应用", (dialog, which) ->
            syncSettings("正在同步音源", () -> TideSync.approve(store, revision)))
        .show();
  }

  private void davForm() {
    LinearLayout f = form();
    TextView hint = new TextView(this);
    hint.setText(R.string.dav_directory_hint);
    f.addView(hint);
    EditText url = field(f, "桌面同步目录的完整地址", store.prefs.getString("dav", ""), 17);
    EditText user = field(f, "用户名（匿名可留空）", store.prefs.getString("user", ""), 1);
    String saved;
    try {
      saved = DavSync.password(store);
    } catch (Exception e) {
      saved = "";
    }
    EditText pass =
        field(
            f,
            "密码或应用专用密码",
            saved,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    dialog(
        "WebDAV 同步",
        f,
        "保存并同步",
        () -> {
          String u = url.getText().toString().trim(),
              a = user.getText().toString(),
              p = pass.getText().toString();
          runJob(
              "正在保存 WebDAV 配置",
              () -> {
                DavSync.configure(store, u, a, p);
                main.post(
                    () -> {
                      if (isDestroyed()) return;
                      syncStatus = "";
                      sync(true);
                    });
                return "";
              });
        });
  }

  private void editPlaylist(String label, Job edit) {
    runJob(
        label,
        () -> {
          String result = edit.run();
          main.post(
              () -> {
                if (isDestroyed()) return;
                if (TideSync.configured(store)) scheduleSync(400);
                transport();
              });
          return result;
        });
  }

  private void cancelChangeWatch() {
    if (changeWatch == null) return;
    changeWatch.cancel();
    changeWatch = null;
  }

  private void scheduleSync(long delay) {
    cancelChangeWatch();
    main.removeCallbacks(autoSync);
    if (!isDestroyed() && TideSync.configured(store)) main.postDelayed(autoSync, delay);
  }

  private void watchChanges() {
    if (!visible || changeWatch != null) return;
    try {
      changeWatch = TideSync.watch(store, (call, lists, sources, error) -> main.post(() -> {
        if (isDestroyed() || call != changeWatch) return;
        changeWatch = null;
        if (error != null) {
          retryChangeWatch(error);
          return;
        }
        if (!changeWatchError.isEmpty()) {
          changeWatchError = "";
          refresh();
        }
        remoteListsChanged |= lists;
        remoteSourcesChanged |= sources;
        scheduleSync(0);
      }));
    } catch (Exception error) {
      retryChangeWatch(error.getMessage());
    }
  }

  private void retryChangeWatch(String error) {
    changeWatchError = error == null || error.isEmpty() ? "无法监听远端变更" : error;
    refresh();
    scheduleSync(15000);
  }

  private void sync(boolean manual) {
    if (syncRunning || !playlistReady || !TideSync.configured(store)) return;
    boolean tide = TideSync.enabled(store);
    boolean lists = manual || remoteListsChanged || store.desktop.pendingCount() > 0;
    boolean sources = TideSync.sourcesEnabled(store) && (manual || remoteSourcesChanged);
    boolean checkSources = TideSync.sourcesEnabled(store) && checkLocalSources;
    if (tide && !lists && !sources && !checkSources) {
      watchChanges();
      return;
    }
    cancelChangeWatch();
    remoteListsChanged = false;
    remoteSourcesChanged = false;
    checkLocalSources = false;
    syncRunning = true;
    refresh();
    syncWorker.execute(() -> {
      String message = "";
      boolean retryLists = false, retrySources = false;
      if (tide) {
        if (lists) {
          try {
            TideSync.syncLists(store);
            message = "汐听歌单已同步";
          } catch (Exception error) {
            message = error.getMessage();
            retryLists = true;
          }
        }
        try {
          if (sources || checkSources && TideSync.hasLocalSourceChanges(store)) {
            TideSync.syncSources(store);
            if (!retryLists) message = "汐听同步已完成";
          }
        } catch (Exception error) {
          if (!retryLists) message = error.getMessage();
          retrySources = true;
        }
      } else {
        try { message = DavSync.sync(store); }
        catch (Exception error) { message = error.getMessage(); retryLists = true; }
      }
      boolean synced = !retryLists && !retrySources;
      String result = message == null ? "同步失败" : message;
      boolean listsFailed = retryLists, sourcesFailed = retrySources;
      main.post(() -> {
        if (isDestroyed()) return;
        syncRunning = false;
        remoteListsChanged |= listsFailed;
        remoteSourcesChanged |= sourcesFailed;
        if (!synced) syncStatus = store.desktop.pendingCount() > 0 ? "修改待同步" : result;
        else if (!result.isEmpty()) syncStatus = result;
        if (manual || !synced) status = result;
        if (manual) Toast.makeText(this, result, Toast.LENGTH_LONG).show();
        refresh();
        transport();
        if (visible) scheduleSync(!synced ? 15000 : tide ? 0 : 60000);
      });
    });
  }

  private void bindExisting(JSONObject binding) throws JSONException {
    ArrayList<JSONObject> choices = new ArrayList<>();
    ArrayList<String> labels = new ArrayList<>();
    JSONArray lists = store.desktop.lists();
    for (int i = 0; i < lists.length(); i++) {
      JSONObject list = lists.getJSONObject(i);
      if ("general".equals(list.optString("type"))) {
        choices.add(list);
        labels.add(list.getString("name"));
      }
    }
    menu("选择要关联的普通列表", labels.toArray(new String[0]), index -> {
      JSONObject target = choices.get(index);
      editPlaylist("正在关联在线来源", () -> {
        store.desktop.properties(target.getString("id"), target.getString("name"), binding, false);
        return "已关联来源，可在歌单内更新列表";
      });
    });
  }

  private void editName() {
    LinearLayout layout = form();
    EditText input = field(layout, "歌单名称", "", 1);
    dialog(
        "新建歌单",
        layout,
        "保存",
        () -> {
          String name = input.getText().toString().trim();
          if (name.isEmpty() || name.length() > 100) {
            notice("请输入 1 至 100 个字符的歌单名称");
            return;
          }
          editPlaylist(
              "正在保存歌单",
              () -> {
                String id = store.desktop.create(name, "", new JSONArray());
                main.post(
                      () -> {
                        try {
                          emit("openList", obj("id", id));
                        } catch (JSONException ignored) {
                        }
                      });

                return "";
              });
        });
  }

  private String playingId() {
    MediaItem current = player == null ? null : player.getCurrentMediaItem();
    return current == null ? "" : current.mediaId;
  }

  private void cancelLyrics() {
    if (lyricRequest == null) return;
    lyricRequest.cancel();
    lyricRequest = null;
  }

  private void loadPicture(String id) {
    catalogWorker.execute(() -> {
      String url = "";
      try {
        JSONObject track = store.track(id);
        if ("local".equals(track.optString("source"))) {
          Uri uri = Uri.parse(track.getString("uri"));
          if ("content".equals(uri.getScheme())) url = CatalogEngine.embeddedCover(this, uri);
        } else {
          url = CatalogEngine.trackArt(this, id, store.sharedRow(track));
        }
      } catch (Exception ignored) {
        // A track can have no artwork, or its provider can be offline.
      }
      final String artwork = url;
      main.post(() -> {
        if (!isDestroyed() && id.equals(playingId())) {
          try { emit("pictureReply", obj("id", id, "url", artwork)); }
          catch (JSONException ignored) { }
        }
      });
    });
  }

  private void loadLyrics(String id, int token) {
    if (id.isEmpty() || !id.equals(playingId())) return;
    cancelLyrics();
    CancellationSignal request = new CancellationSignal();
    lyricRequest = request;
    lyricWorker.execute(
        () -> {
          if (request.isCanceled()) return;
          String lyrics = "", state = "ready";
          try {
            JSONObject song = store.sharedRow(store.track(id));
            JSONObject meta = song.optJSONObject("meta");
            String platform = meta == null ? "" : meta.optString("source");
            if (song.optBoolean("isLocal") || !MusicStore.PLATFORMS.contains(platform)) {
              state = "unavailable";
            } else {
              JSONObject result =
                  CatalogEngine.run(
                      this,
                      obj("action", "lyric", "platform", platform, "musicInfo", song),
                      request);
              lyrics = result.isNull("lyric") ? "" : result.optString("lyric");
              if (lyrics.length() > 160000) throw new IOException("歌词超过大小限制");
            }
          } catch (Exception e) {
            state = "error";
          }
          final String text = lyrics, status = state;
          main.post(
              () -> {
                if (lyricRequest != request || isDestroyed()) return;
                lyricRequest = null;
                if (!id.equals(playingId())) return;
                try {
                  emit(
                      "lyricsReply",
                      obj("token", token, "id", id, "status", status, "lyric", text));
                } catch (JSONException ignored) {
                }
              });
        });
  }

  private void sendCatalog(JSONObject response) {
    main.post(() -> emit("catalogReply", response));
  }

  private void catalog(JSONObject request) {
    catalogWorker.execute(
        () -> {
          int token = request.optInt("token");
          String action = request.optString("action"), platform = request.optString("platform");
          boolean browsing = "list".equals(action) || "charts".equals(action);
          boolean firstPage = browsing && request.optInt("page") == 1;
          String cacheKey = null;
          try {
            if (MusicStore.PLATFORMS.contains(platform)
                && (firstPage || "options".equals(action))) {
              cacheKey =
                  new JSONArray()
                      .put(action)
                      .put(platform)
                      .put(request.optString("sort"))
                      .put(request.optString("tag"))
                      .toString();
              if (!"list".equals(action)) cacheKey = action + ":" + platform;
              byte[] bytes = CatalogEngine.pages(this).read(cacheKey);
              if (bytes != null || ("options".equals(action) && "wy".equals(platform))) {
                JSONObject cached =
                    new JSONObject(
                        bytes != null
                            ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                            : MusicStore.text(
                                getAssets().open("netease-categories.json"), 16 * 1024));
                if (firstPage) CatalogEngine.prefetchCovers(this, cached.optJSONArray("list"));
                sendCatalog(obj("token", token, "result", cached, "cached", true));
              }
            }
          } catch (Exception ignored) {
            // The online request can replace an unreadable cache entry.
          }
          try {
            JSONObject result = CatalogEngine.run(this, request);
            if ("detail".equals(action) || "chart-detail".equals(action) || "search".equals(action)) {
              result.put("list", store.catalogSongs(result.getJSONArray("list")));
              JSONObject info = result.optJSONObject("info");
              if (info != null) info.put("cover", CatalogEngine.art(info.optString("img")));
            } else if (browsing) {
              JSONArray rows = result.getJSONArray("list");
              for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                row.put("cover", CatalogEngine.art(row.optString("img")));
              }
              if (firstPage) CatalogEngine.prefetchCovers(this, rows);
            }
            sendCatalog(obj("token", token, "result", result));
            if (cacheKey != null)
              CatalogEngine.pages(this)
                  .write(
                      cacheKey,
                      result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
          } catch (Exception e) {
            try {
              sendCatalog(
                  obj(
                      "token",
                      token,
                      "error",
                      e.getMessage() == null ? "暂时无法读取在线资源" : e.getMessage()));
            } catch (JSONException ignored) {
            }
          }
        });
  }

  private void saveCatalog(JSONObject request) {
    if (savingCatalog) return;
    savingCatalog = true;
    catalogWorker.execute(
        () -> {
          String result;
          try {
            result = OnlinePlaylist.fetch(this, store.desktop, request, store.desktop.scopeId());
          } catch (Exception e) {
            result = "在线列表操作失败 · " + e.getMessage();
          }
          String message = result;
          main.post(
              () -> {
                if (isDestroyed()) return;
                savingCatalog = false;
                notice(message);
                if (TideSync.configured(store)) scheduleSync(400);
              });
        });
    Toast.makeText(
            this,
            "chart-detail".equals(request.optString("action")) ? "正在读取完整榜单" : "正在读取完整歌单",
            Toast.LENGTH_SHORT)
        .show();
  }

  @Override
  public void onBackPressed() {
    if (!ready) {
      super.onBackPressed();
      return;
    }
    page.evaluateJavascript(
        "window.goBack()",
        v -> {
          if (!"true".equals(v)) moveTaskToBack(true);
        });
  }
}
