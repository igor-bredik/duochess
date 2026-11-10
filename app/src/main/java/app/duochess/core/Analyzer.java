package app.duochess.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the engine on a background thread for the positions the UI currently needs, with a cache. */
public final class Analyzer {
    public interface Listener {
        void onEval();
    }

    public interface EngineFactory {
        Engine create() throws Exception;
    }

    private final EngineFactory factory;
    private final Listener listener;
    private final int movetime;
    private final Map<String, Engine.Eval> cache = new LinkedHashMap<String, Engine.Eval>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Engine.Eval> e) {
            return size() > 2000;
        }
    };
    private List<Position> wanted = new ArrayList<>();
    private Thread thread;
    private volatile boolean running;
    private volatile String error;

    public Analyzer(EngineFactory factory, int movetimeMs, Listener listener) {
        this.factory = factory;
        this.movetime = movetimeMs;
        this.listener = listener;
    }

    public static String keyOf(Position p) {
        String f = p.toFen();
        // ignore move counters
        int i = f.lastIndexOf(' ');
        i = f.lastIndexOf(' ', i - 1);
        return f.substring(0, i);
    }

    public synchronized Engine.Eval get(Position p) {
        return p == null ? null : cache.get(keyOf(p));
    }

    public String error() {
        return error;
    }

    /** Positions to analyse, most important first. Replaces the previous request. */
    public synchronized void request(List<Position> positions) {
        wanted = new ArrayList<>(positions);
        notifyAll();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "analyzer");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        notifyAll();
    }

    private void loop() {
        Engine engine = null;
        try {
            engine = factory.create();
            error = null;
        } catch (Exception e) {
            error = "Не удалось запустить Stockfish: " + e.getMessage();
            running = false;
            listener.onEval();
            return;
        }
        try {
            while (running) {
                Position next = null;
                synchronized (this) {
                    for (Position p : wanted) {
                        if (p != null && !cache.containsKey(keyOf(p))) {
                            next = p;
                            break;
                        }
                    }
                    if (next == null) {
                        try {
                            wait(1000);
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }
                }
                Engine.Eval e = engine.analyse(next, movetime);
                synchronized (this) {
                    cache.put(keyOf(next), e);
                }
                listener.onEval();
            }
        } catch (Exception e) {
            error = "Ошибка Stockfish: " + e.getMessage();
            listener.onEval();
        } finally {
            engine.close();
            running = false;
        }
    }
}
