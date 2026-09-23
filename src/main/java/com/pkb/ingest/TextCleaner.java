package com.pkb.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本归一化。
 *
 * <p>PDF/Word 里没有"段落"这种语义单位，抽出来的文本通常带有：每页重复的页眉页脚、
 * 独立的页码行、被硬换行切断的句子。这些不清理直接切片会显著拉低检索质量，
 * 所以 layoutCleanup=true 时额外做去样板行与段落合并。
 */
@Component
public class TextCleaner {

    /** 连续空格/制表符压成单个空格 */
    private static final Pattern INLINE_BLANK = Pattern.compile("[ \\t\\u00a0]+");
    /** 纯页码行（允许带"第 x 页""- 3 -"等修饰） */
    private static final Pattern PAGE_NUMBER = Pattern.compile("^[\\s\\-—–·第页/]*\\d{1,4}[\\s\\-—–·第页/]*$");
    /** 句末标点：以此结尾说明是完整句，后面的换行是段落分隔而非硬换行 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。．.！!？?：:；;）)】」”\"]$");
    /** 块级起始标记：标题、列表、引用、有序列表 */
    private static final Pattern BLOCK_START = Pattern.compile(
            "^(#{1,6}\\s|[-*+]\\s|>\\s|\\d+[.)]\\s|第[一二三四五六七八九十百千零〇0-9]+[章节条]|"
                    + "[一二三四五六七八九十]+、|.{2,35}全日制学术硕士培养方案$)");

    /** 只移除明确的页码和页脚格式。跨专业重复的学制、学分原文必须保留。 */
    private static final Pattern PAGE_FOOTER = Pattern.compile("^[—–-]\\s*\\d{1,4}\\s*[—–-].*");
    private static final Pattern SCHOOL_HEADER = Pattern.compile("^电子科技大学全日制学术学位硕士研究生培养方案$");
    /** 参与合并的行长度下限，兜底防止短文档里把标题吞进正文 */
    private static final int MIN_PARAGRAPH_LENGTH = 20;
    /** 取较长行的 90 分位近似"正文行宽"，短于该宽度的 60% 视为标题/标签，不参与合并 */
    private static final double WIDTH_PERCENTILE = 0.9;
    private static final double PROSE_WIDTH_RATIO = 0.6;
    /** 段落之间最多保留的空行数 */
    private static final int MAX_BLANK_LINES = 1;

    public String clean(String raw, boolean layoutCleanup) {
        List<String> lines = normalize(raw);
        if (layoutCleanup) {
            lines = dropBoilerplate(lines);
            lines = mergeWrappedLines(lines);
        }
        return join(lines);
    }

    private List<String> normalize(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(INLINE_BLANK.matcher(line).replaceAll(" ").strip());
        }
        return lines;
    }

    private List<String> dropBoilerplate(List<String> lines) {
        List<String> kept = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (PAGE_NUMBER.matcher(line).matches() || PAGE_FOOTER.matcher(line).matches()
                    || SCHOOL_HEADER.matcher(line).matches()) {
                continue;
            }
            kept.add(line);
        }
        return kept;
    }

    private List<String> mergeWrappedLines(List<String> lines) {
        int threshold = mergeThreshold(lines);
        List<String> merged = new ArrayList<>(lines.size());
        int pendingBlank = 0;
        for (String line : lines) {
            if (line.isEmpty()) {
                pendingBlank++;
                continue;
            }
            int last = merged.size() - 1;
            if (last >= 0 && shouldMerge(merged.get(last), line, threshold)) {
                merged.set(last, joinWrapped(merged.get(last), line));
            } else {
                if (pendingBlank > 0 && last >= 0) merged.add("");
                merged.add(line);
            }
            pendingBlank = 0;
        }
        return merged;
    }

    /**
     * 用行宽推断哪些行是"正文行"。PDF 里一个段落会被硬换行切成多行，
     * 而这些行通常都接近正文栏宽；标题、图注这类明显更短的行不能参与合并。
     */
    private int mergeThreshold(List<String> lines) {
        List<Integer> lengths = new ArrayList<>();
        for (String line : lines) {
            if (!line.isEmpty()) {
                lengths.add(line.length());
            }
        }
        if (lengths.isEmpty()) {
            return MIN_PARAGRAPH_LENGTH;
        }
        lengths.sort(Comparator.naturalOrder());
        int index = (int) Math.round(WIDTH_PERCENTILE * (lengths.size() - 1));
        return Math.max(MIN_PARAGRAPH_LENGTH, (int) Math.round(PROSE_WIDTH_RATIO * lengths.get(index)));
    }

    private boolean shouldMerge(String previous, String next, int threshold) {
        if (previous.isEmpty() || next.isEmpty() || previous.length() < threshold) {
            return false;
        }
        if (BLOCK_START.matcher(next).find()) return false;
        return !SENTENCE_END.matcher(previous).find() && !BLOCK_START.matcher(next).find();
    }

    /** 西文硬换行处补空格（连字符断行不补），中文直接拼接 */
    private String joinWrapped(String previous, String next) {
        char tail = previous.charAt(previous.length() - 1);
        char head = next.charAt(0);
        boolean needSpace = tail < 128 && head < 128 && tail != '-' && !Character.isWhitespace(tail);
        return needSpace ? previous + " " + next : previous + next;
    }

    private String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        int blankRun = 0;
        for (String line : lines) {
            if (line.isEmpty()) {
                blankRun++;
                if (blankRun > MAX_BLANK_LINES) {
                    continue;
                }
            } else {
                blankRun = 0;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }
}
