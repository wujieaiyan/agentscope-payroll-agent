package io.agentscope.examples.payroll.kb.ocr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OcrQualityGateTest {

    @Test
    @DisplayName("字符数与字符占比达标时放行")
    void acceptsReadableText() {
        OcrQualityGate gate = new OcrQualityGate(8, 0.5, 0, 0);

        OcrQualityGate.Verdict verdict = gate.evaluate("工资条 2026 年 11 月", 0.9);

        assertTrue(verdict.passed(), verdict.detail());
    }

    @Test
    @DisplayName("字符数不足时拒绝并给出原因")
    void rejectsShortText() {
        OcrQualityGate gate = new OcrQualityGate(8, 0.5, 0, 0);

        OcrQualityGate.Verdict verdict = gate.evaluate("工资条", 0.9);

        assertFalse(verdict.passed());
        assertTrue(verdict.detail().contains("字符数"));
    }

    @Test
    @DisplayName("纯标点噪声按有效字符占比拒绝")
    void rejectsPunctuationNoise() {
        OcrQualityGate gate = new OcrQualityGate(2, 0.5, 0, 0);

        OcrQualityGate.Verdict verdict = gate.evaluate("，。？！……、、——", 0.9);

        assertFalse(verdict.passed());
        assertTrue(verdict.detail().contains("有效字符占比"));
    }

    @Test
    @DisplayName("中文占比阈值生效")
    void rejectsLowCjkRatio() {
        OcrQualityGate gate = new OcrQualityGate(2, 0, 0.5, 0);

        OcrQualityGate.Verdict verdict = gate.evaluate("gross salary", 0.9);

        assertFalse(verdict.passed());
        assertTrue(verdict.detail().contains("中文占比"));
    }

    @Test
    @DisplayName("平均置信度低于阈值时拒绝")
    void rejectsLowConfidence() {
        OcrQualityGate gate = new OcrQualityGate(2, 0, 0, 0.6);

        OcrQualityGate.Verdict verdict = gate.evaluate("工资条 2026 年 11 月", 0.3);

        assertFalse(verdict.passed());
        assertTrue(verdict.detail().contains("置信度"));
    }

    @Test
    @DisplayName("越界阈值被夹到 [0,1]：NaN 视为 0，超过 1 按 1 处理")
    void clampsThresholds() {
        OcrQualityGate gate = new OcrQualityGate(-1, Double.NaN, -2, 1.5);

        assertTrue(gate.evaluate(null, 1.0).passed());
        assertFalse(gate.evaluate("工资条 2026 年 11 月", 0.99).passed());
    }
}
