package com.oneclickredactor.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java redaction engine shared by the Android UI and local unit tests. */
public final class RedactionEngine {
    public static final String DEFAULT_RULES =
            "# 一行一条补充脱敏规则，适合添加专案中的姓名、地址、企业名、账号等。\n" +
            "# 写法一：张三\n" +
            "# 效果：文档里的“张三”会替换为“***”。\n" +
            "# 写法二：某某科技有限公司=>涉案企业\n" +
            "# 效果：左边原文会替换为右边内容。\n" +
            "# 以 # 开头的行会被忽略。\n";

    private static final String COURT_ROLE =
            "(?:委托诉讼代理人|申请执行人|再审申请人|代理审判员|人民陪审员|法定代理人|委托代理人|诉讼代理人|法定代表人|实际控制人|犯罪嫌疑人|被申请人|被上诉人|被执行人|法官助理|合议庭成员|审判员|审判长|书记员|执行员|检察员|公诉人|辩护人|被告人|申请人|上诉人|申诉人|被申诉人|被害人|受害人|嫌疑人|第三人|鉴定人|翻译人员|负责人|联系人|经办人|当事人|委托人|受托人|中介|原告|被告|证人|户主|所有人)";
    private static final String POLICE_ROLE =
            "(?:公安机关负责人|办案部门负责人|证据保全申请人|委托诉讼代理人|法定代理人|案件承办人|主办侦查员|协办侦查员|辨认主持人|主持辨认人|犯罪嫌疑人|违法嫌疑人|违法行为人|行政相对人|被询问人|被讯问人|被调查人|被检查人|被搜查人|被处罚人|被传唤人|被拘传人|受害单位联系人|证据提供人|物品持有人|物品所有人|办案民警|承办民警|主办民警|协办民警|现场民警|办案人员|侦查人员|调查人员|询问人员|讯问人员|记录人员|执法人员|接警人员|处警人员|勘验人员|检查人员|搜查人员|鉴定人员|审核人员|审批人员|翻译人员|被辨认人|被告知人|受送达人|涉案人员|报案人|报警人|控告人|举报人|扭送人|投案人|自首人|被害人|受害人|见证人|辨认人|陪衬人|鉴定人|勘验人|检查人|搜查人|提取人|扣押人|保管人|领取人|送达人|告知人|陈述人|申辩人|监护人|近亲属|家属|驾驶人|驾驶员|承办人|审核人|审批人|询问人|讯问人|调查人|侦查员|记录人|接警人|处警人|负责人|联系人|经办人|民警|警员)";
    private static final String COMPOUND_SURNAMES =
            "欧阳|太史|端木|上官|司马|东方|独孤|南宫|万俟|闻人|夏侯|诸葛|尉迟|公羊|赫连|澹台|皇甫|宗政|濮阳|公冶|太叔|申屠|公孙|慕容|仲孙|钟离|长孙|宇文|司徒|鲜于|司空|闾丘|子车|亓官|司寇|巫马|公西|颛孙|壤驷|公良|漆雕|乐正|宰父|谷梁|拓跋|夹谷|轩辕|令狐|段干|百里|呼延|东郭|南门|羊舌|微生|梁丘|左丘|东门|西门";
    private static final String SURNAMES =
            "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄穆萧尹姚邵汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗丁宣贲邓郁单杭洪包诸左石崔吉龚程邢滑裴陆荣翁荀羊於惠甄曲封芮储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘钭厉戎祖武符刘景詹束龙叶幸司韶郜黎蓟薄印宿白怀蒲台从鄂索咸籍赖卓蔺屠蒙池乔阴胥能苍双闻莘党翟谭贡劳姬申扶堵冉宰郦雍却璩桑桂濮牛寿通边扈燕冀郏浦尚农温别庄晏柴瞿阎连茹习艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公";
    private static final String LIKELY_NAME =
            "(?:(?:" + COMPOUND_SURNAMES + ")[\\u4e00-\\u9fa5·]{1,2}|[" + SURNAMES + "][\\u4e00-\\u9fa5·]{1,2}|[\\u4e00-\\u9fa5]{1,6}·[\\u4e00-\\u9fa5·]{1,10})";

    private final List<CustomRule> customRules;
    private final boolean enableAddressGuess;
    private final Set<String> learnedNames = new LinkedHashSet<>();
    private boolean preventTextGrowth;

