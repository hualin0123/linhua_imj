package com.linhua.fit;

/**
 * 复刻 Python _240830u_fitting_function.py 的拟合函数，包装为 {@link CurveFitter.Model}。
 * 注意：gaussian / sech2 均为峰值归一化（t=0 处=1），与 Python 定义一致。
 */
public final class Models {

    private Models() { }

    /** gaussian(t, fwhm) = exp(-2.77 * (t / (fwhm/0.707))^2) */
    public static CurveFitter.Model gaussian() {
        return (t, p) -> Math.exp(-2.77 * Math.pow(t / (p[0] / 0.707), 2));
    }

    /** sech2(t, fwhm) = (1 / cosh(1.763 * t / (fwhm/0.648)))^2 */
    public static CurveFitter.Model sech2() {
        return (t, p) -> {
            double a = 1.763 * t / (p[0] / 0.648);
            double c = Math.cosh(a);
            return 1.0 / (c * c);
        };
    }

    /** linear_small_G(n, g, b) = n*g + b（先对 y 取 log 再拟合） */
    public static CurveFitter.Model smallG() {
        return (n, p) -> n * p[0] + p[1];
    }

    /** Pabs_lnG(P_abs, lnG_max, P_tf) = lnG_max * (1 - exp(-P_abs / P_tf)) */
    public static CurveFitter.Model pabsLnG() {
        return (P, p) -> p[0] * (1.0 - Math.exp(-P / p[1]));
    }
}
