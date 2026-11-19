package io.github.anylisten.midnight;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.*;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackService extends MediaSessionService {
  private static final String RESUME_QUEUE = "playback-queue";
  private static final long CHECKPOINT_INTERVAL = 5000;
  private final Handler main = new Handler(Looper.getMainLooper());
  private MediaSession session;
  private SharedPreferences prefs, savedQueue, savedProgress;
  private String queueRevision;
  private final Runnable checkpoint =
      new Runnable() {
        @Override
        public void run() {
          if (session == null || !session.getPlayer().isPlaying()) return;
          saveProgress(session.getPlayer());
          main.postDelayed(this, CHECKPOINT_INTERVAL);
        }
      };
  private final SharedPreferences.OnSharedPreferenceChangeListener onPreference =
      (settings, key) -> {
        if ("playMode".equals(key) && session != null) applyPlayMode(session.getPlayer());
      };

  private void applyPlayMode(Player player) {
    String mode = prefs.getString("playMode", "order");
    player.setRepeatMode(
        "one".equals(mode)
            ? Player.REPEAT_MODE_ONE
            : "loop".equals(mode) ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    player.setShuffleModeEnabled("shuffle".equals(mode));
  }

  @Override
  public void onCreate() {
    super.onCreate();
    MusicStore store = MusicStore.get(this);
    DataSource.Factory upstream = new DefaultDataSource.Factory(this);
    DataSource.Factory data = () -> new LxDataSource(this, store, upstream.createDataSource());
    ExoPlayer player =
        new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(data))
            .build();
    player.setAudioAttributes(
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build(),
        true);
    player.setHandleAudioBecomingNoisy(true);
    prefs = store.prefs;
    MediaSession.Builder builder = new MediaSession.Builder(this, player);
    savedQueue = getSharedPreferences(RESUME_QUEUE, MODE_PRIVATE);
    savedProgress = getSharedPreferences("playback-progress", MODE_PRIVATE);
    if (BuildConfig.FLAVOR.equals("dashboard")) {
      builder.setCallback(
          new MediaSession.Callback() {
            @Override
            public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
                MediaSession session, MediaSession.ControllerInfo controller) {
              try {
                return Futures.immediateFuture(restoreQueue(store));
              } catch (JSONException e) {
                return Futures.immediateFailedFuture(e);
              }
            }
          });
    } else if (savedQueue.contains("revision")) {
      try {
        MediaSession.MediaItemsWithStartPosition saved = restoreQueue(store);
        player.setMediaItems(saved.mediaItems, saved.startIndex, saved.startPositionMs);
        queueRevision = savedQueue.getString("revision", null);
      } catch (JSONException e) {
        Log.w("PlaybackService", "Unable to restore saved playback queue", e);
      }
    }
    player.addListener(
        new Player.Listener() {
          @Override
          public void onTimelineChanged(Timeline timeline, int reason) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue(player, store);
          }

          @Override
          public void onEvents(Player player, Player.Events events) {
            if (events.containsAny(
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED)) saveProgress(player);
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
              main.removeCallbacks(checkpoint);
              if (player.isPlaying()) main.postDelayed(checkpoint, CHECKPOINT_INTERVAL);
            }
          }
        });
    session = builder.build();
    prefs.registerOnSharedPreferenceChangeListener(onPreference);
    applyPlayMode(player);
  }

  static MediaItem mediaItem(JSONObject track) throws JSONException {
    String id = track.getString("id");
    return new MediaItem.Builder()
        .setMediaId(id)
        .setUri(new Uri.Builder().scheme("midnight").authority("track").appendPath(id).build())
        .setMediaMetadata(
            new MediaMetadata.Builder()
                .setTitle(track.getString("name"))
                .setArtist(track.optString("artist"))
                .build())
        .build();
  }

  static boolean hasSavedQueue(Context context) {
    return context.getSharedPreferences(RESUME_QUEUE, MODE_PRIVATE).contains("revision");
  }

  private void saveQueue(Player player, MusicStore store) {
    queueRevision = null;
    MediaItem current = player.getCurrentMediaItem();
    if (current == null) {
      savedQueue.edit().clear().apply();
      return;
    }
    JSONArray tracks = new JSONArray();
    for (int i = 0; i < player.getMediaItemCount(); i++) {
      JSONObject track = store.queuedTrack(player.getMediaItemAt(i).mediaId);
      if (track == null) return;
      tracks.put(track);
    }
    queueRevision = UUID.randomUUID().toString();
    savedQueue
        .edit()
        .putString("revision", queueRevision)
        .putString("tracks", tracks.toString())
        .putString("id", current.mediaId)
        .putLong("position", playbackPosition(player))
        .apply();
    saveProgress(player);
  }

  private static long playbackPosition(Player player) {
    return player.getPlaybackState() == Player.STATE_ENDED
        ? 0
        : Math.max(0, player.getCurrentPosition());
  }

  private void saveProgress(Player player) {
    MediaItem current = player.getCurrentMediaItem();
    if (queueRevision == null || current == null) return;
    SharedPreferences.Editor checkpoint =
        savedProgress
            .edit()
            .putString("revision", queueRevision)
            .putString("id", current.mediaId)
            .putLong("position", playbackPosition(player));
    long duration = player.getDuration();
    if (duration > 0) checkpoint.putLong("duration", duration);
    else if (!current.mediaId.equals(savedProgress.getString("id", "")))
      checkpoint.remove("duration");
    checkpoint.apply();
  }

  private MediaSession.MediaItemsWithStartPosition restoreQueue(MusicStore store)
      throws JSONException {
    String revision = savedQueue.getString("revision", "");
    SharedPreferences checkpoint =
        revision.equals(savedProgress.getString("revision", null)) ? savedProgress : savedQueue;
    String target = checkpoint.getString("id", "");
    long position = Math.max(0, checkpoint.getLong("position", 0));
    long duration = checkpoint.getLong("duration", 0);
    JSONArray tracks = new JSONArray(savedQueue.getString("tracks", "[]"));
    ArrayList<MediaItem> items = new ArrayList<>(tracks.length());
    Map<String, JSONObject> queue = new HashMap<>();
    int start = -1;
    for (int i = 0; i < tracks.length(); i++) {
      JSONObject track = tracks.getJSONObject(i);
      MediaItem item = mediaItem(track);
      if (item.mediaId.equals(target) && start < 0) {
        start = items.size();
        if (duration > 0)
          item =
              item.buildUpon()
                  .setMediaMetadata(item.mediaMetadata.buildUpon().setDurationMs(duration).build())
                  .build();
      }
      items.add(item);
      queue.put(item.mediaId, track);
    }
    if (start < 0) throw new JSONException("找不到上次播放的歌曲");
    store.rememberQueue(queue);
    return new MediaSession.MediaItemsWithStartPosition(items, start, position);
  }

  @Override
  public MediaSession onGetSession(MediaSession.ControllerInfo controller) {
    return session;
  }

  @Override
  public void onDestroy() {
    if (session != null) {
      prefs.unregisterOnSharedPreferenceChangeListener(onPreference);
      main.removeCallbacks(checkpoint);
      if (savedProgress != null) saveProgress(session.getPlayer());
      session.getPlayer().release();
      session.release();
    }
    super.onDestroy();
  }
}
