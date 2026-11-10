package app.duochess.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/** Talks UCI to a Stockfish process. */
public final class Engine {
    /** Evaluation from White's point of view. */
    public static final class Eval {
        /** Centipawns (when mate == 0). */
        public int cp;
        /** Moves to mate: positive = White mates, negative = Black mates, 0 = no mate. */
        public int mate;
        public String bestUci;
        public int depth;

        /** Winning chances for White in [-1, 1] (same formula as Lichess). */
        public double whiteChances() {
            if (mate != 0) return mate > 0 ? 1 : -1;
            double c = 2 / (1 + Math.exp(-0.00368208 * cp)) - 1;
            return Math.max(-1, Math.min(1, c));
        }
    }

    private final Process proc;
    private final BufferedWriter in;
    private final BufferedReader out;

    public Engine(String path, int threads, int hashMb) throws IOException {
        proc = new ProcessBuilder(path).redirectErrorStream(true).start();
        in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
        out = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        send("uci");
        waitFor("uciok");
        send("setoption name Threads value " + threads);
        send("setoption name Hash value " + hashMb);
        send("isready");
        waitFor("readyok");
    }

    private void send(String s) throws IOException {
        in.write(s);
        in.write('\n');
        in.flush();
    }

    private void waitFor(String token) throws IOException {
        String line;
        while ((line = out.readLine()) != null) {
            if (line.startsWith(token)) return;
        }
        throw new IOException("engine closed");
    }

    public synchronized Eval analyse(Position p, int movetimeMs) throws IOException {
        send("position fen " + p.toFen());
        send("go depth 20 movetime " + movetimeMs);
        Eval e = new Eval();
        int sign = p.whiteToMove ? 1 : -1;
        String line;
        while ((line = out.readLine()) != null) {
            if (line.startsWith("bestmove")) {
                String[] t = line.split("\\s+");
                if (t.length > 1 && !t[1].equals("(none)")) e.bestUci = t[1];
                break;
            }
            if (!line.startsWith("info") || !line.contains(" score ")) continue;
            if (line.contains(" lowerbound") || line.contains(" upperbound")) continue;
            String[] t = line.split("\\s+");
            for (int i = 0; i < t.length - 1; i++) {
                switch (t[i]) {
                    case "depth":
                        e.depth = parse(t[i + 1]);
                        break;
                    case "cp":
                        if (t[i - 1].equals("score")) {
                            e.cp = sign * parse(t[i + 1]);
                            e.mate = 0;
                        }
                        break;
                    case "mate":
                        if (t[i - 1].equals("score")) {
                            int m = parse(t[i + 1]);
                            e.mate = sign * (m == 0 ? -1 : m);
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        if (line == null) throw new IOException("engine closed");
        if (e.bestUci == null) {
            // No legal moves: mate or stalemate.
            e.mate = 0;
            e.cp = 0;
            if (p.isCheckmate()) e.mate = p.whiteToMove ? -1 : 1;
        }
        return e;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public void close() {
        try {
            send("quit");
        } catch (IOException ignored) {
            // already gone
        }
        proc.destroy();
    }
}
