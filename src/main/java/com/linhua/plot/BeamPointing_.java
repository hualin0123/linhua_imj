package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.List;

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
                plotScatter(d.samples, d.rms);
                logStats(d.stats, d.rms);
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

    /** 图2：X–Y 散点分布图（含原点参考十字）。 */
    private void plotScatter(List<BeamPointingCore.Sample> samples, double rms) {
        int n = samples.size();
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            BeamPointingCore.Sample s = samples.get(i);
            x[i] = s.x;
            y[i] = s.y;
        }

        Plot plot = new Plot("Beam Pointing - X-Y Scatter", "Offset X / um", "Offset Y / um");
        PlotUtil.applyStyle(plot, "Offset X / um", "Offset Y / um", 1, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);
        plot.addScatter(x, y, Color.BLUE, null);
        plot.setOriginCross(true);
        plot.setAnnotation("RMS offset \u2248 " + String.format("%.2f", rms) + " um");
        plot.showPlot();
    }

    /** 将汇总统计与计算得到的 RMS 偏移输出到 Log 窗口。 */
    private void logStats(List<BeamPointingCore.Stats> stats, double rms) {
        StringBuilder sb = new StringBuilder("=== Laser Beam Pointing Stability ===\n");
        sb.append(String.format("RMS offset (computed) = %.3f um\n", rms));
        if (stats.isEmpty()) {
            sb.append("(CSV 中未找到汇总统计表)\n");
        }
        for (BeamPointingCore.Stats s : stats) {
            String name = s.axis.isEmpty() ? "Combined" : s.axis;
            sb.append(String.format(
                "%s: max=%.3f mean=%.3f range=%.3f std=%.3f rms=%.3f pointing=%.3f urad position=%.3f um\n",
                name, s.max, s.mean, s.range, s.std, s.rms, s.pointing, s.position));
        }
        IJ.log(sb.toString());
    }
}
