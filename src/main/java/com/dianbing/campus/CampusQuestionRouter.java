package com.dianbing.campus;

import org.springframework.stereotype.Component;
import com.dianbing.campus.client.CampusQueryGateway;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic route for clearly personal questions about the fictional student. */
@Component
public class CampusQuestionRouter {
    private static final Pattern TERM = Pattern.compile("20\\d{2}[-－](春|秋)");
    private static final Pattern WEEKDAY = Pattern.compile("(?:周|星期)([一二三四五六日天1-7])");
    private static final Pattern SECTIONS = Pattern.compile("第\\s*(\\d{1,2})(?:\\s*[-–至到~]\\s*(\\d{1,2}))?\\s*节");
    private final CampusQueryGateway campus;
    private final CampusAgent campusAgent;
    private final CampusIntentPlanner intentPlanner;

    public CampusQuestionRouter(CampusQueryGateway campus) { this(campus, null, null); }

    public CampusQuestionRouter(CampusQueryGateway campus, CampusAgent campusAgent) {
        this(campus, campusAgent, null);
    }

    @Autowired
    public CampusQuestionRouter(CampusQueryGateway campus, CampusAgent campusAgent,
                                CampusIntentPlanner intentPlanner) {
        this.campus = campus;
        this.campusAgent = campusAgent;
        this.intentPlanner = intentPlanner;
    }

    public record Answer(String route, String text, String ruleQuery, int llmCalls, int toolCalls) {
        public Answer(String route, String text) { this(route, text, null, 0, 0); }
        public Answer(String route, String text, String ruleQuery) { this(route, text, ruleQuery, 0, 0); }
        public boolean needsRuleEvidence() { return ruleQuery != null; }
    }

