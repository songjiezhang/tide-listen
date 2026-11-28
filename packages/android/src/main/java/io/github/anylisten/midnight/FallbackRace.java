package io.github.anylisten.midnight;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/** Owns queued matches and opened streams until the coordinator takes a winner or closes. */
final class FallbackRace<M, R> implements AutoCloseable {
  private final ArrayDeque<M> matches = new ArrayDeque<>();
  private final SourceRace.Close<R> dispose;
  private final long searchDeadline, totalDeadline, stagger;
  private final int capacity;
  private int searches, opening;
  private long nextLaunch;
  private boolean closed;
  private R winner;

  FallbackRace(int searches, int capacity, long searchMs, long totalMs, long staggerMs,
      SourceRace.Close<R> dispose) {
    long now = System.nanoTime();
    this.searchDeadline = now + TimeUnit.MILLISECONDS.toNanos(searchMs);
    this.totalDeadline = now + TimeUnit.MILLISECONDS.toNanos(totalMs);
    this.stagger = TimeUnit.MILLISECONDS.toNanos(staggerMs);
    this.searches = searches;
    this.capacity = capacity;
    this.dispose = dispose;
  }

  synchronized boolean offer(M match) {
    if (closed || System.nanoTime() >= searchDeadline) return false;
    matches.add(match);
    notifyAll();
    return true;
  }

  synchronized void searchFinished() {
    searches--;
    notifyAll();
  }

  synchronized M next() throws InterruptedException {
    while (!closed && winner == null) {
      long now = System.nanoTime();
      if (now >= totalDeadline) break;
      if (!matches.isEmpty() && opening < capacity && (opening == 0 || now >= nextLaunch)) {
        opening++;
        nextLaunch = now + stagger;
        return matches.remove();
      }
      boolean searching = searches > 0 && now < searchDeadline;
      if (matches.isEmpty() && !searching && opening == 0) break;
      long wake = searching ? Math.min(totalDeadline, searchDeadline) : totalDeadline;
      if (!matches.isEmpty() && opening < capacity) wake = Math.min(wake, nextLaunch);
      TimeUnit.NANOSECONDS.timedWait(this, Math.max(1, wake - now));
    }
    return null;
  }

  void finished(R result) {
    synchronized (this) {
      opening--;
      if (result != null && !closed && winner == null && System.nanoTime() < totalDeadline) {
        winner = result;
        result = null;
      }
      notifyAll();
    }
    if (result != null) dispose.close(result);
  }

  synchronized R takeWinner() {
    closed = true;
    R result = winner;
    winner = null;
    notifyAll();
    return result;
  }

  @Override public void close() {
    R abandoned = takeWinner();
    if (abandoned != null) dispose.close(abandoned);
  }
}
