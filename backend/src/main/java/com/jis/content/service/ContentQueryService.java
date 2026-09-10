package com.jis.content.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.jis.common.BizException;
import com.jis.common.PageResult;
import com.jis.common.ResultCode;
import com.jis.content.cache.ContentCache;
import com.jis.content.dto.FollowUpVO;
import com.jis.content.dto.KnowledgePointDetailVO;
import com.jis.content.dto.KnowledgePointListItemVO;
import com.jis.content.dto.KnowledgePointQuery;
import com.jis.content.dto.KnowledgePointView;
import com.jis.content.dto.ModuleTreeVO;
import com.jis.content.dto.RelatedKpVO;
import com.jis.content.dto.SearchHitVO;
import com.jis.content.dto.UserCardStateVO;
import com.jis.content.entity.ContentModule;
import com.jis.content.entity.FollowUp;
import com.jis.content.entity.KnowledgePoint;
import com.jis.content.entity.KpRelation;
import com.jis.content.entity.UserFavorite;
import com.jis.content.entity.UserNote;
import com.jis.content.mapper.ContentModuleMapper;
import com.jis.content.mapper.FollowUpMapper;
import com.jis.content.mapper.KnowledgePointMapper;
import com.jis.content.mapper.KpRelationMapper;
import com.jis.content.mapper.UserFavoriteMapper;
import com.jis.content.mapper.UserNoteMapper;
import com.jis.quiz.entity.QuizQuestion;
import com.jis.quiz.mapper.QuizQuestionMapper;
import com.jis.review.entity.ReviewState;
import com.jis.review.mapper.ReviewStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentQueryService {

    private static final Duration MODULE_TREE_TTL = Duration.ofHours(2);
    private static final Duration CARD_TTL = Duration.ofMinutes(30);

    private static final String CACHE_NS_MODULE_TREE = "module-tree";
    private static final String CACHE_NS_CARD = "kp";

    /**
     * ngram_token_size 默认为 2，短于它的查询串无法被全文索引命中，需回落 LIKE。
     */
    private static final int MIN_FULLTEXT_LENGTH = 2;
    private static final int SEARCH_LIMIT = 30;
    private static final int SNIPPET_LENGTH = 80;

    private static final String HOT_KEYWORD_KEY_PREFIX = "jis:content:hot-keywords:";
    private static final int HOT_KEYWORD_DAYS = 7;
    private static final int HOT_KEYWORD_SIZE = 10;
    private static final Duration HOT_KEYWORD_TTL = Duration.ofDays(HOT_KEYWORD_DAYS + 1);
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ContentModuleMapper contentModuleMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final FollowUpMapper followUpMapper;
    private final KpRelationMapper kpRelationMapper;
    private final QuizQuestionMapper quizQuestionMapper;
    private final UserFavoriteMapper userFavoriteMapper;
    private final UserNoteMapper userNoteMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final ContentCache contentCache;
    private final StringRedisTemplate stringRedisTemplate;

    // ------------------------------------------------------------------
    // 模块树
    // ------------------------------------------------------------------

    public List<ModuleTreeVO> moduleTree() {
        return contentCache.getOrLoad(
                CACHE_NS_MODULE_TREE,
                "all",
                MODULE_TREE_TTL,
                new TypeReference<List<ModuleTreeVO>>() {
                },
                this::loadModuleTree
        );
    }

    private List<ModuleTreeVO> loadModuleTree() {
        List<ContentModule> modules = contentModuleMapper.selectList(
                Wrappers.<ContentModule>lambdaQuery()
                        .orderByAsc(ContentModule::getSort)
                        .orderByAsc(ContentModule::getId)
        );

        Map<Long, Integer> directCounts = loadModuleCardCounts();
        Map<Long, List<ContentModule>> childrenByParent = new LinkedHashMap<>();
        List<ContentModule> roots = new ArrayList<>();

        for (ContentModule module : modules) {
            if (isRoot(module.getParentId())) {
                roots.add(module);
                continue;
            }
            childrenByParent.computeIfAbsent(module.getParentId(), key -> new ArrayList<>())
                    .add(module);
        }

        return roots.stream()
                .map(root -> buildModuleNode(root, childrenByParent, directCounts))
                .toList();
    }

    /**
     * 父模块的卡片数是"自身 + 所有子模块"，否则一级模块会显示 0，
     * 而实际卡片都挂在二级模块下。
     */
    private ModuleTreeVO buildModuleNode(
            ContentModule module,
            Map<Long, List<ContentModule>> childrenByParent,
            Map<Long, Integer> directCounts
    ) {
        List<ModuleTreeVO> children = childrenByParent.getOrDefault(module.getId(), List.of())
                .stream()
                .map(child -> buildModuleNode(child, childrenByParent, directCounts))
                .toList();

        int cardCount = directCounts.getOrDefault(module.getId(), 0)
                + children.stream()
                .mapToInt(ModuleTreeVO::cardCount)
                .sum();

        return new ModuleTreeVO(
                module.getSlug(),
                module.getName(),
                module.getDescription(),
                module.getIcon(),
                module.getSort(),
                cardCount,
                children
        );
    }

    private Map<Long, Integer> loadModuleCardCounts() {
        QueryWrapper<KnowledgePoint> wrapper = new QueryWrapper<>();
        wrapper.select("module_id AS id", "COUNT(*) AS cnt")
                .groupBy("module_id");

        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : knowledgePointMapper.selectMaps(wrapper)) {
            Long moduleId = toLong(row.get("id"));
            if (moduleId != null) {
                counts.put(moduleId, toInt(row.get("cnt")));
            }
        }

        return counts;
    }

    // ------------------------------------------------------------------
    // 卡片列表
    // ------------------------------------------------------------------

    public PageResult<KnowledgePointListItemVO> listKnowledgePoints(
            KnowledgePointQuery query,
            Long userId
    ) {
        Set<Long> moduleIds = resolveModuleIds(query.moduleSlug());

        LambdaQueryWrapper<KnowledgePoint> wrapper = Wrappers.<KnowledgePoint>lambdaQuery()
                .in(moduleIds != null, KnowledgePoint::getModuleId, moduleIds)
                .eq(query.difficulty() != null, KnowledgePoint::getDifficulty, query.difficulty())
                .eq(query.frequency() != null, KnowledgePoint::getFrequency, query.frequency())
                .like(isPresent(query.tag()), KnowledgePoint::getTags, query.tag());

        applySort(wrapper, query.sortBy());

        Page<KnowledgePoint> page = knowledgePointMapper.selectPage(
                Page.of(query.pageOrDefault(), query.sizeOrDefault()),
                wrapper
        );

        List<KnowledgePoint> records = page.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(List.of(), page);
        }

        List<Long> kpIds = records.stream()
                .map(KnowledgePoint::getId)
                .toList();

        Map<Long, ContentModule> modules = loadModules(records);
        Map<Long, Integer> questionCounts = loadQuestionCounts(kpIds);
        Map<Long, ReviewState> reviewStates = loadReviewStates(userId, kpIds);

        List<KnowledgePointListItemVO> items = records.stream()
                .map(kp -> {
                    ReviewState state = reviewStates.get(kp.getId());

                    return new KnowledgePointListItemVO(
                            kp.getSlug(),
                            kp.getTitle(),
                            moduleSlug(modules, kp.getModuleId()),
                            moduleName(modules, kp.getModuleId()),
                            splitTags(kp.getTags()),
                            kp.getDifficulty(),
                            kp.getFrequency(),
                            questionCounts.getOrDefault(kp.getId(), 0),
                            state == null ? null : state.getState(),
                            state == null || state.getDueAt() == null ? null : state.getDueAt()
                                    .toString()
                    );
                })
                .toList();

        return PageResult.of(items, page);
    }

    private void applySort(LambdaQueryWrapper<KnowledgePoint> wrapper, String sortBy) {
        if ("frequency".equalsIgnoreCase(sortBy)) {
            wrapper.orderByDesc(KnowledgePoint::getFrequency)
                    .orderByAsc(KnowledgePoint::getSort);
            return;
        }

        if ("difficulty".equalsIgnoreCase(sortBy)) {
            wrapper.orderByAsc(KnowledgePoint::getDifficulty)
                    .orderByAsc(KnowledgePoint::getSort);
            return;
        }

        if ("title".equalsIgnoreCase(sortBy)) {
            wrapper.orderByAsc(KnowledgePoint::getTitle);
            return;
        }

        wrapper.orderByAsc(KnowledgePoint::getModuleId)
                .orderByAsc(KnowledgePoint::getSort);
    }

    // ------------------------------------------------------------------
    // 卡片详情
    // ------------------------------------------------------------------

    public KnowledgePointView getCard(String slug, Long userId) {
        KnowledgePointDetailVO card = contentCache.getOrLoad(
                CACHE_NS_CARD,
                slug,
                CARD_TTL,
                new TypeReference<KnowledgePointDetailVO>() {
                },
                () -> loadCard(slug)
        );

        if (card == null) {
            throw new BizException(ResultCode.KNOWLEDGE_POINT_NOT_FOUND);
        }

        return new KnowledgePointView(card, loadUserCardState(slug, userId));
    }

    private KnowledgePointDetailVO loadCard(String slug) {
        KnowledgePoint kp = knowledgePointMapper.selectOne(
                Wrappers.<KnowledgePoint>lambdaQuery()
                        .eq(KnowledgePoint::getSlug, slug)
        );
        if (kp == null) {
            return null;
        }

        ContentModule module = contentModuleMapper.selectById(kp.getModuleId());

        List<FollowUp> followUps = followUpMapper.selectList(
                Wrappers.<FollowUp>lambdaQuery()
                        .eq(FollowUp::getKpId, kp.getId())
                        .orderByAsc(FollowUp::getSort)
        );

        Long questionCount = quizQuestionMapper.selectCount(
                Wrappers.<QuizQuestion>lambdaQuery()
                        .eq(QuizQuestion::getKpId, kp.getId())
        );

        return new KnowledgePointDetailVO(
                kp.getSlug(),
                kp.getTitle(),
                module == null ? null : module.getSlug(),
                module == null ? null : module.getName(),
                splitTags(kp.getTags()),
                kp.getDifficulty(),
                kp.getFrequency(),
                kp.getElevatorAnswer(),
                kp.getDetailMd(),
                buildFollowUpTree(followUps),
                kp.getPitfallsMd(),
                kp.getBonusMd(),
                kp.getVersionDiffMd(),
                loadRelated(kp.getId()),
                questionCount == null ? 0 : questionCount.intValue(),
                kp.getUpdateTime() == null ? null : kp.getUpdateTime()
                        .toString()
        );
    }

    /**
     * 把扁平的追问链还原成树。第一层的 parentId 为 0。
     */
    private List<FollowUpVO> buildFollowUpTree(List<FollowUp> nodes) {
        if (nodes.isEmpty()) {
            return List.of();
        }

        Map<Long, List<FollowUp>> childrenByParent = new LinkedHashMap<>();
        for (FollowUp node : nodes) {
            childrenByParent.computeIfAbsent(node.getParentId(), key -> new ArrayList<>())
                    .add(node);
        }

        return buildFollowUpChildren(0L, childrenByParent);
    }

    private List<FollowUpVO> buildFollowUpChildren(
            Long parentId,
            Map<Long, List<FollowUp>> childrenByParent
    ) {
        return childrenByParent.getOrDefault(parentId, List.of())
                .stream()
                .map(node -> new FollowUpVO(
                        node.getQKey(),
                        node.getQuestion(),
                        node.getAnswerMd(),
                        node.getDepth(),
                        buildFollowUpChildren(node.getId(), childrenByParent)
                ))
                .toList();
    }

    private List<RelatedKpVO> loadRelated(Long kpId) {
        List<KpRelation> relations = kpRelationMapper.selectList(
                Wrappers.<KpRelation>lambdaQuery()
                        .eq(KpRelation::getFromKpId, kpId)
                        .orderByAsc(KpRelation::getSort)
        );
        if (relations.isEmpty()) {
            return List.of();
        }

        Set<Long> targetIds = new LinkedHashSet<>();
        relations.forEach(relation -> targetIds.add(relation.getToKpId()));

        Map<Long, KnowledgePoint> targets = knowledgePointMapper.selectByIds(targetIds)
                .stream()
                .collect(LinkedHashMap::new, (map, kp) -> map.put(kp.getId(), kp), Map::putAll);

        Map<Long, ContentModule> modules = loadModules(new ArrayList<>(targets.values()));

        List<RelatedKpVO> result = new ArrayList<>();
        for (KpRelation relation : relations) {
            KnowledgePoint target = targets.get(relation.getToKpId());
            if (target == null) {
                // 目标卡片在后续导入中被删除时会走到这里，跳过而不是给出死链
                log.debug("关联目标已不存在，relationId={} toKpId={}", relation.getId(), relation.getToKpId());
                continue;
            }

            result.add(new RelatedKpVO(
                    target.getSlug(),
                    target.getTitle(),
                    moduleName(modules, target.getModuleId()),
                    relation.getRelationType(),
                    target.getDifficulty(),
                    target.getFrequency()
            ));
        }

        return result;
    }

    private UserCardStateVO loadUserCardState(String slug, Long userId) {
        Long kpId = findKpId(slug);
        if (kpId == null) {
            return UserCardStateVO.empty();
        }

        UserFavorite favorite = userFavoriteMapper.selectOne(
                Wrappers.<UserFavorite>lambdaQuery()
                        .eq(UserFavorite::getUserId, userId)
                        .eq(UserFavorite::getKpId, kpId)
        );

        UserNote note = userNoteMapper.selectOne(
                Wrappers.<UserNote>lambdaQuery()
                        .eq(UserNote::getUserId, userId)
                        .eq(UserNote::getKpId, kpId)
        );

        ReviewState state = reviewStateMapper.selectOne(
                Wrappers.<ReviewState>lambdaQuery()
                        .eq(ReviewState::getUserId, userId)
                        .eq(ReviewState::getKpId, kpId)
        );

        return new UserCardStateVO(
                favorite != null,
                note == null ? "" : note.getContentMd(),
                note == null || note.getUpdateTime() == null ? null : note.getUpdateTime()
                        .toString(),
                state == null ? null : state.getState(),
                state == null || state.getDueAt() == null ? null : state.getDueAt()
                        .toString(),
                state == null ? null : state.getStability(),
                state == null ? null : state.getReps(),
                state == null ? null : state.getLapses()
        );
    }

    // ------------------------------------------------------------------
    // 搜索
    // ------------------------------------------------------------------

    public List<SearchHitVO> search(String keyword) {
        String trimmed = keyword == null ? "" : keyword.strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }

        recordHotKeyword(trimmed);

        List<KnowledgePoint> hits = executeSearch(trimmed);

        Map<Long, ContentModule> modules = loadModules(hits);

        return hits.stream()
                .map(kp -> new SearchHitVO(
                        kp.getSlug(),
                        kp.getTitle(),
                        moduleName(modules, kp.getModuleId()),
                        splitTags(kp.getTags()),
                        kp.getDifficulty(),
                        kp.getFrequency(),
                        snippet(kp.getElevatorAnswer())
                ))
                .toList();
    }

    /**
     * 三级检索，精度优先、逐级放宽：
     *
     * <ol>
     *   <li><b>布尔短语</b>：把每个词加双引号，要求 ngram 片段连续出现。
     *       命中少但准，是「缓存穿透」这类中文词组的主要来源。</li>
     *   <li><b>自然语言</b>：bigram 或匹配 + 相关度排序。召回更宽，
     *       但会把只提到「缓存」的卡片也带进来，所以放在第二级。</li>
     *   <li><b>LIKE</b>：兜底。ngram_token_size 默认为 2，单字查询
     *       走不了全文索引；混合词（如「HashMap 扩容」）也可能切不出命中。</li>
     * </ol>
     */
    private List<KnowledgePoint> executeSearch(String keyword) {
        if (keyword.length() < MIN_FULLTEXT_LENGTH) {
            return knowledgePointMapper.searchByLike(keyword, SEARCH_LIMIT);
        }

        List<KnowledgePoint> hits = knowledgePointMapper.searchByPhrase(
                toRequiredTermsQuery(keyword),
                SEARCH_LIMIT
        );
        if (!hits.isEmpty()) {
            return hits;
        }

        hits = knowledgePointMapper.searchByKeyword(keyword, SEARCH_LIMIT);
        if (!hits.isEmpty()) {
            return hits;
        }

        return knowledgePointMapper.searchByLike(keyword, SEARCH_LIMIT);
    }

    /**
     * 构造布尔模式查询串：每个词加 {@code +} 前缀变成「必需词」，词间即「与」。
     *
     * <p>为什么用 {@code +词} 而不是 {@code "词"}：在 ngram 分词下，
     * 加引号的短语要求 ngram 片段**连续**出现，中文里换行、标点断开的
     * 情况（「缓存穿透、击穿、雪崩」）就搜不到；而 {@code +词} 只要求
     * 词存在，同时把多词查询从「或」收紧成「与」——
     * 实测 {@code +缓存 +击穿} 能精确命中目标卡片，
     * 而自然语言模式会把只提到「缓存」的 JVM 卡片也带进来。
     *
     * <p>双引号与前后空白会被剔除，避免用户输入破坏查询串结构。
     */
    private String toRequiredTermsQuery(String keyword) {
        String sanitized = keyword.replace("\"", " ")
                .strip();

        return Arrays.stream(sanitized.split("\\s+"))
                .filter(part -> !part.isEmpty())
                .map(part -> "+" + part)
                .collect(Collectors.joining(" "));
    }

    /**
     * 热搜词用「按天分桶的 ZSet」统计，读时合并最近 7 天。
     *
     * <p>不用单个长期 ZSet，是因为对 ZSet 续期等于把整个窗口向后滑动，
     * 很久以前搜过的词永远不会过期。按天分桶后，过期是自然的。
     */
    private void recordHotKeyword(String keyword) {
        String key = HOT_KEYWORD_KEY_PREFIX + LocalDate.now()
                .format(DAY_FORMAT);

        stringRedisTemplate.opsForZSet()
                .incrementScore(key, keyword, 1d);
        stringRedisTemplate.expire(key, HOT_KEYWORD_TTL);
    }

    public List<String> hotKeywords() {
        Map<String, Double> merged = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();

        for (int offset = 0; offset < HOT_KEYWORD_DAYS; offset++) {
            String key = HOT_KEYWORD_KEY_PREFIX + today.minusDays(offset)
                    .format(DAY_FORMAT);

            Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeWithScores(key, 0, HOT_KEYWORD_SIZE - 1);
            if (tuples == null) {
                continue;
            }

            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple.getValue() == null || tuple.getScore() == null) {
                    continue;
                }
                merged.merge(tuple.getValue(), tuple.getScore(), Double::sum);
            }
        }

        return merged.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Double>comparingByValue()
                        .reversed())
                .limit(HOT_KEYWORD_SIZE)
                .map(Map.Entry::getKey)
                .toList();
    }

    // ------------------------------------------------------------------
    // 收藏与笔记
    // ------------------------------------------------------------------

    @Transactional
    public boolean setFavorite(String slug, Long userId, boolean favorite) {
        Long kpId = requireKpId(slug);

        if (!favorite) {
            userFavoriteMapper.delete(
                    Wrappers.<UserFavorite>lambdaQuery()
                            .eq(UserFavorite::getUserId, userId)
                            .eq(UserFavorite::getKpId, kpId)
            );

            return false;
        }

        boolean exists = userFavoriteMapper.exists(
                Wrappers.<UserFavorite>lambdaQuery()
                        .eq(UserFavorite::getUserId, userId)
                        .eq(UserFavorite::getKpId, kpId)
        );
        if (!exists) {
            UserFavorite entity = new UserFavorite();
            entity.setUserId(userId);
            entity.setKpId(kpId);
            userFavoriteMapper.insert(entity);
        }

        return true;
    }

    public List<KnowledgePointListItemVO> listFavorites(Long userId) {
        List<UserFavorite> favorites = userFavoriteMapper.selectList(
                Wrappers.<UserFavorite>lambdaQuery()
                        .eq(UserFavorite::getUserId, userId)
                        .orderByDesc(UserFavorite::getCreateTime)
        );
        if (favorites.isEmpty()) {
            return List.of();
        }

        List<Long> kpIds = favorites.stream()
                .map(UserFavorite::getKpId)
                .toList();

        Map<Long, KnowledgePoint> kpById = knowledgePointMapper.selectByIds(kpIds)
                .stream()
                .collect(LinkedHashMap::new, (map, kp) -> map.put(kp.getId(), kp), Map::putAll);

        Map<Long, ContentModule> modules = loadModules(new ArrayList<>(kpById.values()));
        Map<Long, Integer> questionCounts = loadQuestionCounts(kpIds);
        Map<Long, ReviewState> reviewStates = loadReviewStates(userId, kpIds);

        return kpIds.stream()
                .map(kpById::get)
                .filter(Objects::nonNull)
                .map(kp -> {
                    ReviewState state = reviewStates.get(kp.getId());

                    return new KnowledgePointListItemVO(
                            kp.getSlug(),
                            kp.getTitle(),
                            moduleSlug(modules, kp.getModuleId()),
                            moduleName(modules, kp.getModuleId()),
                            splitTags(kp.getTags()),
                            kp.getDifficulty(),
                            kp.getFrequency(),
                            questionCounts.getOrDefault(kp.getId(), 0),
                            state == null ? null : state.getState(),
                            state == null || state.getDueAt() == null ? null : state.getDueAt()
                                    .toString()
                    );
                })
                .toList();
    }

    @Transactional
    public void saveNote(String slug, Long userId, String contentMd) {
        Long kpId = requireKpId(slug);
        String normalized = contentMd == null ? "" : contentMd;

        UserNote existing = userNoteMapper.selectOne(
                Wrappers.<UserNote>lambdaQuery()
                        .eq(UserNote::getUserId, userId)
                        .eq(UserNote::getKpId, kpId)
        );

        if (existing == null) {
            UserNote note = new UserNote();
            note.setUserId(userId);
            note.setKpId(kpId);
            note.setContentMd(normalized);
            userNoteMapper.insert(note);
            return;
        }

        existing.setContentMd(normalized);
        userNoteMapper.updateById(existing);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 解析模块 slug 为 id 集合：包含自身与其所有子模块。
     *
     * @return null 表示未指定模块（不过滤）；空集表示模块不存在
     */
    private Set<Long> resolveModuleIds(String moduleSlug) {
        if (!isPresent(moduleSlug)) {
            return null;
        }

        List<ContentModule> modules = contentModuleMapper.selectList(
                Wrappers.<ContentModule>lambdaQuery()
        );

        ContentModule target = modules.stream()
                .filter(module -> moduleSlug.trim()
                        .equals(module.getSlug()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return Set.of();
        }

        Set<Long> ids = new LinkedHashSet<>();
        ids.add(target.getId());
        modules.stream()
                .filter(module -> target.getId()
                        .equals(module.getParentId()))
                .forEach(module -> ids.add(module.getId()));

        return ids;
    }

    private Long findKpId(String slug) {
        KnowledgePoint projection = knowledgePointMapper.selectOne(
                Wrappers.<KnowledgePoint>lambdaQuery()
                        .select(KnowledgePoint::getId)
                        .eq(KnowledgePoint::getSlug, slug)
        );

        return projection == null ? null : projection.getId();
    }

    private Long requireKpId(String slug) {
        Long kpId = findKpId(slug);
        if (kpId == null) {
            throw new BizException(ResultCode.KNOWLEDGE_POINT_NOT_FOUND);
        }

        return kpId;
    }

    /**
     * 一次批量取回涉及的模块。列表页必须批量取，逐条查会变成 N+1。
     */
    private Map<Long, ContentModule> loadModules(List<KnowledgePoint> points) {
        Set<Long> moduleIds = new LinkedHashSet<>();
        points.forEach(kp -> {
            if (kp.getModuleId() != null) {
                moduleIds.add(kp.getModuleId());
            }
        });

        if (moduleIds.isEmpty()) {
            return Map.of();
        }

        return contentModuleMapper.selectByIds(moduleIds)
                .stream()
                .collect(
                        LinkedHashMap::new,
                        (map, module) -> map.put(module.getId(), module),
                        Map::putAll
                );
    }

    private static String moduleSlug(Map<Long, ContentModule> modules, Long moduleId) {
        ContentModule module = modules.get(moduleId);

        return module == null ? null : module.getSlug();
    }

    private static String moduleName(Map<Long, ContentModule> modules, Long moduleId) {
        ContentModule module = modules.get(moduleId);

        return module == null ? null : module.getName();
    }

    private Map<Long, Integer> loadQuestionCounts(List<Long> kpIds) {
        if (kpIds.isEmpty()) {
            return Map.of();
        }

        QueryWrapper<QuizQuestion> wrapper = new QueryWrapper<>();
        wrapper.select("kp_id AS id", "COUNT(*) AS cnt")
                .in("kp_id", kpIds)
                .groupBy("kp_id");

        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : quizQuestionMapper.selectMaps(wrapper)) {
            Long kpId = toLong(row.get("id"));
            if (kpId != null) {
                counts.put(kpId, toInt(row.get("cnt")));
            }
        }

        return counts;
    }

    private Map<Long, ReviewState> loadReviewStates(Long userId, List<Long> kpIds) {
        if (kpIds.isEmpty()) {
            return Map.of();
        }

        return reviewStateMapper.selectList(
                        Wrappers.<ReviewState>lambdaQuery()
                                .eq(ReviewState::getUserId, userId)
                                .in(ReviewState::getKpId, kpIds)
                )
                .stream()
                .collect(
                        LinkedHashMap::new,
                        (map, state) -> map.put(state.getKpId(), state),
                        Map::putAll
                );
    }

    private List<String> splitTags(String tags) {
        if (!isPresent(tags)) {
            return List.of();
        }

        return Arrays.stream(tags.split(","))
                .map(String::strip)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }

    private String snippet(String text) {
        if (!isPresent(text)) {
            return "";
        }

        String flattened = text.replace("\n", " ")
                .strip();

        return flattened.length() <= SNIPPET_LENGTH
                ? flattened
                : flattened.substring(0, SNIPPET_LENGTH) + "…";
    }

    private static boolean isRoot(Long parentId) {
        return parentId == null || parentId == 0L;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
