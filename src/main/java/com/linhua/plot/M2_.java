package com.linhua.plot;

import com.linhua.io.XlsxReader;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * M²（光束质量因子）绘图插件。
 *
 * <p>读取 CSV（列名与 Python 参考程序一致）：
 * <pre>
 * position (mm), x beam diameter (mm), y beam diameter (mm)
 * ...
 * </pre>
 * 将直径除以 2 得到光束半径，分别对 X / Y 拟合双曲线
 * {@code w(z) = w0 * sqrt(1 + B^2 * (z - z0)^2)}，其中
 * {@code B = M^2 * lambda / (pi * w0^2)}，故 {@code M^2 = B * pi * w0^2 / lambda}。
 * 拟合采用自带的最速下降/Levenberg–Marquardt 实现，<b>不依赖</b>任何第三方库。</p>
 *
 * <p>绘制内容：X / Y 的拟合曲线（实线）+ 原始数据点（空心标记），
 * 图例标注各自的 M² 值，并附注波长 λ。</p>
 *
 * @author Hua Lin
 * @since 0.1.0
 */
public class M2_ implements PlugIn {

    private static final String[] POS_ALIASES = {"position (mm)"};
    private static final String[] XD_ALIASES = {"x beam diameter (mm)"};
    private static final String[] YD_ALIASES = {"y beam diameter (mm)"};

    @Override
    public void run(String arg) {
        OpenDialog od = new OpenDialog("选择 M2 数据 CSV", null);
        String path = od.getPath();
        if (path == null) {
            return; // 用户取消
        }

        GenericDialog gd = new GenericDialog("M2 参数");
        gd.addNumericField("Wavelength (nm)", 2060, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        double lambdaNm = gd.getNextNumber();

        try {
            Data d = parseFile(path);
            if (d.n == 0) {
                IJ.error("未从文件中解析到任何有效数据行。");
                return;
            }

            double lamMm = lambdaNm * 1e-6; // nm -> mm

            Fit fx = fit(d.z, d.xr, lamMm);
            Fit fy = fit(d.z, d.yr, lamMm);

            double zmin = min(d.z), zmax = max(d.z);
            int N = 200;
            double[] zf = new double[N];
            double[] wfX = new double[N];
            double[] wfY = new double[N];
            for (int i = 0; i < N; i++) {
                double z = zmin + (zmax - zmin) * i / (N - 1);
                zf[i] = z;
                wfX[i] = model(z, fx);
                wfY[i] = model(z, fy);
            }

            Plot plot = new Plot("M2 - Beam Radius vs Position", "Position (mm)", "Beam radius (mm)");
            // 复刻 Python (matplotlib) 出图格式
            plot.setDrawTitle(false);
            plot.setFontFamily("Arial");
            plot.setLabelFontSize(18f);
            plot.setTickFontSize(16f);
            plot.setTickBold(true);
            plot.setXTicksDecimals(0);
            plot.setYTicksDecimals(2);
            plot.setTicksInward(true);
            plot.setGrid(false);
            plot.setBox(true);
            float lw = 2.4f;   // 拟合曲线线宽 = ax_width*0.3
            float mr = 6f;     // 数据点标记半径（≈ markersize 11.2pt）
            float me = 1.5f;   // 标记边宽 = ax_width*0.15
            plot.addLine(zf, wfX, Color.RED, null, lw);
            plot.addLine(zf, wfY, Color.BLUE, null, lw);
            plot.addScatter(d.z, d.xr, Color.RED,
                    "Mx\u00b2 = " + String.format("%.2f", fx.m2), Plot.MARKER_CIRCLE, true, mr, me);
            plot.addScatter(d.z, d.yr, Color.BLUE,
                    "My\u00b2 = " + String.format("%.2f", fy.m2), Plot.MARKER_TRIANGLE, true, mr, me);
            plot.setLegend(true);
            plot.setLegendLocation(Plot.LEGEND_LOWER_RIGHT);
            plot.setLegendFrame(false);
            plot.setLegendFontSize(15f);
            plot.setNorthAnnotation("\u03bb = " + (int) lambdaNm + " nm");
            plot.showPlot();

            IJ.log("=== M2 ===\n"
                    + "λ = " + (int) lambdaNm + " nm\n"
                    + "Mx² = " + String.format("%.3f", fx.m2)
                    + "  (w0=" + String.format("%.4f", fx.w0) + " mm, z0=" + String.format("%.3f", fx.z0) + " mm)\n"
                    + "My² = " + String.format("%.3f", fy.m2)
                    + "  (w0=" + String.format("%.4f", fy.w0) + " mm, z0=" + String.format("%.3f", fy.z0) + " mm)");
        } catch (Exception e) {
            IJ.error("解析失败", e.getMessage());
            e.printStackTrace();
        }
    }

    // ----------------------------------------------------------------
    // CSV 解析
    // ----------------------------------------------------------------

    private static final class Data {
        double[] z;
        double[] xr; // 半径 = 直径/2
        double[] yr;
        int n;
    }

    /** 按扩展名分派：xlsx/xlsm 走 XlsxReader，其余按 CSV 处理。 */
    private static Data parseFile(String path) throws Exception {
        String lower = path.toLowerCase();
        List<List<String>> tbl;
        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
            tbl = XlsxReader.read(new File(path));
        } else {
            tbl = readCsv(path);
        }
        return buildData(tbl);
    }

