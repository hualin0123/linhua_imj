package com.linhua.plot;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Beam Pointing 的共用数据核心：CSV 解析 + 数据载体。
 * 单文件入口（BeamPointing_）与多文件对比入口（BeamPointingCompare_）共用，避免重复解析逻辑。
 */
public final class BeamPointingCore {

    /** 单个采样点。 */
    public static class Sample {
        double t;  // 距首个采样的秒数（相对时间）
        double x;  // 偏移X / μm
        double y;  // 偏移Y / μm
        double r;  // 偏移(合成) / μm
    }

    /** 汇总统计的一行（按轴）。 */
    public static class Stats {
        String axis;
        double max, mean, range, std, rms, pointing, position;
    }

    /** 一次测量解析后的结果。 */
    public static class Data {
        final List<Sample> samples;
        final List<Stats> stats;
        final double rms;
        Data(List<Sample> samples, List<Stats> stats, double rms) {
            this.samples = samples;
            this.stats = stats;
            this.rms = rms;
        }
    }

    private BeamPointingCore() { }

    /** 解析一个 Beam Pointing CSV，返回采样点、汇总统计与计算得到的 RMS 偏移。 */
    public static Data load(String path) throws Exception {
        List<Sample> samples = new ArrayList<>();
        List<Stats> stats = new ArrayList<>();
        double t0 = Double.NaN;
        double sumR2 = 0.0;

        BufferedReader br = new BufferedReader(new FileReader(path));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("\"")) {
                try {
                    Sample s = parseSample(line);
                    if (Double.isNaN(t0)) {
                        t0 = s.t;
                    }
                    s.t -= t0;
                    sumR2 += s.r * s.r;
                    samples.add(s);
                } catch (Exception ex) {
                    IJlog("跳过无法解析的数据行: " + line);
                }
                continue;
            }
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
            throw new Exception("未从文件中解析到任何采样数据。");
        }
        double rms = Math.sqrt(sumR2 / samples.size());
        return new Data(samples, stats, rms);
    }

    /** 解析一行采样数据："日期, 时间",X,Y,偏移。 */
    private static Sample parseSample(String line) throws Exception {
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

        String[] tp = timeField.split(",");
        String tstr = tp[tp.length - 1].trim();
        String[] hms = tstr.split(":");
        double sec = Integer.parseInt(hms[0]) * 3600
                   + Integer.parseInt(hms[1]) * 60
                   + Double.parseDouble(hms[2]);
        s.t = sec;
        return s;
    }

    private static void IJlog(String msg) {
        // 延迟到运行时再引用 ij.IJ，避免核心类与 UI 强耦合
        ij.IJ.log(msg);
    }
}
