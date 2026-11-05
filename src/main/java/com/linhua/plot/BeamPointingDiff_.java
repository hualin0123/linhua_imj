package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * 透射器件指向稳定性差分插件（输出样式对齐 {@code BeamPointing_}：时间轨迹图 + X-Y 散点图 + 一致 Log）。
 *
 * <p>取参考臂（无器件）与测量臂（含被测透射元件）两组 *同步采集* 的 Beam Pointing CSV，
 * 把两路按绝对时间（当天秒数）重采样到统一网格，做增益匹配后逐点差分
 * <pre>   D(t) = meas(t) - k·ref(t)   </pre>
 * 从而把激光器抖动 / 环境振动 / 空气湍流 / 两臂光路等共模影响从测量臂中扣除，
 * 仅保留透射元器件自身引入的动态指向起伏（两臂静态偏折差经去均值已剔除，不计入抖动）。</p>
 *
 * <p>时间对齐采用「重采样到共同网格」，对 1 Hz 量级的本地数据可直接吸收两机之间的
 * 固定 lag 与采样时钟 skew（无需亚采样精度）。增益匹配对各轴独立用去均值后的最小二乘
 * {@code k = <r,m>/<r,r>} 估计。解析、统计与指标口径（pointing = RMS×1000/L）复用
 * {@link BeamPointingCore} 与 README「指向类指标口径」。</p>
 *
 * <p>输出（风格与 {@code BeamPointing_} 完全一致）：
 * <ol>
 *   <li>差分时间轨迹图（仿 {@code BeamPointing_.plotTimeTrace}：ΔX/ΔY/Δ合成 三条线，
 *       红/绿/蓝，轴 Time / s 与 Offset / um）；</li>
 *   <li>差分 X-Y 散点图（仿 {@code BeamPointing_.plotScatter}：点 + 质心 RMS 圈、
 *       右上角传播距离框、{@code RMS offset ≈ …} 注解，输入距离后轴与圈切换为 μrad）；</li>
 *   <li>ImageJ Log（仿 {@code BeamPointing_.printStats} 的 RMS/Mean/pointing 风格，
 *       并附差分特有的共模抑制对照）。</li>
 * </ol>
 */
public class BeamPointingDiff_ implements PlugIn {

    private double distMm = 0;                                  // 传播距离(mm)，>0 时散点显示 μrad
    private List<BeamPointingCore.Sample> diffSamples;           // 差分结果(已去均值)
    private double[] diffDx, diffDy, diffD, tg;                 // 差分分量/合成/时间网格(μm)
    private double kX, kY;                                      // 增益匹配系数
    private BeamPointingCore.Data refData, measData;             // 留存供 Log 复用
    private Plot diffScatterPlot;                               // 图2(用于重绘)

    @Override
    public void run(String arg) {
        File[] refFiles = PlotUtil.chooseFiles("选择参考臂 CSV（无器件）", "csv");
        if (refFiles.length == 0) {
            return;
        }
        File[] measFiles = PlotUtil.chooseFiles("选择测量臂 CSV（含被测器件）", "csv");
        if (measFiles.length == 0) {
            return;
        }
        try {
            refData = BeamPointingCore.load(refFiles[0].getAbsolutePath());
            measData = BeamPointingCore.load(measFiles[0].getAbsolutePath());
            process(refData, measData);
        } catch (Exception e) {
            IJ.error("处理失败", e.getMessage());
            e.printStackTrace();
        }
    }

