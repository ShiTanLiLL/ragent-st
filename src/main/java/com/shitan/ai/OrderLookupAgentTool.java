package com.shitan.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 教学用本地只读订单查询；证明 Agent 可以把知识规则和业务事实组合起来。
 */
@Component
public class OrderLookupAgentTool implements AgentTool {

    private static final Map<String, OrderSnapshot> ORDERS = Map.of(
            "ORD-1001", new OrderSnapshot("ORD-1001", "delivered", 3, "耳机"),
            "ORD-1002", new OrderSnapshot("ORD-1002", "delivered", 12, "键盘")
    );

    @Override
    public String name() {
        return "order_lookup";
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                name(),
                "按订单号读取订单状态、签收天数和商品；这是只读工具，不会退款",
                List.of("orderId")
        );
    }

    /**
     * 查询固定教学订单；不存在时返回可观察结果，让模型决定如何向用户说明。
     */
    @Override
    public String execute(Map<String, String> arguments, AgentScope scope) {
        String orderId = arguments.get("orderId");
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("工具参数不能为空：orderId");
        }
        OrderSnapshot order = ORDERS.get(orderId.strip().toUpperCase());
        if (order == null) {
            return "订单不存在：" + orderId.strip();
        }
        return "订单号=" + order.id()
                + "，状态=" + order.status()
                + "，签收天数=" + order.deliveredDays()
                + "，商品=" + order.product();
    }

    /**
     * 一条只读订单快照，不包含任何修改订单的方法。
     */
    private record OrderSnapshot(String id, String status, int deliveredDays, String product) {
    }
}
