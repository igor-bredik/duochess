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

    /**
     * Text for the overlay: line 1 the last move and its judgement, line 2 what was better,
     * line 3 the evaluation and best move now. Null evals mean "still thinking".
     */
    public static String[] describe(Position prev, Move last, Position cur, Engine.Eval ePrev, Engine.Eval eCur) {
        String l1, l2 = "", l3;
        if (last == null || prev == null) {
            l1 = "Начальная позиция";
        } else if (last.isNull()) {
            l1 = "Ход " + cur.fullmove;
        } else {
            String label = moveLabel(prev, last);
            if (ePrev == null || eCur == null) {
                l1 = label + " — считаю…";
            } else {
                String j = judgement(prev, last, ePrev, eCur);
                l1 = label + " — " + j;
                if (isBad(j)) {
                    String b = bestSan(prev, ePrev);
                    if (b != null) l2 = "Лучше было " + b + " (" + shortEval(ePrev) + ")";
                }
            }
        }
        if (cur.isCheckmate()) {
            l3 = "Мат. " + (cur.whiteToMove ? "Чёрные" : "Белые") + " победили";
        } else if (cur.isStalemate()) {
            l3 = "Пат, ничья";
        } else if (eCur == null) {
            l3 = "Считаю…";
        } else {
            String b = bestSan(cur, eCur);
            l3 = "Оценка " + evalText(eCur) + (b != null ? ". Ход " + (cur.whiteToMove ? "белых" : "чёрных") + ", лучший: " + b : "");
        }
        return new String[] {l1, l2, l3};
    }

    public static String shortEval(Engine.Eval e) {
        if (e.mate != 0) return (e.mate > 0 ? "+" : "−") + "мат в " + Math.abs(e.mate);
        return String.format(Locale.US, "%+.1f", e.cp / 100.0);
    }
}
