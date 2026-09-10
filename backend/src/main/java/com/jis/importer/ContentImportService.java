package com.jis.importer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jis.common.BizException;
import com.jis.common.RedisLock;
import com.jis.common.ResultCode;
import com.jis.content.cache.ContentCache;
import com.jis.content.entity.ContentModule;
import com.jis.content.entity.FollowUp;
import com.jis.content.entity.KnowledgePoint;
import com.jis.content.entity.KpRelation;
import com.jis.content.mapper.ContentModuleMapper;
import com.jis.content.mapper.FollowUpMapper;
import com.jis.content.mapper.KnowledgePointMapper;
import com.jis.content.mapper.KpRelationMapper;
import com.jis.importer.dto.ImportResultVO;
import com.jis.importer.parser.CardParseException;
import com.jis.importer.parser.MarkdownCardParser;
import com.jis.importer.parser.ParsedCard;
import com.jis.importer.parser.ParsedFollowUp;
import com.jis.importer.parser.ParsedModule;
import com.jis.importer.parser.ParsedQuestion;
import com.jis.importer.parser.ParsedRelation;
import com.jis.quiz.entity.QuizQuestion;
import com.jis.quiz.mapper.QuizQuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 把 {@code content/} 下的 Markdown 内容导入数据库。
 *
 * <p>三条设计原则：
 *
 * <ol>
 *   <li><b>幂等</b>：一切以业务键（模块 slug、卡片 slug、追问 qKey、题目 qKey）
 *       做 upsert，重复导入结果一致，可以放心反复执行。</li>
 *   <li><b>不动用户数据</b>：只写内容表，绝不触碰 {@code review_state}、
 *       {@code user_note}、{@code user_favorite}、{@code quiz_record}。
 *       用户复习到一半重新导入内容，进度不会丢。</li>
 *   <li><b>按文件隔离失败</b>：单个文件格式错误只跳过该文件并记入 errors，
 *       其余内容照常导入。但**不会因此删除**数据库里的卡片——
 *       否则一个文件的笔误就会连带删掉线上内容。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentImportService {

    private static final String MODULE_FILE_NAME = "_module.yml";
    private static final String MARKDOWN_SUFFIX = ".md";
    private static final String LOCK_KEY = "jis:content:import:lock";
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private final ContentImportProperties properties;
    private final MarkdownCardParser parser;
    private final ContentModuleMapper contentModuleMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final FollowUpMapper followUpMapper;
    private final KpRelationMapper kpRelationMapper;
    private final QuizQuestionMapper quizQuestionMapper;
    private final ContentCache contentCache;
    private final RedisLock redisLock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 扫描并导入全部内容。用 Redis 锁串行化，避免多实例或重复点击造成并发写冲突。
     */
    public ImportResultVO importAll() {
        Path root = properties.resolvedRoot();
        if (!Files.isDirectory(root)) {
            throw new BizException(
                    ResultCode.INTERNAL_ERROR,
                    "内容目录不存在：" + root + "（可用 jis.content.root 指定）"
            );
        }

        String lockToken = redisLock.tryLock(LOCK_KEY, LOCK_TTL);
        if (lockToken == null) {
            throw new BizException(ResultCode.IMPORT_IN_PROGRESS);
        }

        try {
            ImportResultVO result = transactionTemplate.execute(status -> doImport(root));

            return result == null ? emptyResult() : result;
        } finally {
            redisLock.unlock(LOCK_KEY, lockToken);
        }
    }

    private ImportResultVO doImport(Path root) {
        long startNanos = System.nanoTime();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        List<ParsedModule> modules = scanModules(root, errors);
        Map<String, Long> moduleIds = upsertModules(modules);

        List<ParsedCard> cards = scanCards(root, moduleIds, errors);
        Map<String, Long> kpIds = upsertCards(cards, moduleIds);

        int followUpCount = 0;
        int questionCount = 0;
        for (ParsedCard card : cards) {
            Long kpId = kpIds.get(card.slug());
            followUpCount += upsertFollowUps(kpId, card);
            questionCount += upsertQuestions(kpId, card);
        }

        // 关联必须放在所有卡片入库之后，否则指向同批其他卡片的关联会解析不到
        int relationCount = 0;
        for (ParsedCard card : cards) {
            relationCount += upsertRelations(kpIds.get(card.slug()), card, kpIds, warnings);
        }

        warnOrphanCards(root, cards, warnings);

        contentCache.evictAll();

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info(
                "内容导入完成：模块 {} 个，卡片 {} 张，追问 {} 条，题目 {} 道，关联 {} 条，耗时 {} ms",
                modules.size(),
                cards.size(),
                followUpCount,
                questionCount,
                relationCount,
                elapsedMs
        );

        if (!errors.isEmpty()) {
            log.warn("内容导入有 {} 个文件被跳过，请修正后重新导入", errors.size());
            errors.forEach(error -> log.warn("导入错误：{}", error));
        }
        warnings.forEach(warning -> log.info("导入提示：{}", warning));

        return new ImportResultVO(
                modules.size(),
                cards.size(),
                followUpCount,
                questionCount,
                relationCount,
                elapsedMs,
                warnings,
                errors
        );
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /**
     * 扫描所有 {@code _module.yml} 构建模块定义。
     *
     * <p>按目录层级从浅到深处理，父模块的 slug 必然已经解析完并记在
     * {@code slugByDirectory} 里，因此一趟扫描就能定下父子关系。
     */
    private List<ParsedModule> scanModules(Path root, List<String> errors) {
        List<Path> moduleFiles;
        try (Stream<Path> paths = Files.walk(root)) {
            moduleFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> MODULE_FILE_NAME.equals(path.getFileName()
                            .toString()))
                    .sorted(
                            Comparator.comparingInt((Path path) -> path.getNameCount())
                                    .thenComparing(Path::toString)
                    )
                    .toList();
        } catch (IOException e) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "扫描内容目录失败：" + e.getMessage());
        }

        Set<Path> moduleDirectories = moduleFiles.stream()
                .map(Path::getParent)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        Map<String, String> slugByDirectory = new LinkedHashMap<>();
        List<ParsedModule> modules = new ArrayList<>();

        for (Path moduleFile : moduleFiles) {
            Path directory = moduleFile.getParent();
            String sourcePath = relativePath(root, moduleFile);

            try {
                ParsedModule parsed = parser.parseModule(
                        Files.readString(moduleFile, StandardCharsets.UTF_8),
                        sourcePath,
                        directory.toString(),
                        null,
                        1
                );

                Path parentDirectory = nearestModuleDirectory(directory.getParent(), moduleDirectories);
                String parentSlug = parentDirectory == null
                        ? null
                        : slugByDirectory.get(parentDirectory.toString());

                modules.add(new ParsedModule(
                        parsed.slug(),
                        parsed.name(),
                        parsed.description(),
                        parsed.icon(),
                        parsed.sort(),
                        parentSlug,
                        parentDirectory == null ? 1 : 2,
                        parsed.directoryPath()
                ));

                slugByDirectory.put(directory.toString(), parsed.slug());
            } catch (CardParseException e) {
                errors.add(e.getMessage());
            } catch (IOException e) {
                errors.add("[" + sourcePath + "] 读取失败：" + e.getMessage());
            }
        }

        return modules;
    }

    private Path nearestModuleDirectory(Path start, Set<Path> moduleDirectories) {
        Path current = start;
        while (current != null) {
            if (moduleDirectories.contains(current)) {
                return current;
            }
            current = current.getParent();
        }

        return null;
    }

    private List<ParsedCard> scanCards(Path root, Map<String, Long> moduleIds, List<String> errors) {
        List<Path> cardFiles;
        try (Stream<Path> paths = Files.walk(root)) {
            cardFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString()
                            .endsWith(MARKDOWN_SUFFIX))
                    .filter(path -> !path.getFileName()
                            .toString()
                            .startsWith("_"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "扫描内容目录失败：" + e.getMessage());
        }

        List<ParsedCard> cards = new ArrayList<>();
        Set<String> seenSlugs = new LinkedHashSet<>();

        for (Path file : cardFiles) {
            String sourcePath = relativePath(root, file);
            try {
                ParsedCard card = parser.parseCard(
                        Files.readString(file, StandardCharsets.UTF_8),
                        sourcePath,
                        numericPrefix(file.getFileName()
                                .toString())
                );

                if (!moduleIds.containsKey(card.moduleSlug())) {
                    errors.add(
                            "[" + sourcePath + "] module「" + card.moduleSlug() + "」不存在，已跳过该卡片"
                    );
                    continue;
                }

                if (!seenSlugs.add(card.slug())) {
                    errors.add("[" + sourcePath + "] slug「" + card.slug() + "」与其他文件重复，已跳过");
                    continue;
                }

                cards.add(card);
            } catch (CardParseException e) {
                errors.add(e.getMessage());
            } catch (IOException e) {
                errors.add("[" + sourcePath + "] 读取失败：" + e.getMessage());
            }
        }

        return cards;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    private Map<String, Long> upsertModules(List<ParsedModule> modules) {
        Map<String, Long> ids = new LinkedHashMap<>();

        for (ParsedModule module : modules) {
            ContentModule existing = contentModuleMapper.selectOne(
                    Wrappers.<ContentModule>lambdaQuery()
                            .eq(ContentModule::getSlug, module.slug())
            );

            ContentModule entity = existing == null ? new ContentModule() : existing;
            entity.setName(module.name());
            entity.setSlug(module.slug());
            entity.setDescription(module.description());
            entity.setIcon(module.icon());
            entity.setSort(module.sort());
            entity.setLevel(module.level());
            entity.setParentId(
                    module.parentSlug() == null ? 0L : ids.getOrDefault(module.parentSlug(), 0L)
            );

            if (existing == null) {
                contentModuleMapper.insert(entity);
            } else {
                contentModuleMapper.updateById(entity);
            }

            ids.put(module.slug(), entity.getId());
        }

        return ids;
    }

    private Map<String, Long> upsertCards(List<ParsedCard> cards, Map<String, Long> moduleIds) {
        Map<String, Long> ids = new LinkedHashMap<>();

        for (ParsedCard card : cards) {
            KnowledgePoint existing = knowledgePointMapper.selectOne(
                    Wrappers.<KnowledgePoint>lambdaQuery()
                            .eq(KnowledgePoint::getSlug, card.slug())
            );

            KnowledgePoint entity = existing == null ? new KnowledgePoint() : existing;
            entity.setModuleId(moduleIds.get(card.moduleSlug()));
            entity.setSlug(card.slug());
            entity.setTitle(card.title());
            entity.setElevatorAnswer(card.elevatorAnswer());
            entity.setDetailMd(card.detailMd());
            entity.setPitfallsMd(card.pitfallsMd());
            entity.setBonusMd(card.bonusMd());
            entity.setVersionDiffMd(card.versionDiffMd());
            entity.setDifficulty(card.difficulty());
            entity.setFrequency(card.frequency());
            entity.setTags(String.join(",", card.tags()));
            entity.setSourcePath(card.sourcePath());
            entity.setSort(card.sort());

            if (existing == null) {
                entity.setViewCount(0);
                knowledgePointMapper.insert(entity);
            } else {
                knowledgePointMapper.updateById(entity);
            }

            ids.put(card.slug(), entity.getId());
        }

        return ids;
    }

    private int upsertFollowUps(Long kpId, ParsedCard card) {
        Map<String, FollowUp> existingByKey = new LinkedHashMap<>();
        followUpMapper.selectList(
                        Wrappers.<FollowUp>lambdaQuery()
                                .eq(FollowUp::getKpId, kpId)
                )
                .forEach(row -> existingByKey.put(row.getQKey(), row));

        Map<String, FollowUp> rows = new LinkedHashMap<>();
        int sort = 0;

        // 第一遍：保证每个节点都落库，父节点先写，子节点才能引用到 id
        for (ParsedFollowUp node : card.followUps()) {
            FollowUp row = existingByKey.get(node.qKey());
            boolean created = row == null;

            if (created) {
                row = new FollowUp();
                row.setKpId(kpId);
                row.setQKey(node.qKey());
                row.setParentId(0L);
            }

            row.setQuestion(node.question());
            row.setAnswerMd(node.answerMd());
            row.setDepth(node.depth());
            row.setSort(sort++);

            if (created) {
                followUpMapper.insert(row);
            } else {
                followUpMapper.updateById(row);
            }

            rows.put(node.qKey(), row);
        }

        // 第二遍：回填 parentId。parsed 顺序不保证父节点在前，所以单独走一遍
        for (ParsedFollowUp node : card.followUps()) {
            FollowUp row = rows.get(node.qKey());
            Long parentId = 0L;

            if (node.parentQKey() != null) {
                FollowUp parent = rows.get(node.parentQKey());
                parentId = parent == null ? 0L : parent.getId();
            }

            if (!Objects.equals(row.getParentId(), parentId)) {
                row.setParentId(parentId);
                followUpMapper.updateById(row);
            }
        }

        List<Long> staleFollowUpIds = existingByKey.values()
                .stream()
                .filter(row -> !rows.containsKey(row.getQKey()))
                .map(FollowUp::getId)
                .toList();
        if (!staleFollowUpIds.isEmpty()) {
            followUpMapper.deleteByIds(staleFollowUpIds);
        }

        return card.followUps()
                .size();
    }

    private int upsertQuestions(Long kpId, ParsedCard card) {
        Map<String, QuizQuestion> existingByKey = new LinkedHashMap<>();
        quizQuestionMapper.selectList(
                        Wrappers.<QuizQuestion>lambdaQuery()
                                .eq(QuizQuestion::getKpId, kpId)
                )
                .forEach(row -> existingByKey.put(row.getQKey(), row));

        int sort = 0;
        for (ParsedQuestion question : card.questions()) {
            QuizQuestion row = existingByKey.get(question.qKey());
            boolean created = row == null;

            if (created) {
                row = new QuizQuestion();
            }

            row.setKpId(kpId);
            row.setQKey(question.qKey());
            row.setType(question.type());
            row.setStemMd(question.stemMd());
            row.setOptionsJson(toJson(question.options()));
            // 挖空题没有 answer，统一写空串：answer 列是 NOT NULL，
            // 交给 MyBatis-Plus 跳过 null 字段会撞上「Field doesn't have a default value」
            row.setAnswer(question.answer() == null ? "" : question.answer());
            row.setBlanksJson(toJson(question.blanks()));
            row.setAnalysisMd(question.analysisMd());
            row.setDifficulty(question.difficulty());
            row.setSourcePath(card.sourcePath());
            row.setSort(sort++);

            if (created) {
                quizQuestionMapper.insert(row);
            } else {
                quizQuestionMapper.updateById(row);
            }
        }

        Set<String> currentQKeys = card.questions()
                .stream()
                .map(ParsedQuestion::qKey)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        List<Long> staleQuestionIds = existingByKey.values()
                .stream()
                .filter(row -> !currentQKeys.contains(row.getQKey()))
                .map(QuizQuestion::getId)
                .toList();
        if (!staleQuestionIds.isEmpty()) {
            quizQuestionMapper.deleteByIds(staleQuestionIds);
        }

        return card.questions()
                .size();
    }

    private int upsertRelations(
            Long fromKpId,
            ParsedCard card,
            Map<String, Long> kpIds,
            List<String> warnings
    ) {
        // 关联完全由 Markdown 推导，直接重建最简单且不会残留脏数据
        kpRelationMapper.delete(
                Wrappers.<KpRelation>lambdaQuery()
                        .eq(KpRelation::getFromKpId, fromKpId)
        );

        if (card.relations()
                .isEmpty()) {
            return 0;
        }

        Set<String> inserted = new LinkedHashSet<>();
        int sort = 0;

        for (ParsedRelation relation : card.relations()) {
            // 同一张卡片重复引用同一目标会撞唯一键，去重
            if (!inserted.add(relation.targetSlug() + "|" + relation.type())) {
                continue;
            }

            if (relation.targetSlug()
                    .equals(card.slug())) {
                warnings.add("[" + card.sourcePath() + "] related 指向了自己，已跳过");
                continue;
            }

            Long toKpId = resolveKpId(relation.targetSlug(), kpIds);
            if (toKpId == null) {
                warnings.add(
                        "[" + card.sourcePath() + "] related 指向的「" + relation.targetSlug()
                                + "」尚未编写，已跳过（写好后再导入即可自动补上）"
                );
                continue;
            }

            KpRelation entity = new KpRelation();
            entity.setFromKpId(fromKpId);
            entity.setToKpId(toKpId);
            entity.setRelationType(relation.type());
            entity.setSort(sort++);

            try {
                kpRelationMapper.insert(entity);
            } catch (DuplicateKeyException e) {
                log.debug("关联已存在，跳过：{} -> {}", card.slug(), relation.targetSlug());
            }
        }

        return sort;
    }

    private Long resolveKpId(String slug, Map<String, Long> kpIds) {
        Long fromThisRun = kpIds.get(slug);
        if (fromThisRun != null) {
            return fromThisRun;
        }

        KnowledgePoint existing = knowledgePointMapper.selectOne(
                Wrappers.<KnowledgePoint>lambdaQuery()
                        .select(KnowledgePoint::getId)
                        .eq(KnowledgePoint::getSlug, slug)
        );

        return existing == null ? null : existing.getId();
    }

    /**
     * 提醒数据库中存在、但本次导入未覆盖的卡片。
     *
     * <p>刻意只提醒不删除：本次导入若有文件解析失败，那张卡片看起来也是"未覆盖"，
     * 自动删除会造成内容丢失。
     */
    private void warnOrphanCards(Path root, List<ParsedCard> cards, List<String> warnings) {
        Set<String> importedSlugs = cards.stream()
                .map(ParsedCard::slug)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        List<String> orphans = knowledgePointMapper.selectList(
                        Wrappers.<KnowledgePoint>lambdaQuery()
                                .select(KnowledgePoint::getSlug, KnowledgePoint::getSourcePath)
                )
                .stream()
                .filter(kp -> !importedSlugs.contains(kp.getSlug()))
                .map(kp -> kp.getSlug() + "（源文件 " + kp.getSourcePath() + "）")
                .toList();

        if (!orphans.isEmpty()) {
            warnings.add(
                    "以下 " + orphans.size() + " 张卡片在库中但本次未导入，"
                            + "对应的 Markdown 可能已被删除：库中数据保留未动，"
                            + "如需清理请手动处理。\n  " + String.join("\n  ", orphans)
            );
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("序列化失败", e));
        }
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file)
                .toString()
                .replace('\\', '/');
    }

    /**
     * 文件名数字前缀，用于同模块内的展示顺序，如 {@code 03-hashmap.md} → 3。
     */
    private static int numericPrefix(String fileName) {
        int cursor = 0;
        while (cursor < fileName.length() && Character.isDigit(fileName.charAt(cursor))) {
            cursor++;
        }

        if (cursor == 0) {
            return 0;
        }

        try {
            return Integer.parseInt(fileName.substring(0, cursor));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static ImportResultVO emptyResult() {
        return new ImportResultVO(
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of("导入未执行")
        );
    }
}