    public Optional<Answer> answer(String question) {
        if (question == null || question.isBlank()) return Optional.empty();
        String q = question.strip();
        if (intentPlanner != null && intentPlanner.isCandidate(q)) {
            CampusIntentPlanner.Plan plan = intentPlanner.classify(q);
            if (plan.mixed()) {
                if (plan.needsClarification()) {
                    // 当前登录学生已有专业和年级时直接使用档案，不要求用户重复输入。
                    if (campus.currentStudent().isPresent()) {
                        return Optional.of(answerMixedRuleQuestion(q, q));
                    }
                    return Optional.of(new Answer("campus_intent_clarification", plan.clarification()));
                }
                return Optional.of(answerMixedRuleQuestion(q, plan.ruleQuery()));
            }
        }
        if ((q.contains("我") || q.contains("本人")) && isMixedRuleQuestion(q)) {
            return Optional.of(answerMixedRuleQuestion(q));
        }
        if (campusAgent != null) {
            Optional<Answer> agentAnswer = campusAgent.answer(q);
            if (agentAnswer.isPresent()) return agentAnswer;
        }
        if (q.contains("空教室") || q.contains("空闲教室")) {
            Integer day = findWeekday(q);
            Matcher sections = SECTIONS.matcher(q);
            if (day == null || !sections.find()) {
                return Optional.of(new Answer("free_classrooms_mock",
                        "请指定星期和节次，例如“2026-秋周二第3-4节有哪些空教室？”。仅提供虚构排课表推算。"));
            }
            int start = Integer.parseInt(sections.group(1));
            int end = sections.group(2) == null ? start : Integer.parseInt(sections.group(2));
            try {
                String term = findTerm(q);
                if (term == null) term = campus.currentSemester();
                var rooms = campus.findFreeClassrooms(term, day, start, end, null, null);
                StringBuilder text = new StringBuilder("**Mock 空教室 · ").append(term)
                        .append(" 周").append(day).append(" 第 ").append(start).append("–")
                        .append(end).append(" 节**\n\n");
                if (rooms.isEmpty()) text.append("没有符合条件的模拟空教室。\n");
                for (var room : rooms) {
                    text.append("- ").append(room.code()).append("（").append(room.building())
                            .append("，").append(room.capacity()).append(" 座）\n");
                }
                text.append("\n基于虚构整校每周排课表推算，不代表实时占用或可预约状态。");
                return Optional.of(new Answer("free_classrooms_mock", text.toString()));
            } catch (IllegalArgumentException ex) {
                return Optional.of(new Answer("free_classrooms_mock", ex.getMessage()));
            }
        }
        if ((q.contains("查课程") || q.contains("找课程") || q.contains("有哪些课程") || q.contains("课程列表"))
                && !q.contains("课程表") && !q.contains("课表")) {
            String semester = findTerm(q);
            String keyword = q.replaceAll("20\\d{2}[-－](春|秋)", "")
                    .replace("帮我", "").replace("请", "").replace("查询", "").replace("查", "")
                    .replace("找", "").replace("有哪些", "").replace("课程列表", "")
                    .replace("课程", "").replace("？", "").replace("?", "").strip();
            var courses = Optional.ofNullable(campus.searchCourses(keyword.isBlank() ? null : keyword, semester))
                    .orElseGet(List::of);
            StringBuilder text = new StringBuilder("**Mock 课程查询**");
            if (semester != null) text.append(" · ").append(semester);
            text.append("\n\n");
            if (courses.isEmpty()) text.append("没有找到匹配的模拟课程。\n");
            for (var course : courses) {
                text.append("- ").append(course.code()).append(" ").append(course.name())
                        .append("（").append(course.credits()).append(" 学分）\n");
            }
            text.append("\n以上为虚构课程目录，不代表真实开课信息。");
            return Optional.of(new Answer("courses_mock", text.toString()));
        }
        if (!(q.contains("我") || q.contains("本人"))) return Optional.empty();
        String semester = findTerm(q);
        if (q.contains("推荐") && q.contains("课")) {
            var plan = campus.recommendCourses(semester == null ? campus.currentSemester() : semester);
            StringBuilder text = new StringBuilder("**Mock 选课建议 · ").append(plan.semester()).append("**\n\n")
                    .append("已通过课程对应的学分尚缺 ").append(plan.missingPassedCredits())
                    .append("；本学期已排课 ").append(plan.plannedCredits())
                    .append(" 学分，假设全部通过后仍缺 ").append(plan.projectedMissingCredits())
                    .append(" 学分。\n\n");
            if (plan.candidates().isEmpty()) text.append("没有满足先修条件与类别缺口的模拟候选课。\n");
            for (var course : plan.candidates()) {
                text.append("- ").append(course.courseCode()).append(" ")
                        .append(course.courseName()).append("（").append(course.credits())
                        .append(" 学分）：").append(course.reason()).append("\n");
            }
            var excluded = plan.decisions().stream().filter(d -> !d.eligible()).toList();
            if (!excluded.isEmpty()) {
                text.append("\n未推荐的模拟课程：\n");
                for (var decision : excluded) {
                    text.append("- ").append(decision.courseCode()).append("：")
                            .append(decision.reason()).append("\n");
                }
            }
            text.append("\n仅依据虚构培养要求、名额和周课表计算；真实开课及选课状态尚未接入。");
            return Optional.of(new Answer("recommendations_mock", text.toString()));
        }
        if (q.contains("学分缺口") || q.contains("还差多少学分") || q.contains("修够学分")) {
            var gap = campus.myCreditGap();
            StringBuilder text = new StringBuilder("**Mock 学分分析（虚构培养要求）**：要求 ")
                    .append(gap.requiredCredits()).append(" 学分，已通过 ")
                    .append(gap.earnedCredits()).append(" 学分，尚缺 ")
                    .append(gap.missingCredits()).append(" 学分。\n\n");
            for (var category : gap.categories()) {
                text.append("- ").append(category.category()).append("：已修 ")
                        .append(category.earnedCredits()).append(" / 要求 ")
                        .append(category.requiredCredits()).append("，尚缺 ")
                        .append(category.missingCredits()).append(" 学分\n");
            }
            text.append("\n这不是根据真实培养方案作出的毕业资格判断。可在[校园服务演示](/campus.html)核对明细。");
            return Optional.of(new Answer("credit_gap_mock", text.toString()));
        }
        if (q.contains("考试安排") || q.contains("什么时候考试") || q.contains("考试时间")) {
            var exams = campus.myExams(semester);
            StringBuilder text = new StringBuilder("**Mock 考试安排（演示学生）**\n\n");
            if (exams.isEmpty()) text.append("指定学期没有模拟考试安排。\n");
            for (var exam : exams) {
                text.append("- ").append(exam.date()).append(" ")
                        .append(exam.startTime()).append("–").append(exam.endTime())
                        .append("：").append(exam.courseName()).append("，")
                        .append(exam.room()).append("\n");
            }
            text.append("\n以上为虚构安排，请勿据此参加真实考试。");
            return Optional.of(new Answer("exams_mock", text.toString()));
        }
        if (q.contains("成绩") && !q.contains("成绩规定") && !q.contains("成绩要求")
                && !q.contains("合格要求")) {
            var grades = campus.myGrades(semester);
            StringBuilder text = new StringBuilder("**Mock 成绩（演示学生）**");
            if (semester != null) text.append(" · ").append(semester);
            text.append("\n\n");
            if (grades.isEmpty()) text.append("该学期没有模拟成绩记录。\n");
            for (var grade : grades) {
                text.append("- ").append(grade.courseCode()).append(" ")
                        .append(grade.courseName()).append("：").append(grade.score())
                        .append(" 分（").append(grade.status()).append("）\n");
            }
            text.append("\n以上均为虚构数据，非真实教务成绩。可在[校园服务演示](/campus.html)核对明细。");
            if (q.contains("毕业") || q.contains("培养方案") || q.contains("规定")) {
                text.append("当前不能用这些模拟成绩判断是否符合真实培养方案或学校规定。");
            }
            return Optional.of(new Answer("grades_mock", text.toString()));
        }
        if (q.contains("课表") || q.contains("上什么课")) {
            Integer day = findWeekday(q);
            var meetings = campus.mySchedule(semester, day);
            StringBuilder text = new StringBuilder("**Mock 课表（演示学生）**\n\n");
            if (meetings.isEmpty()) text.append("指定条件下没有模拟课程安排。\n");
            for (var meeting : meetings) {
                text.append("- 周").append(meeting.weekday()).append(" 第 ")
                        .append(meeting.startSection()).append("–")
                        .append(meeting.endSection()).append(" 节：")
                        .append(meeting.courseName()).append("，")
                        .append(meeting.room()).append("\n");
            }
            text.append("\n以上均为虚构数据，非真实课表。可在[校园服务演示](/campus.html)核对明细。");
            return Optional.of(new Answer("schedule_mock", text.toString()));
        }
        return Optional.empty();
    }

