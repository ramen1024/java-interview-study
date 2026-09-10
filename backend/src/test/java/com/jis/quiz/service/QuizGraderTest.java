package com.jis.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.jis.quiz.dto.QuizAnswer;
import com.jis.quiz.entity.QuizQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 判分规则的单元测试。
 *
 * <p>这里守的是「错了也不报错、只是判分结果不对」的那类缺陷：多选漏了顺序无关性、
 * 挖空题把「a b」和「ab」判成同一个答案之类。它们不会让任何接口抛异常，
 * 只会在用户答对时扣分，靠手工点页面很难发现，所以用测试把边界钉住。
 */
class QuizGraderTest {

    private final QuizGrader grader = new QuizGrader();

    @Nested
    @DisplayName("单选与判断题")
    class ChoiceLike {

        @Test
        @DisplayName("选项字母相同即正确")
        void exactLetterMatches() {
            assertThat(grade(question(QuizQuestion.TYPE_CHOICE, "B"), "B").correct())
                    .isTrue();
        }

        @Test
        @DisplayName("作答大小写不敏感")
        void letterCaseInsensitive() {
            assertThat(grade(question(QuizQuestion.TYPE_CHOICE, "B"), "b").correct())
                    .isTrue();
        }

        @Test
        @DisplayName("作答首尾空白被忽略")
        void surroundingWhitespaceIgnored() {
            assertThat(grade(question(QuizQuestion.TYPE_CHOICE, "B"), "  B  ").correct())
                    .isTrue();
        }

        @Test
        @DisplayName("选项字母不同则错误")
        void differentLetterIsWrong() {
            assertThat(grade(question(QuizQuestion.TYPE_CHOICE, "B"), "C").correct())
                    .isFalse();
        }

        @Test
        @DisplayName("未作答按错误处理，不抛异常")
        void nullAnswerIsWrong() {
            assertThat(grade(question(QuizQuestion.TYPE_CHOICE, "B"), (String) null).correct())
                    .isFalse();
        }

