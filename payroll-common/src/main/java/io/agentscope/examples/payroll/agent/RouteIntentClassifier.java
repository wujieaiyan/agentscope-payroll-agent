package io.agentscope.examples.payroll.agent;

/**
 * 语义意图判定的最小契约：KB 语义层与薪酬语义桶共用，便于顾问层在不启动 ES 的前提下测试。
 */
public interface RouteIntentClassifier {

    boolean ready();

    SemanticRouteClassifier.Decision classify(String question);
}
