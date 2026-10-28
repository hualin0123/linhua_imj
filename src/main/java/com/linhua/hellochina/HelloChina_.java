package com.linhua.hellochina;

import ij.IJ;
import ij.plugin.PlugIn;

/**
 * Hello China! —— linhua 插件组的示例子命令，与 Hello World 同级（均位于 LinHua 菜单下）。
 *
 * <p>位于 {@code com.linhua.hellochina} 子包。新增同级命令时，
 * 在 {@code com.linhua.<yourtool>} 下仿照本类写命令类，并在 plugins.config 加一行
 * <pre>Plugins > LinHua, "显示名", com.linhua.&lt;yourtool&gt;.Xxx_</pre></p>
 *
 * @author Hua Lin
 * @since 0.1.0
 */
public class HelloChina_ implements PlugIn {

    /** 独立运行入口，便于在 ImageJ 外部直接测试。 */
    public static void main(String[] args) {
        new HelloChina_().run("");
    }

    /** ImageJ 插件入口。 */
    @Override
    public void run(String arg) {
        IJ.showMessage("linhua / Hello China!", "Hello China!");
    }
}
