package io.github.anylisten.midnight;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Owns candidate results until the waiting caller takes the winner. */
final class SourceRace<T, R> {
  static final long HEDGE_DELAY_MS = 1000;
  static final int MAX_CONCURRENT_SOURCES = 3;
  static final long ATTEMPT_TIMEOUT_MS = 8000;
  private static final ExecutorService WORKERS = Executors.newCachedThreadPool(task -> {
    Thread thread = new Thread(task, "LX source request");
    thread.setDaemon(true);
    return thread;
  });
  private static final ScheduledThreadPoolExecutor TIMERS = new ScheduledThreadPoolExecutor(1, task -> {
    Thread thread = new Thread(task, "LX source timer");
    thread.setDaemon(true);
    return thread;
  });

  static {
    TIMERS.setRemoveOnCancelPolicy(true);
  }

  interface Cancellation {
    void cancel(boolean timedOut);
  }

  interface Observation {
    void record(boolean timedOut);
  }

  interface Control {
    void onCancel(Cancellation action);
    void onResult(Observation observer);
    void checkCanceled() throws InterruptedException;
  }

  interface Request<T, R> {
    R run(T source, Control control) throws Exception;
  }

  interface Close<R> {
    void close(R result);
  }

  static final class Skipped extends IOException {
    Skipped(String message) {
      super(message);
    }
  }

  static final class Failure extends IOException {
    final int attempts;

    Failure(int attempts, Exception error) {
      super(error == null ? "" : error.getMessage(), error);
      this.attempts = attempts;
    }
  }

  private final List<T> candidates;
  private final SourceHealthTracker health;
  private final SourceHealthTracker.Key<T> keys;
  private final Request<T, R> request;
  private final Close<R> close;
  private final long hedgeDelayMs, timeoutMs;
  private final List<Attempt> running = new ArrayList<>();
  private ScheduledFuture<?> hedge;
  private long lastLaunch;
  private Exception lastError;
  private int next, failures;
  private boolean done, canceled;
  private R winner;

  SourceRace(List<T> candidates, SourceHealthTracker health, SourceHealthTracker.Key<T> keys,
      Request<T, R> request, Close<R> close) {
    this(candidates, health, keys, request, close, HEDGE_DELAY_MS, ATTEMPT_TIMEOUT_MS);
  }

  SourceRace(List<T> candidates, SourceHealthTracker health, SourceHealthTracker.Key<T> keys,
      Request<T, R> request, Close<R> close, long hedgeDelayMs, long timeoutMs) {
    this.candidates = candidates;
    this.health = health;
    this.keys = keys;
    this.request = request;
    this.close = close;
    this.hedgeDelayMs = hedgeDelayMs;
    this.timeoutMs = timeoutMs;
  }

  R await() throws InterruptedException, Failure {
    try {
      synchronized (this) {
        schedule();
        while (!done) wait();
        if (canceled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (winner == null) throw new Failure(failures, lastError);
        R result = winner;
        winner = null;
        return result;
      }
    } catch (InterruptedException error) {
      cancel();
      throw error;
    }
  }

  void cancel() {
    R result;
    synchronized (this) {
      canceled = true;
      finish();
      result = winner;
      winner = null;
    }
    if (result != null) close.close(result);
  }

  private void finish() {
    done = true;
    if (hedge != null) hedge.cancel(false);
    for (Attempt attempt : running) attempt.stop(false);
    running.clear();
    notifyAll();
  }

  private void schedule() {
    if (hedge != null) hedge.cancel(false);
    if (done) return;
    if (next == candidates.size()) {
      if (running.isEmpty()) finish();
    } else if (running.isEmpty()) {
      launch();
    } else if (running.size() < MAX_CONCURRENT_SOURCES) {
      long delay = TimeUnit.MILLISECONDS.toNanos(hedgeDelayMs) - (System.nanoTime() - lastLaunch);
      hedge = TIMERS.schedule(() -> {
        synchronized (SourceRace.this) {
          // A completion may have scheduled a different hedge before this callback got the lock.
          if (!done && next < candidates.size() && running.size() < MAX_CONCURRENT_SOURCES
              && System.nanoTime() - lastLaunch >= TimeUnit.MILLISECONDS.toNanos(hedgeDelayMs)) {
            launch();
          }
        }
      }, Math.max(0, delay), TimeUnit.NANOSECONDS);
    }
  }

  private void launch() {
    Attempt attempt = new Attempt(candidates.get(next++));
    lastLaunch = attempt.started;
    running.add(attempt);
    attempt.worker = WORKERS.submit(attempt::run);
    attempt.timeout = TIMERS.schedule(() -> failed(attempt, new IOException("音源请求超时"), true),
        timeoutMs, TimeUnit.MILLISECONDS);
    schedule();
  }

  private synchronized boolean succeeded(Attempt attempt, R result) {
    if (!running.remove(attempt)) return false;
    attempt.timeout.cancel(false);
    if (attempt.observer != null) attempt.observer.record(false);
    attempt.sample.success(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - attempt.started));
    winner = result;
    finish();
    return true;
  }

  private synchronized void failed(Attempt attempt, Exception error, boolean timedOut) {
    if (!running.remove(attempt)) return;
    if (timedOut) attempt.stop(true);
    else attempt.timeout.cancel(false);
    if (!(error instanceof Skipped) && !(error instanceof InterruptedException)) {
      attempt.sample.failure();
      if (attempt.observer != null) attempt.observer.record(timedOut || error instanceof TimeoutException);
      failures++;
      lastError = error;
    }
    schedule();
  }

  private final class Attempt implements Control {
    final T candidate;
    final SourceHealthTracker.Sample sample;
    final long started = System.nanoTime();
    Future<?> worker, timeout;
    Cancellation onCancel;
    Observation observer;
    boolean stopped, timedOut;

    Attempt(T candidate) {
      this.candidate = candidate;
      sample = health.track(keys.get(candidate));
    }

    void run() {
      try {
        checkCanceled();
        R result = request.run(candidate, this);
        if (!succeeded(this, result)) close.close(result);
      } catch (Exception error) {
        failed(this, error, false);
      }
    }

    void stop(boolean timedOut) {
      stopped = true;
      this.timedOut = timedOut;
      timeout.cancel(false);
      // Once Media3 owns a Call, let open() unwind on its own worker. Interrupting
      // its response Future can leave a concurrently delivered Response without a reader.
      if (onCancel == null) worker.cancel(true);
      else onCancel.cancel(timedOut);
    }

    @Override
    public void onCancel(Cancellation action) {
      synchronized (SourceRace.this) {
        onCancel = action;
        if (stopped && action != null) action.cancel(timedOut);
      }
    }

    @Override
    public void onResult(Observation action) {
      synchronized (SourceRace.this) {
        observer = action;
        if (stopped && timedOut) action.record(true);
      }
    }

    @Override
    public void checkCanceled() throws InterruptedException {
      synchronized (SourceRace.this) {
        if (stopped || Thread.currentThread().isInterrupted()) throw new InterruptedException();
      }
    }
  }
}
