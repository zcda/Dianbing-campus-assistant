package com.pkb.rag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 角标校验（设计文档 D7 / ⑧）：解析答案中的 [n] 角标并判断能否映射到本次 sources。
 *
 * <p>用途：生成后只埋点与评测，不改写答案 —— 模型漏打角标（F3）从"不会被发现"变成可量化。
 * 判定是纯字符串解析，确定性、零 token。
 */
public final class CitationCheck {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})]");

    private CitationCheck() {
    }

    /**
     * @param answer      最终答案
     * @param sourceCount 本次送进 Prompt 的片段数（角标合法区间 [1, sourceCount]）
     * @return total=角标总数（0 记为 0 并单列）；valid=可映射到本次 sources 的角标数
     */
    public static Result check(String answer, int sourceCount) {
        int total = 0;
        int valid = 0;
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            total++;
            int n = Integer.parseInt(matcher.group(1));
            if (n >= 1 && n <= sourceCount) {
                valid++;
            }
        }
        return new Result(total, valid);
    }

    public record Result(int total, int valid) {

        /** 角标可映射率 = 可映射到来源的角标数 / 角标总数；角标总数为 0 时单列（coverage 返回 0）。不验证语义支撑。 */
        public double coverage() {
            return total <= 0 ? 0.0 : (double) valid / total;
        }
    }
}
