package app.duochess.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Builds PGN text and Lichess analysis links from a list of SAN moves. */
public final class Pgn {
    private Pgn() {}

    public static String movetext(List<String> san) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < san.size(); i++) {
            if (i % 2 == 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(i / 2 + 1).append(". ");
            } else {
                sb.append(' ');
            }
            sb.append(san.get(i));
        }
        return sb.toString();
    }

    /** Result tag from the final position: 1-0, 0-1, 1/2-1/2 or *. */
    public static String result(List<String> san) {
        Position p = Position.initial();
        for (String s : san) {
            Move m = p.findSan(s);
            if (m == null) return "*";
            p = p.make(m);
        }
        if (p.isCheckmate()) return p.whiteToMove ? "0-1" : "1-0";
        if (p.isStalemate()) return "1/2-1/2";
        return "*";
    }

    public static String full(List<String> san, boolean playerWhite, long timeMs) {
        String date = new SimpleDateFormat("yyyy.MM.dd", Locale.US).format(new Date(timeMs));
        String res = result(san);
        String me = "Я";
        String opp = "Соперник";
        StringBuilder sb = new StringBuilder();
        sb.append("[Event \"Duolingo\"]\n");
        sb.append("[Site \"Duolingo\"]\n");
        sb.append("[Date \"").append(date).append("\"]\n");
        sb.append("[White \"").append(playerWhite ? me : opp).append("\"]\n");
        sb.append("[Black \"").append(playerWhite ? opp : me).append("\"]\n");
        sb.append("[Result \"").append(res).append("\"]\n\n");
        String mt = movetext(san);
        sb.append(mt);
        if (mt.length() > 0) sb.append(' ');
        sb.append(res).append('\n');
        return sb.toString();
    }

    public static String lichessUrl(List<String> san, boolean playerWhite) {
        String mt = movetext(san);
        String enc;
        try {
            enc = URLEncoder.encode(mt, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            enc = mt;
        }
        return "https://lichess.org/analysis/pgn/" + enc + "?color=" + (playerWhite ? "white" : "black");
    }
}
