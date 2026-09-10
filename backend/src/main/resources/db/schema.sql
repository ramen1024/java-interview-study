-- ============================================================
--  Java 面试学习网站 —— 数据库结构
--  MySQL 8.0+ / utf8mb4 / InnoDB
--
--  设计要点：
--   1. 内容（卡片、追问链、题目）以 Markdown 为唯一事实源，
--      由导入器 upsert 进这些表，因此所有内容表都带 source_path / 稳定业务键，
--      保证可重复导入而不产生重复数据。
--   2. knowledge_point 上建 ngram 全文索引支撑中文搜索
--      （ngram_token_size 默认 2，单字查询需在应用层回落 LIKE）。
--   3. review_state 承载 FSRS 算法状态，review_log 只追加不修改。
--
--  本脚本幂等，可重复执行。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `jis`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `jis`;

-- ------------------------------------------------------------
-- 1. 用户
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `username`    VARCHAR(64)     NOT NULL COMMENT '登录名',
    `password`    VARCHAR(100)    NOT NULL COMMENT 'BCrypt 散列',
    `nickname`    VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '昵称',
    `email`       VARCHAR(128)    NOT NULL DEFAULT '',
    `role`        VARCHAR(32)     NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1 正常 0 禁用',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户';

-- ------------------------------------------------------------
-- 2. 内容模块（两级树：一级模块 -> 二级分类）
--    slug 作为导入时的稳定业务键
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `content_module`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `parent_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 表示一级模块',
    `name`        VARCHAR(64)     NOT NULL COMMENT '模块名',
    `slug`        VARCHAR(64)     NOT NULL COMMENT '稳定业务键，如 java-basics',
    `description` VARCHAR(512)    NOT NULL DEFAULT '',
    `icon`        VARCHAR(64)     NOT NULL DEFAULT '' COMMENT 'Element Plus 图标名',
    `sort`        INT             NOT NULL DEFAULT 0,
    `level`       TINYINT         NOT NULL DEFAULT 1 COMMENT '1 一级 2 二级',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_content_module_slug` (`slug`),
    KEY `idx_content_module_parent` (`parent_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='内容模块';

-- ------------------------------------------------------------
-- 3. 知识点卡片（六段式结构）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `knowledge_point`
(
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `module_id`        BIGINT UNSIGNED NOT NULL,
    `slug`             VARCHAR(128)    NOT NULL COMMENT '稳定业务键，如 hashmap-internals',
    `title`            VARCHAR(200)    NOT NULL COMMENT '知识点标题（面试问题形式）',
    `elevator_answer`  TEXT            NOT NULL COMMENT '第一段：电梯版回答，30 秒说清结论',
    `detail_md`        MEDIUMTEXT      NULL COMMENT '第二段：展开讲解（原理 + 源码）',
    `pitfalls_md`      MEDIUMTEXT      NULL COMMENT '第四段：常见坑（答错就扣分的表述）',
    `bonus_md`         MEDIUMTEXT      NULL COMMENT '第四段：加分点（让面试官眼前一亮）',
    `version_diff_md`  MEDIUMTEXT      NULL COMMENT '第五段：版本差异（Java 8/17/21、MySQL 5.7/8.0 等）',
    `difficulty`       TINYINT         NOT NULL DEFAULT 2 COMMENT '1 易 2 中 3 难',
    `frequency`        TINYINT         NOT NULL DEFAULT 2 COMMENT '面试热度 1 低频 2 常见 3 高频',
    `tags`             VARCHAR(255)    NOT NULL DEFAULT '' COMMENT '逗号分隔',
    `source_path`      VARCHAR(255)    NOT NULL DEFAULT '' COMMENT 'Markdown 源文件相对路径',
    `sort`             INT             NOT NULL DEFAULT 0,
    `view_count`       INT             NOT NULL DEFAULT 0,
    `create_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_point_slug` (`slug`),
    KEY `idx_knowledge_point_module` (`module_id`),
    KEY `idx_knowledge_point_frequency` (`frequency`),
    FULLTEXT KEY `ft_knowledge_point_search` (`title`, `elevator_answer`, `detail_md`, `tags`) WITH PARSER ngram
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识点卡片';

-- ------------------------------------------------------------
-- 4. 追问链（树形，模拟面试官由浅入深连续追问）
--    kp_id + q_key 唯一，保证增量导入时能对齐已有节点
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `follow_up`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `kp_id`       BIGINT UNSIGNED NOT NULL,
    `parent_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 表示追问链第一层',
    `q_key`       VARCHAR(64)     NOT NULL COMMENT '卡片内稳定键，如 q1 / q2-1',
    `question`    VARCHAR(500)    NOT NULL COMMENT '面试官的追问',
    `answer_md`   MEDIUMTEXT      NOT NULL COMMENT '参考答案',
    `depth`       TINYINT         NOT NULL DEFAULT 1,
    `sort`        INT             NOT NULL DEFAULT 0,
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follow_up_kp_qkey` (`kp_id`, `q_key`),
    KEY `idx_follow_up_parent` (`parent_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='面试追问链';

-- ------------------------------------------------------------
-- 5. 知识点关联（双链，形成知识网而非孤岛）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `kp_relation`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `from_kp_id`    BIGINT UNSIGNED NOT NULL,
    `to_kp_id`      BIGINT UNSIGNED NOT NULL,
    `relation_type` VARCHAR(32)     NOT NULL DEFAULT 'RELATED' COMMENT 'RELATED / PREREQUISITE / CONTRAST / DEEPEN',
    `sort`          INT             NOT NULL DEFAULT 0,
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kp_relation` (`from_kp_id`, `to_kp_id`, `relation_type`),
    KEY `idx_kp_relation_to` (`to_kp_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识点关联';

-- ------------------------------------------------------------
-- 6. 自测题目
--    type: CHOICE 单选 / MULTI 多选 / JUDGE 判断 / CLOZE 代码挖空
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `quiz_question`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `kp_id`        BIGINT UNSIGNED NULL COMMENT '关联知识点，可为空',
    `q_key`        VARCHAR(64)     NOT NULL COMMENT '全局稳定键，如 hashmap-internals-1',
    `type`         VARCHAR(16)     NOT NULL,
    `stem_md`      TEXT            NOT NULL COMMENT '题干，支持 Markdown 与代码块',
    `options_json` JSON            NULL COMMENT '选项 [{"key":"A","text":"..."}]',
    `answer`       VARCHAR(255)    NOT NULL DEFAULT '' COMMENT 'CHOICE: A；MULTI: ABC；JUDGE: T/F；CLOZE 用空字符串（判分改看 blanks_json）',
    `blanks_json`  JSON            NULL COMMENT 'CLOZE：每空可接受答案的数组，如 [["尾插","尾部插入"]]',
    `analysis_md`  MEDIUMTEXT      NULL COMMENT '解析',
    `difficulty`   TINYINT         NOT NULL DEFAULT 2,
    `source_path`  VARCHAR(255)    NOT NULL DEFAULT '',
    `sort`         INT             NOT NULL DEFAULT 0,
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_quiz_question_qkey` (`q_key`),
    KEY `idx_quiz_question_kp` (`kp_id`),
    KEY `idx_quiz_question_type` (`type`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='自测题目';

-- ------------------------------------------------------------
-- 7. 复习状态（FSRS 算法状态载体）
--    一个用户对一张卡片只有一行，导入内容不影响本表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `review_state`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`        BIGINT UNSIGNED NOT NULL,
    `kp_id`          BIGINT UNSIGNED NOT NULL,
    `state`          TINYINT         NOT NULL DEFAULT 0 COMMENT '0 New 1 Learning 2 Review 3 Relearning',
    `stability`      DOUBLE          NOT NULL DEFAULT 0 COMMENT '记忆稳定性 S，单位：天',
    `difficulty`     DOUBLE          NOT NULL DEFAULT 0 COMMENT '记忆难度 D，1~10',
    `reps`           INT             NOT NULL DEFAULT 0 COMMENT '累计复习次数',
    `lapses`         INT             NOT NULL DEFAULT 0 COMMENT '遗忘次数（评 1 不会）',
    `due_at`         DATETIME        NOT NULL COMMENT '下次到期时间',
    `last_review_at` DATETIME        NULL,
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_review_state_user_kp` (`user_id`, `kp_id`),
    KEY `idx_review_state_due` (`user_id`, `due_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='复习状态(FSRS)';

-- ------------------------------------------------------------
-- 8. 复习日志（只追加，用于统计与算法调参）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `review_log`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT UNSIGNED NOT NULL,
    `kp_id`           BIGINT UNSIGNED NOT NULL,
    `rating`          TINYINT         NOT NULL COMMENT '1 不会 2 模糊 3 会讲 4 轻松',
    `duration_ms`     INT             NOT NULL DEFAULT 0 COMMENT '自评耗时',
    `stability_after` DOUBLE          NOT NULL DEFAULT 0,
    `difficulty_after` DOUBLE         NOT NULL DEFAULT 0,
    `interval_days`   DOUBLE          NOT NULL DEFAULT 0 COMMENT '本次算出的下次间隔',
    `reviewed_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_review_log_user_time` (`user_id`, `reviewed_at`),
    KEY `idx_review_log_kp` (`kp_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='复习日志';

-- ------------------------------------------------------------
-- 9. 用户笔记
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_note`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT UNSIGNED NOT NULL,
    `kp_id`       BIGINT UNSIGNED NOT NULL,
    `content_md`  MEDIUMTEXT      NOT NULL,
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_note_user_kp` (`user_id`, `kp_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户笔记';

-- ------------------------------------------------------------
-- 10. 用户收藏
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_favorite`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT UNSIGNED NOT NULL,
    `kp_id`       BIGINT UNSIGNED NOT NULL,
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_favorite_user_kp` (`user_id`, `kp_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户收藏';

-- ------------------------------------------------------------
-- 11. 答题记录（错题本的来源，按错误次数聚合出薄弱清单）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `quiz_record`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT UNSIGNED NOT NULL,
    `question_id` BIGINT UNSIGNED NOT NULL,
    `user_answer` VARCHAR(255)    NOT NULL DEFAULT '' COMMENT '用户作答',
    `is_correct`  TINYINT         NOT NULL DEFAULT 0,
    `answered_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_quiz_record_user_time` (`user_id`, `answered_at`),
    KEY `idx_quiz_record_user_question` (`user_id`, `question_id`, `is_correct`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='答题记录';

-- ------------------------------------------------------------
-- 12. 每日学习统计（热力图数据源，由复习/答题行为累加）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `study_daily`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT UNSIGNED NOT NULL,
    `stat_date`     DATE            NOT NULL,
    `review_count`  INT             NOT NULL DEFAULT 0 COMMENT '复习卡片数',
    `new_count`     INT             NOT NULL DEFAULT 0 COMMENT '首次接触卡片数',
    `quiz_count`    INT             NOT NULL DEFAULT 0 COMMENT '答题数',
    `correct_count` INT             NOT NULL DEFAULT 0 COMMENT '答对数',
    `duration_sec`  INT             NOT NULL DEFAULT 0 COMMENT '学习时长（秒）',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_study_daily_user_date` (`user_id`, `stat_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='每日学习统计';
