package com.jis.quiz.controller;

import java.util.List;

import com.jis.common.Result;
import com.jis.common.UserContext;
import com.jis.quiz.dto.QuizQuestionVO;
import com.jis.quiz.dto.QuizSubmitRequest;
import com.jis.quiz.dto.QuizSubmitResultVO;
import com.jis.quiz.service.QuizService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "自测", description = "随机抽题、批量判分、错题本")
@Validated
@RestController
@RequestMapping("/api/quiz")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;

    @Operation(
            summary = "随机抽题",
            description = "返回的题目不含答案与解析，提交判分后才会下发"
    )
    @GetMapping("/questions")
    public Result<List<QuizQuestionVO>> draw(
            @RequestParam(required = false) String moduleSlug,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @Min(1) @Max(50) Integer count
    ) {
        return Result.success(
                quizService.draw(UserContext.requireUserId(), moduleSlug, type, count)
        );
    }

    @Operation(
            summary = "批量提交判分",
            description = "多选题忽略作答顺序；挖空题忽略大小写与首尾空白，逐空给出对错"
    )
    @PostMapping("/submit")
    public Result<QuizSubmitResultVO> submit(@Valid @RequestBody QuizSubmitRequest request) {
        return Result.success(
                quizService.submit(UserContext.requireUserId(), request)
        );
    }

    @Operation(
            summary = "错题本",
            description = "只收录「最近一次作答错误」的题目，答对后自动移出"
    )
    @GetMapping("/wrong")
    public Result<List<QuizQuestionVO>> wrongBook() {
        return Result.success(quizService.wrongBook(UserContext.requireUserId()));
    }
}
