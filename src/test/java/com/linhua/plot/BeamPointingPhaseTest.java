package com.linhua.plot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * 分阶段对比的纯逻辑自验证测试（合成数据，无需真实 CSV）。
 * 覆盖 {@link BeamPointingCore#centroidStats}、{@link BeamPointingCore#slicePhase} 与
 * {@link BeamPointingPhaseCompare_#parseCuts}。详见仓库根目录 {@code VERIFICATION.md}。
 */
public class BeamPointingPhaseTest {

    // ---- centroidStats：{xmean, ymean, rmean, crms} ----
    @Test
    public void centroidStatsBasic() {
        List<BeamPointingCore.Sample> s = new ArrayList<>();
        add(s, 0, 0, 0);
        add(s, 2, 0, 0);
        add(s, -2, 0, 0);
        add(s, 0, 2, 0);
        add(s, 0, -2, 0);
        double[] st = BeamPointingCore.centroidStats(s);
        assertEquals("xmean=0", 0, st[0], 1e-9);
        assertEquals("ymean=0", 0, st[1], 1e-9);
        assertEquals("rmean=0", 0, st[2], 1e-9);
        // 绕质心 RMS = sqrt(16/5)
        assertEquals("crms = sqrt(16/5)", Math.sqrt(16.0 / 5.0), st[3], 1e-9);
    }

    @Test
    public void centroidStatsEmpty() {
        double[] st = BeamPointingCore.centroidStats(new ArrayList<>());
        assertEquals(0, st[0], 1e-9);
        assertEquals(0, st[1], 1e-9);
    }

    // ---- slicePhase：按绝对时间窗口 [a,b) 切片 + 重基为阶段相对时间 ----
    @Test
    public void slicePhaseInWindow() {
        // absStart=1000；相对 t = 0,100,200,300,400 → 绝对 1000,1100,1200,1300,1400
        List<BeamPointingCore.Sample> s = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            BeamPointingCore.Sample q = new BeamPointingCore.Sample();
            q.x = i;
            q.y = i;
            q.r = i;
            q.t = i * 100;
            s.add(q);
        }
        // 窗口 [1050, 1350) → 命中绝对 1100,1200,1300；起始重基 1050
        List<BeamPointingCore.Sample> out = BeamPointingCore.slicePhase(s, 1000, 1050, 1350);
        assertEquals(3, out.size());
        assertEquals(50, out.get(0).t, 1e-9);   // 1100-1050
        assertEquals(150, out.get(1).t, 1e-9);
        assertEquals(250, out.get(2).t, 1e-9);
        assertEquals(1, out.get(0).x, 1e-9);    // 值保持不变
        assertEquals(3, out.get(2).x, 1e-9);
    }

    @Test
    public void slicePhaseBoundsExclusiveEnd() {
        List<BeamPointingCore.Sample> s = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            BeamPointingCore.Sample q = new BeamPointingCore.Sample();
            q.x = i;
            q.y = 0;
            q.r = 0;
            q.t = i * 100;
            s.add(q);
        }
        // 起始闭区间：abs=1000(t=0) 应被包含；结束开区间：abs=1400(t=400) 应被排除
        List<BeamPointingCore.Sample> out = BeamPointingCore.slicePhase(s, 1000, 1000, 1400);
        assertEquals(4, out.size());
        assertEquals(0, out.get(0).t, 1e-9);
    }

    @Test
    public void slicePhaseEmpty() {
        List<BeamPointingCore.Sample> s = new ArrayList<>();
        BeamPointingCore.Sample q = new BeamPointingCore.Sample();
        q.t = 0;
        s.add(q);
        assertEquals(0, BeamPointingCore.slicePhase(s, 1000, 5000, 6000).size());
    }

    // ---- parseCuts：阶段分界解析 ----
    @Test
    public void parseCutsSameDay() throws Exception {
        long anchor = BeamPointingCore.ymdToDays(2026, 11, 10);
        double[] c = BeamPointingPhaseCompare_.parseCuts("11:20, 18:00", anchor);
        assertArrayEquals(new double[]{
                anchor * 86400.0 + 11 * 3600 + 20 * 60,
                anchor * 86400.0 + 18 * 3600
        }, c, 1e-9);
    }

    @Test
    public void parseCutsAbsoluteDateAndSorted() throws Exception {
        long anchor = BeamPointingCore.ymdToDays(2026, 11, 10);
        long d9 = BeamPointingCore.ymdToDays(2026, 11, 9);
        double[] c = BeamPointingPhaseCompare_.parseCuts("2026-11-10 11:20, 2026-11-09 18:00", anchor);
        assertEquals(2, c.length);
        // 应升序：较早的 11-09 18:00 在前
        assertEquals(d9 * 86400.0 + 18 * 3600, c[0], 1e-9);
        assertEquals(anchor * 86400.0 + 11 * 3600 + 20 * 60, c[1], 1e-9);
    }

    @Test
    public void parseCutsChineseCommaAndWhitespace() throws Exception {
        long anchor = BeamPointingCore.ymdToDays(2026, 11, 10);
        double[] c = BeamPointingPhaseCompare_.parseCuts("11:20， 18:00 ", anchor);
        assertEquals(2, c.length);
        assertArrayEquals(new double[]{
                anchor * 86400.0 + 11 * 3600 + 20 * 60,
                anchor * 86400.0 + 18 * 3600
        }, c, 1e-9);
    }

    @Test
    public void parseCutsEmpty() throws Exception {
        assertEquals(0, BeamPointingPhaseCompare_.parseCuts("", 100).length);
        assertEquals(0, BeamPointingPhaseCompare_.parseCuts("   ", 100).length);
    }

    // ---- parseTime：分隔符 / 容错 ----
    @Test
    public void parseTimeSlashAndJoined() throws Exception {
        long anchor = BeamPointingCore.ymdToDays(2026, 11, 10);
        assertEquals(anchor * 86400.0 + 11 * 3600 + 20 * 60,
                BeamPointingPhaseCompare_.parseTime("11:20", anchor), 1e-9);
        // 斜杠日期 + 时间
        assertEquals(anchor * 86400.0 + 11 * 3600 + 20 * 60,
                BeamPointingPhaseCompare_.parseTime("2026/11/10 11:20", anchor), 1e-9);
        // 斜杠日期无时间 -> 默认 00:00
        assertEquals(anchor * 86400.0,
                BeamPointingPhaseCompare_.parseTime("2026/11/10", anchor), 1e-9);
        // 日期与时间用 T 连接
        assertEquals(anchor * 86400.0 + 9 * 3600,
                BeamPointingPhaseCompare_.parseTime("2026-11-10T09:00", anchor), 1e-9);
    }

    // ---- 工具 ----
    private static void add(List<BeamPointingCore.Sample> list, double x, double y, double r) {
        BeamPointingCore.Sample s = new BeamPointingCore.Sample();
        s.x = x;
        s.y = y;
        s.r = r;
        list.add(s);
    }
}