    private final Pattern creditCode = p("(?<![A-Za-z0-9])(?=[0-9A-HJ-NPQRTUWXY]{18}(?![A-Za-z0-9]))(?=[0-9A-HJ-NPQRTUWXY]*[A-HJ-NPQRTUWXY])[0-9A-HJ-NPQRTUWXY]{2}\\d{6}[0-9A-HJ-NPQRTUWXY]{10}(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private final Pattern id18 = p("(?<![A-Za-z0-9])([1-9]\\d{5})(18|19|20)\\d{2}((0[1-9])|(1[0-2]))(([0-2]\\d)|(3[01]))\\d{3}[\\dXx](?![A-Za-z0-9])");
    private final Pattern id15 = p("(?<!\\d)[1-9]\\d{5}\\d{2}((0[1-9])|(1[0-2]))(([0-2]\\d)|(3[01]))\\d{3}(?!\\d)");
    private final Pattern mobile = p("(?<!\\d)(\\+?86[- ]?)?(1[3-9]\\d{9})(?!\\d)");
    private final Pattern landline = p("(?<!\\d)(0\\d{2,3})[- ]?(\\d{7,8})(?!\\d)");
    private final Pattern email = p("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private final Pattern bank = p("(?<!\\d)\\d{12,19}(?!\\d)");
    private final Pattern passport = p("(?<![A-Za-z0-9])(?:E|G|P|S|D)\\d{7,8}(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private final Pattern plate = p("(?<![\\u4e00-\\u9fa5A-Za-z0-9])[\\u4e00-\\u9fa5][A-Z][A-Z0-9]{5,6}(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private final Pattern nameLabel = p("((?:姓名|名字|被害人|受害人|嫌疑人|证人|联系人|负责人|法定代表人|实际控制人|经办人|申请人|被申请人|当事人|户主|所有人|收件人|发件人|开户名|户名)\\s*[:：]?\\s*)([\\u4e00-\\u9fa5·]{2,8})(?=\\s|$|[，,。；;、)）])");
    private final Pattern addressLabel = p("((?:家庭住址|户籍地址|户籍地|现住址|居住地|住址|住所地|地址|通讯地址|联系地址|注册地址|经营地址|办公地址|送达地址)\\s*[:：]?\\s*)([^\\r\\n，,。；;]{4,100})");
    private final Pattern companyLabel = p("((?:单位名称|企业名称|公司名称|机构名称|客户名称|供应商|采购方|销售方|用人单位|工作单位)\\s*[:：]?\\s*)([^\\r\\n，,。；;]{2,80})");
    private final Pattern companyName = p("[\\u4e00-\\u9fa5A-Za-z0-9（）()·\\-]{2,60}(?:有限责任公司|股份有限公司|集团有限公司|有限公司|分公司|合作社|个体工商户|事务所|研究所|医院|学校)");
    private final Pattern ageLabel = p("((?:年龄|年纪|岁数)\\s*[:：]?\\s*)([1-9]\\d?|1[01]\\d|120)(\\s*(?:岁|周岁)?)");
    private final Pattern ageSuffix = p("(?<!\\d)([1-9]\\d?|1[01]\\d|120)\\s*(岁|周岁)(?!\\d)");
    private final Pattern addressGuess = p("[\\u4e00-\\u9fa5]{2,}(?:省|自治区|市|区|县|旗)[\\u4e00-\\u9fa5A-Za-z0-9一二三四五六七八九十零〇号弄栋幢单元室楼层路街道巷镇乡村组\\-]{5,100}(?:号|室|单元|楼|层|村|组)");
    private final Pattern explicitRoleName = p("(?:" + COURT_ROLE + "|" + POLICE_ROLE + ")(?:[一二三四五六七八九十\\d]+)?\\s*[:：]\\s*(?<name>" + LIKELY_NAME + ")");
    private final Pattern inlineRoleName = p("(?:" + COURT_ROLE + "|" + POLICE_ROLE + ")(?:[一二三四五六七八九十\\d]+)?\\s*(?<name>" + LIKELY_NAME + ")(?=\\s|$|[，,。；;、：:（）()]|的|与|和|及|向|由|在|担任|负责|办理|承办|询问|讯问|调查|侦查|记录|到场|到案|到庭|男|女|系|称|说|表示|陈述|签字|签名|参加)");
    private final Pattern roleList = p("(?:" + COURT_ROLE + "|" + POLICE_ROLE + ")(?:[一二三四五六七八九十\\d]+)?[ \\t]*[:：][ \\t]*(?<names>[^\\r\\n。；;]{2,120})");
    private final Pattern likelyName = p(LIKELY_NAME);

    public RedactionEngine(List<CustomRule> customRules, boolean enableAddressGuess) {
        this.customRules = customRules == null ? Collections.emptyList() : new ArrayList<>(customRules);
        this.enableAddressGuess = enableAddressGuess;
    }

    public static List<CustomRule> parseRules(String text) {
        List<CustomRule> rules = new ArrayList<>();
        if (text == null) return rules;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String token = line;
            String replacement = "***";
            int arrow = line.indexOf("=>");
            if (arrow >= 0) {
                token = line.substring(0, arrow).trim();
                replacement = line.substring(arrow + 2).trim();
                if (replacement.isEmpty()) replacement = "***";
            }
            if (!token.isEmpty()) rules.add(new CustomRule(token, replacement));
        }
        rules.sort((a, b) -> Integer.compare(b.token.length(), a.token.length()));
        return rules;
    }

    public String anonymize(String text, Stats stats) {
        if (text == null || text.isEmpty()) return text;
        learnNamesFromText(text);
        String result = text;
        for (CustomRule rule : customRules) {
            result = replaceLiteral(result, rule.token, rule.replacement, "补充规则", stats);
        }
        List<String> names = new ArrayList<>(learnedNames);
        names.sort(Comparator.comparingInt(String::length).reversed());
        for (String name : names) {
            result = replaceLiteral(result, name, maskName(name), "姓名（角色识别）", stats);
        }

        result = replace(result, companyLabel, "企业/单位", stats, m -> group(m, 1) + "[单位已脱敏]");
        result = replace(result, addressLabel, "地址", stats, m -> group(m, 1) + "[地址已脱敏]");
        result = replace(result, nameLabel, "姓名", stats, m -> group(m, 1) + maskName(group(m, 2)));
        result = replace(result, creditCode, "统一社会信用代码", stats, m -> maskKeep(m.group().toUpperCase(Locale.ROOT), 4, 4));
        result = replace(result, id18, "身份证号", stats, m -> maskKeep(m.group(), 3, 4));
        result = replace(result, id15, "身份证号", stats, m -> maskKeep(m.group(), 3, 3));
        result = replace(result, mobile, "手机号", stats, m -> group(m, 1) + maskKeep(group(m, 2), 3, 4));
        result = replace(result, landline, "座机号", stats, m -> group(m, 1) + "-****" + right(group(m, 2), 2));
        result = replace(result, email, "邮箱", stats, m -> {
            String value = m.group();
            int at = value.indexOf('@');
            return at <= 0 ? "***" : value.substring(0, 1) + "***" + value.substring(at);
        });
        result = replace(result, passport, "护照号", stats, m -> maskKeep(m.group().toUpperCase(Locale.ROOT), 1, 2));
        result = replace(result, plate, "车牌号", stats, m -> maskKeep(m.group().toUpperCase(Locale.ROOT), 2, 1));
        result = replace(result, bank, "银行卡/账号", stats, m -> maskKeep(m.group(), 4, 4));
        result = replace(result, companyName, "企业/单位", stats, m -> "[单位已脱敏]");
        result = replace(result, ageLabel, "年龄", stats, m -> group(m, 1) + "**" + group(m, 3));
        result = replace(result, ageSuffix, "年龄", stats, m -> "**" + group(m, 2));
        if (enableAddressGuess) {
            result = replace(result, addressGuess, "地址", stats, m -> "[地址已脱敏]");
        }
        return result;
    }

    public String anonymizeWithoutTextGrowth(String text, Stats stats) {
        boolean previous = preventTextGrowth;
        preventTextGrowth = true;
        try {
            return anonymize(text, stats);
        } finally {
            preventTextGrowth = previous;
        }
    }

    public String anonymizeStrongIdentifiersOnly(String text, Stats stats) {
        if (text == null || text.isEmpty()) return text;
        String result = text;
        result = replace(result, creditCode, "统一社会信用代码", stats, m -> maskKeep(m.group().toUpperCase(Locale.ROOT), 4, 4));
        result = replace(result, id18, "身份证号", stats, m -> maskKeep(m.group(), 3, 4));
        result = replace(result, id15, "身份证号", stats, m -> maskKeep(m.group(), 3, 3));
        result = replace(result, mobile, "手机号", stats, m -> group(m, 1) + maskKeep(group(m, 2), 3, 4));
        result = replace(result, landline, "座机号", stats, m -> group(m, 1) + "-****" + right(group(m, 2), 2));
        return replace(result, bank, "银行卡/账号", stats, m -> maskKeep(m.group(), 4, 4));
    }

    public void learnNamesFromText(String text) {
        if (text == null || text.isEmpty()) return;
        learnFromNamedPattern(explicitRoleName, text, "name");
        learnFromNamedPattern(inlineRoleName, text, "name");
        Matcher lists = roleList.matcher(text);
        while (lists.find()) {
            Matcher names = likelyName.matcher(group(lists, "names"));
            while (names.find()) addLearnedName(names.group());
        }
    }

    private void learnFromNamedPattern(Pattern pattern, String text, String group) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) addLearnedName(group(matcher, group));
    }

    private void addLearnedName(String name) {
        if (name == null) return;
        name = name.trim();
        if (name.length() < 2 || name.length() > 18 || name.contains("某")) return;
        String[] fragments = {"人员", "民警", "情况", "内容", "单位", "公司", "机关", "部门", "地址", "电话", "身份", "材料", "记录", "签名", "指印", "时间", "地点", "是否", "什么", "如何", "为何", "知道", "清楚", "回答", "以上", "完毕", "不详", "没有", "无异议"};
        for (String fragment : fragments) if (name.contains(fragment)) return;
        learnedNames.add(name);
    }

    private String replaceLiteral(String input, String token, String replacement, String category, Stats stats) {
        return replace(input, Pattern.compile(Pattern.quote(token)), category, stats,
                m -> preventTextGrowth ? limitReplacementLength(m.group(), replacement) : replacement);
    }

    private String replace(String input, Pattern pattern, String category, Stats stats, Function<Matcher, String> evaluator) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer out = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            count++;
            String replacement = evaluator.apply(matcher);
            if (preventTextGrowth) replacement = limitReplacementLength(matcher.group(), replacement);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(out);
        stats.add(category, count);
        return out.toString();
    }

