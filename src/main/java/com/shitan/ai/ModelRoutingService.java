package com.shitan.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * 按业务档位选择模型，并在一次调用失败时切换候选；每个候选独立维护三态健康状态。
 */
@Service
public class ModelRoutingService {

    private final ChatModelInvoker invoker;
    private final List<ManagedChatModel> candidates;

    /**
     * 从配置读取候选，格式为 fast=qwen-turbo-latest,standard=qwen-plus-latest,deep=qwen-max-latest。
     * 没有配置时只启用已经验证过的 standard 模型，不假装存在不可用候选。
     */
    @Autowired
    public ModelRoutingService(
            BailianChatModelInvoker invoker,
            @Value("${ragent.chat-models:standard=qwen-plus-latest}") String configuredModels
    ) {
        this(invoker, parseCandidates(configuredModels));
    }

    /**
     * 为测试或未来配置入口保存明确的候选列表。
     */
    public ModelRoutingService(ChatModelInvoker invoker, List<ManagedChatModel> candidates) {
        this.invoker = invoker;
        this.candidates = List.copyOf(candidates);
        if (this.candidates.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个模型候选");
        }
    }

    /**
     * 按请求档位尝试候选；首个非空响应成功后立即返回，失败候选进入 OPEN 状态。
     *
     * @param tier       请求需要的模型档位
     * @param question   用户问题
     * @param evidences  已经通过检索和精排的证据
     * @return 第一个成功模型生成的答案
     * @throws IOException 所有候选都失败时返回最后一个 IO 异常
     * @throws InterruptedException 当前线程被中断
     */
    public String generate(ModelTier tier, String question, List<KnowledgeEntry> evidences)
            throws IOException, InterruptedException {
        Exception lastFailure = null;
        for (ManagedChatModel candidate : orderedCandidates(tier)) {
            if (!candidate.tryAcquire()) {
                continue;
            }
            try {
                String answer = invoker.invoke(candidate.model(), question, evidences);
                if (answer == null || answer.isBlank()) {
                    throw new IllegalStateException("模型返回空正文：" + candidate.model());
                }
                candidate.markSuccess();
                return answer;
            } catch (IOException | InterruptedException exception) {
                candidate.markFailure();
                lastFailure = exception;
                if (exception instanceof InterruptedException interrupted) {
                    throw interrupted;
                }
            } catch (RuntimeException exception) {
                candidate.markFailure();
                lastFailure = exception;
            }
        }
        if (lastFailure instanceof IOException ioException) {
            throw ioException;
        }
        if (lastFailure instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        throw new IllegalStateException("没有健康的模型候选", lastFailure);
    }

    /**
     * 返回候选状态快照，便于测试观察哪个模型被隔离。
     */
    public Map<String, ModelHealthState> healthStates() {
        Map<String, ModelHealthState> states = new java.util.LinkedHashMap<>();
        for (ManagedChatModel candidate : candidates) {
            states.put(candidate.model(), candidate.state());
        }
        return Map.copyOf(states);
    }

    /**
     * 先按请求档位筛选，再把其他档位作为降级候选，最后按 priority 稳定排序。
     */
    private List<ManagedChatModel> orderedCandidates(ModelTier tier) {
        return candidates.stream()
                .sorted(Comparator.comparingInt((ManagedChatModel candidate) ->
                        candidate.tier() == tier ? 0 : 1)
                        .thenComparingInt(ManagedChatModel::priority))
                .toList();
    }

    /**
     * 把环境配置转换为模型候选，同时拒绝重复模型名和未知档位。
     */
    private static List<ManagedChatModel> parseCandidates(String configuredModels) {
        List<ManagedChatModel> parsed = new ArrayList<>();
        Set<String> modelNames = new HashSet<>();
        if (configuredModels == null || configuredModels.isBlank()) {
            return List.of(new ManagedChatModel("qwen-plus-latest", ModelTier.STANDARD, 0));
        }
        String[] items = configuredModels.split(",");
        for (int index = 0; index < items.length; index++) {
            String item = items[index].strip();
            String[] parts = item.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            ModelTier tier;
            try {
                tier = ModelTier.valueOf(parts[0].strip().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            String model = parts[1].strip();
            if (!model.isBlank() && modelNames.add(model)) {
                parsed.add(new ManagedChatModel(model, tier, index));
            }
        }
        return parsed;
    }
}
