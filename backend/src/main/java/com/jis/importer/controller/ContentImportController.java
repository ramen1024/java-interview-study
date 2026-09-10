package com.jis.importer.controller;

import com.jis.common.Result;
import com.jis.importer.ContentImportService;
import com.jis.importer.dto.ImportResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手动触发内容导入。
 *
 * <p>改完 Markdown 不必重启应用，调这个接口即可生效。
 * 由 Redis 锁保证同一时刻只有一次导入在跑。
 */
@Tag(name = "内容导入", description = "从 content/ 目录重新导入 Markdown 内容")
@RestController
@RequestMapping("/api/admin/content")
@RequiredArgsConstructor
public class ContentImportController {

    private final ContentImportService contentImportService;

    @Operation(summary = "重新导入全部内容", description = "幂等；不会影响任何用户的学习进度")
    @PostMapping("/import")
    public Result<ImportResultVO> importAll() {
        return Result.success(contentImportService.importAll());
    }
}
