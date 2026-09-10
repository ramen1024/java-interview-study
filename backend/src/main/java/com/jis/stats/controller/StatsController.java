package com.jis.stats.controller;

import java.util.List;

import com.jis.common.Result;
import com.jis.common.UserContext;
import com.jis.stats.dto.HeatmapVO;
import com.jis.stats.dto.ModuleMasteryVO;
import com.jis.stats.dto.OverviewVO;
import com.jis.stats.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "统计", description = "学习概览、热力图、模块掌握度")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @Operation(summary = "学习概览", description = "到期数、今日复习与答题、连续打卡、掌握卡片数")
    @GetMapping("/overview")
    public Result<OverviewVO> overview() {
        return Result.success(statsService.overview(UserContext.requireUserId()));
    }

    @Operation(summary = "年度热力图", description = "只返回有学习记录的日期，空白格由前端补齐")
    @GetMapping("/heatmap")
    public Result<HeatmapVO> heatmap(@RequestParam(required = false) Integer year) {
        return Result.success(statsService.heatmap(UserContext.requireUserId(), year));
    }

    @Operation(summary = "模块掌握度", description = "两级结构，父模块数值含子模块，供雷达图与明细共用")
    @GetMapping("/modules")
    public Result<List<ModuleMasteryVO>> moduleMastery() {
        return Result.success(statsService.moduleMastery(UserContext.requireUserId()));
    }
}
