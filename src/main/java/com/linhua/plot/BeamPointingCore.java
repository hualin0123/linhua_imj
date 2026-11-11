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
        /** 首个采样点的绝对时间（含日期的秒，与 {@code Sample.t} 同一坐标；{@code Sample.t} 是相对它的偏移）。 */
        final double absStart;
        Data(List<Sample> samples, List<Stats> stats, double rms, double absStart) {
            this.samples = samples;
            this.stats = stats;
            this.rms = rms;
            this.absStart = absStart;
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
        return new Data(samples, stats, rms, t0);
    }

    /** 质心位置偏移大小（μm）：hypot(meanX, meanY)。用于替代 CSV 中的 position 统计。 */
    public static double positionUm(List<Sample> samples) {
        double sx = 0, sy = 0;
        int m = samples.size();
        for (Sample s : samples) {
            sx += s.x;
            sy += s.y;
        }
        return Math.hypot(sx / m, sy / m);
    }

    /** 由质心位置（μm）与传播距离（mm）换算指向角（μrad）。距离 <= 0 时返回 NaN。 */
    public static double pointingUrad(double positionUm, double distMm) {
        return distMm > 0 ? positionUm * 1000.0 / distMm : Double.NaN;
    }

    /** 关于质心的二维 RMS 抖动（μm）：sqrt(mean((x-x̄)²+(y-ȳ)²))。即标准指向稳定性所用的空间 RMS。 */
    public static double centroidRmsUm(List<Sample> samples) {
        double sx = 0, sy = 0;
        int m = samples.size();
        for (Sample s : samples) {
            sx += s.x;
            sy += s.y;
        }
        double mx = sx / m, my = sy / m;
        double ss = 0;
        for (Sample s : samples) {
            double dx = s.x - mx, dy = s.y - my;
            ss += dx * dx + dy * dy;
        }
        return Math.sqrt(ss / m);
    }

    /**
     * 给定一组样本，返回 {xmean, ymean, rmean, crms}（绕质心的二维 RMS 抖动）。
     * 纯计算、无副作用，供「全部」与「分阶段」统计共用，也可被单测直接断言。
     */
    public static double[] centroidStats(List<Sample> samples) {
        int m = samples.size();
        if (m == 0) {
            return new double[]{0, 0, 0, 0};
        }
        double sx = 0, sy = 0, sr = 0;
        for (Sample s : samples) {
            sx += s.x;
            sy += s.y;
            sr += s.r;
        }
        double xm = sx / m, ym = sy / m, rm = sr / m;
        double ss = 0;
        for (Sample s : samples) {
            double dx = s.x - xm, dy = s.y - ym;
            ss += dx * dx + dy * dy;
        }
        double cr = Math.sqrt(ss / m);
        return new double[]{xm, ym, rm, cr};
    }

    /**
     * 按绝对时间窗口 [a, b) 切片：返回新样本列表，其 {@code t} 重基为相对窗口起点的
     * 「阶段相对时间」（便于阶段内从 0 起画时间轨迹）。样本本身（x/y/r）不被修改。
     * 纯计算、无副作用，可供单测直接断言。
     *
     * @param samples  相对时间样本（{@code Sample.t} = absStart + s.t 为绝对时间）
     * @param absStart 本文件首个采样的绝对时间
     * @param a, b     绝对时间窗口 [a, b)
     */
    public static List<Sample> slicePhase(List<Sample> samples, double absStart, double a, double b) {
        List<Sample> out = new ArrayList<>();
        for (Sample s : samples) {
            double abs = absStart + s.t;
            if (abs >= a && abs < b) {
                Sample c = new Sample();
                c.x = s.x;
                c.y = s.y;
                c.r = s.r;
                c.t = abs - a; // 重基为阶段相对时间
                out.add(c);
            }
        }
        return out;
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
        if (tp.length < 2) {
            throw new Exception("时间字段缺少日期或时间（应为 \"YYYY-MM-DD, HH:MM:SS\"）");
        }
        String[] d = tp[0].trim().split("-");
        if (d.length < 3) {
            throw new Exception("日期格式应为 YYYY-MM-DD");
        }
        int Y = Integer.parseInt(d[0]), M = Integer.parseInt(d[1]), D = Integer.parseInt(d[2]);
        String[] hms = tp[1].trim().split(":");
        if (hms.length < 3) {
            throw new Exception("时间格式应为 HH:MM:SS");
        }
        double tod = Integer.parseInt(hms[0]) * 3600
                   + Integer.parseInt(hms[1]) * 60
                   + Double.parseDouble(hms[2]);
        // 绝对秒(含日期)：跨午夜单调递增，避免"当天秒数"在午夜回绕导致 t 变负/乱序
        s.t = ymdToDays(Y, M, D) * 86400.0 + tod;
        return s;
    }

    /** 公历日期 -> 距 1970-01-01 的天数(Howard Hinnant 算法，单调且对跨午夜/跨日正确)。 */
    public static long ymdToDays(int y, int m, int d) {
        int yy = (m <= 2) ? y - 1 : y;
        int mm = (m <= 2) ? m + 12 : m;
        long era = (yy >= 0 ? yy : yy - 399) / 400;
        long yoe = yy - era * 400;                       // [0, 399]
        long doy = (153L * (mm - 3) + 2) / 5 + d - 1;    // [0, 365]
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy; // [0, 146096]
        return era * 146097L + doe - 719468L;             // 距 1970-01-01 的天数
    }

    private static void IJlog(String msg) {
        // 延迟到运行时再引用 ij.IJ，避免核心类与 UI 强耦合
        ij.IJ.log(msg);
    }
}