    private void process(BeamPointingCore.Data ref, BeamPointingCore.Data meas) {
        double[] rt = toArr(ref.samples, 0), rx = toArr(ref.samples, 1), ry = toArr(ref.samples, 2);
        double[] mt = toArr(meas.samples, 0), mx = toArr(meas.samples, 1), my = toArr(meas.samples, 2);

        // 共同时间网格：两路时间交集，步长取两者较小名义间隔(吸收时钟 skew)
        double t0 = Math.max(rt[0], mt[0]);
        double t1 = Math.min(rt[rt.length - 1], mt[mt.length - 1]);
        if (t1 <= t0) {
            IJ.error("两文件时间不重叠，请确认所选 CSV 内容");
            return;
        }
        double dt = Math.min(meanDt(rt), meanDt(mt));
        int ng = (int) Math.floor((t1 - t0) / dt) + 1;
        tg = new double[ng];
        for (int i = 0; i < ng; i++) {
            tg[i] = t0 + i * dt;
        }

        // 重采样到统一网格(线性插值；吸收固定 lag 与时钟 skew)
        double[] rgx = resample(rt, rx, tg), rgy = resample(rt, ry, tg);
        double[] mgx = resample(mt, mx, tg), mgy = resample(mt, my, tg);

        // 增益匹配(各轴独立，去均值后最小二乘 k=<r,m>/<r,r>)
        double[] rgxm = subMean(rgx), rgym = subMean(rgy);
        double[] mgxm = subMean(mgx), mgym = subMean(mgy);
        kX = dot(rgxm, mgxm) / dot(rgxm, rgxm);
        kY = dot(rgym, mgym) / dot(rgym, rgym);
        if (kX < 0 || kY < 0 || kX > 2 || kY > 2) {
            IJ.log("⚠ 增益异常(kX=" + kX + ", kY=" + kY + ")，请检查两臂光路极性是否一致。");
        }

        // 差分 D(t) = meas - k·ref (仅动态起伏)
        double[] Dx = new double[ng], Dy = new double[ng], D = new double[ng];
        for (int i = 0; i < ng; i++) {
            Dx[i] = mgxm[i] - kX * rgxm[i];
            Dy[i] = mgym[i] - kY * rgym[i];
            D[i] = Math.hypot(Dx[i], Dy[i]);
        }

        // 构造差分 Sample 列表，复用既有统计
        diffSamples = new ArrayList<>();
        for (int i = 0; i < ng; i++) {
            BeamPointingCore.Sample s = new BeamPointingCore.Sample();
            s.t = tg[i];
            s.x = Dx[i];
            s.y = Dy[i];
            s.r = D[i];
            diffSamples.add(s);
        }
        diffDx = Dx;
        diffDy = Dy;
        diffD = D;

        // 输出（对齐 BeamPointing_ 样式：时间轨迹图 + X-Y 散点图 + Log）
        plotTimeTrace();
        plotDiffScatter();
        printStats();
    }

    // ===================== 图1：仿 BeamPointing_ 的时间轨迹图 =====================

    private void plotTimeTrace() {
        Plot p = new Plot("Beam Pointing (Differential) - Time Trace", "Time / s", "Offset / um");
        PlotUtil.applyStyle(p, "Time / s", "Offset / um", 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);
        p.addLine(tg, diffDx, Color.RED, "ΔX");
        p.addLine(tg, diffDy, Color.GREEN, "ΔY");
        p.addLine(tg, diffD, Color.BLUE, "Δ(合成)");
        p.showPlot();
    }

    // ===================== 图2：仿 BeamPointing_ 的 X-Y 散点图 =====================

