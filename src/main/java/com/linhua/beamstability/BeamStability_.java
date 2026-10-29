package com.linhua.beamstability;

import ij.IJ;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;

/**
 * 激光光束指向稳定性绘图插件。
 *
 * <p>读取由激光指向测试设备导出的 CSV（格式同 1.csv），生成两张图：
 * <ol>
 *   <li>时间轨迹图：偏移X / 偏移Y / 偏移(合成) 随时间的变化曲线；</li>
 *   <li>X–Y 散点图：各采样时刻光束位置的分布（含原点参考十字与 RMS 偏移标注）。</li>
 * </ol>
 * 同时把汇总统计量输出到 ImageJ 的 Log 窗口。</p>
 *
 * <p>CSV 约定（与 1.csv 一致）：
 * <pre>
 * 起始时间,...
 * 结束时间,...
 * 轴,最大偏移/μm,平均偏移/μm,偏移范围/μm,标准差/μm,均方根/μm,指向稳定性/urad,位置稳定性/μm
 * X,...
 * Y,...
 * (空轴=合成),...
 * 时间,偏移X/μm,偏移Y/μm,偏移/μm
 *
 * "2026-10-27, 14:17:13.073",0,0,0
 * ...
 * </pre>
 * 时间字段用引号包裹且内部含逗号，本类已做对应解析。</p>
 *
 * @author Hua Lin
 * @since 0.1.0
 */
public class BeamStability_ implements PlugIn {

    /** 单个采样点。 */
    private static class Sample {
        double t;  // 距首个采样的秒数
        double x;  // 偏移X / μm
        double y;  // 偏移Y / μm
        double r;  // 偏移(合成) / μm
    }

    /** 汇总统计的一行（按轴）。 */
    private static class Stats {
        String axis;
        double max, mean, range, std, rms, pointing, position;
    }

    /** ImageJ 插件入口。 */
    @Override
    public void run(String arg) {
        OpenDialog od = new OpenDialog("选择激光指向稳定性 CSV", null);
        String path = od.getPath();
        if (path == null) {
            return; // 用户取消
        }
        try {
            parseAndPlot(path);
        } catch (Exception e) {
            IJ.error("解析失败", e.getMessage());
            e.printStackTrace();
        }
    }

    private void parseAndPlot(String path) throws Exception {
        ArrayList<Sample> samples = new ArrayList<>();
        ArrayList<Stats> stats = new ArrayList<>();
        double t0 = Double.NaN;
        double sumR2 = 0.0;

        // 不依赖中文表头判断数据起始（避免 CSV 编码与运行 JVM 默认编码不一致导致误判）。
        // 规则：以 '"' 开头的行尝试按采样数据解析；其余行尝试按汇总统计行解析。
        BufferedReader br = new BufferedReader(new FileReader(path));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("\"")) {
                // 采样数据行
                try {
                    Sample s = parseSample(line);
                    if (Double.isNaN(t0)) {
                        t0 = s.t;
                    }
                    s.t -= t0;
                    sumR2 += s.r * s.r;
                    samples.add(s);
                } catch (Exception ex) {
                    IJ.log("跳过无法解析的数据行: " + line);
                }
                continue;
            }
            // 尝试按汇总统计行解析（轴, 8 个数值）
            String[] p = line.split(",");
            if (p.length >= 8) {
                try {
                    Stats s = new Stats();
                    s.axis = p[0].trim();
                    s.max = Double.parseDouble(p[1].trim());
                    s.mean = Double.parseDouble(p[2].trim());
                    s.range = Double.parseDouble(p[3].trim());
                    s.std = Double.parseDouble(p[4].trim());
                    s.rms = Double.parseDouble(p[5].trim());
                    s.pointing = Double.parseDouble(p[6].trim());
                    s.position = Double.parseDouble(p[7].trim());
                    stats.add(s);
                } catch (NumberFormatException ignored) {
                    // 不是汇总统计行，忽略
                }
            }
        }
        br.close();

        if (samples.isEmpty()) {
            IJ.error("未从文件中解析到任何采样数据。");
            return;
        }

        double rms = Math.sqrt(sumR2 / samples.size());
        plotTimeTrace(samples);
        plotScatter(samples, rms);
        logStats(stats, rms);
    }

    /** 解析一行采样数据："日期, 时间",X,Y,偏移。 */
    private Sample parseSample(String line) throws Exception {
        if (!line.startsWith("\"")) {
            throw new Exception("数据行格式不符（缺少引号时间字段）");
        }
        int end = line.indexOf('"', 1);
        if (end < 0) {
            throw new Exception("时间字段引号不匹配");
        }
        String timeField = line.substring(1, end);
        String rest = line.substring(end + 1);
        String[] v = rest.split(",");
        if (v.length < 4) {
            throw new Exception("数据列不足");
        }
        Sample s = new Sample();
        s.x = Double.parseDouble(v[1].trim());
        s.y = Double.parseDouble(v[2].trim());
        s.r = Double.parseDouble(v[3].trim());

        // 解析时间部分 "2026-10-27, 14:17:13.073" -> 取最后的 HH:MM:SS.mmm
        String[] tp = timeField.split(",");
        String tstr = tp[tp.length - 1].trim();
        String[] hms = tstr.split(":");
        double sec = Integer.parseInt(hms[0]) * 3600
                   + Integer.parseInt(hms[1]) * 60
                   + Double.parseDouble(hms[2]);
        s.t = sec;
        return s;
    }

    /** 图1：偏移随时间变化（X / Y / 合成）。 */
    private void plotTimeTrace(ArrayList<Sample> samples) {
        int n = samples.size();
        double[] t = new double[n];
        double[] x = new double[n];
        double[] y = new double[n];
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            t[i] = s.t;
            x[i] = s.x;
            y[i] = s.y;
            r[i] = s.r;
        }

        BeamPlot plot = new BeamPlot("Beam Pointing - Time Trace", "Time / s", "Offset / um");
        plot.addLine(t, x, Color.RED, "Offset X");
        plot.addLine(t, y, Color.GREEN, "Offset Y");
        plot.addLine(t, r, Color.BLUE, "Offset (combined)");
        plot.setLegend(true);
        plot.showPlot();
    }

    /** 图2：X–Y 散点分布图（含原点参考十字）。 */
    private void plotScatter(ArrayList<Sample> samples, double rms) {
        int n = samples.size();
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            x[i] = s.x;
            y[i] = s.y;
        }

        BeamPlot plot = new BeamPlot("Beam Pointing - X-Y Scatter", "Offset X / um", "Offset Y / um");
        plot.addScatter(x, y, Color.BLUE, null);
        plot.setOriginCross(true);
        plot.setAnnotation("RMS offset \u2248 " + String.format("%.2f", rms) + " um");
        plot.showPlot();
    }

    /** 将汇总统计与计算得到的 RMS 偏移输出到 Log 窗口。 */
    private void logStats(ArrayList<Stats> stats, double rms) {
        StringBuilder sb = new StringBuilder("=== Laser Beam Pointing Stability ===\n");
        sb.append(String.format("RMS offset (computed) = %.3f um\n", rms));
        if (stats.isEmpty()) {
            sb.append("(CSV 中未找到汇总统计表)\n");
        }
        for (Stats s : stats) {
            String name = s.axis.isEmpty() ? "Combined" : s.axis;
            sb.append(String.format(
                "%s: max=%.3f mean=%.3f range=%.3f std=%.3f rms=%.3f pointing=%.3f urad position=%.3f um\n",
                name, s.max, s.mean, s.range, s.std, s.rms, s.pointing, s.position));
        }
        IJ.log(sb.toString());
    }
}
