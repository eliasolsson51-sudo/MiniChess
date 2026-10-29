package app.minichess;

import java.util.ArrayList;
import java.util.List;

/**
 * Legal-move generator for one position. Squares are {file, rank} with 0..7 (a..h, 1..8).
 * Pieces: uppercase = white, lowercase = black, 0 = empty. KQRBNP.
 * Castling rights and en passant are not known from the screen: castling is offered when
 * king and rook stand on their home squares; en passant is not offered (0 = free movement).
 */
final class ChessLogic {

    private ChessLogic() {
    }

    private static final int[][] KNIGHT = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
    private static final int[][] KING = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final int[][] ROOK_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] BISHOP_DIRS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    static boolean isWhite(char p) {
        return p != 0 && Character.isUpperCase(p);
    }

    private static boolean on(int f, int r) {
        return f >= 0 && f < 8 && r >= 0 && r < 8;
    }

    /** Legal destination squares for the piece on (f, r). Empty list if there is no piece. */
    static List<int[]> legalMoves(char[][] b, int f, int r) {
        List<int[]> out = new ArrayList<>();
        char p = b[f][r];
        if (p == 0) return out;
        boolean white = isWhite(p);
        for (int[] to : pseudoMoves(b, f, r)) {
            char[][] copy = copy(b);
            copy[to[0]][to[1]] = p;
            copy[f][r] = 0;
            // Castling also moves the rook (matters for the check test).
            if (Character.toUpperCase(p) == 'K' && Math.abs(to[0] - f) == 2) {
                int rookFrom = to[0] > f ? 7 : 0;
                int rookTo = to[0] > f ? 5 : 3;
                copy[rookTo][r] = copy[rookFrom][r];
                copy[rookFrom][r] = 0;
            }
            int[] k = findKing(copy, white);
            if (k == null || !attacked(copy, k[0], k[1], !white)) {
                out.add(to);
            }
        }
        return out;
    }

    private static List<int[]> pseudoMoves(char[][] b, int f, int r) {
        List<int[]> out = new ArrayList<>();
        char p = b[f][r];
        boolean white = isWhite(p);
        switch (Character.toUpperCase(p)) {
            case 'N':
                for (int[] d : KNIGHT) step(b, f + d[0], r + d[1], white, out);
                break;
            case 'K':
                for (int[] d : KING) step(b, f + d[0], r + d[1], white, out);
                addCastling(b, f, r, white, out);
                break;
            case 'R':
                slide(b, f, r, ROOK_DIRS, white, out);
                break;
            case 'B':
                slide(b, f, r, BISHOP_DIRS, white, out);
                break;
            case 'Q':
                slide(b, f, r, ROOK_DIRS, white, out);
                slide(b, f, r, BISHOP_DIRS, white, out);
                break;
            case 'P': {
                int dir = white ? 1 : -1;
                int start = white ? 1 : 6;
                if (on(f, r + dir) && b[f][r + dir] == 0) {
                    out.add(new int[]{f, r + dir});
                    if (r == start && b[f][r + 2 * dir] == 0) {
                        out.add(new int[]{f, r + 2 * dir});
                    }
                }
                for (int df = -1; df <= 1; df += 2) {
                    int tf = f + df, tr = r + dir;
                    if (on(tf, tr) && b[tf][tr] != 0 && isWhite(b[tf][tr]) != white) {
                        out.add(new int[]{tf, tr});
                    }
                }
                break;
            }
            default:
                break;
        }
        return out;
    }

    private static void step(char[][] b, int f, int r, boolean white, List<int[]> out) {
        if (!on(f, r)) return;
        if (b[f][r] == 0 || isWhite(b[f][r]) != white) out.add(new int[]{f, r});
    }

    private static void slide(char[][] b, int f, int r, int[][] dirs, boolean white, List<int[]> out) {
        for (int[] d : dirs) {
            int tf = f + d[0], tr = r + d[1];
            while (on(tf, tr)) {
                if (b[tf][tr] == 0) {
                    out.add(new int[]{tf, tr});
                } else {
                    if (isWhite(b[tf][tr]) != white) out.add(new int[]{tf, tr});
                    break;
                }
                tf += d[0];
                tr += d[1];
            }
        }
    }

    private static void addCastling(char[][] b, int f, int r, boolean white, List<int[]> out) {
        int home = white ? 0 : 7;
        char rook = white ? 'R' : 'r';
        if (f != 4 || r != home || attacked(b, 4, home, !white)) return;
        // King side: f, g empty and not attacked.
        if (b[7][home] == rook && b[5][home] == 0 && b[6][home] == 0
                && !attacked(b, 5, home, !white)) {
            out.add(new int[]{6, home});
        }
        // Queen side: b, c, d empty; c, d not attacked.
        if (b[0][home] == rook && b[1][home] == 0 && b[2][home] == 0 && b[3][home] == 0
                && !attacked(b, 3, home, !white)) {
            out.add(new int[]{2, home});
        }
    }

    /** Is square (f, r) attacked by the given side? */
    static boolean attacked(char[][] b, int f, int r, boolean byWhite) {
        // Pawns.
        int pr = r - (byWhite ? 1 : -1);
        char pawn = byWhite ? 'P' : 'p';
        if (on(f - 1, pr) && b[f - 1][pr] == pawn) return true;
        if (on(f + 1, pr) && b[f + 1][pr] == pawn) return true;
        // Knights and king.
        char knight = byWhite ? 'N' : 'n';
        for (int[] d : KNIGHT) {
            if (on(f + d[0], r + d[1]) && b[f + d[0]][r + d[1]] == knight) return true;
        }
        char king = byWhite ? 'K' : 'k';
        for (int[] d : KING) {
            if (on(f + d[0], r + d[1]) && b[f + d[0]][r + d[1]] == king) return true;
        }
        // Sliders.
        if (rayHits(b, f, r, ROOK_DIRS, byWhite ? 'R' : 'r', byWhite ? 'Q' : 'q')) return true;
        return rayHits(b, f, r, BISHOP_DIRS, byWhite ? 'B' : 'b', byWhite ? 'Q' : 'q');
    }

    private static boolean rayHits(char[][] b, int f, int r, int[][] dirs, char a, char q) {
        for (int[] d : dirs) {
            int tf = f + d[0], tr = r + d[1];
            while (on(tf, tr)) {
                char p = b[tf][tr];
                if (p != 0) {
                    if (p == a || p == q) return true;
                    break;
                }
                tf += d[0];
                tr += d[1];
            }
        }
        return false;
    }

    private static int[] findKing(char[][] b, boolean white) {
        char k = white ? 'K' : 'k';
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                if (b[f][r] == k) return new int[]{f, r};
            }
        }
        return null;
    }

    private static char[][] copy(char[][] b) {
        char[][] c = new char[8][];
        for (int i = 0; i < 8; i++) c[i] = b[i].clone();
        return c;
    }
}