    private void plotDiffScatter() {
        diffScatterPlot = new Plot("Beam Pointing (Differential) - X-Y Scatter", "Offset X / um", "Offset Y / um");
        PlotUtil.applyStyle(diffScatterPlot, "Offset X / um", "Offset Y / um", 1, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);
        JTextField distField = new JTextField(4);
        if (distMm > 0) {
            distField.setText(String.valueOf(distMm));
        }
        distField.setToolTipText("输入传播距离(mm)，回车/失焦后散点 X/Y 与 RMS 圈转为 μrad（pointing）");
        DoubleSupplier read = () -> {
            try {
                double v = Double.parseDouble(distField.getText().trim());
                return v > 0 ? v : 0;
            } catch (Exception ex) {
                return 0;
            }
        };
        Consumer<Double> repaint = (dmm) -> {
            distMm = dmm;
            redrawDiffScatter();
            printStats();
        };
        distField.addActionListener(e -> repaint.accept(read.getAsDouble()));
        distField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                repaint.accept(read.getAsDouble());
            }
        });
        JPanel extra = PlotUtil.buildOptionsPanel(null, distField);
        diffScatterPlot.addTopRightControl(extra);
        redrawDiffScatter();
        diffScatterPlot.showPlot();
    }

    private void redrawDiffScatter() {
        int n = diffSamples.size();
        double factor = distMm > 0 ? 1000.0 / distMm : 1.0;
        boolean urad = factor != 1.0;
        if (urad) {
            diffScatterPlot.setXLabel("Offset X / urad");
            diffScatterPlot.setYLabel("Offset Y / urad");
        } else {
            diffScatterPlot.setXLabel("Offset X / um");
            diffScatterPlot.setYLabel("Offset Y / um");
        }
        diffScatterPlot.clearSeriesData();
        double[] x = new double[n], y = new double[n];
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            x[i] = diffDx[i] * factor;
            y[i] = diffDy[i] * factor;
            sx += x[i];
            sy += y[i];
        }
        double cx = sx / n, cy = sy / n;
        double rmsUm = BeamPointingCore.centroidRmsUm(diffSamples);
        diffScatterPlot.addScatter(x, y, Color.BLUE, null);
        diffScatterPlot.addCircle(cx, cy, rmsUm * factor, Color.DARK_GRAY);
        diffScatterPlot.setAnnotation("RMS offset ≈ " + String.format("%.3f", rmsUm * factor) + (urad ? " urad" : " um"));
        diffScatterPlot.refresh();
    }

    // ===================== Log（与 BeamPointing_ 完全一致的文字风格） =====================

    private void printStats() {
        double rmsUm = BeamPointingCore.centroidRmsUm(diffSamples);
        double posUm = BeamPointingCore.positionUm(diffSamples);
        double pointUrad = BeamPointingCore.pointingUrad(rmsUm, distMm);
        StringBuilder sb = new StringBuilder("=== Laser Beam Pointing Stability (Differential) ===\n");
        sb.append(String.format("RMS offset (computed) = %.3f um\n", rmsUm));
        sb.append(String.format("Mean offset (centroid) = %.3f um\n", posUm));
        if (Double.isNaN(pointUrad)) {
            sb.append("pointing = -  (请在散点图右上角输入传播距离 mm)\n");
        } else {
            sb.append(String.format("pointing = %.3f urad\n", pointUrad));
        }
        // 共模抑制：差分残余相对测量臂总抖动的下降比例
        double rmsMeas = BeamPointingCore.centroidRmsUm(measData.samples);
        if (rmsMeas > 0) {
            sb.append(String.format("共模抑制 残余/原 = %.3f  (下降 %.1f%%)\n",
                    rmsUm / rmsMeas, (1 - rmsUm / rmsMeas) * 100));
        }
        IJ.log(sb.toString());
    }

    // ===================== 数组工具 =====================

    /** which: 0=t, 1=x, 2=y。 */
    private static double[] toArr(List<BeamPointingCore.Sample> s, int which) {
        double[] a = new double[s.size()];
        for (int i = 0; i < a.length; i++) {
            BeamPointingCore.Sample x = s.get(i);
            a[i] = which == 0 ? x.t : (which == 1 ? x.x : x.y);
        }
        return a;
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) {
            s += v;
        }
        return s / a.length;
    }

    private static double meanDt(double[] t) {
        return (t[t.length - 1] - t[0]) / Math.max(1, t.length - 1);
    }

    private static double[] subMean(double[] a) {
        double m = mean(a);
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            o[i] = a[i] - m;
        }
        return o;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** 线性插值重采样：t 单调递增；tg 落在 [t0,tN] 内时安全（交集网格已保证）。 */
    private static double[] resample(double[] t, double[] v, double[] tg) {
        double[] out = new double[tg.length];
        int n = t.length, j = 0;
        for (int i = 0; i < tg.length; i++) {
            double x = tg[i];
            if (x <= t[0]) {
                out[i] = v[0];
                continue;
            }
            if (x >= t[n - 1]) {
                out[i] = v[n - 1];
                continue;
            }
            while (j < n - 2 && t[j + 1] < x) {
                j++;
            }
            double f = (x - t[j]) / (t[j + 1] - t[j]);
            out[i] = v[j] + f * (v[j + 1] - v[j]);
        }
        return out;
    }
}
