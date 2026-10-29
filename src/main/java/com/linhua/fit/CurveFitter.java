package com.linhua.fit;

/**
 * 自包含的 Levenberg–Marquardt 非线性最小二乘拟合器（无第三方依赖）。
 * 类比 Python 的 scipy.optimize.curve_fit，但用数值雅可比、对任意参数维数通用。
 *
 * <p>拟合模型只需实现 {@link Model#value(double, double[])}。初值 {@code p0} 应尽可能接近真值。
 */
public final class CurveFitter {

    /** 待拟合模型：x 处、给定参数 p 时的函数值。 */
    public interface Model {
        double value(double x, double[] p);
    }

    private CurveFitter() { }

    /**
     * 拟合 y ≈ model(x, p)。
     *
     * @param x   自变量
     * @param y   观测值
     * @param model 模型
     * @param p0  参数初值（不会被修改，返回新的拟合结果）
     * @return 拟合后的参数（长度同 p0）
     */
    public static double[] fit(double[] x, double[] y, Model model, double[] p0) {
        int m = p0.length;
        double[] p = new double[m];
        System.arraycopy(p0, 0, p, 0, m);
        int n = x.length;
        if (n == 0 || m == 0) {
            return p;
        }

        double lambda = 1e-3;
        double ssrPrev = ssr(x, y, model, p);
        double[] h = new double[m];
        for (int i = 0; i < m; i++) {
            h[i] = Math.max(Math.abs(p[i]) * 1e-6, 1e-9);
        }

        for (int it = 0; it < 400; it++) {
            double[][] JtJ = new double[m][m];
            double[] Jtr = new double[m];
            for (int i = 0; i < n; i++) {
                double f = model.value(x[i], p);
                double[] deriv = new double[m];
                for (int a = 0; a < m; a++) {
                    double pa = p[a];
                    p[a] = pa + h[a];
                    double fp = model.value(x[i], p);
                    p[a] = pa;
                    deriv[a] = (fp - f) / h[a];
                }
                double ri = y[i] - f;
                for (int a = 0; a < m; a++) {
                    Jtr[a] += deriv[a] * ri;
                    for (int b = 0; b < m; b++) {
                        JtJ[a][b] += deriv[a] * deriv[b];
                    }
                }
            }
            for (int a = 0; a < m; a++) {
                JtJ[a][a] += lambda;
            }
            double[] dp = solve(JtJ, Jtr);
            if (dp == null) {
                break;
            }
            double[] pNew = new double[m];
            for (int a = 0; a < m; a++) {
                pNew[a] = p[a] + dp[a];
            }
            double ssrNew = ssr(x, y, model, pNew);
            if (ssrNew < ssrPrev) {
                System.arraycopy(pNew, 0, p, 0, m);
                ssrPrev = ssrNew;
                lambda = Math.max(lambda / 10.0, 1e-12);
            } else {
                lambda = Math.min(lambda * 10.0, 1e12);
            }
            double maxStep = 0;
            for (int a = 0; a < m; a++) {
                maxStep = Math.max(maxStep, Math.abs(dp[a]));
            }
            if (maxStep < 1e-9) {
                break;
            }
        }
        return p;
    }

    private static double ssr(double[] x, double[] y, Model model, double[] p) {
        double s = 0;
        for (int i = 0; i < x.length; i++) {
            double r = y[i] - model.value(x[i], p);
            s += r * r;
        }
        return s;
    }

    /** 解 n×n 线性方程组 A·x = b（高斯消元 + 部分主元）。 */
    private static double[] solve(double[][] A, double[] b) {
        int n = A.length;
        double[][] M = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                M[i][j] = A[i][j];
            }
            M[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) {
                    piv = r;
                }
            }
            double[] tmp = M[piv];
            M[piv] = M[col];
            M[col] = tmp;
            double d = M[col][col];
            if (Math.abs(d) < 1e-30) {
                d = 1e-30;
            }
            for (int j = col; j <= n; j++) {
                M[col][j] /= d;
            }
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double f = M[r][col];
                if (f == 0) {
                    continue;
                }
                for (int j = col; j <= n; j++) {
                    M[r][j] -= f * M[col][j];
                }
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = M[i][n];
        }
        return x;
    }
}
