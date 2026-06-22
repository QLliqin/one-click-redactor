package com.oneclickredactor.mobile;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RedactionEngineTest {
    @Test
    public void redactsBuiltInIdentifiersAndKeepsUsefulFragments() {
        RedactionEngine engine = new RedactionEngine(List.of(), true);
        RedactionEngine.Stats stats = new RedactionEngine.Stats();
        String source = "姓名：张三，身份证号11010519491231002X，手机13800138000，" +
                "邮箱zhangsan@example.com，年龄32岁，住址：北京市海淀区中关村大街18号。";

        String changed = engine.anonymize(source, stats);

        assertTrue(changed.contains("姓名：张某"));
        // The desktop engine checks 18-character social-credit-code candidates first.
        assertTrue(changed, changed.contains("1101**********002X"));
        assertTrue(changed.contains("138****8000"));
        assertTrue(changed.contains("z***@example.com"));
        assertTrue(changed.contains("年龄**岁"));
        assertTrue(changed.contains("住址：[地址已脱敏]"));
        assertTrue(stats.total() >= 6);
        assertFalse(changed.contains("中关村大街18号"));
    }

    @Test
    public void learnsMultipleRoleNamesAcrossTheDocument() {
        RedactionEngine engine = new RedactionEngine(List.of(), true);
        RedactionEngine.Stats stats = new RedactionEngine.Stats();
        String changed = engine.anonymize("办案民警：张三、李四。张三询问，李四记录。", stats);

        assertTrue(changed.contains("张某"));
        assertTrue(changed.contains("李某"));
        assertFalse(changed.contains("张三"));
        assertFalse(changed.contains("李四"));
    }

    @Test
    public void parsesAndAppliesCustomRulesLongestFirst() {
        List<RedactionEngine.CustomRule> rules = RedactionEngine.parseRules(
                "# 注释\n某某科技有限公司=>涉案企业\n王五\n");
        RedactionEngine engine = new RedactionEngine(rules, false);
        RedactionEngine.Stats stats = new RedactionEngine.Stats();

        assertEquals("涉案企业与***", engine.anonymize("某某科技有限公司与王五", stats));
        assertEquals(2, stats.snapshot().get("补充规则").intValue());
    }

    @Test
    public void noGrowthModeNeverExpandsLegacyParagraph() {
        RedactionEngine engine = new RedactionEngine(List.of(), true);
        RedactionEngine.Stats stats = new RedactionEngine.Stats();
        String source = "地址：北京市海淀区中关村大街18号";
        String changed = engine.anonymizeWithoutTextGrowth(source, stats);
        assertTrue(changed.length() <= source.length());
    }
}
