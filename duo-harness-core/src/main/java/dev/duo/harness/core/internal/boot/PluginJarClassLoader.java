package dev.duo.harness.core.internal.boot;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.duo.harness.core.api.PluginException;

/**
 * 插件包类加载器（ADR-0037 决策二）：每个插件包（fat-jar）一个实例，
 * <b>自优先</b>委派——包内类一律取包内副本，包内没有的（JDK、duo-harness-core
 * 的 Plugin/Context API）经 parent 解析。自优先是插件包隔离的根基：与宿主或
 * 其他包同 FQCN 的类互不可见，卸载 = 容器拔除 + 本加载器 close + 丢弃。
 *
 * <p>约定：插件包不得打入 duo-harness-core 的宿主 API 类（自优先会取到包内
 * 副本，与宿主同类不同器）——交货文档明示。</p>
 *
 * <p>public 供 BootLoader（boot 行装载）与插件中心（运行期安装，M35 工单 05）
 * 复用；位于 internal.boot 与 BootLoader 同包。</p>
 */
public final class PluginJarClassLoader extends URLClassLoader {

    private PluginJarClassLoader(Path jar, ClassLoader parent) throws IOException {
        super("plugin-jar-" + jar, new URL[] {jar.toUri().toURL()}, parent);
    }

    /**
     * 打开插件包：文件不存在即刻点名（fail-fast，不等类加载时才炸）。
     * 文件存在但内容损坏的，延迟到类加载时以点名异常呈现。
     */
    public static PluginJarClassLoader open(Path jar) {
        if (!Files.isRegularFile(jar)) {
            throw new PluginException("插件包文件不存在: " + jar);
        }
        try {
            return new PluginJarClassLoader(jar, PluginJarClassLoader.class.getClassLoader());
        } catch (IOException e) {
            throw new PluginException("插件包打开失败: " + jar + "（" + e.getMessage() + "）", e);
        }
    }

    /**
     * 装载插件包的入口类：强制从本包解析（{@link #loadClass} 自优先）并初始化。
     */
    public Class<?> loadPluginClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, this);
    }

    /**
     * 自优先委派：先查本包（findClass），包内没有才放行 parent——与标准
     * URLClassLoader 的 parent-first 相反，这是"同 FQCN 互不可见"的实现点。
     * java.* 等包内不存在的类自然回落 parent（findClass 抛 CNFE 后走委派）。
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException e) {
                    c = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }
}
