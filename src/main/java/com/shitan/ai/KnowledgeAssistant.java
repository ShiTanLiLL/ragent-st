package com.shitan.ai;

import java.util.List;

public class KnowledgeAssistant {

    private final List<KnowledgeEntry> knowledgeEntries;

    public KnowledgeAssistant(List<KnowledgeEntry> knowledgeEntries) {
        this.knowledgeEntries = knowledgeEntries;
    }

    public KnowledgeAnswer answer(String question) {
        KnowledgeEntry bestKnowledge = null;
        int bestScore = 0;

        for (KnowledgeEntry knowledge : knowledgeEntries) {
            int score = calculateScore(question, knowledge);

            // 只有更高分才替换当前结果；零分知识不能成为回答依据。
            if (score > bestScore) {
                bestKnowledge = knowledge;
                bestScore = score;
            }
        }

        if (bestKnowledge == null) {
            return new KnowledgeAnswer("暂时没有找到相关知识。", null);
        }

        return new KnowledgeAnswer(bestKnowledge.content(), bestKnowledge.title());
    }

    private int calculateScore(String question, KnowledgeEntry knowledge) {
        int score = 0;

        for (String keyword : knowledge.keywords()) {
            if (question.contains(keyword)) {
                score++;
            }
        }

        return score;
    }
}
