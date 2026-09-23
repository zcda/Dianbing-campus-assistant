package com.pkb.chunk;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 以条款为优先边界，章标题随条款一同进入索引，便于定位引用。 */
@Component
public class RuleChunkStrategy implements ChunkStrategy {
    private static final Pattern ARTICLE = Pattern.compile("^第[一二三四五六七八九十百千零〇0-9]+条(?!款)");
    private static final Pattern CHAPTER = Pattern.compile("^第[一二三四五六七八九十百千零〇0-9]+[章节]");
    private static final Pattern PROGRAM = Pattern.compile("^[\\p{IsHan}·（）()]{2,25} 全日制学术硕士培养方案$");
    private static final Pattern SECTION = Pattern.compile("^[一二三四五六七八九十]+、");
    private final HeadingChunkStrategy fallback;

    public RuleChunkStrategy(HeadingChunkStrategy fallback) {
        this.fallback = fallback;
    }

    @Override
    public String name() { return "rule"; }

    @Override
    public List<String> split(String content, ChunkBudget budget) {
        if (content == null || content.isBlank()) return List.of();
        if (content.contains("全日制学术硕士培养方案")) return splitCurriculum(content, budget);
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String chapter = "";
        boolean sawArticle = false;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            if (CHAPTER.matcher(trimmed).find()) {
                if (!current.isEmpty()) sections.add(current.toString());
                current = new StringBuilder();
                chapter = trimmed;
                continue;
            }
            if (ARTICLE.matcher(trimmed).find()) {
                if (!current.isEmpty()) sections.add(current.toString());
                current = new StringBuilder();
                if (!chapter.isBlank()) current.append(chapter).append('\n');
                sawArticle = true;
            }
            current.append(line).append('\n');
        }
        if (!current.isEmpty()) sections.add(current.toString());
        if (!sawArticle) return fallback.split(content, budget);
        List<String> result = new ArrayList<>();
        for (String section : sections) {
            // 极长条款沿用预算切分；通常一条保持完整，例外条件不会被拆散。
            result.addAll(fallback.split(section, budget));
        }
        return result;
    }

    private List<String> splitCurriculum(String content, ChunkBudget budget) {
        List<String> result = new ArrayList<>();
        String program = "";
        String sectionTitle = "";
        StringBuilder section = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            if (PROGRAM.matcher(trimmed).matches()) {
                addCurriculumSection(result, program, sectionTitle, section.toString(), budget);
                section = new StringBuilder();
                program = trimmed;
                sectionTitle = "";
            } else if (SECTION.matcher(trimmed).find() && !program.isBlank()) {
                addCurriculumSection(result, program, sectionTitle, section.toString(), budget);
                section = new StringBuilder();
                sectionTitle = trimmed;
            }
            section.append(line).append('\n');
        }
        addCurriculumSection(result, program, sectionTitle, section.toString(), budget);
        return result;
    }

    private void addCurriculumSection(List<String> result, String program, String title,
                                      String content, ChunkBudget budget) {
        String text = content.strip();
        if (text.isEmpty()) return;
        for (String piece : fallback.split(text, budget)) {
            String prefix = program.isBlank() ? "" : program + "\n";
            if (!title.isBlank() && !piece.startsWith(title)) prefix += title + "\n";
            if (!program.isBlank() && piece.startsWith(program)) prefix = "";
            result.add((prefix + piece).strip());
        }
    }
}
