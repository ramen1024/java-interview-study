package com.jis.backup.controller;

import com.jis.backup.dto.BackupSummaryVO;
import com.jis.backup.dto.BackupVO;
import com.jis.backup.service.BackupService;
import com.jis.common.Result;
import com.jis.common.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "数据备份", description = "导出与恢复当前用户的学习数据")
@RestController
@RequestMapping("/api/backup")
@RequiredArgsConstructor
public class BackupController {

    private final BackupService backupService;

    @Operation(
            summary = "导出学习数据",
            description = "复习进度、笔记、收藏、答题记录与每日统计；关联用业务键（slug / qKey），不含自增 id"
    )
    @GetMapping("/export")
    public Result<BackupVO> export() {
        return Result.success(backupService.export(UserContext.requireUserId()));
    }

    @Operation(
            summary = "导入学习数据",
            description = "按业务键幂等恢复；已删除的卡片/题目会跳过并计入警告，不会整单失败"
    )
    @PostMapping("/import")
    public Result<BackupSummaryVO> importBackup(@RequestBody BackupVO backup) {
        return Result.success(
                backupService.importBackup(UserContext.requireUserId(), backup)
        );
    }
}
