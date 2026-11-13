package app.duochess.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Follows a game shown on screen: finds the board in the starting position (or reads the position
 * now on the board, when asked), then keeps track of moves going forward and backward (as when
 * stepping through Duolingo's game review).
 */
public final class Tracker {
    public interface Listener {
        /** The shown position changed. Called on the capture thread. */
        void onPosition(Tracker t);

        void onStatus(String status);

        /**
         * Piece looks learned from a starting position and the places the board was seen at,
         * worth keeping for later sessions.
         */
        void onCalibrated(Calibration pieces, List<BoardGeom> boards);

        /** A message for the user, e.g. why the position could not be read. */
        void onNotice(String text);
    }

    private final Listener listener;
    private BoardGeom geom;
    private Calibration cal;
    private boolean whiteBottom;
    private final List<Position> positions = new ArrayList<>();
    private final List<Move> moves = new ArrayList<>();
    private int cur;
    private boolean active;

    private int[] lastSig;
    private int stable;
    private int[] lastDeepSig;
    private int lastJump = -1;
    private int invisible;
    private int frameNo;
    private String status = "";
    /** Piece looks from the last starting position seen (this session or an earlier one). */
    private Calibration saved;
    /** Where boards were seen (most recent first): screens differ between the game and the review. */
    private final List<BoardGeom> boards = new ArrayList<>();
    /** False if the game was picked up from the middle (move numbers unknown). */
    private boolean fromStart = true;
    /** 0: nothing asked; 1 / 2: read the board with White / Black to move. */
    private volatile int readRequest;
    public final StringBuilder log = new StringBuilder();

    public Tracker(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------ state for the UI

    public synchronized boolean active() {
        return active;
    }

    public synchronized int ply() {
        return cur;
    }

    public synchronized Position position(int i) {
        return positions.get(i);
    }

    public synchronized Position current() {
        return active ? positions.get(cur) : null;
    }

    /** Number of moves known in the current line (may run past the shown position). */
    public synchronized int lineLength() {
        return active ? moves.size() : 0;
    }

    public synchronized Move move(int i) {
        return moves.get(i);
    }

    /** True if the board was visible in the latest frame. */
    public synchronized boolean boardVisible() {
        return geom != null && invisible == 0;
    }

    /** Move that led to the current position, or null at the start. */
    public synchronized Move lastMove() {
        return active && cur > 0 ? moves.get(cur - 1) : null;
    }

    public synchronized List<String> sanLine() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < moves.size(); i++) out.add(positions.get(i).san(moves.get(i)));
        return out;
    }

    public synchronized boolean playerWhite() {
        return whiteBottom;
    }

    /** True if the position can be read from the board (piece looks are known). */
    public synchronized boolean canRead() {
        return saved != null;
    }

    public synchronized void setSaved(Calibration c, List<BoardGeom> knownBoards) {
        saved = c;
        boards.clear();
        if (knownBoards != null) boards.addAll(knownBoards);
    }

    /** True if the game was followed from the starting position. */
    public synchronized boolean fromStart() {
        return fromStart;
    }

    /** Asks to take the position now on the board as the start, with the given side to move. */
    public void requestRead(boolean whiteToMove) {
        readRequest = whiteToMove ? 1 : 2;
    }

    public boolean readPending() {
        return readRequest != 0;
    }

    public String status() {
        return status;
    }

    public synchronized BoardGeom geom() {
        return geom;
    }

    private void setStatus(String s) {
        if (!s.equals(status)) {
            status = s;
            listener.onStatus(s);
        }
    }

    private void logLine(String s) {
        if (log.length() > 20000) log.delete(0, 10000);
        log.append(s).append('\n');
    }

    // ------------------------------------------------------------------ frame processing

    public void process(Frame f, long now) {
        boolean changed;
        synchronized (this) {
            changed = step(f, now);
        }
        if (changed) listener.onPosition(this);
    }

    private boolean step(Frame f, long now) {
        frameNo++;
        int rq = readRequest;
        if (rq != 0) {
            readRequest = 0;
            return readBoard(f, rq == 1);
        }
        if (geom == null) {
            if (frameNo % 2 != 0) return false;
            Vision.Detection d = Vision.detect(f, null);
            if (d == null) {
                setStatus("Ищу доску. Откройте партию в начальной позиции");
                return false;
            }
            adopt(d, f, now);
            return true;
        }
        Calibration.Obs[] img = cal.observe(f, geom);
        if (!cal.visible(img)) {
            invisible++;
            if (invisible % 4 == 0) {
                Vision.Detection d = Vision.detect(f, null);
                if (d != null) {
                    adopt(d, f, now);
                    return true;
                }
            }
            if (invisible >= 6) setStatus("Не вижу доску");
            return false;
        }
        invisible = 0;
        int[] sig = new int[64];
        for (int i = 0; i < 64; i++) sig[i] = img[i].state;
        if (lastSig != null && hamming(sig, lastSig) == 0) {
            stable++;
        } else {
            stable = 0;
        }
        lastSig = sig;
        if (stable < 2) return false;
        return evaluate(img, f, sig, now);
    }

    private static int hamming(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) n++;
        return n;
    }

    private Calibration.Obs[] toChess(Calibration.Obs[] img, boolean wb) {
        Calibration.Obs[] ch = new Calibration.Obs[64];
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) ch[Vision.chessSquare(r, c, wb)] = img[r * 8 + c];
        return ch;
    }

    private void adopt(Vision.Detection d, Frame f, long now) {
        geom = d.geom;
        whiteBottom = d.whiteBottom;
        cal = Calibration.build(f, geom, whiteBottom, Position.initial(), d.odd);
        remember();
        startGame();
        lastSig = null;
        stable = 0;
        logLine("board found: " + geom + (whiteBottom ? " white" : " black") + " at bottom, odd " + d.oddCount);
        setStatus(statusText());
    }

    private void startGame() {
        positions.clear();
        moves.clear();
        positions.add(Position.initial());
        cur = 0;
        active = true;
        lastDeepSig = null;
        lastJump = -1;
        fromStart = true;
    }

    private void remember() {
        saved = cal.snapshot();
        rememberBoard(geom);
        listener.onCalibrated(saved, new ArrayList<>(boards));
    }

    /** Returns true if g is a new place. */
    private boolean rememberBoard(BoardGeom g) {
        boolean known = !boards.isEmpty() && boards.get(0).near(g);
        for (int i = boards.size() - 1; i >= 0; i--) if (boards.get(i).near(g)) boards.remove(i);
        boards.add(0, g);
        while (boards.size() > 4) boards.remove(boards.size() - 1);
        return !known;
    }

    private String statusText() {
        String side = whiteBottom ? "белые" : "чёрные";
        if (!fromStart) return "Слежу за доской (" + side + " снизу)";
        return String.format(Locale.forLanguageTag("ru"), "Слежу за доской (%s снизу), ход %d", side, (cur + 1) / 2);
    }

    /** Takes the position now on the board as the start of the game. */
    private boolean readBoard(Frame f, boolean whiteToMove) {
        if (saved == null) {
            listener.onNotice("Сначала откройте партию в начальной позиции: так я запомню, как выглядят фигуры");
            return false;
        }
        BoardGeom g = Vision.findBoard(f);
        if (g == null) {
            // Pieces may hide the lines the search needs: try where boards were seen before.
            List<BoardGeom> known = new ArrayList<>();
            if (geom != null) known.add(geom);
            known.addAll(boards);
            for (BoardGeom k : known) {
                if (Vision.looksLikeBoard(f, k)) {
                    g = k;
                    break;
                }
            }
        }
        if (g == null) {
            logLine("read: no board");
            listener.onNotice("Не вижу доску на экране");
            return false;
        }
        Calibration c = saved.forFrame(f, g);
        BoardReader.Result r = BoardReader.read(f, g, c, whiteToMove, geom != null ? whiteBottom : true);
        if (r.pos == null) {
            logLine("read failed at " + g + ": " + r.error);
            listener.onNotice(r.error);
            return false;
        }
        geom = g;
        cal = c;
        whiteBottom = r.whiteBottom;
        positions.clear();
        moves.clear();
        positions.add(r.pos);
        cur = 0;
        active = true;
        fromStart = false;
        lastDeepSig = null;
        lastJump = -1;
        lastSig = null;
        stable = 0;
        invisible = 0;
        logLine("read position at " + g + ": " + r.pos.toFen() + (whiteBottom ? ", white" : ", black") + " at bottom");
        if (rememberBoard(g)) listener.onCalibrated(saved, new ArrayList<>(boards));
        if (r.note != null) listener.onNotice(r.note);
        setStatus(statusText());
        return true;
    }

    private boolean evaluate(Calibration.Obs[] img, Frame f, int[] sig, long now) {
        Calibration.Obs[] ch = toChess(img, whiteBottom);
        float base = Search.colorCost(ch, positions.get(cur));
        if (base <= 0.75f) return false;

        // A position we already know (stepping back, or forward again).
        int bestIdx = -1;
        float bestCost = Float.MAX_VALUE;
        for (int i = 0; i < positions.size(); i++) {
            float c = Search.colorCost(ch, positions.get(i));
            if (c < bestCost || (c == bestCost && Math.abs(i - cur) < Math.abs(bestIdx - cur))) {
                bestCost = c;
                bestIdx = i;
            }
        }
        boolean known = bestCost <= 1.0f && bestIdx != cur;
        // One step back, or forward again along the known line.
        if (known && Math.abs(bestIdx - cur) == 1) {
            return jumpTo(bestIdx);
        }
        // A single move forward is more likely than a jump back to a repeated position.
        Search.Result r1 = Search.exhaustive(positions.get(cur), ch, 1);
        if (r1.accept(base)) {
            extend(r1.best.seq, img);
            return true;
        }
        if (known) {
            return jumpTo(bestIdx);
        }

        // A new game from the start, either orientation.
        for (int k = 0; k < 2; k++) {
            boolean wb = k == 0 ? whiteBottom : !whiteBottom;
            Calibration.Obs[] c2 = k == 0 ? ch : toChess(img, wb);
            if (Search.colorCost(c2, Position.initial()) <= 0.5f) {
                newGame(f, wb, null);
                return true;
            }
        }

        // One or two moves forward from the current position.
        Search.Result r = Search.exhaustive(positions.get(cur), ch, 2);
        if (r.accept(base)) {
            extend(r.best.seq, img);
            return true;
        }

        // A position shortly after an earlier one (stepping back into a part we skipped over,
        // or tracking went wrong a few moves ago).
        for (int i = cur - 1; i >= Math.max(0, cur - 12); i--) {
            float b2 = Search.colorCost(ch, positions.get(i));
            Search.Result r2 = Search.exhaustive(positions.get(i), ch, 2);
            if (r2.accept(b2)) {
                cur = i;
                extend(r2.best.seq, img);
                return true;
            }
        }

        boolean deep = !Arrays.equals(sig, lastDeepSig);
        if (deep) {
            lastDeepSig = sig.clone();
            // Several moves at once.
            r = Search.beam(positions.get(cur), ch, 14, 96);
            if (r.accept(base)) {
                int from = cur;
                extend(r.best.seq, img);
                if (r.best.seq.size() >= 2) lastJump = from;
                return true;
            }
        }

        // The side to move is probably wrong (after a jump the number of moves is ambiguous).
        Search.Result rn = Search.exhaustive(positions.get(cur), ch, 2, true);
        if (rn.accept(base)) {
            if (lastJump >= 0 && lastJump < cur && deep) {
                Position jp = positions.get(lastJump);
                Search.Result rj = Search.beam(jp, ch, Math.min(20, cur - lastJump + 4), 96);
                if (rj.accept(Search.colorCost(ch, jp))) {
                    cur = lastJump;
                    lastJump = -1;
                    extend(rj.best.seq, img);
                    logLine("repaired line after jump");
                    return true;
                }
            }
            extend(rn.best.seq, img);
            return true;
        }

        // A new game where the opponent already made the first move.
        for (int k = 0; k < 2; k++) {
            boolean wb = k == 0 ? whiteBottom : !whiteBottom;
            Calibration.Obs[] c2 = k == 0 ? ch : toChess(img, wb);
            Position start = Position.initial();
            Search.Result r0 = Search.exhaustive(start, c2, 2);
            if (r0.accept(Search.colorCost(c2, start)) && r0.best.color <= 1.0f) {
                boolean[] skip = new boolean[64];
                for (Move m : r0.best.seq) {
                    skip[Vision.imageIndex(m.from, wb)] = true;
                    skip[Vision.imageIndex(m.to, wb)] = true;
                }
                newGame(f, wb, skip);
                extend(r0.best.seq, toImage(c2, wb));
                return true;
            }
        }
        setStatus("Не узнаю позицию. Вернитесь к началу партии или нажмите «Позиция»");
        return false;
    }

    private boolean jumpTo(int idx) {
        cur = idx;
        logLine("jump to ply " + cur);
        setStatus(statusText());
        return true;
    }

    private Calibration.Obs[] toImage(Calibration.Obs[] ch, boolean wb) {
        Calibration.Obs[] img = new Calibration.Obs[64];
        for (int sq = 0; sq < 64; sq++) img[Vision.imageIndex(sq, wb)] = ch[sq];
        return img;
    }

    private void newGame(Frame f, boolean wb, boolean[] skip) {
        whiteBottom = wb;
        cal = Calibration.build(f, geom, wb, Position.initial(), skip);
        remember();
        startGame();
        logLine("new game, " + (wb ? "white" : "black") + " at bottom");
        setStatus(statusText());
    }

    private void extend(List<Move> seq, Calibration.Obs[] img) {
        // Keep the known continuation if we are just replaying it.
        boolean replay = true;
        for (int i = 0; i < seq.size(); i++) {
            int idx = cur + i;
            if (idx >= moves.size() || !moves.get(idx).equals(seq.get(i))) {
                replay = false;
                break;
            }
        }
        if (!replay) {
            while (moves.size() > cur) moves.remove(moves.size() - 1);
            while (positions.size() > cur + 1) positions.remove(positions.size() - 1);
            Position p = positions.get(cur);
            for (Move m : seq) {
                Position n = p.make(m);
                moves.add(m);
                positions.add(n);
                p = n;
            }
        }
        cur += seq.size();
        Position now = positions.get(cur);
        for (Move m : seq) {
            if (!m.isNull()) learnSquare(now, m.to, img);
        }
        logLine("moves " + seq + " -> ply " + cur);
        setStatus(statusText());
    }

    private void learnSquare(Position p, int sq, Calibration.Obs[] img) {
        int idx = Vision.imageIndex(sq, whiteBottom);
        Calibration.Obs o = img[idx];
        if (o == null || p.b[sq] == 0) return;
        int parity = ((idx >> 3) + (idx & 7)) & 1;
        cal.learn(p.b[sq], parity, o.desc);
    }

    /** Ends tracking (the next frame starts looking for a board again). */
    public synchronized void reset() {
        geom = null;
        cal = null;
        active = false;
        positions.clear();
        moves.clear();
        cur = 0;
    }
}
