package app.duochess.core;

/**
 * Reads a whole position from one frame, using piece looks remembered from an earlier starting
 * position. Used to start following a game from the middle.
 */
public final class BoardReader {
    private BoardReader() {}

    public static final class Result {
        /** The position read, or null (then error says why). */
        public Position pos;
        public boolean whiteBottom;
        public String error;
        /** Something the user should know, e.g. that the side to move was corrected. */
        public String note;
    }

    private static final int WK = 6 + Position.K, BK = 6 - Position.K, WP = 6 + Position.P, BP = 6 - Position.P;

    /**
     * @param priorWhiteBottom orientation to use when the pieces do not make it clear
     */
    public static Result read(Frame f, BoardGeom g, Calibration cal, boolean whiteToMove, boolean priorWhiteBottom) {
        Result res = new Result();
        if (!cal.knows(Position.K) || !cal.knows(-Position.K)) {
            res.error = "Не помню, как выглядят короли. Откройте партию в начальной позиции";
            return res;
        }
        // dist[i][k]: how unlike image square i is to piece k (index code + 6).
        float[][] dist = new float[64][];
        int[] k = new int[64];
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int i = r * 8 + c;
                int ring = Vision.ringColor(f, g, r, c);
                dist[i] = cal.distances(Vision.descriptor(f, g, r, c, ring));
                k[i] = best(dist[i], -1, -1, -1, -1);
            }
        }

        // Exactly one king of each colour: the square most like it.
        int wk = placeKing(dist, k, WK, -1);
        placeKing(dist, k, BK, wk);
        // No pawns on the first and last rows, at most 8 of each colour.
        for (int i = 0; i < 64; i++) {
            int row = i >> 3;
            if ((row == 0 || row == 7) && (k[i] == WP || k[i] == BP)) k[i] = best(dist[i], WP, BP, WK, BK);
        }
        limitPawns(dist, k, WP);
        limitPawns(dist, k, BP);

        int white = 0, black = 0;
        for (int i = 0; i < 64; i++) {
            if (k[i] > 6) white++;
            if (k[i] < 6) black++;
        }
        if (white > 16 || black > 16) {
            res.error = "Не получилось прочитать позицию: слишком много фигур";
            return res;
        }

        float dir = direction(k);
        boolean wb = Math.abs(dir) < 1f ? priorWhiteBottom : dir > 0;
        Position p = new Position();
        for (int i = 0; i < 64; i++) p.b[Vision.chessSquare(i >> 3, i & 7, wb)] = k[i] - 6;
        p.inferCastling();
        p.ep = -1;
        p.fullmove = 1;
        p.whiteToMove = whiteToMove;

        int a = p.kingSquare(true), b = p.kingSquare(false);
        if (Math.abs((a & 7) - (b & 7)) <= 1 && Math.abs((a >> 3) - (b >> 3)) <= 1) {
            res.error = "Не получилось прочитать позицию: короли стоят рядом";
            return res;
        }
        if (p.inCheck(!whiteToMove)) {
            if (p.inCheck(whiteToMove)) {
                res.error = "Не получилось прочитать позицию: шах обоим королям";
                return res;
            }
            // The side that just moved cannot be in check, so the other side is to move.
            p.whiteToMove = !whiteToMove;
            res.note = "Король " + (whiteToMove ? "чёрных" : "белых") + " под шахом, значит ходят "
                    + (whiteToMove ? "чёрные" : "белые");
        }
        res.pos = p;
        res.whiteBottom = wb;
        return res;
    }

    /** Closest kind of piece, leaving out up to four kinds (-1 = none). */
    private static int best(float[] d, int x1, int x2, int x3, int x4) {
        int bi = 6;
        float bv = Float.MAX_VALUE;
        for (int j = 0; j < 13; j++) {
            if (j == x1 || j == x2 || j == x3 || j == x4) continue;
            if (d[j] < bv) {
                bv = d[j];
                bi = j;
            }
        }
        return bi;
    }

    private static int placeKing(float[][] dist, int[] k, int king, int taken) {
        int sq = -1;
        float cost = Float.MAX_VALUE;
        for (int i = 0; i < 64; i++) {
            if (i == taken) continue;
            float c = dist[i][king] - dist[i][k[i]];
            if (c < cost || (c == cost && dist[i][king] < dist[sq][king])) {
                cost = c;
                sq = i;
            }
        }
        for (int i = 0; i < 64; i++) {
            if (k[i] == king && i != sq) k[i] = best(dist[i], WK, BK, -1, -1);
        }
        k[sq] = king;
        return sq;
    }

    private static void limitPawns(float[][] dist, int[] k, int pawn) {
        while (true) {
            int n = 0, worst = -1;
            float cost = Float.MAX_VALUE;
            for (int i = 0; i < 64; i++) {
                if (k[i] != pawn) continue;
                n++;
                int alt = best(dist[i], WP, BP, WK, BK);
                float c = dist[i][alt] - dist[i][pawn];
                if (c < cost) {
                    cost = c;
                    worst = i;
                }
            }
            if (n <= 8) return;
            k[worst] = best(dist[worst], WP, BP, WK, BK);
        }
    }

    /**
     * Positive if White plays from the bottom of the screen: pawns and kings usually stay on
     * their own half of the board.
     */
    private static float direction(int[] k) {
        float s = 0;
        for (int i = 0; i < 64; i++) {
            if (k[i] == 6) continue;
            float down = (i >> 3) - 3.5f;
            float w;
            switch (Math.abs(k[i] - 6)) {
                case Position.P: w = 1f; break;
                case Position.K: w = 2f; break;
                default: w = 0.3f; break;
            }
            s += k[i] > 6 ? w * down : -w * down;
        }
        return s;
    }
}
