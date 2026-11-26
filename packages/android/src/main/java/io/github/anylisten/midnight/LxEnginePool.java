package io.github.anylisten.midnight;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

final class LxEnginePool<E extends LxEnginePool.Engine> {
  interface Engine {
    void close();
    boolean isBroken();
  }

  interface Open<E> {
    E run(Runnable onBroken) throws Exception;
  }

  private final long idleMs;
  private final Map<String, Entry> entries = new HashMap<>();
  private final ExecutorService initializer = Executors.newCachedThreadPool(task -> daemon(task, "LX engine init"));

  private final ScheduledThreadPoolExecutor sweeper = new ScheduledThreadPoolExecutor(1, task -> daemon(task, "LX engine idle"));
  private final SourceHealthTracker health;

  LxEnginePool(SourceHealthTracker health) {
    this(health, TimeUnit.MINUTES.toMillis(5));
  }

  LxEnginePool(SourceHealthTracker health, long idleMs) {
    this.health = health;
    this.idleMs = idleMs;
    sweeper.setRemoveOnCancelPolicy(true);
  }

  private static Thread daemon(Runnable task, String name) {
    Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    return thread;
  }

  private final class Entry {
    final int revision;
    final FutureTask<E> opening;
    E engine;
    int users, timeouts;
    boolean invalidated;
    ScheduledFuture<?> idle;
    long generation;

    Entry(String id, int revision, Open<E> open) {
      this.revision = revision;
      opening = new FutureTask<>(() -> {
        E opened;
        try {
          opened = open.run(() -> {
            synchronized (LxEnginePool.this) {
              if (entries.get(id) == this && engine != null) {
                health.invalidateSource(id);
                invalidate(id);
              }
            }
          });
        } catch (Exception error) {
          synchronized (LxEnginePool.this) {
            if (entries.get(id) == this) entries.remove(id);
          }
          throw error;
        }
        synchronized (LxEnginePool.this) {
          if (invalidated || opened.isBroken()) {
            if (entries.get(id) == this) entries.remove(id);
            opened.close();
            throw new IOException("音源已变更");
          }
          engine = opened;
        }
        return opened;
      });
    }

    void close() {
      invalidated = true;
      if (idle != null) idle.cancel(false);
      opening.cancel(true);
      if (engine != null) engine.close();
    }
  }

  final class Lease implements AutoCloseable {
    private final String id;
    private final Entry entry;
    private boolean released;

    Lease(String id, Entry entry) {
      this.id = id;
      this.entry = entry;
    }

    E await() throws Exception {
      try {
        return entry.opening.get();
      } catch (CancellationException error) {
        throw new SourceRace.Skipped("音源已变更");
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception) throw (Exception) cause;
        throw new IOException("音源初始化失败", cause);
      }
    }

    void recordResult(boolean timeout) {
      synchronized (LxEnginePool.this) {
        if (entries.get(id) != entry) return;
        if (!timeout) entry.timeouts = 0;
        else if (++entry.timeouts >= 2) {
          health.invalidateSource(id);
          invalidate(id);
        }
      }
    }

    @Override
    public void close() {
      synchronized (LxEnginePool.this) {
        if (released) return;
        released = true;
        if (--entry.users != 0 || entries.get(id) != entry) return;
        if (entry.engine == null) invalidate(id);
        else {
          long generation = entry.generation;
          entry.idle = sweeper.schedule(() -> {
            synchronized (LxEnginePool.this) {
              if (entries.get(id) == entry && entry.generation == generation && entry.users == 0) invalidate(id);
            }
          }, idleMs, TimeUnit.MILLISECONDS);
        }
      }
    }
  }

  synchronized Lease acquire(String id, int revision, Open<E> open) {
    Entry entry = entries.get(id);
    if (entry != null && (entry.revision != revision || entry.engine != null && entry.engine.isBroken())) {
      health.invalidateSource(id);
      invalidate(id);
      entry = null;
    }
    if (entry == null) {
      entry = new Entry(id, revision, open);
      entries.put(id, entry);
      initializer.execute(entry.opening);
    }
    if (entry.idle != null) entry.idle.cancel(false);
    entry.generation++;
    entry.users++;
    return new Lease(id, entry);
  }

  synchronized boolean isReady(String id, int revision) {
    Entry entry = entries.get(id);
    return entry != null && entry.revision == revision && entry.engine != null && !entry.engine.isBroken();
  }

  synchronized void invalidate(String id) {
    Entry entry = entries.remove(id);
    if (entry != null) entry.close();
  }

}
