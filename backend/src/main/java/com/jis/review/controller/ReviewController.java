package com.jis.review.controller;

import com.jis.common.Result;
import com.jis.common.UserContext;
import com.jis.review.dto.RateRequest;
import com.jis.review.dto.RateResultVO;
import com.jis.review.dto.ReviewQueueVO;
import com.jis.review.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "复习", description = "间隔重复队列与评分")
@Validated
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(
            summary = "今日复习队列",
            description = "到期卡（最逾期优先）+ 每日配额内的新卡。新卡配额用于避免一次性面对全部内容"
    )
    @GetMapping("/queue")
    public Result<ReviewQueueVO> queue(
            @RequestParam(required = false) String moduleSlug,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit
    ) {
        return Result.success(
                reviewService.buildQueue(UserContext.requireUserId(), moduleSlug, limit)
        );
    }

    @Operation(
            summary = "提交复习评分",
            description = "1 不会 / 2 模糊 / 3 会讲 / 4 轻松。返回新的稳定性、难度与下次到期时间"
    )
    @PostMapping("/cards/{slug}/rate")
    public Result<RateResultVO> rate(
            @PathVariable String slug,
            @Valid @RequestBody RateRequest request
    ) {
        return Result.success(
                reviewService.rate(UserContext.requireUserId(), slug, request)
        );
    }

    @Operation(
            summary = "重置某张卡的复习进度",
            description = "回到「新卡」状态；复习日志会保留，用于统计与后续算法调参"
    )
    @DeleteMapping("/cards/{slug}/progress")
    public Result<Void> reset(@PathVariable String slug) {
        reviewService.reset(UserContext.requireUserId(), slug);

        return Result.success();
    }
}
