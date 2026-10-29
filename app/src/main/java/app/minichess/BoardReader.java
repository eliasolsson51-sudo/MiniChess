package app.minichess;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the chess.com board from the accessibility tree. chess.com describes every square,
 * e.g. "Vit Springare på f3" / "g1, tomt" (Swedish) or "White Knight on f3" / "g1, empty".
 */
final class BoardReader {

    /** One square as it appears on screen. */
    static final class Square {
        int file;      // 0..7 = a..h
        int rank;      // 0..7 = 1..8
        char piece;    // KQRBNP / kqrbnp / 0
        final Rect bounds = new Rect();
    }

    /** Result: 8x8 grid in screen order ([col][row], row 0 = top) plus the position. */
    static final class Board {
        final Square[][] grid = new Square[8][8];
        final char[][] pos = new char[8][8]; // [file][rank]
        final Rect bounds = new Rect();

        /** Screen cell {col, row} of a board square, or null. */
        int[] cellOf(int file, int rank) {
            for (int c = 0; c < 8; c++) {
                for (int r = 0; r < 8; r++) {
                    Square s = grid[c][r];
                    if (s != null && s.file == file && s.rank == rank) return new int[]{c, r};
                }
            }
            return null;
        }
    }

    private static final Pattern SQUARE = Pattern.compile("(?:^|\\s)([a-h][1-8])(?=,|\\s*$)");

    private BoardReader() {
    }

    /** Returns the board, or null if it is not on screen. */
    static Board read(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<Square> squares = new ArrayList<>();
        collect(root, squares, 0);
        if (squares.size() != 64) return null;

        Board board = new Board();
        board.bounds.set(squares.get(0).bounds);
        for (Square s : squares) board.bounds.union(s.bounds);
        float cw = board.bounds.width() / 8f;
        float ch = board.bounds.height() / 8f;
        if (cw < 4 || ch < 4) return null;

        for (Square s : squares) {
            int c = (int) ((s.bounds.exactCenterX() - board.bounds.left) / cw);
            int r = (int) ((s.bounds.exactCenterY() - board.bounds.top) / ch);
            if (c < 0 || c > 7 || r < 0 || r > 7 || board.grid[c][r] != null) return null;
            board.grid[c][r] = s;
            board.pos[s.file][s.rank] = s.piece;
        }
        return board;
    }

    private static void collect(AccessibilityNodeInfo node, List<Square> out, int depth) {
        if (node == null || depth > 40 || out.size() > 64) return;
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            Square s = parse(desc.toString());
            if (s != null) {
                node.getBoundsInScreen(s.bounds);
                if (!s.bounds.isEmpty()) out.add(s);
                return;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collect(child, out, depth + 1);
        }
    }

    static Square parse(String desc) {
        Matcher m = SQUARE.matcher(desc.trim());
        if (!m.find()) return null;
        String name = m.group(1);
        Square s = new Square();
        s.file = name.charAt(0) - 'a';
        s.rank = name.charAt(1) - '1';

        String d = desc.toLowerCase(Locale.ROOT);
        boolean white = d.startsWith("vit") || d.startsWith("white");
        boolean black = d.startsWith("svart") || d.startsWith("black");
        if (!white && !black) {
            s.piece = 0; // "g1, tomt" / "g1, empty"
            return s;
        }
        char type;
        if (d.contains("kung") || d.contains("king")) type = 'K';
        else if (d.contains("dam") || d.contains("drottning") || d.contains("queen")) type = 'Q';
        else if (d.contains("torn") || d.contains("rook")) type = 'R';
        else if (d.contains("löpare") || d.contains("lopare") || d.contains("bishop")) type = 'B';
        else if (d.contains("springare") || d.contains("häst") || d.contains("knight")) type = 'N';
        else if (d.contains("bonde") || d.contains("pawn")) type = 'P';
        else return null; // unknown wording: don't guess
        s.piece = white ? type : Character.toLowerCase(type);
        return s;
    }
}