    private static List<List<String>> readCsv(String path) throws Exception {
        List<List<String>> tbl = new ArrayList<>();
        BufferedReader br = new BufferedReader(new FileReader(path));
        String line;
        while ((line = br.readLine()) != null) {
            String[] v = line.split(",");
            List<String> row = new ArrayList<>(v.length);
            for (String s : v) {
                row.add(s.trim());
            }
            tbl.add(row);
        }
        br.close();
        return tbl;
    }

    private static Data buildData(List<List<String>> tbl) throws Exception {
        if (tbl.isEmpty()) {
            throw new Exception("文件为空或无法解析。");
        }
        int hdrRow = -1, ip = -1, ix = -1, iy = -1;
        for (int r = 0; r < tbl.size(); r++) {
            List<String> row = tbl.get(r);
            int a = -1, b = -1, c = -1;
            for (int i = 0; i < row.size(); i++) {
                String h = row.get(i).trim().toLowerCase();
                if (a < 0 && match(h, POS_ALIASES)) a = i;
                if (b < 0 && match(h, XD_ALIASES)) b = i;
                if (c < 0 && match(h, YD_ALIASES)) c = i;
            }
            if (a >= 0 && b >= 0 && c >= 0) {
                hdrRow = r;
                ip = a;
                ix = b;
                iy = c;
                break;
            }
        }
        if (hdrRow < 0) {
            throw new Exception("缺少必要列，需要：position (mm), x beam diameter (mm), y beam diameter (mm)");
        }

        ArrayList<Double> Z = new ArrayList<>(), X = new ArrayList<>(), Y = new ArrayList<>();
        for (int r = hdrRow + 1; r < tbl.size(); r++) {
            List<String> row = tbl.get(r);
            if (ip >= row.size() || ix >= row.size() || iy >= row.size()) {
                continue;
            }
            String sz = row.get(ip), sx = row.get(ix), sy = row.get(iy);
            if (sz.isEmpty() || sx.isEmpty() || sy.isEmpty()) {
                continue;
            }
            try {
                double z = Double.parseDouble(sz);
                double xd = Double.parseDouble(sx);
                double yd = Double.parseDouble(sy);
                Z.add(z);
                X.add(xd / 2.0);
                Y.add(yd / 2.0);
            } catch (NumberFormatException ex) {
                IJ.log("跳过无法解析的数据行: " + row);
            }
        }

        Data d = new Data();
        d.n = Z.size();
        d.z = toArray(Z);
        d.xr = toArray(X);
        d.yr = toArray(Y);
        return d;
    }

    private static boolean match(String header, String[] aliases) {
        for (String a : aliases) {
            if (header.contains(a)) {
                return true;
            }
        }
        return false;
    }

    private static double[] toArray(ArrayList<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }

    // ----------------------------------------------------------------
    // 双曲线拟合（Levenberg–Marquardt，自带实现，无第三方依赖）
    //   w(z) = w0 * sqrt(1 + B^2 * (z - z0)^2)
    //   M^2 = |B| * pi * w0^2 / lambda
    // ----------------------------------------------------------------

    private static final class Fit {
        double w0;
        double B;
        double z0;
        double m2;
    }

    private static double model(double z, double w0, double B, double z0) {
        double d = z - z0;
        return w0 * Math.sqrt(1.0 + B * B * d * d);
    }

    private static double model(double z, Fit f) {
        return model(z, f.w0, f.B, f.z0);
    }