    private static String limitReplacementLength(String original, String replacement) {
        if (replacement == null || replacement.length() <= original.length()) return replacement;
        String compact = replacement.replace("[地址已脱敏]", "***").replace("[单位已脱敏]", "***");
        if (compact.length() > original.length()) compact = compact.replaceAll("\\*{2,}", "*");
        return compact.length() <= original.length() ? compact : compact.substring(0, original.length());
    }

    private static String maskName(String name) {
        if (name == null || name.isEmpty()) return "***";
        if (name.length() <= 2) return name.substring(0, 1) + "某";
        return name.substring(0, 1) + "某".repeat(Math.min(name.length() - 1, 3));
    }

    private static String maskKeep(String value, int left, int right) {
        if (value == null || value.isEmpty()) return "***";
        if (value.length() <= left + right) return "*".repeat(value.length());
        return value.substring(0, left) + "*".repeat(value.length() - left - right) + value.substring(value.length() - right);
    }

    private static String right(String value, int count) {
        if (value == null || value.isEmpty()) return "";
        return value.length() <= count ? value : value.substring(value.length() - count);
    }

    private static String group(Matcher matcher, int index) {
        String value = matcher.group(index);
        return value == null ? "" : value;
    }

    private static String group(Matcher matcher, String name) {
        String value = matcher.group(name);
        return value == null ? "" : value;
    }

    private static Pattern p(String expression) {
        return Pattern.compile(expression);
    }

    private static Pattern p(String expression, int flags) {
        return Pattern.compile(expression, flags);
    }

    public static final class CustomRule {
        public final String token;
        public final String replacement;

        public CustomRule(String token, String replacement) {
            this.token = token;
            this.replacement = replacement;
        }
    }

    public static final class Stats {
        private final Map<String, Integer> counts = new LinkedHashMap<>();

        public void add(String category, int count) {
            if (count > 0) counts.put(category, counts.getOrDefault(category, 0) + count);
        }

        public int total() {
            int total = 0;
            for (int value : counts.values()) total += value;
            return total;
        }

        public Map<String, Integer> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        }

        public String summary() {
            if (counts.isEmpty()) return "未发现规则命中";
            List<Map.Entry<String, Integer>> values = new ArrayList<>(counts.entrySet());
            values.sort((a, b) -> {
                int byCount = Integer.compare(b.getValue(), a.getValue());
                return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
            });
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : values) parts.add(entry.getKey() + " " + entry.getValue());
            return String.join("，", parts);
        }
    }
}
