package app.duochess.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal but complete chess rules: legal move generation (castling, en passant,
 * promotion), making moves, check / mate detection and SAN output.
 * Pieces: positive = white, negative = black.
 */
public final class Position {
    public static final int P = 1, N = 2, B = 3, R = 4, Q = 5, K = 6;

    private static final int WK = 1, WQ = 2, BK = 4, BQ = 8;

    private static final int[][] KNIGHT = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
    private static final int[][] KING = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final int[][] DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] ORTHO = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public final int[] b = new int[64];
    public boolean whiteToMove = true;
    public int castling = WK | WQ | BK | BQ;
    public int ep = -1;
    public int fullmove = 1;

    public static Position initial() {
        Position p = new Position();
        int[] back = {R, N, B, Q, K, B, N, R};
        for (int f = 0; f < 8; f++) {
            p.b[f] = back[f];
            p.b[8 + f] = P;
            p.b[48 + f] = -P;
            p.b[56 + f] = -back[f];
        }
        return p;
    }

    public static Position fromFen(String fen) {
        String[] parts = fen.trim().split("\\s+");
        Position p = new Position();
        String[] ranks = parts[0].split("/");
        for (int i = 0; i < 8; i++) {
            int r = 7 - i, f = 0;
            for (char c : ranks[i].toCharArray()) {
                if (Character.isDigit(c)) {
                    f += c - '0';
                } else {
                    int t = "pnbrqk".indexOf(Character.toLowerCase(c)) + 1;
                    p.b[r * 8 + f] = Character.isUpperCase(c) ? t : -t;
                    f++;
                }
            }
        }
        p.whiteToMove = parts.length < 2 || parts[1].equals("w");
        p.castling = 0;
        if (parts.length > 2) {
            if (parts[2].contains("K")) p.castling |= WK;
            if (parts[2].contains("Q")) p.castling |= WQ;
            if (parts[2].contains("k")) p.castling |= BK;
            if (parts[2].contains("q")) p.castling |= BQ;
        }
        p.ep = -1;
        if (parts.length > 3 && !parts[3].equals("-")) {
            p.ep = (parts[3].charAt(1) - '1') * 8 + (parts[3].charAt(0) - 'a');
        }
        if (parts.length > 5) p.fullmove = Integer.parseInt(parts[5]);
        return p;
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                int v = b[r * 8 + f];
                if (v == 0) {
                    empty++;
                    continue;
                }
                if (empty > 0) { sb.append(empty); empty = 0; }
                char c = "pnbrqk".charAt(Math.abs(v) - 1);
                sb.append(v > 0 ? Character.toUpperCase(c) : c);
            }
            if (empty > 0) sb.append(empty);
            if (r > 0) sb.append('/');
        }
        sb.append(whiteToMove ? " w " : " b ");
        String c = (castling & WK) != 0 ? "K" : "";
        if ((castling & WQ) != 0) c += "Q";
        if ((castling & BK) != 0) c += "k";
        if ((castling & BQ) != 0) c += "q";
        sb.append(c.isEmpty() ? "-" : c);
        sb.append(' ').append(ep >= 0 ? Move.sqName(ep) : "-");
        sb.append(" 0 ").append(fullmove);
        return sb.toString();
    }

    /** Key that identifies the placement and side to move. */
    public String key() {
        StringBuilder sb = new StringBuilder(66);
        for (int v : b) sb.append((char) ('H' + v));
        sb.append(whiteToMove ? 'w' : 'b');
        return sb.toString();
    }

    public Position copy() {
        Position p = new Position();
        System.arraycopy(b, 0, p.b, 0, 64);
        p.whiteToMove = whiteToMove;
        p.castling = castling;
        p.ep = ep;
        p.fullmove = fullmove;
        return p;
    }

    private static boolean on(int f, int r) {
        return f >= 0 && f < 8 && r >= 0 && r < 8;
    }

    /** 0 = empty, 1 = white piece, 2 = black piece. */
    public int colorAt(int sq) {
        int v = b[sq];
        return v == 0 ? 0 : (v > 0 ? 1 : 2);
    }

    public boolean isAttacked(int sq, boolean byWhite) {
        int f = sq & 7, r = sq >> 3;
        int s = byWhite ? 1 : -1;
        // pawns
        int pr = r - s;
        if (on(f - 1, pr) && b[pr * 8 + f - 1] == s * P) return true;
        if (on(f + 1, pr) && b[pr * 8 + f + 1] == s * P) return true;
        for (int[] d : KNIGHT) {
            int nf = f + d[0], nr = r + d[1];
            if (on(nf, nr) && b[nr * 8 + nf] == s * N) return true;
        }
        for (int[] d : KING) {
            int nf = f + d[0], nr = r + d[1];
            if (on(nf, nr) && b[nr * 8 + nf] == s * K) return true;
        }
        for (int[] d : DIAG) {
            int nf = f + d[0], nr = r + d[1];
            while (on(nf, nr)) {
                int v = b[nr * 8 + nf];
                if (v != 0) {
                    if (v == s * B || v == s * Q) return true;
                    break;
                }
                nf += d[0];
                nr += d[1];
            }
        }
        for (int[] d : ORTHO) {
            int nf = f + d[0], nr = r + d[1];
            while (on(nf, nr)) {
                int v = b[nr * 8 + nf];
                if (v != 0) {
                    if (v == s * R || v == s * Q) return true;
                    break;
                }
                nf += d[0];
                nr += d[1];
            }
        }
        return false;
    }

    public int kingSquare(boolean white) {
        int k = white ? K : -K;
        for (int i = 0; i < 64; i++) if (b[i] == k) return i;
        return -1;
    }

    public boolean inCheck(boolean white) {
        int k = kingSquare(white);
        return k >= 0 && isAttacked(k, !white);
    }

    private void addPawnMove(List<Move> out, int from, int to, boolean ep) {
        int r = to >> 3;
        if (r == 7 || r == 0) {
            out.add(new Move(from, to, Q, false, false));
            out.add(new Move(from, to, R, false, false));
            out.add(new Move(from, to, B, false, false));
            out.add(new Move(from, to, N, false, false));
        } else {
            out.add(new Move(from, to, 0, false, ep));
        }
    }

    private List<Move> pseudoLegal() {
        List<Move> out = new ArrayList<>(48);
        boolean w = whiteToMove;
        int s = w ? 1 : -1;
        for (int sq = 0; sq < 64; sq++) {
            int v = b[sq];
            if (v == 0 || (v > 0) != w) continue;
            int type = Math.abs(v);
            int f = sq & 7, r = sq >> 3;
            switch (type) {
                case P: {
                    int nr = r + s;
                    if (on(f, nr) && b[nr * 8 + f] == 0) {
                        addPawnMove(out, sq, nr * 8 + f, false);
                        int startRank = w ? 1 : 6;
                        int nr2 = r + 2 * s;
                        if (r == startRank && b[nr2 * 8 + f] == 0) out.add(new Move(sq, nr2 * 8 + f, 0, false, false));
                    }
                    for (int df = -1; df <= 1; df += 2) {
                        int nf = f + df;
                        if (!on(nf, nr)) continue;
                        int t = nr * 8 + nf;
                        if (b[t] != 0 && (b[t] > 0) != w) addPawnMove(out, sq, t, false);
                        else if (t == ep) addPawnMove(out, sq, t, true);
                    }
                    break;
                }
                case N:
                    jumps(out, sq, KNIGHT);
                    break;
                case B:
                    slides(out, sq, DIAG);
                    break;
                case R:
                    slides(out, sq, ORTHO);
                    break;
                case Q:
                    slides(out, sq, DIAG);
                    slides(out, sq, ORTHO);
                    break;
                case K:
                    jumps(out, sq, KING);
                    castles(out, sq);
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    private void jumps(List<Move> out, int sq, int[][] dirs) {
        int f = sq & 7, r = sq >> 3;
        boolean w = whiteToMove;
        for (int[] d : dirs) {
            int nf = f + d[0], nr = r + d[1];
            if (!on(nf, nr)) continue;
            int t = nr * 8 + nf;
            if (b[t] == 0 || (b[t] > 0) != w) out.add(new Move(sq, t, 0, false, false));
        }
    }

    private void slides(List<Move> out, int sq, int[][] dirs) {
        int f = sq & 7, r = sq >> 3;
        boolean w = whiteToMove;
        for (int[] d : dirs) {
            int nf = f + d[0], nr = r + d[1];
            while (on(nf, nr)) {
                int t = nr * 8 + nf;
                if (b[t] == 0) {
                    out.add(new Move(sq, t, 0, false, false));
                } else {
                    if ((b[t] > 0) != w) out.add(new Move(sq, t, 0, false, false));
                    break;
                }
                nf += d[0];
                nr += d[1];
            }
        }
    }

    private void castles(List<Move> out, int sq) {
        boolean w = whiteToMove;
        int home = w ? 4 : 60;
        if (sq != home) return;
        int rook = w ? R : -R;
        boolean enemy = !w;
        if ((castling & (w ? WK : BK)) != 0 && b[home + 3] == rook && b[home + 1] == 0 && b[home + 2] == 0
                && !isAttacked(home, enemy) && !isAttacked(home + 1, enemy) && !isAttacked(home + 2, enemy)) {
            out.add(new Move(home, home + 2, 0, true, false));
        }
        if ((castling & (w ? WQ : BQ)) != 0 && b[home - 4] == rook && b[home - 1] == 0 && b[home - 2] == 0
                && b[home - 3] == 0 && !isAttacked(home, enemy) && !isAttacked(home - 1, enemy)
                && !isAttacked(home - 2, enemy)) {
            out.add(new Move(home, home - 2, 0, true, false));
        }
    }

    public List<Move> legalMoves() {
        List<Move> pseudo = pseudoLegal();
        List<Move> out = new ArrayList<>(pseudo.size());
        for (Move m : pseudo) {
            Position n = make(m);
            if (!n.inCheck(whiteToMove)) out.add(m);
        }
        return out;
    }

    /** Returns the position after the move; this position is not changed. */
    public Position make(Move m) {
        Position p = copy();
        if (m.isNull()) {
            p.ep = -1;
            if (!whiteToMove) p.fullmove++;
            p.whiteToMove = !whiteToMove;
            return p;
        }
        int piece = b[m.from];
        int s = piece > 0 ? 1 : -1;
        int type = Math.abs(piece);
        p.b[m.from] = 0;
        p.b[m.to] = m.promo != 0 ? s * m.promo : piece;
        if (m.enPassant) {
            p.b[(m.from & ~7) | (m.to & 7)] = 0;
        }
        if (m.castle) {
            if ((m.to & 7) == 6) {
                p.b[m.to + 1] = 0;
                p.b[m.to - 1] = s * R;
            } else {
                p.b[m.to - 2] = 0;
                p.b[m.to + 1] = s * R;
            }
        }
        if (type == K) p.castling &= s > 0 ? ~(WK | WQ) : ~(BK | BQ);
        p.castling &= ~cornerMask(m.from);
        p.castling &= ~cornerMask(m.to);
        p.ep = -1;
        if (type == P && Math.abs(m.to - m.from) == 16) p.ep = (m.from + m.to) / 2;
        if (!whiteToMove) p.fullmove++;
        p.whiteToMove = !whiteToMove;
        return p;
    }

    private static int cornerMask(int sq) {
        switch (sq) {
            case 0: return WQ;
            case 7: return WK;
            case 56: return BQ;
            case 63: return BK;
            default: return 0;
        }
    }

    public boolean isCheckmate() {
        return inCheck(whiteToMove) && legalMoves().isEmpty();
    }

    public boolean isStalemate() {
        return !inCheck(whiteToMove) && legalMoves().isEmpty();
    }

    /** Standard algebraic notation of a legal move in this position. */
    public String san(Move m) {
        if (m.isNull()) return "--";
        String s;
        int type = Math.abs(b[m.from]);
        if (m.castle) {
            s = (m.to & 7) == 6 ? "O-O" : "O-O-O";
        } else {
            boolean capture = b[m.to] != 0 || m.enPassant;
            StringBuilder sb = new StringBuilder();
            if (type == P) {
                if (capture) sb.append((char) ('a' + (m.from & 7))).append('x');
                sb.append(Move.sqName(m.to));
                if (m.promo != 0) sb.append('=').append(" NBRQ".charAt(m.promo - 1));
            } else {
                sb.append(" PNBRQK".charAt(type));
                boolean ambiguous = false, sameFile = false, sameRank = false;
                for (Move o : legalMoves()) {
                    if (o.to != m.to || o.from == m.from || Math.abs(b[o.from]) != type) continue;
                    ambiguous = true;
                    if ((o.from & 7) == (m.from & 7)) sameFile = true;
                    if ((o.from >> 3) == (m.from >> 3)) sameRank = true;
                }
                if (ambiguous) {
                    if (!sameFile) sb.append((char) ('a' + (m.from & 7)));
                    else if (!sameRank) sb.append((char) ('1' + (m.from >> 3)));
                    else sb.append(Move.sqName(m.from));
                }
                if (capture) sb.append('x');
                sb.append(Move.sqName(m.to));
            }
            s = sb.toString();
        }
        Position after = make(m);
        if (after.inCheck(after.whiteToMove)) s += after.legalMoves().isEmpty() ? "#" : "+";
        return s;
    }

    /** Finds a legal move by UCI string, e.g. "e2e4" or "e7e8q". */
    public Move findUci(String uci) {
        for (Move m : legalMoves()) if (m.uci().equals(uci)) return m;
        return null;
    }

    /** Finds a legal move by SAN (check marks optional). */
    public Move findSan(String san) {
        String want = san.replace("+", "").replace("#", "");
        for (Move m : legalMoves()) {
            if (san(m).replace("+", "").replace("#", "").equals(want)) return m;
        }
        return null;
    }
}
