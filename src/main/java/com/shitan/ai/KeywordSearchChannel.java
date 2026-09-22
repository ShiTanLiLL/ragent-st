package com.shitan.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 精确关键词召回通道：优先保护工单号、错误码、数字和原文短语。
 */
@Component
public class KeywordSearchChannel implements SearchChannel {

    private static final Pattern EXACT_TERM = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_-]{2,}|\\d+(?:\\.\\d+)?|[\\p{IsHan}]{2,8}"
    );

    private final KnowledgeRepository knowledgeRepository;

    /**
     * 保存可按作用域读取文本片段的知识仓库。
     */
    public KeywordSearchChannel(KnowledgeRepository knowledgeRepository) {
        this.knowledgeRepository = knowledgeRepository;
    }

    @Override
    public String name() {
        return "keyword";
    }

    /**
     * 提取可精确比较的词，在数据库返回的作用域片段中按出现次数排序。
     */
    @Override
    public SearchChannelResult search(SearchQuery query) {
        long startedAt = System.nanoTime();
        List<String> terms = extractTerms(query.question());
        if (terms.isEmpty()) {
            return new SearchChannelResult(name(), List.of(), elapsedMillis(startedAt));
        }
        List<RetrievedEvidence> candidates = knowledgeRepository.searchKeywordCandidates(
                query.knowledgeBaseIds(),
                terms,
                query.limit()
        );
        return new SearchChannelResult(name(), candidates, elapsedMillis(startedAt));
    }

    /**
     * 保留字母数字编号和 2～8 个汉字的连续短语，去重但维持原问题顺序。
     */
    private List<String> extractTerms(String question) {
        Matcher matcher = EXACT_TERM.matcher(question);
        Set<String> terms = new LinkedHashSet<>();
        while (matcher.find()) {
            String term = matcher.group().strip().toLowerCase(Locale.ROOT);
            if (!term.isBlank()) {
                terms.add(term);
            }
        }
        return new ArrayList<>(terms);
    }

    /**
     * 把纳秒起点转换为本通道可观察的毫秒耗时。
     */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