        @Test
        @DisplayName("判断题 T/F 大小写不敏感")
        void judgeCaseInsensitive() {
            assertThat(grade(question(QuizQuestion.TYPE_JUDGE, "T"), "t").correct())
                    .isTrue();
            assertThat(grade(question(QuizQuestion.TYPE_JUDGE, "T"), "F").correct())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("多选按集合比较")
    class Multi {

        @Test
        @DisplayName("顺序不同不影响对错")
        void orderDoesNotMatter() {
            assertThat(grade(question(QuizQuestion.TYPE_MULTI, "ABD"), "DBA").correct())
                    .isTrue();
        }

        @Test
        @DisplayName("少选算错")
        void subsetIsWrong() {
            assertThat(grade(question(QuizQuestion.TYPE_MULTI, "ABD"), "AB").correct())
                    .isFalse();
        }

        @Test
        @DisplayName("多选算错")
        void supersetIsWrong() {
            assertThat(grade(question(QuizQuestion.TYPE_MULTI, "ABD"), "ABCD").correct())
                    .isFalse();
        }

        @Test
        @DisplayName("重复字母被去重后比较")
        void duplicateLettersCollapse() {
            assertThat(grade(question(QuizQuestion.TYPE_MULTI, "AB"), "AAB").correct())
                    .isTrue();
        }

        @Test
        @DisplayName("回显的作答被规范化成字母升序")
        void userAnswerNormalizedToSortedLetters() {
            QuizGrader.Graded graded = grade(question(QuizQuestion.TYPE_MULTI, "ABD"), "dBa");

            assertThat(graded.correct()).isTrue();
            assertThat(graded.userAnswer()).isEqualTo("ABD");
        }

        @Test
        @DisplayName("非字母数字字符被忽略，不影响判分")
        void nonAlphanumericIgnored() {
            assertThat(grade(question(QuizQuestion.TYPE_MULTI, "AB"), "A,B").correct())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("挖空题")
    class Cloze {

        private static final String BLANKS = """
                [["尾插","尾部插入"],["oldCap"]]
                """;

        @Test
        @DisplayName("全部空都答对才算对")
        void allBlanksMustMatch() {
            assertThat(grade(cloze(BLANKS), List.of("尾插", "oldCap")).correct())
                    .isTrue();
        }

        @Test
        @DisplayName("任一空答错即算错")
        void oneWrongBlankFailsTheQuestion() {
            QuizGrader.Graded graded = grade(cloze(BLANKS), List.of("尾插", "oldTable"));

            assertThat(graded.correct()).isFalse();
            assertThat(graded.blankResults()).containsExactly(true, false);
        }

        @Test
        @DisplayName("同一空有多个可接受答案")
        void anyAcceptedAnswerMatches() {
            assertThat(grade(cloze(BLANKS), List.of("尾部插入", "oldCap")).correct())
                    .isTrue();
        }

        @Test
        @DisplayName("大小写不敏感")
        void caseInsensitive() {
            assertThat(grade(cloze(BLANKS), List.of("尾插", "OLDCAP")).correct())
                    .isTrue();
        }

        @Test
        @DisplayName("首尾空白与连续空白都被归一化")
        void whitespaceNormalized() {
            assertThat(grade(cloze("[[\"new  Capacity\"]]"), List.of("  new Capacity  "))
                    .correct())
                    .isTrue();
        }

        @Test
        @DisplayName("空白不能被完全删掉——a b 与 ab 是两个不同的答案")
        void internalWhitespaceSignificant() {
            assertThat(grade(cloze("[[\"ab\"]]"), List.of("a b")).correct())
                    .isFalse();
        }

        @Test
        @DisplayName("作答数量少于空数时，缺的空按未作答计为错误")
        void missingBlankIsWrong() {
            QuizGrader.Graded graded = grade(cloze(BLANKS), List.of("尾插"));

            assertThat(graded.correct()).isFalse();
            assertThat(graded.blankResults()).containsExactly(true, false);
        }

        @Test
        @DisplayName("多提交的空被忽略，不影响判分")
        void extraSubmittedBlanksIgnored() {
            assertThat(grade(cloze(BLANKS), List.of("尾插", "oldCap", "多余")).correct())
                    .isTrue();
        }

        @Test
        @DisplayName("作答列表为 null 时整题判错且不抛异常")
        void nullBlanksAnsweredWrong() {
            QuizGrader.Graded graded = grade(cloze(BLANKS), (List<String>) null);

            assertThat(graded.correct()).isFalse();
            assertThat(graded.blankResults()).containsExactly(false, false);
        }

        @Test
        @DisplayName("空答案为空字符串时判错，而不是被当成答对")
        void blankAcceptedAnswerListYieldsWrong() {
            assertThat(grade(cloze("[[\"\"]]"), List.of("")).correct())
                    .isFalse();
        }

        @Test
        @DisplayName("题目没有可接受答案时判错，避免把空题当答对")
        void noAcceptedBlanksYieldsWrong() {
            QuizGrader.Graded graded = grade(cloze(null), List.of("随便"));

            assertThat(graded.correct()).isFalse();
            assertThat(graded.blankResults()).isEmpty();
        }

        @Test
        @DisplayName("回显把各空作答用竖线拼接")
        void userAnswerJoinsBlanks() {
            QuizGrader.Graded graded = grade(cloze(BLANKS), List.of(" 尾插 ", "oldCap"));

            assertThat(graded.userAnswer()).isEqualTo("尾插 | oldCap");
        }

        @Test
        @DisplayName("acceptedBlanks 原样回传给前端")
        void acceptedBlanksPassedThrough() {
            QuizGrader.Graded graded = grade(cloze(BLANKS), List.of("尾插", "oldCap"));

            assertThat(graded.acceptedBlanks())
                    .containsExactly(List.of("尾插", "尾部插入"), List.of("oldCap"));
        }
    }

    @Nested
    @DisplayName("可接受答案的解析")
    class ReadBlanks {

        @Test
        @DisplayName("null 与空白返回空列表")
        void nullOrBlankYieldsEmpty() {
            assertThat(grader.readBlanks(null)).isEmpty();
            assertThat(grader.readBlanks("   ")).isEmpty();
        }

        @Test
        @DisplayName("JSON 被改坏时降级为空列表，不让整批发卷失败")
        void malformedJsonDegradesToEmpty() {
            assertThat(grader.readBlanks("{不是合法 JSON")).isEmpty();
        }

        @Test
        @DisplayName("正常 JSON 按空分组解析")
        void parsesNestedArrays() {
            assertThat(grader.readBlanks("[[\"a\",\"b\"],[\"c\"]]"))
                    .containsExactly(List.of("a", "b"), List.of("c"));
        }
    }

    // ------------------------------------------------------------------

    private QuizGrader.Graded grade(QuizQuestion question, String answer) {
        return grader.grade(question, new QuizAnswer("q1", answer, null));
    }

    private QuizGrader.Graded grade(QuizQuestion question, List<String> blanks) {
        return grader.grade(question, new QuizAnswer("q1", null, blanks));
    }

    private QuizQuestion question(String type, String answer) {
        QuizQuestion question = new QuizQuestion();
        question.setType(type);
        question.setAnswer(answer);

        return question;
    }

    private QuizQuestion cloze(String blanksJson) {
        QuizQuestion question = new QuizQuestion();
        question.setType(QuizQuestion.TYPE_CLOZE);
        question.setBlanksJson(blanksJson);

        return question;
    }
}
