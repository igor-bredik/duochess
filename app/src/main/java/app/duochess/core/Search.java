package app.duochess.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Finds the move sequence that best explains what is seen on the board. */
public final class Search {
    private Search() {}

    /** Cost of position p against observations (chess-square indexed). [0] colour cost, [1] ranking cost. */
    public static float[] cost(Calibration.Obs[] ch, Position p) {
        float c = 0, t = 0;
        for (int sq = 0; sq < 64; sq++) {
            int e = p.colorAt(sq);
            Calibration.Obs o = ch[sq];
            if (o.state == Calibration.UNKNOWN) {
                c += o.best == e ? 0.25f : 0.75f;
            } else if (o.state != e) {
                c += 1f;
            } else if (e != 0 && o.code != p.b[sq]) {
                t += 0.2f;
            }
        }
        return new float[] {c, c + t};
    }

    public static float colorCost(Calibration.Obs[] ch, Position p) {
        return cost(ch, p)[0];
    }

    public static final class Node {
        public final Position pos;
        public final List<Move> seq;
        public final float color;
        public final float rank;

        Node(Position pos, List<Move> seq, float[] c) {
            this.pos = pos;
            this.seq = seq;
            this.color = c[0];
            float pen = 0;
            for (Move m : seq) pen += m.isNull() ? 0.8f : 0.3f;
            this.rank = c[1] + pen;
        }
    }

    public static final class Result {
        public Node best;
        public Node second;

        /** True if best clearly explains the observation and nothing else does. */
        public boolean accept(float baseCost) {
            if (best == null || best.color > 1.5f || best.color + 1.5f > baseCost) return false;
            if (second == null) return true;
            if (second.color - best.color >= 1.0f) return true;
            return sameOccupancy(best.pos, second.pos) && second.rank >= best.rank;
        }
    }

    private static boolean sameOccupancy(Position a, Position b) {
        for (int i = 0; i < 64; i++) if (a.colorAt(i) != b.colorAt(i)) return false;
        return true;
    }

    private static void consider(Map<String, Node> finals, Node n) {
        String k = n.pos.key();
        Node old = finals.get(k);
        if (old == null || n.rank < old.rank) finals.put(k, n);
    }

    private static Result pick(Map<String, Node> finals) {
        Result r = new Result();
        for (Node n : finals.values()) {
            if (r.best == null || better(n, r.best)) {
                r.second = r.best;
                r.best = n;
            } else if (r.second == null || better(n, r.second)) {
                r.second = n;
            }
        }
        return r;
    }

    private static boolean better(Node a, Node b) {
        if (Math.abs(a.color - b.color) >= 0.5f) return a.color < b.color;
        return a.rank < b.rank;
    }

    private static List<Move> plus(List<Move> seq, Move m) {
        List<Move> s = new ArrayList<>(seq.size() + 1);
        s.addAll(seq);
        s.add(m);
        return s;
    }

    public static Result exhaustive(Position start, Calibration.Obs[] ch, int maxDepth) {
        return exhaustive(start, ch, maxDepth, false);
    }

    /**
     * Tries every sequence of up to maxDepth (1..3) moves. With allowNull the sequence may start
     * with a pass, which fixes a wrong side to move.
     */
    public static Result exhaustive(Position start, Calibration.Obs[] ch, int maxDepth, boolean allowNull) {
        Map<String, Node> finals = new HashMap<>();
        List<Node> frontier = new ArrayList<>();
        frontier.add(new Node(start, new ArrayList<Move>(), cost(ch, start)));
        for (int depth = 1; depth <= maxDepth; depth++) {
            List<Node> next = new ArrayList<>();
            for (Node n : frontier) {
                List<Move> options = n.pos.legalMoves();
                if (allowNull && depth == 1) {
                    options = new ArrayList<>(options);
                    options.add(Move.NULL);
                }
                for (Move m : options) {
                    Position p = n.pos.make(m);
                    Node c = new Node(p, plus(n.seq, m), cost(ch, p));
                    consider(finals, c);
                    if (depth < maxDepth) next.add(c);
                }
            }
            Result r = pick(finals);
            if (r.best != null && r.best.color <= 1.0f) return r;
            frontier = next;
        }
        return pick(finals);
    }

    /** Beam search for longer jumps (several moves at once). */
    public static Result beam(Position start, Calibration.Obs[] ch, int maxDepth, int width) {
        Map<String, Node> finals = new HashMap<>();
        List<Node> beam = new ArrayList<>();
        beam.add(new Node(start, new ArrayList<Move>(), cost(ch, start)));
        for (int depth = 1; depth <= maxDepth; depth++) {
            Map<String, Node> level = new HashMap<>();
            for (Node n : beam) {
                for (Move m : n.pos.legalMoves()) {
                    Position p = n.pos.make(m);
                    Node c = new Node(p, plus(n.seq, m), cost(ch, p));
                    String k = p.key();
                    Node old = level.get(k);
                    if (old == null || c.rank < old.rank) level.put(k, c);
                }
            }
            List<Node> all = new ArrayList<>(level.values());
            Collections.sort(all, (a, b) -> Float.compare(a.rank, b.rank));
            for (Node n : all) if (n.color <= 1.5f) consider(finals, n);
            Result r = pick(finals);
            if (r.best != null && r.best.color <= 0.5f && depth >= r.best.seq.size()) return r;
            beam = all.subList(0, Math.min(width, all.size()));
        }
        return pick(finals);
    }
}
