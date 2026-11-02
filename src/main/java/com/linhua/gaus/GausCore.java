package com.linhua.gaus;

import static java.lang.Math.PI;

/**
 * 高斯光束 / ABCD 矩阵光学计算的纯 Java 内核（不依赖任何第三方库）。
 *
 * <p>单位约定（与 Python 原脚本一致）：所有几何长度、焦距、曲率半径均以 <b>mm</b> 计；
 * 波长默认 2060 nm = 0.00206 mm。波长在 GUI 中以 nm 输入后乘以 1e-6 转为 mm。</p>
 *
 * <p>覆盖的物理：
 * <ul>
 *   <li>基本 ABCD 元件矩阵：自由空间传播、球面反射镜、薄透镜；</li>
 *   <li>环形矩阵 (A+D)/2 稳定性判据与基模腰斑 {@code w0 = sqrt(2λ|B|/(π√(4-(A+D)²)))}；</li>
 *   <li>复 q 参数传播 {@code q2=(A·q1+B)/(C·q1+D)} 及其腰斑/曲率反解；</li>
 *   <li>模匹配、准直、自由空间传播、透镜变换等闭式公式。</li>
 * </ul>
 *
 * @author Hua Lin
 * @since 0.1.0
 */
public final class GausCore {

    /** 默认波长 2060 nm，单位为 mm。 */
    public static final double DEFAULT_LAMBDA_MM = 2060e-6;

    private GausCore() { }

    // ------------------------------------------------------------------
    // 复数
    // ------------------------------------------------------------------

    /** 轻量复数。 */
    public static final class Cx {
        public final double re, im;

        public Cx(double re, double im) {
            this.re = re;
            this.im = im;
        }

        public static Cx of(double re) {
            return new Cx(re, 0);
        }

        public static Cx of(double re, double im) {
            return new Cx(re, im);
        }

        public Cx add(Cx o) {
            return new Cx(re + o.re, im + o.im);
        }

        public Cx sub(Cx o) {
            return new Cx(re - o.re, im - o.im);
        }

        public Cx mul(Cx o) {
            return new Cx(re * o.re - im * o.im, re * o.im + im * o.re);
        }

        public Cx div(Cx o) {
            double d = o.re * o.re + o.im * o.im;
            return new Cx((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d);
        }
    }

    // ------------------------------------------------------------------
    // 2x2 实矩阵（ABCD）
    // ------------------------------------------------------------------

    /** ABCD 矩阵（实数）。 */
    public static final class Mat {
        public double a, b, c, d;

        public Mat(double a, double b, double c, double d) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
        }

        /** 自由空间传播 L (mm)。 */
        public static Mat propagation(double L) {
            return new Mat(1, L, 0, 1);
        }

        /** 球面反射镜（曲率半径 R，mm）。R>0 为凹面朝向光束。 */
        public static Mat mirror(double R) {
            return new Mat(1, 0, -2.0 / R, 1);
        }

        /** 薄透镜（焦距 f，mm）。 */
        public static Mat lens(double f) {
            return new Mat(1, 0, -1.0 / f, 1);
        }

        public Mat mul(Mat o) {
            return new Mat(
                    a * o.a + b * o.c, a * o.b + b * o.d,
                    c * o.a + d * o.c, c * o.b + d * o.d);
        }

        /** 整数次幂（快速幂）。 */
        public Mat pow(int n) {
            Mat r = new Mat(1, 0, 0, 1);
            Mat base = this;
            int e = n;
            while (e > 0) {
                if ((e & 1) == 1) {
                    r = r.mul(base);
                }
                base = base.mul(base);
                e >>= 1;
            }
            return r;
        }

        /** 迹 (A+D)。 */
        public double trace() {
            return a + d;
        }

        /** 稳定性参数 (A+D)/2。|·|≤1 即稳定。 */
        public double stability() {
            return (a + d) / 2.0;
        }
    }

    // ------------------------------------------------------------------
    // 由 ABCD 矩阵反解基模腰斑
    // ------------------------------------------------------------------

    /**
     * 由（往返）ABCD 矩阵解基模腰斑半径 w0 (mm)。不稳定（|A+D|>2，根号内为负）返回 NaN。
     *
     * <pre>w0 = sqrt( 2·λ·|B| / (π·sqrt(4-(A+D)²)) )</pre>
     */
    public static double waistFromAbcd(Mat m, double lambdaMm) {
        double tr = m.trace();
        double disc = 4 - tr * tr;
        if (disc <= 0) {
            return Double.NaN;
        }
        return Math.sqrt(2.0 * lambdaMm * Math.abs(m.b) / (PI * Math.sqrt(disc)));
    }

