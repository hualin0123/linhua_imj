package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import ij.text.TextWindow;
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
    private List<BeamPointingCore.Sample> diffSamples;           // 差分结果(全网格，缺口为 NaN)
    private List<BeamPointingCore.Sample> validSamples;          // 差分有效点(两臂均有真实数据，供统计/散点)
    private double[] diffDx, diffDy, diffD, tg;                 // 差分分量/合成/时间网格(μm)
    private double kX, kY;                                      // 增益匹配系数
    private BeamPointingCore.Data refData, measData;             // 留存供 Log 复用
    private String measName;                                    // 测量臂(含被测器件)CSV 名(去扩展名)，用于图例
    private Plot diffScatterPlot;                               // 图2(用于重绘)
    private static TextWindow diffLogWnd;                       // 差分统计输出窗口(标题=Beam Pointing Differential)
    private static final double ALIGN_TOL = 1e-3;               // 时长差小于该值(s)视为一致，不裁剪
    private static final double GAP_FACTOR = 3.0;               // 相邻采样间隔超 名义步长×该值 即判定为缺失段(缺口)
    private String refName;                                    // 参考臂(无器件)CSV 名(去扩展名)，用于日志
    private String alignNote = "";                             // 时间对齐裁剪说明(非空时写入 Log)

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
            measName = stripExt(measFiles[0].getName());
            refName = stripExt(refFiles[0].getName());
            process(refData, measData);
        } catch (Exception e) {
            IJ.error("处理失败", e.getMessage());
            e.printStackTrace();
        }
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        if (d > 0 && n.substring(d).equalsIgnoreCase(".csv")) {
            return n.substring(0, d);
        }
        return n;
    }

    private void process(BeamPointingCore.Data ref, BeamPointingCore.Data meas) {
        // ---- 时间对齐：检测两文件测试时长是否一致，不一致则自动裁剪较长的文件 ----
        double rt0 = ref.samples.get(0).t, rt1 = ref.samples.get(ref.samples.size() - 1).t;
        double mt0 = meas.samples.get(0).t, mt1 = meas.samples.get(meas.samples.size() - 1).t;
        double durRef = rt1 - rt0, durMeas = mt1 - mt0;
        double t0 = Math.max(rt0, mt0);
        double t1 = Math.min(rt1, mt1);
        alignNote = "";
        if (Math.abs(durRef - durMeas) > ALIGN_TOL) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(
                    "【时间对齐】两文件测试时长不一致，已自动将较长的文件裁剪到共同时间区间 [%.2f, %.2f] s（重叠 %.2f s）\n",
                    t0, t1, t1 - t0));
            if (durRef > durMeas) {
                int before = ref.samples.size();
                ref = trimToRange(ref, t0, t1);
                sb.append(String.format(
                        "  参考臂 %s: %.2f s (%d 点) → 裁剪为 %.2f s (%d 点)，丢弃 %.2f s / %d 点\n",
                        refName, durRef, before, t1 - t0, ref.samples.size(),
                        durRef - (t1 - t0), before - ref.samples.size()));
            } else {
                int before = meas.samples.size();
                meas = trimToRange(meas, t0, t1);
                sb.append(String.format(
                        "  测量臂 %s: %.2f s (%d 点) → 裁剪为 %.2f s (%d 点)，丢弃 %.2f s / %d 点\n",
                        measName, durMeas, before, t1 - t0, meas.samples.size(),
                        durMeas - (t1 - t0), before - meas.samples.size()));
            }
            alignNote = sb.toString();
        }
        // 留存(可能已裁剪)的数据供 stats 复用
        refData = ref;
        measData = meas;

        double[] rt = toArr(ref.samples, 0), rx = toArr(ref.samples, 1), ry = toArr(ref.samples, 2);
        double[] mt = toArr(meas.samples, 0), mx = toArr(meas.samples, 1), my = toArr(meas.samples, 2);

        // 共同时间网格：两路时间交集，步长取两者较小的"名义间隔"(忽略缺口段，吸收时钟 skew)
        if (t1 <= t0) {
            IJ.error("两文件时间不重叠，请确认所选 CSV 内容");
            return;
        }
        double stepR = nominalStep(rt, GAP_FACTOR), stepM = nominalStep(mt, GAP_FACTOR);
        double dt = Math.min(stepR, stepM);
        int ng = (int) Math.floor((t1 - t0) / dt) + 1;
        tg = new double[ng];
        for (int i = 0; i < ng; i++) {
            tg[i] = t0 + i * dt;
        }

        // 重采样到统一网格；缺口段(相邻采样间隔超阈值)置 NaN，绝不插值伪造
        double gapR = GAP_FACTOR * stepR, gapM = GAP_FACTOR * stepM;
        double[] rgx = resample(rt, rx, tg, gapR), rgy = resample(rt, ry, tg, gapR);
        double[] mgx = resample(mt, mx, tg, gapM), mgy = resample(mt, my, tg, gapM);

        // 增益匹配(各轴独立，去均值后最小二乘 k=<r,m>/<r,r>)；均值/点积均跳过缺口 NaN
        double mRx = meanValid(rgx), mRy = meanValid(rgy);
        double mMx = meanValid(mgx), mMy = meanValid(mgy);
        double[] rgxm = new double[ng], rgym = new double[ng];
        double[] mgxm = new double[ng], mgym = new double[ng];
        for (int i = 0; i < ng; i++) {
            boolean rOk = !Double.isNaN(rgx[i]) && !Double.isNaN(rgy[i]);
            boolean mOk = !Double.isNaN(mgx[i]) && !Double.isNaN(mgy[i]);
            rgxm[i] = rOk ? rgx[i] - mRx : Double.NaN;
            rgym[i] = rOk ? rgy[i] - mRy : Double.NaN;
            mgxm[i] = mOk ? mgx[i] - mMx : Double.NaN;
            mgym[i] = mOk ? mgy[i] - mMy : Double.NaN;
        }
        kX = dotValid(rgxm, mgxm) / dotValid(rgxm, rgxm);
        kY = dotValid(rgym, mgym) / dotValid(rgym, rgym);
        if (!Double.isFinite(kX) || kX < 0 || kX > 2) {
            IJ.log("⚠ 增益异常(kX=" + kX + ")，请检查两臂光路极性是否一致（或参考臂缺口过多）。");
            if (!Double.isFinite(kX)) {
                kX = 1;
            }
        }
        if (!Double.isFinite(kY) || kY < 0 || kY > 2) {
            IJ.log("⚠ 增益异常(kY=" + kY + ")，请检查两臂光路极性是否一致（或参考臂缺口过多）。");
            if (!Double.isFinite(kY)) {
                kY = 1;
            }
        }

        // 差分 D(t) = meas - k·ref；仅在两臂均有真实数据的时刻计算，否则置 NaN(缺口)
        double[] Dx = new double[ng], Dy = new double[ng], D = new double[ng];
        for (int i = 0; i < ng; i++) {
            if (Double.isNaN(rgxm[i]) || Double.isNaN(rgym[i])
                    || Double.isNaN(mgxm[i]) || Double.isNaN(mgym[i])) {
                Dx[i] = Dy[i] = D[i] = Double.NaN;
            } else {
                Dx[i] = mgxm[i] - kX * rgxm[i];
                Dy[i] = mgym[i] - kY * rgym[i];
                D[i] = Math.hypot(Dx[i], Dy[i]);
            }
        }

        // 构造差分 Sample：全网格(缺口为 NaN 供绘图断线) + 有效点列表(供统计/散点)
        diffSamples = new ArrayList<>();
        validSamples = new ArrayList<>();
        for (int i = 0; i < ng; i++) {
            BeamPointingCore.Sample s = new BeamPointingCore.Sample();
            s.t = tg[i];
            s.x = Dx[i];
            s.y = Dy[i];
            s.r = D[i];
            diffSamples.add(s);
            if (!Double.isNaN(D[i])) {
                validSamples.add(s);
            }
        }
        diffDx = Dx;
        diffDy = Dy;
        diffD = D;

        // 缺口诊断：统计两臂因缺口被排除的网格点数
        int gapPtsR = countNaN(rgx), gapPtsM = countNaN(mgx);
        if (gapPtsR > 0 || gapPtsM > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(alignNote);
            sb.append("【数据缺口】检测到文件中间存在缺失数据段，已自动跳过这些时刻(不参与插值/差分)：\n");
            if (gapPtsR > 0) {
                sb.append(String.format("  参考臂 %s: %d / %d 个网格点位于缺口，已排除\n", refName, gapPtsR, ng));
            }
            if (gapPtsM > 0) {
                sb.append(String.format("  测量臂 %s: %d / %d 个网格点位于缺口，已排除\n", measName, gapPtsM, ng));
            }
            alignNote = sb.toString();
        }
        if (!alignNote.isEmpty()) {
            IJ.log(alignNote.trim());
        }
        if (validSamples.isEmpty()) {
            IJ.error("两文件在重叠时段内均无共同有效数据，无法计算差分。");
            return;
        }

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
        int n = validSamples.size();
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
        if (n == 0) {
            diffScatterPlot.refresh();
            return;
        }
        double[] x = new double[n], y = new double[n];
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            x[i] = validSamples.get(i).x * factor;
            y[i] = validSamples.get(i).y * factor;
            sx += x[i];
            sy += y[i];
        }
        double cx = sx / n, cy = sy / n;
        double rmsUm = BeamPointingCore.centroidRmsUm(validSamples);
        String unit = urad ? "urad" : "um";
        String label = measName + "(校准)" + String.format(" (%.1f %s)", rmsUm * factor, unit);
        diffScatterPlot.addScatter(x, y, Color.BLUE, label, Plot.MARKER_CIRCLE, true, 4f, 1f);
        diffScatterPlot.addCircle(cx, cy, rmsUm * factor, Color.BLUE);
        diffScatterPlot.refresh();
    }

    // ===================== Log（与 BeamPointing_ 完全一致的文字风格） =====================

    private void printStats() {
        double rmsUm = validSamples.isEmpty() ? Double.NaN : BeamPointingCore.centroidRmsUm(validSamples);
        double posUm = validSamples.isEmpty() ? Double.NaN : BeamPointingCore.positionUm(validSamples);
        double pointUrad = BeamPointingCore.pointingUrad(rmsUm, distMm);
        StringBuilder sb = new StringBuilder();
        if (!alignNote.isEmpty()) {
            sb.append(alignNote).append("\n");
        }
        sb.append("=== Laser Beam Pointing Stability (Differential) ===\n");
        if (validSamples.isEmpty()) {
            sb.append("（无有效差分点，无法统计 RMS / Mean）\n");
        } else {
            sb.append(String.format("RMS offset (computed) = %.3f um\n", rmsUm));
            sb.append(String.format("Mean offset (centroid) = %.3f um\n", posUm));
        }
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
        // 输出到标题为 "Beam Pointing Differential" 的窗口（与菜单名呼应，带显微镜图标），复用同一窗口避免堆叠
        if (diffLogWnd == null || !diffLogWnd.isShowing()) {
            diffLogWnd = new TextWindow("Beam Pointing Differential", sb.toString(), 600, 320);
        } else {
            diffLogWnd.getTextPanel().clear();
            diffLogWnd.getTextPanel().append(sb.toString());
            diffLogWnd.toFront();
        }
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

    /** 将 Data 裁剪到时间区间 [t0, t1]（闭区间），丢弃区间外采样点，重算 RMS。 */
    private static BeamPointingCore.Data trimToRange(BeamPointingCore.Data d, double t0, double t1) {
        List<BeamPointingCore.Sample> kept = new ArrayList<>();
        for (BeamPointingCore.Sample s : d.samples) {
            if (s.t >= t0 && s.t <= t1) {
                kept.add(s);
            }
        }
        double sumR2 = 0;
        for (BeamPointingCore.Sample s : kept) {
            sumR2 += s.r * s.r;
        }
        double rms = kept.isEmpty() ? Double.NaN : Math.sqrt(sumR2 / kept.size());
        return new BeamPointingCore.Data(kept, d.stats, rms);
    }

    /** 名义采样步长：取所有"非缺口"相邻间隔的均值(缺口段按 gapFactor×粗均值判定并排除)。 */
    private static double nominalStep(double[] t, double gapFactor) {
        double rough = (t[t.length - 1] - t[0]) / Math.max(1, t.length - 1);
        double thr = gapFactor * rough;
        double total = 0;
        int cnt = 0;
        for (int i = 0; i < t.length - 1; i++) {
            double d = t[i + 1] - t[i];
            if (d > 0 && d <= thr) {
                total += d;
                cnt++;
            }
        }
        return cnt > 0 ? total / cnt : rough;
    }

    /** 均值(跳过 NaN)；全为 NaN 时返回 NaN。 */
    private static double meanValid(double[] a) {
        double s = 0;
        int m = 0;
        for (double v : a) {
            if (!Double.isNaN(v)) {
                s += v;
                m++;
            }
        }
        return m > 0 ? s / m : Double.NaN;
    }

    /** 点积(仅对两数组均非 NaN 的项累加)。 */
    private static double dotValid(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) {
                s += a[i] * b[i];
            }
        }
        return s;
    }

    /** 统计数组中的 NaN 个数。 */
    private static int countNaN(double[] a) {
        int c = 0;
        for (double v : a) {
            if (Double.isNaN(v)) {
                c++;
            }
        }
        return c;
    }

    /**
     * 线性插值重采样：t 单调递增。若目标点所在的相邻采样间隔超过 maxStep(视为数据缺失段)，
     * 返回 NaN 而非插值伪造，从而让下游差分/绘图自然跳过该缺口。
     */
    private static double[] resample(double[] t, double[] v, double[] tg, double maxStep) {
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
            if ((t[j + 1] - t[j]) > maxStep) {
                out[i] = Double.NaN;
                continue;
            }
            double f = (x - t[j]) / (t[j + 1] - t[j]);
            out[i] = v[j] + f * (v[j + 1] - v[j]);
        }
        return out;
    }
}