    private String findTerm(String q) {
        Matcher matcher = TERM.matcher(q);
        return matcher.find() ? matcher.group().replace('－', '-') : null;
    }

    private boolean isMixedRuleQuestion(String q) {
        boolean personal = q.contains("成绩") || q.contains("已修学分") || q.contains("我的学分")
                || q.contains("我修了") || q.contains("我还差");
        boolean compare = q.contains("是否满足") || q.contains("能否满足")
                || q.contains("够不够") || q.contains("是否达到") || q.contains("是否达标")
                || q.contains("符合") || q.contains("能毕业") || q.contains("还差多少");
        boolean rule = q.contains("培养方案") || q.contains("毕业") || q.contains("学分要求");
        return personal && compare && rule;
    }

    private Answer answerMixedRuleQuestion(String q) {
        String ruleQuery = q.replaceFirst("^(?:我的)?(?:成绩|已修学分|学分)(?:是否|能否|够不够)?满足", "")
                .replaceFirst("^(?:我)?(?:是否|能否)达到", "");
        if (ruleQuery.isBlank()) ruleQuery = q;
        return answerMixedRuleQuestion(q, ruleQuery);
    }

    private Answer answerMixedRuleQuestion(String q, String ruleQuery) {
        var student = campus.currentStudent().orElse(null);
        ruleQuery = enrichRuleQuery(ruleQuery, student);
        var gap = campus.myCreditGap();
        StringBuilder text = new StringBuilder("**Mock 个人学业数据（演示）**：已通过 ")
                .append(gap.earnedCredits()).append(" 学分（");
        for (int i = 0; i < gap.categories().size(); i++) {
            var category = gap.categories().get(i);
            if (i > 0) text.append("；");
            text.append(category.category()).append(" ").append(category.earnedCredits()).append(" 学分");
        }
        text.append("）。");
        if (student != null) {
            text.append("当前学生档案：").append(student.major()).append("，")
                    .append(student.cohort()).append(" 级。");
        }
        text.append("本次演示将把这些数据作为当前用户的教务数据，与检索到的培养方案进行联合判断。");
        return new Answer("campus_rule_mix_mock", text.toString(), ruleQuery);
    }

    private String enrichRuleQuery(String ruleQuery, CampusDataProvider.Student student) {
        String resolved = ruleQuery == null || ruleQuery.isBlank() ? "毕业学分要求" : ruleQuery.strip();
        if (student == null) return resolved;
        var scope = com.dianbing.qa.retrieval.RuleScope.from(resolved);
        StringBuilder prefix = new StringBuilder();
        if (scope.discipline() == null && student.major() != null && !student.major().isBlank()) {
            prefix.append(student.major()).append(' ');
        }
        if (scope.academicYear() == null && student.cohort() > 0) {
            prefix.append(student.cohort()).append("级 ");
        }
        return prefix.append(resolved).toString().strip();
    }

    private Integer findWeekday(String q) {
        Matcher matcher = WEEKDAY.matcher(q);
        if (!matcher.find()) return null;
        return switch (matcher.group(1)) {
            case "一", "1" -> 1;
            case "二", "2" -> 2;
            case "三", "3" -> 3;
            case "四", "4" -> 4;
            case "五", "5" -> 5;
            case "六", "6" -> 6;
            default -> 7;
        };
    }
}