    // ------------------------------------------------------------------
    // q 参数传播
    // ------------------------------------------------------------------

    /**
     * 复 q 参数经 ABCD 矩阵传播：q2 = (A·q1 + B)/(C·q1 + D)，A/B/C/D 为实数。
     */
    public static Cx propagateQ(Mat m, Cx q1) {
        Cx num = q1.mul(Cx.of(m.a)).add(Cx.of(m.b));
        Cx den = q1.mul(Cx.of(m.c)).add(Cx.of(m.d));
        return num.div(den);
    }

    /**
     * 由腰斑 w0 (mm) 与波前曲率半径 R (mm) 构造 q 参数（复数）。
     * R 非有限（±∞）表示平面波前（束腰处）。
     *
     * <pre>1/q = 1/R - i·λ/(π·w0²)</pre>
     */
    public static Cx qFromWaist(double w0, double R, double lambdaMm) {
        double invR = Double.isFinite(R) ? 1.0 / R : 0.0;
        Cx inv = Cx.of(invR).sub(Cx.of(0, lambdaMm / (PI * w0 * w0)));
        return Cx.of(1, 0).div(inv);
    }

    /** 由 q 参数解腰斑半径 w (mm)；非物理（根号内为负）返回 NaN。 */
    public static double waistFromQ(Cx q, double lambdaMm) {
        Cx inv = Cx.of(1, 0).div(q);
        double im = inv.im;            // = -λ/(π·w²)
        double w2 = -lambdaMm / (PI * im);
        if (w2 <= 0) {
            return Double.NaN;
        }
        return Math.sqrt(w2);
    }

    /** 由 q 参数解波前曲率半径倒数 1/R（实部）。 */
    public static double curvatureInv(Cx q) {
        return Cx.of(1, 0).div(q).re;
    }

    // ------------------------------------------------------------------
    // 闭式公式
    // ------------------------------------------------------------------

    /** 瑞利范围 f = π·w0²/λ (mm)。 */
    public static double rayleigh(double w0, double lambdaMm) {
        return PI * w0 * w0 / lambdaMm;
    }

    /** 准直后腰斑 w1 = w0·F/f，其中 f = π·w0²/λ。 */
    public static double collimateW(double w0, double F, double lambdaMm) {
        return w0 * F / rayleigh(w0, lambdaMm);
    }

    /** 自由空间传播 w(L) = sqrt( λ/π · (L²+f²)/f )，f = π·w0²/λ。 */
    public static double propagateW(double w0, double L, double lambdaMm) {
        double f = rayleigh(w0, lambdaMm);
        return Math.sqrt(lambdaMm / PI * (L * L + f * f) / f);
    }

    /**
     * 透镜变换：腰斑 w0 经焦距 F 的透镜，在距透镜 L 处（L 为可变距离）的：
     * <pre>
     *   L1 = F + ((L-F)·F²)/((L-F)²+f²)
     *   w1 = w0·F / sqrt((L-F)²+f²)
     * </pre>
     * 返回 {L1, w1}。
     */
    public static double[] lensTransform(double w0, double F, double L, double lambdaMm) {
        double f = rayleigh(w0, lambdaMm);
        double d = L - F;
        double denom = Math.sqrt(d * d + f * f);
        return new double[]{
                F + d * F * F / (d * d + f * f),
                w0 * F / denom
        };
    }

    /**
     * 模匹配四解：给定两束腰斑 w1、w2 与透镜焦距 F，计算透镜两侧到各自束腰的距离。
     * <pre>
     *   f  = π·w1·w2/λ
     *   s  = sqrt(F²-f²)
     *   L1 = F + (w1/w2)·s ;  L2 = F + (w2/w1)·s
     *   La = F - (w1/w2)·s ;  Lb = F - (w2/w1)·s
     * </pre>
     * 返回 {L1, L2, La, Lb}。若 F<f（无实解）返回全 NaN。
     */
    public static double[] modeMatching(double w1, double w2, double F, double lambdaMm) {
        double f = PI * w1 * w2 / lambdaMm;
        double disc = F * F - f * f;
        if (disc < 0) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        }
        double s = Math.sqrt(disc);
        return new double[]{
                F + (w1 / w2) * s,
                F + (w2 / w1) * s,
                F - (w1 / w2) * s,
                F - (w2 / w1) * s
        };
    }
}
