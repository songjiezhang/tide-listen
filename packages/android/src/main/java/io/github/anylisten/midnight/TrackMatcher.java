package io.github.anylisten.midnight;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Matches an equivalent track across online music platforms using the same normalized
 * title/artist/album/duration rules as desktop AnyListen and lx-music-mobile.
 */
final class TrackMatcher {
  static final List<String> FALLBACK_ORDER = Arrays.asList("kw", "kg", "tx", "wy", "mg");
  private static final Pattern NOISE =
      Pattern.compile("[\\s'.,，&\"、()（）`~\\-<>|/\\\\\\[\\]!！]+");
  private static final Pattern SINGER_SPLIT = Pattern.compile("[、/,，&]+");

  private TrackMatcher() {}

  static List<String> fallbackPlatforms(String originalPlatform) {
    List<String> platforms = new ArrayList<>();
    for (String platform : FALLBACK_ORDER) {
      if (!platform.equals(originalPlatform)) platforms.add(platform);
    }
    return platforms;
  }

  static String searchQuery(String name, String artist) {
    String cleanName = name == null ? "" : name.trim();
    String cleanArtist = artist == null ? "" : artist.trim();
    if (cleanName.isEmpty()) return cleanArtist;
    if (cleanArtist.isEmpty()) return cleanName;
    return cleanName + " " + cleanArtist;
  }

  static String normalize(String text) {
    if (text == null) return "";
    return NOISE.matcher(text.trim()).replaceAll("").toLowerCase(Locale.ROOT);
  }

  static String normalizeSingers(String singer) {
    if (singer == null || singer.trim().isEmpty()) return "";
    String[] parts = SINGER_SPLIT.split(singer);
    List<String> list = new ArrayList<>(parts.length);
    for (String part : parts) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) list.add(trimmed);
    }
    if (list.isEmpty()) return "";
    Collections.sort(list, String::compareToIgnoreCase);
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) joined.append('、');
      joined.append(list.get(i));
    }
    return normalize(joined.toString());
  }

  static int parseIntervalSeconds(String interval) {
    if (interval == null || interval.trim().isEmpty()) return 0;
    String[] parts = interval.trim().split(":");
    int total = 0;
    int unit = 1;
    try {
      for (int i = parts.length - 1; i >= 0; i--) {
        total += Integer.parseInt(parts[i].trim()) * unit;
        unit *= 60;
      }
      return Math.max(0, total);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static boolean intervalClose(int a, int b, boolean strict) {
    if (a <= 0 || b <= 0) return true;
    return Math.abs(a - b) < (strict ? 5 : 30);
  }

  private static boolean eitherIncludes(String a, String b) {
    if (a.isEmpty() || b.isEmpty()) return false;
    return a.contains(b) || b.contains(a);
  }

  static final class Candidate {
    final String id;
    final String name;
    final String singer;
    final String album;
    final String interval;

    Candidate(String id, String name, String singer, String album, String interval) {
      this.id = id;
      this.name = name;
      this.singer = singer;
      this.album = album;
      this.interval = interval;
    }
  }

  /** Picks the highest-confidence matching candidate index, or -1 if none matches. */
  static int pickBestIndex(
      String targetName,
      String targetSinger,
      String targetAlbum,
      String targetInterval,
      List<Candidate> candidates) {
    if (candidates == null || candidates.isEmpty()) return -1;
    String fName = normalize(targetName);
    if (fName.isEmpty()) return -1;
    String fSinger = normalizeSingers(targetSinger);
    String fAlbum = normalize(targetAlbum);
    int fInterval = parseIntervalSeconds(targetInterval);

    int best = pickPassIndex(fName, fSinger, fAlbum, fInterval, candidates, true);
    if (best >= 0) return best;
    return pickPassIndex(fName, fSinger, fAlbum, fInterval, candidates, false);
  }

  /** Picks the highest-confidence matching row from a CatalogEngine search result list. */
  static JSONObject pickBest(
      String targetName,
      String targetSinger,
      String targetAlbum,
      String targetInterval,
      JSONArray rows) {
    if (rows == null || rows.length() == 0) return null;
    int limit = Math.min(rows.length(), 20);
    List<Candidate> candidates = new ArrayList<>(limit);
    List<JSONObject> validRows = new ArrayList<>(limit);
    for (int i = 0; i < limit; i++) {
      JSONObject row = rows.optJSONObject(i);
      if (row == null) continue;
      JSONObject meta = row.optJSONObject("meta");
      if (meta == null || meta.optString("musicId").isEmpty()) continue;
      candidates.add(
          new Candidate(
              row.optString("id"),
              row.optString("name"),
              row.optString("singer"),
              meta.optString("albumName"),
              row.optString("interval")));
      validRows.add(row);
    }
    int best = pickBestIndex(targetName, targetSinger, targetAlbum, targetInterval, candidates);
    return best < 0 ? null : validRows.get(best);
  }

  private static int pickPassIndex(
      String fName,
      String fSinger,
      String fAlbum,
      int fInterval,
      List<Candidate> candidates,
      boolean strict) {
    int bestIndex = -1;
    int bestScore = -1;
    int limit = Math.min(candidates.size(), 20);
    for (int i = 0; i < limit; i++) {
      Candidate candidate = candidates.get(i);
      if (candidate == null) continue;

      String rName = normalize(candidate.name);
      if (rName.isEmpty()) continue;
      String rSinger = normalizeSingers(candidate.singer);
      String rAlbum = normalize(candidate.album);
      int rInterval = parseIntervalSeconds(candidate.interval);
      if (!intervalClose(fInterval, rInterval, strict)) continue;

      boolean exactName = rName.equals(fName);
      boolean includesName = exactName || eitherIncludes(fName, rName);
      boolean exactSinger = !fSinger.isEmpty() && rSinger.equals(fSinger);
      boolean includesSinger = fSinger.isEmpty() || exactSinger || eitherIncludes(fSinger, rSinger);
      boolean exactAlbum = !fAlbum.isEmpty() && rAlbum.equals(fAlbum);
      boolean exactInterval = fInterval > 0 && rInterval > 0 && Math.abs(fInterval - rInterval) <= 2;

      boolean accepted =
          (exactName && includesSinger)
              || (strict && exactSinger && includesName)
              || (strict && exactAlbum && includesSinger && includesName);
      if (!accepted) continue;

      int score = 0;
      if (exactName) score += 100;
      else if (includesName) score += 40;
      if (exactSinger) score += 80;
      else if (includesSinger) score += 40;
      if (exactInterval) score += 30;
      if (exactAlbum) score += 20;
      // Slight preference for earlier search results on ties.
      score -= i;
      if (score > bestScore) {
        bestScore = score;
        bestIndex = i;
      }
    }
    return bestIndex;
  }
}
