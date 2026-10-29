# LinHua —— ImageJ 工具集合插件

基于 Maven 的 ImageJ1 插件组。顶层菜单为 `Plugins>LinHua`，每个工具放在各自的子目录下。

## 目录结构

```
linhua_imj/
├── pom.xml                      # 依赖 net.imagej:ij 1.53t，Java 8
├── deploy.ps1                   # 一键部署脚本（见下方「构建与部署」）
├── run.bat / run.sh             # 本地编译并运行（需 Maven）
└── src/main/
    ├── java/com/linhua/
    │   ├── plot/                # 画图模块：通用自绘图表库 + 各 Plot 类功能
    │   │   ├── Plot.java          #   通用画图库（复原/缩放/平移/导出 PNG·SVG，matplotlib 风格）
    │   │   ├── SvgGraphics.java    #   Graphics2D -> SVG 桥接（Plot 内部使用）
    │   │   ├── PlotUtil.java       #   配色/标记分配、刻度数字格式、统一风格应用、统一文件选择（多选对话框）
    │   │   ├── BeamPointing_.java  #   功能：Beam Pointing 单次测量
    │   │   ├── BeamPointingCompare_.java #   功能：Beam Pointing 多文件对比（多选对话框，按相对时间叠加，可选中心对齐；散点可选空心标记）
    │   │   ├── BeamPointingCore.java #   共用：CSV 解析 + 数据载体（单跑与对比共用）
    │   │   ├── M2_.java            #   功能：M2 光束质量因子
    │   │   ├── Multiline1_.java    #   功能：多线图（同 X，多选对话框）
    │   │   ├── Multiline2_.java    #   功能：多线图（异 X，多选对话框）
    │   │   ├── Multifitting1_.java #   功能：多组拟合（同 X，gaussian/sech2/small_G）
    │   │   ├── Multifitting2_.java #   功能：多组拟合（异 X）
    │   │   ├── OutputPower_.java   #   功能：输出功率曲线
    │   │   ├── PulseDuration_.java #   功能：脉冲时长（归一化轨迹）
    │   │   ├── PulseDuration1_.java#   功能：脉冲时长（含 gaussian/sech2 拟合 + FWHM）
    │   │   ├── PowerStability_.java#   功能：功率稳定性（RMS 抖动标注）
    │   │   ├── Spectrum_.java      #   功能：光谱（min-max 归一化）
    │   │   ├── Absorption_.java    #   功能：吸收效率/吸收功率密度 + 写回 x4、π偏+σ偏
    │   │   └── Pabs_lnG_.java      #   功能：吸收截面/荧光寿命拟合 + 写回 Pabs/G/lnG
    │   ├── fit/                  # 曲线拟合
    │   │   ├── CurveFitter.java    #   通用 Levenberg–Marquardt 非线性最小二乘
    │   │   └── Models.java         #   gaussian / sech2 / small_G / Pabs_lnG 模型
    │   └── io/                   # 文件读写（纯 JDK，无第三方依赖）
    │       ├── XlsxReader.java     #   极简 xlsx 读取器
    │       ├── XlsxWriter.java     #   极简 xlsx 写出器（写回计算列）
    │       └── Table.java          #   XlsxReader 结果的列访问封装
    │   └── <yourtool>/          # 今后新增工具：在此加子目录
    │       └── Xxx_.java
    └── resources/
        └── plugins.config        # 菜单清单（Plugins>LinHua > ...）
```

## 新增一个工具

1. 在 `src/main/java/com/linhua/<yourtool>/` 下新建命令类，类名以 `_` 结尾、实现 `ij.plugin.PlugIn`，
   例如 `com.linhua.measure.Measure_.java`。
2. 在 `src/main/resources/plugins.config` 加一行：
   - 与现有命令**同级**（直接挂在 LinHua 下）：
     ```
     Plugins>LinHua, "显示名", com.linhua.<yourtool>.Xxx_
     ```
   - 或归到 LinHua 下的**子菜单**（自动生成子菜单）：
     ```
     Plugins>LinHua><yourtool>, "显示名", com.linhua.<yourtool>.Xxx_
     ```

> ⚠️ **菜单分隔符 `>` 两侧不要加空格！** ImageJ 用 `startsWith("Plugins>")` 这类判断识别子菜单，
> 写成 `Plugins > LinHua`（带空格）会被当成普通菜单名，结果工具直接挂在 `Plugins` 顶级、而 `LinHua`
> 子菜单根本不生成。正确写法一律是 **`Plugins>LinHua`（无空格）**，多级子菜单同理：`Plugins>LinHua><yourtool>`。
> 这是本项目踩过的真实坑，务必照此书写。

## 构建与部署（标准方法）

> 以后说「部署」即按此流程执行：编译整个源码树 → 打 jar → 复制到 ImageJ 的 plugins 目录。

**关键路径（固定）**
- ImageJ 根目录：`C:\Users\hual\ij154-win-java8\ImageJ`
- `ij.jar`：`C:\Users\hual\ij154-win-java8\ImageJ\ij.jar`
- 部署目标：`C:\Users\hual\ij154-win-java8\ImageJ\plugins`
- 编译用 JDK：`C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot`（含 `javac` / `jar`）

**步骤（无需联网，直接对本地 ij.jar 编译）**
1. 用本机 `javac` 以 `--release 8` 递归编译 `src/main/java` 下的全部 `.java`，
   classpath 指向本地 `ij.jar`。
2. 把 `src/main/resources/plugins.config` 拷进编译输出目录（jar 根目录需要它来生成菜单）。
3. 用 `jar` 打成 `LinHua_VyyMMdd.jar`（日期戳为当日 `yyMMdd`）。
4. 将 jar 复制到 `C:\Users\hual\ij154-win-java8\ImageJ\plugins`。
5. 若 ImageJ 已在运行，重启一次以加载新 jar；菜单 `Plugins>LinHua > ...` 即出现。

**一键脚本**
在 `linhua_imj` 目录下执行：

```powershell
powershell -ExecutionPolicy Bypass -File deploy.ps1
```

脚本会自动完成上述 1–4 步（使用独立干净目录 `target\build`，避免混入旧 class），
并把 jar 覆盖部署到 plugins 目录。

## 本地运行（可选）

```bat
run.bat        # Windows（需 Maven + 联网拉取依赖）
./run.sh       # Linux / macOS
```

生成的 jar 位于 `target/LinHua_VyyMMdd.jar`，放入 ImageJ/Fiji 的 `plugins/` 目录即插即用。
