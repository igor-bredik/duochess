package app.duochess.core;

import java.util.Locale;

/** Turns engine results into short Russian text for the overlay. */
public final class Advice {
    private Advice() {}

    /** SAN with Russian piece letters: К конь, С слон, Л ладья, Ф ферзь, Кр король. */
    public static String ru(String san) {
        if (san == null || san.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        char c0 = san.charAt(0);
        int start = 0;
        switch (c0) {
            case 'N': sb.append('К'); start = 1; break;
            case 'B': sb.append('С'); start = 1; break;
            case 'R': sb.append('Л'); start = 1; break;
            case 'Q': sb.append('Ф'); start = 1; break;
            case 'K': sb.append("Кр"); start = 1; break;
            default: break;
        }
        String rest = san.substring(start).replace("=Q", "=Ф").replace("=R", "=Л").replace("=B", "=С").replace("=N", "=К");
        return sb.append(rest).toString();
    }

    public static String moveLabel(Position before, Move m) {
        String num = before.fullmove + (before.whiteToMove ? ". " : "… ");
        return num + ru(before.san(m));
    }

    public static String evalText(Engine.Eval e) {
        if (e.mate != 0) {
            return "мат в " + Math.abs(e.mate) + (e.mate > 0 ? " за белых" : " за чёрных");
        }
        double v = e.cp / 100.0;
        if (Math.abs(v) < 0.05) return "0.0 (равно)";
        return String.format(Locale.US, "%+.1f", v) + (v > 0 ? " (белые лучше)" : " (чёрные лучше)");
    }

    /** Lichess-style judgement of a move from the drop in winning chances. */
    public static String judgement(Position before, Move played, Engine.Eval eBefore, Engine.Eval eAfter) {
        if (eBefore.bestUci != null && eBefore.bestUci.equals(played.uci())) return "лучший ход";
        double sign = before.whiteToMove ? 1 : -1;
        double loss = sign * (eBefore.whiteChances() - eAfter.whiteChances());
        if (loss >= 0.3) return "зевок";
        if (loss >= 0.2) return "ошибка";
        if (loss >= 0.1) return "неточность";
        return "хороший ход";
    }

    public static boolean isBad(String judgement) {
        return judgement.equals("зевок") || judgement.equals("ошибка") || judgement.equals("неточность");
    }

    public static String bestSan(Position p, Engine.Eval e) {
        if (e == null || e.bestUci == null) return null;
        Move m = p.findUci(e.bestUci);
        return m == null ? null : ru(p.san(m));
    }

    public static final int NEUTRAL = 0, GOOD = 1, INACCURACY = 2, MISTAKE = 3, BLUNDER = 4;

    /** What the overlay shows for one position. */
    public static final class View {
        public String l1 = "";
        public String l2 = "";
        public String l3 = "";
        public int verdict = NEUTRAL;
        /** Engine's best move in the shown position (green arrow), or null. */
        public Move best;
        /** Move played next in the game when it is not the best one (red arrow), or null. */
        public Move played;
    }

    public static int verdictOf(String judgement) {
        switch (judgement) {
            case "зевок": return BLUNDER;
            case "ошибка": return MISTAKE;
            case "неточность": return INACCURACY;
            default: return GOOD;
        }
    }

    private static String header(Position p) {
        if (p.fullmove == 1 && p.whiteToMove) return "Начало, ход белых";
        return p.fullmove + (p.whiteToMove ? ". Ход белых" : "… Ход чёрных");
    }

    /**
     * Describes the shown position cur (before its move is made).
     *
     * @param next  move played next in the game, if already known
     * @param eNext evaluation of the position after next
     * @param prev  previous position and last the move that led to cur (for when next is unknown)
     */
    public static View describe(Position cur, Engine.Eval eCur, Move next, Engine.Eval eNext,
                                Position prev, Move last, Engine.Eval ePrev) {
        View v = new View();
        if (cur.isCheckmate()) {
            v.l1 = "Мат. " + (cur.whiteToMove ? "Чёрные" : "Белые") + " победили";
            return v;
        }
        if (cur.isStalemate()) {
            v.l1 = "Пат, ничья";
            return v;
        }
        String head = header(cur);
        if (eCur == null) {
            v.l1 = head;
            v.l3 = "Считаю…";
            return v;
        }
        Move best = eCur.bestUci != null ? cur.findUci(eCur.bestUci) : null;
        v.best = best;
        String bestTxt = best != null ? ru(cur.san(best)) : "?";
        if (next != null && !next.isNull()) {
            String playedTxt = ru(cur.san(next));
            if (eNext == null) {
                v.l1 = head + ". В партии " + playedTxt + " — считаю…";
                v.l2 = "Лучший ход " + bestTxt + " (зелёная стрелка)";
            } else {
                String j = judgement(cur, next, eCur, eNext);
                v.verdict = verdictOf(j);
                v.l1 = head + ". В партии " + playedTxt + " — " + j;
                if (best != null && best.equals(next)) {
                    v.l2 = "Это и есть лучший ход (зелёная стрелка)";
                } else {
                    v.played = next;
                    v.l2 = isBad(j) ? "Лучше " + bestTxt + " (зелёная стрелка)"
                            : "Тоже нормально. Лучший " + bestTxt + " (зелёная стрелка)";
                }
            }
            v.l3 = "Оценка сейчас " + evalText(eCur);
            return v;
        }
        v.l1 = head + ". Лучший ход " + bestTxt;
        v.l3 = "Оценка " + evalText(eCur);
        if (prev != null && last != null && !last.isNull() && ePrev != null) {
            String j = judgement(prev, last, ePrev, eCur);
            v.l2 = "Прошлый ход " + moveLabel(prev, last) + " — " + j;
        } else if (prev == null) {
            v.l2 = "Совет: пролистайте партию до конца и вернитесь в начало, тогда оценка каждого хода будет видна до него";
        }
        return v;
    }

    public static String shortEval(Engine.Eval e) {
        if (e.mate != 0) return (e.mate > 0 ? "+" : "−") + "мат в " + Math.abs(e.mate);
        return String.format(Locale.US, "%+.1f", e.cp / 100.0);
    }
}
