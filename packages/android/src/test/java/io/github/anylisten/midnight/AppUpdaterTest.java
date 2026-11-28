package io.github.anylisten.midnight;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class AppUpdaterTest {
  @Test
  public void comparesStableReleaseToInstalledBeta() {
    assertTrue(AppUpdater.compareVersions("0.13.9", "0.13.10-beta.1") < 0);
    assertTrue(AppUpdater.compareVersions("0.13.10", "0.13.10-beta.1") > 0);
    assertTrue(AppUpdater.compareVersions("0.13.11", "0.13.10-beta.1") > 0);
    assertTrue(AppUpdater.compareVersions("0.13.11-beta.10", "0.13.11-beta.2") > 0);
    assertTrue(AppUpdater.compareVersions("0.13.11-beta.1", "0.13.11") < 0);
    assertEquals(0, AppUpdater.compareVersions("0.13.10", "0.13.10"));
  }

  private JSONObject release(String version, String edition) throws Exception {
    String tag = "v" + version;
    String name = "TideListen-" + edition + "-" + version + ".apk";
    return new JSONObject().put("tag_name", tag).put("prerelease", version.contains("-"))
        .put("assets", new JSONArray().put(new JSONObject().put("name", name)
            .put("browser_download_url", AppUpdater.REPOSITORY + "/releases/download/" + tag + "/" + name)));
  }

  @Test public void stableChannelRejectsBetaEvenIfTheReleaseWasMislabelled() throws Exception {
    JSONObject beta = release("1.2.0-beta.1", "Android");
    assertFalse(AppUpdater.eligible(beta, false));
    assertTrue(AppUpdater.eligible(beta, true));
    assertFalse(AppUpdater.eligible(beta.put("prerelease", false), false));
    assertFalse(AppUpdater.eligible(release("1.2.0-rc.1", "Android"), true));
    assertTrue(AppUpdater.eligible(release("1.2.0", "Android"), false));
  }

  @Test public void betaChannelChoosesNewestCompatibleApkAcrossPages() throws Exception {
    JSONObject beta = release("1.1.0-beta.10", "Android");
    JSONObject car = release("1.2.0-beta.1", "Android-Car");
    JSONObject foreign = release("9.0.0", "Android");
    foreign.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.org/file");
    JSONArray releases = new JSONArray().put(car).put(beta).put(release("1.0.0", "Android"))
        .put(release("1.1.0-beta.2", "Android")).put(foreign).put(release("2.0.0", "Android").put("draft", true));
    assertSame(beta, AppUpdater.newestForEdition(releases, null, "Android"));
    assertSame(car, AppUpdater.newestForEdition(releases, null, "Android-Car"));
    JSONObject stable = release("1.1.0", "Android");
    assertSame(stable, AppUpdater.newestForEdition(new JSONArray().put(stable), beta, "Android"));
    assertSame(stable, AppUpdater.newestForEdition(releases, stable, "Android"));
  }
}