    private static double ssr(double[] z, double[] w, double w0, double B, double z0) {
        double s = 0;
        for (int i = 0; i < z.length; i++) {
            double r = w[i] - model(z[i], w0, B, z0);
            s += r * r;
        }
        return s;
    }

    private static Fit fit(double[] z, double[] w, double lamMm) {
        int n = z.length;
        int imin = 0;
        for (int i = 1; i < n; i++) {
            if (w[i] < w[imin]) {
                imin = i;
            }
        }
        double w0 = Math.max(w[imin], 1e-6);
        double z0 = z[imin];
        double span = Math.abs(z[n - 1] - z[0]) + 1e-9;
        double B = Math.max(Math.abs((w[n - 1] - w[0]) / (w0 * span)), 1e-3);

        double[] p = {w0, B, z0};
        double lambda = 1e-3;
        double ssrPrev = ssr(z, w, p[0], p[1], p[2]);

        for (int it = 0; it < 200; it++) {
            double[][] JtJ = new double[3][3];
            double[] Jtr = new double[3];
            for (int i = 0; i < n; i++) {
                double f = model(z[i], p[0], p[1], p[2]);
                double h0 = Math.max(Math.abs(p[0]) * 1e-6, 1e-9);
                double h1 = Math.max(Math.abs(p[1]) * 1e-6, 1e-9);
                double h2 = Math.max(Math.abs(p[2]) * 1e-6, 1e-6);
                double d0 = (model(z[i], p[0] + h0, p[1], p[2]) - f) / h0;
                double d1 = (model(z[i], p[0], p[1] + h1, p[2]) - f) / h1;
                double d2 = (model(z[i], p[0], p[1], p[2] + h2) - f) / h2;
                double ri = w[i] - f;
                double[] deriv = {d0, d1, d2};
                for (int a = 0; a < 3; a++) {
                    Jtr[a] += deriv[a] * ri;
                    for (int b = 0; b < 3; b++) {
                        JtJ[a][b] += deriv[a] * deriv[b];
                    }
                }
            }
            for (int a = 0; a < 3; a++) {
                JtJ[a][a] += lambda;
            }
            double[] dp = solve3(JtJ, Jtr);
            double[] pNew = {p[0] + dp[0], p[1] + dp[1], p[2] + dp[2]};
            if (pNew[0] <= 0) {
                pNew[0] = 1e-6;
            }
            double ssrNew = ssr(z, w, pNew[0], pNew[1], pNew[2]);
            if (ssrNew < ssrPrev) {
                p = pNew;
                ssrPrev = ssrNew;
                lambda = Math.max(lambda / 10.0, 1e-9);
            } else {
                lambda = Math.min(lambda * 10.0, 1e9);
            }
            if (Math.abs(dp[0]) < 1e-9 && Math.abs(dp[1]) < 1e-9 && Math.abs(dp[2]) < 1e-9) {
                break;
            }
        }

        Fit fit = new Fit();
        fit.w0 = p[0];
        fit.B = p[1];
        fit.z0 = p[2];
        fit.m2 = Math.abs(p[1]) * Math.PI * p[0] * p[0] / lamMm;
        fit.m2 = Math.round(fit.m2 * 100.0) / 100.0; // 2 位小数
        return fit;
    }

    /** 解 3×3 线性方程组 A·x = b（高斯消元 + 部分主元）。 */
    private static double[] solve3(double[][] A, double[] b) {
        double[][] M = new double[3][4];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                M[i][j] = A[i][j];
            }
            M[i][3] = b[i];
        }
        for (int col = 0; col < 3; col++) {
            int piv = col;
            for (int r = col + 1; r < 3; r++) {
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
            for (int j = col; j <= 3; j++) {
                M[col][j] /= d;
            }
            for (int r = 0; r < 3; r++) {
                if (r == col) {
                    continue;
                }
                double f = M[r][col];
                if (f != 0) {
                    for (int j = col; j <= 3; j++) {
                        M[r][j] -= f * M[col][j];
                    }
                }
            }
        }
        return new double[]{M[0][3], M[1][3], M[2][3]};
    }

    private static double min(double[] a) {
        double m = a[0];
        for (double v : a) {
            if (v < m) {
                m = v;
            }
        }
        return m;
    }

    private static double max(double[] a) {
        double m = a[0];
        for (double v : a) {
            if (v > m) {
                m = v;
            }
        }
        return m;
    }
}
