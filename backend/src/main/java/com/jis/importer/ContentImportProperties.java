package com.jis.importer;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内容导入配置，对应 {@code jis.content.*}。
 */
@ConfigurationProperties(prefix = "jis.content")
public record ContentImportProperties(

        String root,

        boolean autoImport
) {

    private static final String DEFAULT_ROOT = "../content";

    /**
     * 解析成绝对路径。相对路径按进程工作目录解析，
     * 这样 {@code mvn spring-boot:run} 在 backend/ 下执行时能指向仓库根的 content/。
     */
    public Path resolvedRoot() {
        String configured = (root == null || root.isBlank()) ? DEFAULT_ROOT : root;

        return Paths.get(configured)
                .toAbsolutePath()
                .normalize();
    }
}
