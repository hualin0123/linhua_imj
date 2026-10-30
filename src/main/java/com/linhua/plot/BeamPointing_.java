package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * 激光光束指向稳定性绘图插件（单次测量）。
 *
 * <p>读取由激光指向测试设备导出的 CSV（格式同 1.csv），生成两张图：
 * <ol>
 *   <li>时间轨迹图：偏移X / 偏移Y / 偏移(合成) 随时间的变化曲线；</li>
 *   <li>X–Y 散点图：各采样时刻光束位置的分布（含原点参考十字与 RMS 偏移标注）。</li>
 * </ol>
 * 同时把汇总统计量输出到 ImageJ 的 Log 窗口。</p>
 *
 * <p>解析与数据载体见 {@link BeamPointingCore}（与多文件对比入口共用）。</p>
 *
 * @author Hua Lin
 * @since 0.1.0
 */
public class BeamPointing_ implements PlugIn {

    /** 当前散点图的传播距离（mm）；>0 时 X/Y 以 μrad 显示。 */
    private double distMm = 0;

    /** ImageJ 插件入口。 */
    @Override
    public void run(String arg) {
        File[] files = PlotUtil.chooseFiles("选择激光指向稳定性 CSV", "csv");
        if (files.length == 0) {
            return; // 用户取消
        }
        for (File f : files) {
            try {
                BeamPointingCore.Data d = BeamPointingCore.load(f.getAbsolutePath());
                plotTimeTrace(d.samples);
                plotScatter(d);
            } catch (Exception e) {
                IJ.error("解析失败: " + f.getName(), e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /** 图1：偏移随时间变化（X / Y / 合成）。 */
    private void plotTimeTrace(List<BeamPointingCore.Sample> samples) {
        int n = samples.size();
        double[] t = new double[n];
        double[] x = new double[n];
        double[] y = new double[n];
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            BeamPointingCore.Sample s = samples.get(i);
            t[i] = s.t;
            x[i] = s.x;
            y[i] = s.y;
            r[i] = s.r;
        }

        Plot plot = new Plot("Beam Pointing - Time Trace", "Time / s", "Offset / um");
        PlotUtil.applyStyle(plot, "Time / s", "Offset / um", 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);
        plot.addLine(t, x, Color.RED, "Offset X");
        plot.addLine(t, y, Color.GREEN, "Offset Y");
        plot.addLine(t, r, Color.BLUE, "Offset (combined)");
        plot.showPlot();
    }

    /** 图2：X–Y 散点分布图；右上角可输入传播距离将 X/Y 转为 μrad（pointing）。 */
    private void plotScatter(BeamPointingCore.Data d) {
        Plot plot = new Plot("Beam Pointing - X-Y Scatter", "Offset X / um", "Offset Y / um");
        PlotUtil.applyStyle(plot, "Offset X / um", "Offset Y / um", 1, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);

        JTextField distField = new JTextField(4);
        if (distMm > 0) {
            distField.setText(String.valueOf(distMm));
        }
        distField.setToolTipText("输入测量面到基准面的传播距离(mm)，回车或失焦后散点 X/Y 转为 μrad（pointing）");
        java.util.function.DoubleSupplier readDist = () -> {
            try {
                double v = Double.parseDouble(distField.getText().trim());
                return v > 0 ? v : 0;
            } catch (Exception ex) {
                return 0;
            }
        };
        java.util.function.Consumer<Double> repaint = (dmm) -> {
            distMm = dmm;
            redrawScatter(plot, d);
            printStats(d);
        };
        distField.addActionListener(e -> repaint.accept(readDist.getAsDouble()));
        distField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) { repaint.accept(readDist.getAsDouble()); }
        });
        JPanel extra = PlotUtil.buildOptionsPanel(null, distField);
        plot.addTopRightControl(extra);

        redrawScatter(plot, d);
        plot.showPlot();
        printStats(d);
    }

    /** 在已有 Plot 上按当前距离重绘散点（μm 或 μrad）。 */
    private void redrawScatter(Plot plot, BeamPointingCore.Data d) {
        List<BeamPointingCore.Sample> samples = d.samples;
        int n = samples.size();
        double factor = distMm > 0 ? 1000.0 / distMm : 1.0;
        boolean urad = factor != 1.0;
        if (urad) {
            plot.setXLabel("Offset X / urad");
            plot.setYLabel("Offset Y / urad");
        } else {
            plot.setXLabel("Offset X / um");
            plot.setYLabel("Offset Y / um");
        }
        plot.clearSeriesData();
        double[] x = new double[n], y = new double[n];
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            x[i] = samples.get(i).x * factor;
            y[i] = samples.get(i).y * factor;
            sx += samples.get(i).x;
            sy += samples.get(i).y;
        }
        double cx = sx / n, cy = sy / n;
        double rmsUm = BeamPointingCore.centroidRmsUm(d.samples);
        plot.addScatter(x, y, Color.BLUE, null);
        plot.addCircle(cx * factor, cy * factor, rmsUm * factor, Color.DARK_GRAY);
        plot.setAnnotation("RMS offset ≈ " + String.format("%.3f", rmsUm * factor) + (urad ? " urad" : " um"));
        plot.refresh();
    }

    /** 将 RMS / position / pointing 输出到 Log（均由散点重算，pointing 需传播距离）。 */
    private void printStats(BeamPointingCore.Data d) {
        double posUm = BeamPointingCore.positionUm(d.samples);
        double rmsUm = BeamPointingCore.centroidRmsUm(d.samples);
        double pointUrad = BeamPointingCore.pointingUrad(rmsUm, distMm);
        StringBuilder sb = new StringBuilder("=== Laser Beam Pointing Stability ===\n");
        sb.append(String.format("RMS offset (computed) = %.3f um\n", rmsUm));
        sb.append(String.format("Mean offset (centroid) = %.3f um\n", posUm));
        if (Double.isNaN(pointUrad)) {
            sb.append("pointing = -  (请在散点图右上角输入传播距离 mm)\n");
        } else {
            sb.append(String.format("pointing = %.3f urad\n", pointUrad));
        }
        IJ.log(sb.toString());
    }
}
