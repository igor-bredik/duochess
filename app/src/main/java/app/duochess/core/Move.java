package app.duochess.core;

/** A chess move. Squares are 0..63, a1 = 0, h1 = 7, a8 = 56. */
public final class Move {
    public final int from;
    public final int to;
    /** Promotion piece type (Position.N..Position.Q) or 0. */
    public final int promo;
    public final boolean castle;
    public final boolean enPassant;

    public Move(int from, int to, int promo, boolean castle, boolean enPassant) {
        this.from = from;
        this.to = to;
        this.promo = promo;
        this.castle = castle;
        this.enPassant = enPassant;
    }

    /** "Pass" used only when the side to move had to be corrected. */
    public static final Move NULL = new Move(-1, -1, 0, false, false);

    public boolean isNull() {
        return from < 0;
    }

    public Move withPromo(int p) {
        return new Move(from, to, p, castle, enPassant);
    }

    public static String sqName(int sq) {
        return "" + (char) ('a' + (sq & 7)) + (char) ('1' + (sq >> 3));
    }

    public String uci() {
        if (isNull()) return "0000";
        String s = sqName(from) + sqName(to);
        if (promo != 0) s += "nbrq".charAt(promo - Position.N);
        return s;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Move)) return false;
        Move m = (Move) o;
        return m.from == from && m.to == to && m.promo == promo;
    }

    @Override
    public int hashCode() {
        return from * 64 * 8 + to * 8 + promo;
    }

    @Override
    public String toString() {
        return uci();
    }
}
