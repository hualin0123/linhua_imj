package com.linhua.gaus;

import com.linhua.plot.Plot;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Gaus 模块共用的绘图 / 求解辅助方法（与 {@code plot.Plot} 协作）。
 * 数学内核见 {@link GausCore}。
 *
 * @author Hua Lin
 * @since 0.1.0
 */
public final class GausUtil {

    private GausUtil() { }

    /** 把含 NaN 的扫描结果按连续有限段拆成多条线加入 Plot（仅首段带图例标签）。 */
    public static void addSplitLine(Plot p, double[] x, double[] y, Color c, String label) {
        int start = -1;
        boolean first = true;
        for (int i = 0; i <= y.length; i++) {
            boolean bad = (i == y.length) || !Double.isFinite(y[i]);
            if (bad) {
                if (start >= 0) {
                    int nn = i - start;
                    double[] sx = new double[nn];
                    double[] sy = new double[nn];
                    System.arraycopy(x, start, sx, 0, nn);
                    System.arraycopy(y, start, sy, 0, nn);
                    p.addLine(sx, sy, c, first ? label : null);
                    first = false;
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
    }

    /** 在一维扫描 y 中找出所有「有限（即稳定）」的连续区间，返回字符串。 */
    public static String stableIntervals(double[] x, double[] y) {
        List<String> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= y.length; i++) {
            boolean ok = (i < y.length) && Double.isFinite(y[i]);
            if (!ok) {
                if (start >= 0) {
                    out.add("[" + fmt(x[start]) + ", " + fmt(x[i - 1]) + "]");
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        return out.isEmpty() ? "无" : String.join(", ", out);
    }

    /** 解析逗号分隔的数值列表。 */
    public static double[] parseList(String s) {
        if (s == null) {
            return new double[0];
        }
        String[] parts = s.split("[,\\s]+");
        List<Double> v = new ArrayList<>();
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                v.add(Double.parseDouble(p));
            } catch (NumberFormatException ignored) {
                // 跳过无法解析的项
            }
        }
        double[] r = new double[v.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = v.get(i);
        }
        return r;
    }

    /** 二维牛顿法（数值 Jacobian，最大 100 步，容差 1e-9）。无解返回 null。 */
    public static double[] newton2D(double z1, double z2,
                                    BiFunction<Double, Double, double[]> f) {
        double a = z1, b = z2;
        for (int it = 0; it < 100; it++) {
            double[] fa = f.apply(a, b);
            if (Math.hypot(fa[0], fa[1]) < 1e-9) {
                return new double[]{a, b};
            }
            double h = 1e-6;
            double[] fx = f.apply(a + h, b);
            double[] fy = f.apply(a, b + h);
            double j11 = (fx[0] - fa[0]) / h, j12 = (fy[0] - fa[0]) / h;
            double j21 = (fx[1] - fa[1]) / h, j22 = (fy[1] - fa[1]) / h;
            double det = j11 * j22 - j12 * j21;
            if (Math.abs(det) < 1e-12) {
                return null;
            }
            double da = (j22 * fa[0] - j12 * fa[1]) / det;
            double db = (-j21 * fa[0] + j11 * fa[1]) / det;
            a -= da;
            b -= db;
            if (Math.abs(da) < 1e-9 && Math.abs(db) < 1e-9) {
                return new double[]{a, b};
            }
        }
        double[] fa = f.apply(a, b);
        return Math.hypot(fa[0], fa[1]) < 1e-6 ? new double[]{a, b} : null;
    }

    /** 数值格式化（简短显示，兼容 μ/∞ 等）。 */
    public static String fmt(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return "∞";
        }
        if (Math.abs(v) >= 1e5 || (v != 0 && Math.abs(v) < 1e-3)) {
            return String.format("%.3e", v);
        }
        return String.format("%.4g", v);
    }

}
