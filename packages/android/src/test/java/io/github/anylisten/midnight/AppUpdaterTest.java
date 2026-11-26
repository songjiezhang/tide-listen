package io.github.anylisten.midnight;

import static org.junit.Assert.*;

import org.junit.Test;

public class AppUpdaterTest {
  @Test
  public void comparesStableReleaseToInstalledBeta() {
    assertTrue(AppUpdater.compareStableToInstalled("0.13.9", "0.13.10-beta.1") < 0);
    assertTrue(AppUpdater.compareStableToInstalled("0.13.10", "0.13.10-beta.1") > 0);
    assertTrue(AppUpdater.compareStableToInstalled("0.13.11", "0.13.10-beta.1") > 0);
    assertEquals(0, AppUpdater.compareStableToInstalled("0.13.10", "0.13.10"));
  }
}
