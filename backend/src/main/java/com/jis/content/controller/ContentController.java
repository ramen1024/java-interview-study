package com.jis.content.controller;

import java.util.List;

import com.jis.common.PageResult;
import com.jis.common.Result;
import com.jis.common.UserContext;
import com.jis.content.dto.KnowledgePointListItemVO;
import com.jis.content.dto.KnowledgePointQuery;
import com.jis.content.dto.KnowledgePointView;
import com.jis.content.dto.ModuleTreeVO;
import com.jis.content.dto.SaveNoteRequest;
import com.jis.content.dto.SearchHitVO;
import com.jis.content.service.ContentQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识内容接口。
 *
 * <p>对外一律使用业务键（卡片 slug、模块 slug），不暴露自增数字 id：
 * URL 可读，也彻底避开了 BIGINT 在 JavaScript 里丢精度的问题。
 */
@Tag(name = "内容", description = "知识体系、卡片、搜索、收藏与笔记")
@Validated
@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
public class ContentController {

    private final ContentQueryService contentQueryService;

    @Operation(summary = "知识体系模块树", description = "两级模块树，cardCount 含子模块的卡片数")
    @GetMapping("/modules")
    public Result<List<ModuleTreeVO>> modules() {
        return Result.success(contentQueryService.moduleTree());
    }

    @Operation(summary = "卡片列表", description = "可按模块、标签、难度、热度过滤并分页")
    @GetMapping("/cards")
    public Result<PageResult<KnowledgePointListItemVO>> cards(
            @RequestParam(required = false) String moduleSlug,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) @Min(1) @Max(3) Integer difficulty,
            @RequestParam(required = false) @Min(1) @Max(3) Integer frequency,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer size
    ) {
        KnowledgePointQuery query = new KnowledgePointQuery(
                moduleSlug,
                tag,
                difficulty,
                frequency,
                sortBy,
                page,
                size
        );

        return Result.success(
                contentQueryService.listKnowledgePoints(query, UserContext.requireUserId())
        );
    }

    @Operation(summary = "卡片详情", description = "六段式正文 + 追问链树 + 关联卡片 + 我的笔记收藏与复习状态")
    @GetMapping("/cards/{slug}")
    public Result<KnowledgePointView> card(@PathVariable String slug) {
        return Result.success(
                contentQueryService.getCard(slug, UserContext.requireUserId())
        );
    }

    @Operation(summary = "搜索知识点", description = "MySQL ngram 全文索引，短查询串回落 LIKE")
    @GetMapping("/search")
    public Result<List<SearchHitVO>> search(
            @RequestParam @Size(min = 1, max = 50, message = "搜索词长度需在 1~50 之间") String keyword
    ) {
        return Result.success(contentQueryService.search(keyword));
    }

    @Operation(summary = "热搜词", description = "合并最近 7 天的搜索词频次")
    @GetMapping("/search/hot")
    public Result<List<String>> hotKeywords() {
        return Result.success(contentQueryService.hotKeywords());
    }

    @Operation(summary = "收藏或取消收藏")
    @PutMapping("/cards/{slug}/favorite")
    public Result<Boolean> favorite(
            @PathVariable String slug,
            @RequestParam boolean favorite
    ) {
        return Result.success(
                contentQueryService.setFavorite(slug, UserContext.requireUserId(), favorite)
        );
    }

    @Operation(summary = "我的收藏")
    @GetMapping("/favorites")
    public Result<List<KnowledgePointListItemVO>> favorites() {
        return Result.success(
                contentQueryService.listFavorites(UserContext.requireUserId())
        );
    }

    @Operation(summary = "保存卡片笔记")
    @PutMapping("/cards/{slug}/note")
    public Result<Void> saveNote(
            @PathVariable String slug,
            @Valid @RequestBody SaveNoteRequest request
    ) {
        contentQueryService.saveNote(slug, UserContext.requireUserId(), request.contentMd());

        return Result.success();
    }
}
