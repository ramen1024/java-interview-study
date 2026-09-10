package com.jis.importer;

import com.jis.importer.dto.ImportResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时自动导入内容。
 *
 * <p>导入失败**不能让应用起不来**：内容有问题时服务照常提供（用户还能复习
 * 已入库的卡片），把错误清楚地打出来即可。因此这里捕获所有异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentImportRunner implements ApplicationRunner {

    private final ContentImportProperties properties;
    private final ContentImportService contentImportService;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.autoImport()) {
            log.info("已关闭启动自动导入（jis.content.auto-import=false）");
            return;
        }

        log.info("开始导入内容，目录：{}", properties.resolvedRoot());

        try {
            ImportResultVO result = contentImportService.importAll();

            log.info(
                    "内容就绪：模块 {} 个，卡片 {} 张，追问 {} 条，题目 {} 道",
                    result.moduleCount(),
                    result.cardCount(),
                    result.followUpCount(),
                    result.questionCount()
            );

            if (result.hasErrors()) {
                log.warn("有 {} 个内容文件被跳过，详见上方导入错误日志", result.errors()
                        .size());
            }
        } catch (Exception e) {
            log.error("启动自动导入失败，服务继续启动；可在修正内容后调用导入接口重试", e);
        }
    }
}
