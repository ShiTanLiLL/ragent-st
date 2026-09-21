package com.shitan.ai;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * 把完整问题拆成子问题，并根据知识库名称中的领域词给出最小作用域判断。
 *
 * <p>本课先使用可观察、可重复的本地规则，不把“意图树”直接变成一个无法调试的模型黑盒。
 * 后续如果规则不足，再让模型参与候选排序；本类仍保留最终的回落和澄清边界。</p>
 */
@Service
public class IntentPlanner {

    private final KnowledgeRepository knowledgeRepository;

    /**
     * 保存知识库查询入口；规划本身不负责向量检索和回答生成。
     *
     * @param knowledgeRepository 用于读取知识库编号和名称
     */
    public IntentPlanner(KnowledgeRepository knowledgeRepository) {
        this.knowledgeRepository = knowledgeRepository;
    }

    /**
     * 先按连接词拆分问题，再为每个子问题选择知识库作用域。
     *
     * @param question         已经完成会话改写的完整问题
     * @param requestedBaseId  调用方明确指定的知识库，可为空
     * @return 按原问题顺序排列的规划结果
     */
    public List<IntentPlan> plan(String question, String requestedBaseId) {
        List<KnowledgeBase> bases = knowledgeRepository.findAllKnowledgeBases();
        if (requestedBaseId != null && !requestedBaseId.isBlank()
                && bases.stream().noneMatch(base -> base.id().equals(requestedBaseId.strip()))) {
            throw new NoSuchElementException("知识库不存在：" + requestedBaseId.strip());
        }
        List<String> subQuestions = splitQuestions(question);
        List<IntentPlan> plans = new ArrayList<>();
        for (String subQuestion : subQuestions) {
            plans.add(planOne(subQuestion, requestedBaseId, bases));
        }
        return List.copyOf(plans);
    }

    /**
     * 将常见并列连接词转换成多个可独立检索的问题；没有连接词时保留原句。
     *
     * @param question 原始完整问题
     * @return 清理空白后的子问题
     */
    private List<String> splitQuestions(String question) {
        List<String> parts = Arrays.stream(
                        question.split("\\s*(?:并且|以及|同时|另外|；|;|\\n)\\s*")
                )
                .map(String::strip)
                .filter(part -> !part.isBlank())
                .toList();
        return parts.isEmpty() ? List.of(question.strip()) : parts;
    }

    /**
     * 对单个子问题计算知识库名称命中；同分候选视为歧义，没有命中则回落全库。
     */
    private IntentPlan planOne(
            String question,
            String requestedBaseId,
            List<KnowledgeBase> bases
    ) {
        if (requestedBaseId != null && !requestedBaseId.isBlank()) {
            return new IntentPlan(question, List.of(requestedBaseId.strip()), 1.0, false, false, null);
        }

        List<ScoredBase> ranked = bases.stream()
                .map(base -> new ScoredBase(base, score(question, base.name())))
                .sorted(Comparator.comparingInt(ScoredBase::score).reversed())
                .toList();
        if (ranked.isEmpty() || ranked.get(0).score() == 0) {
            return new IntentPlan(question, List.of(), 0.0, true, false, null);
        }

        int topScore = ranked.get(0).score();
        List<ScoredBase> tied = ranked.stream()
                .takeWhile(candidate -> candidate.score() == topScore)
                .toList();
        if (tied.size() > 1) {
            String names = tied.stream().map(candidate -> candidate.base().name()).toList().toString();
            return new IntentPlan(
                    question,
                    tied.stream().map(candidate -> candidate.base().id()).toList(),
                    0.5,
                    false,
                    true,
                    "你提到的“" + matchedWord(question, tied.get(0).base().name())
                            + "”可能属于多个知识库，请选择：" + names
            );
        }
        return new IntentPlan(
                question,
                List.of(tied.get(0).base().id()),
                0.9,
                false,
                false,
                null
        );
    }

    /**
     * 用知识库名称的路径片段作为最小意图词；完整片段命中优先于单个字符。
     */
    private int score(String question, String baseName) {
        String normalizedQuestion = question.toLowerCase(Locale.ROOT);
        return Arrays.stream(baseName.split("[/|>、，,：: ]+"))
                .map(String::strip)
                .filter(token -> token.length() >= 2)
                .mapToInt(token -> normalizedQuestion.contains(token.toLowerCase(Locale.ROOT)) ? token.length() : 0)
                .sum();
    }

    /**
     * 生成易懂的歧义词提示；找不到时使用通用“这个主题”。
     */
    private String matchedWord(String question, String baseName) {
        for (String token : baseName.split("[/|>、，,：: ]+")) {
            if (token.length() >= 2 && question.contains(token)) {
                return token;
            }
        }
        return "这个主题";
    }

    private record ScoredBase(KnowledgeBase base, int score) {
    }
}
