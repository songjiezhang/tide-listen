package io.github.anylisten.midnight;

import static io.github.anylisten.midnight.MusicStore.obj;

import android.content.Context;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.json.*;

/** Source identity shared with the desktop Online Metadata extension. */
final class OnlinePlaylist {
  static JSONObject binding(JSONObject list) throws JSONException {
    if (!"online".equals(list.optString("type"))) return null;
    JSONObject meta = list.getJSONObject("meta");
    return obj("extensionId", meta.optString("extensionId"), "source", meta.optString("source"),
        "sourceType", meta.optString("sourceType"), "syncId", meta.optString("syncId"),
        "date", meta.optString("date"));
  }

  static void validate(JSONObject ref) throws IOException {
    String kind = ref.optString("sourceType");
    if (!"online-metadata".equals(ref.optString("extensionId")) ||
        !MusicStore.PLATFORMS.contains(ref.optString("source")) ||
        !("songlist".equals(kind) || "topSongs".equals(kind)) || ref.optString("syncId").trim().isEmpty())
      throw new IOException("请选择支持的平台、列表类型并填写原列表 ID");
  }

  static boolean matches(JSONObject list, JSONObject ref) throws JSONException {
    JSONObject current = binding(list);
    if (current == null) return false;
    for (String key : new String[]{"extensionId", "source", "sourceType", "syncId", "date"})
      if (!current.optString(key).equals(ref.optString(key))) return false;
    return true;
  }

  static void bind(JSONObject list, JSONObject ref) throws JSONException {
    boolean changed = !matches(list, ref);
    list.put("type", "online");
    JSONObject meta = list.getJSONObject("meta");
    for (String key : new String[]{"extensionId", "source", "sourceType", "syncId", "date"})
      meta.put(key, ref.optString(key));
    if (changed) meta.put("syncTime", 0);
    if (!meta.has("autoSync")) meta.put("autoSync", false);
  }

  static String fetch(Context context, DesktopPlaylists library, JSONObject request, String scope) throws Exception {
    long started = System.currentTimeMillis();
    JSONObject binding = obj("extensionId", "online-metadata", "source", request.getString("platform"),
        "sourceType", "chart-detail".equals(request.optString("action")) ? "topSongs" : "songlist",
        "syncId", request.getString("id"), "date", request.optString("date"));
    OnlinePlaylist.validate(binding);
    JSONArray rows = new JSONArray();
    Set<String> seen = new HashSet<>();
    if (!"chart-detail".equals(request.optString("action"))) request.put("action", "detail");
    String picture = "";
    for (int page = 1; page <= 100; page++) {
      JSONObject result = CatalogEngine.run(context, request.put("page", page));
      JSONObject info = result.optJSONObject("info");
      if (page == 1 && info != null) picture = info.optString("img");
      JSONArray songs = result.getJSONArray("list");
      int previous = rows.length();
      for (int i = 0; i < songs.length(); i++) {
        JSONObject row = songs.getJSONObject(i);
        if (seen.add(row.getString("id"))) rows.put(row);
      }
      int limit = Math.max(1, result.optInt("limit", songs.length()));
      int total = result.optInt("total", songs.length());
      if (songs.length() == 0 && total > 0)
        throw new IOException("来源返回了空页，已保留原列表");
      if (page * limit >= total) {
        String target = request.optString("target");
        library.saveOnline(scope, target, request.getString("name"), picture, binding, rows, started);
        return target.isEmpty() ? "已加入「我的列表」" : "列表已更新";
      }
      if (rows.length() == previous) throw new IOException("该来源暂未返回完整歌单，请稍后重试");
    }
    throw new IOException("该歌单超出收藏上限");
  }
}
