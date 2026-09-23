package com.pkb.campus;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CampusService {
    private final CampusDataProvider data;

    public CampusService(CampusDataProvider data) { this.data = data; }

    public record GradeView(String courseCode, String courseName, int credits, String semester,
                            int score, String status) {}
    public record MeetingView(String courseCode, String courseName, String semester, int weekday,
                              int startSection, int endSection, String room) {}
    public record CategoryGap(String category, int requiredCredits, int earnedCredits, int missingCredits) {}
    public record CreditGap(int requiredCredits, int earnedCredits, int missingCredits,
                            List<CategoryGap> categories) {}
    public record ExamView(String courseCode, String courseName, String semester, String date,
                           String startTime, String endTime, String room) {}
    public record Recommendation(String courseCode, String courseName, int credits, String category,
                                 List<String> prerequisites, String reason) {}
    public record RecommendationDecision(String courseCode, boolean eligible, String reason) {}
    public record RecommendationPlan(String semester, int missingPassedCredits,
                                     int plannedCredits, int projectedMissingCredits,
                                     List<Recommendation> candidates, List<RecommendationDecision> decisions) {}
    public record FreeClassroom(String code, String building, int capacity) {}

    public CampusDataProvider.Student currentStudent() { return data.student(); }
    public String currentSemester() {
        return data.schedule().stream().map(CampusDataProvider.ClassMeeting::semester)
                .max(String::compareTo).orElseThrow(() -> new IllegalStateException("Mock 课表没有学期"));
    }

    public List<CampusDataProvider.Course> searchCourses(String query, String semester) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String term = semester == null ? "" : semester.strip();
        return data.courses().stream()
                .filter(c -> term.isEmpty() || c.semester().equals(term))
                .filter(c -> needle.isEmpty() || c.code().toLowerCase(Locale.ROOT).contains(needle)
                        || c.name().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(CampusDataProvider.Course::code)).toList();
    }

    public CampusDataProvider.Course courseDetail(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("courseCode 不能为空");
        return data.courses().stream().filter(c -> c.code().equalsIgnoreCase(code.strip()))
                .findFirst().orElseThrow(() -> new NoSuchElementException("课程不存在: " + code));
    }

    public List<GradeView> myGrades(String semester) {
        String term = semester == null ? "" : semester.strip();
        return data.grades().stream().filter(g -> term.isEmpty() || g.semester().equals(term))
                .map(g -> {
                    var course = courseDetail(g.courseCode());
                    return new GradeView(course.code(), course.name(), course.credits(),
                            g.semester(), g.score(), g.status());
                }).sorted(Comparator.comparing(GradeView::courseCode)).toList();
    }

    public List<MeetingView> mySchedule(String semester, Integer weekday) {
        if (weekday != null && (weekday < 1 || weekday > 7)) {
            throw new IllegalArgumentException("weekday 必须为 1 到 7");
        }
        String term = semester == null ? "" : semester.strip();
        return data.schedule().stream()
                .filter(m -> term.isEmpty() || m.semester().equals(term))
                .filter(m -> weekday == null || m.weekday() == weekday)
                .map(m -> new MeetingView(m.courseCode(), courseDetail(m.courseCode()).name(),
                        m.semester(), m.weekday(), m.startSection(), m.endSection(), m.room()))
                .sorted(Comparator.comparingInt(MeetingView::weekday)
                        .thenComparingInt(MeetingView::startSection)).toList();
    }

    /** Only passed courses count; repeated grades for one course contribute credits once. */
    public CreditGap myCreditGap() {
        Map<String, CampusDataProvider.Course> catalog = data.courses().stream()
                .collect(Collectors.toMap(CampusDataProvider.Course::code, Function.identity()));
        Map<String, Integer> earned = passedCodes().stream()
                .map(catalog::get).filter(java.util.Objects::nonNull)
                .collect(Collectors.groupingBy(CampusDataProvider.Course::category,
                        Collectors.summingInt(CampusDataProvider.Course::credits)));
        List<CategoryGap> categories = data.creditRequirements().stream()
                .map(r -> new CategoryGap(r.category(), r.requiredCredits(),
                        earned.getOrDefault(r.category(), 0),
                        Math.max(0, r.requiredCredits() - earned.getOrDefault(r.category(), 0))))
                .toList();
        int required = categories.stream().mapToInt(CategoryGap::requiredCredits).sum();
        int passed = categories.stream().mapToInt(CategoryGap::earnedCredits).sum();
        int missing = categories.stream().mapToInt(CategoryGap::missingCredits).sum();
        return new CreditGap(required, passed, missing, categories);
    }

    public List<ExamView> myExams(String semester) {
        String term = semester == null ? "" : semester.strip();
        return data.exams().stream()
                .filter(e -> term.isEmpty() || e.semester().equals(term))
                .map(e -> new ExamView(e.courseCode(), courseDetail(e.courseCode()).name(),
                        e.semester(), e.date(), e.startTime(), e.endTime(), e.room()))
                .sorted(Comparator.comparing(ExamView::date).thenComparing(ExamView::startTime))
                .toList();
    }

    /** Weekly mock timetable only; a room is free for the entire requested section interval. */
    public List<FreeClassroom> findFreeClassrooms(String semester, Integer weekday,
                                                  Integer startSection, Integer endSection,
                                                  String building, Integer minCapacity) {
        if (weekday == null || weekday < 1 || weekday > 7) {
            throw new IllegalArgumentException("weekday 必须为 1 到 7");
        }
        if (startSection == null || endSection == null || startSection < 1 ||
                endSection > 12 || startSection > endSection) {
            throw new IllegalArgumentException("节次范围必须为 1 到 12，且 startSection 不大于 endSection");
        }
        if (minCapacity != null && minCapacity < 1) {
            throw new IllegalArgumentException("minCapacity 必须大于 0");
        }
        String term = semester == null || semester.isBlank() ? currentSemester() : semester.strip();
        String requestedBuilding = building == null ? "" : building.strip();
        Set<String> occupied = data.roomBookings().stream()
                .filter(b -> term.equals(b.semester()) && b.weekday() == weekday)
                .filter(b -> b.startSection() <= endSection && startSection <= b.endSection())
                .map(CampusDataProvider.RoomBooking::roomCode).collect(Collectors.toSet());
        return data.classrooms().stream()
                .filter(r -> !occupied.contains(r.code()))
                .filter(r -> requestedBuilding.isEmpty() || requestedBuilding.equals(r.building()))
                .filter(r -> minCapacity == null || r.capacity() >= minCapacity)
                .sorted(Comparator.comparing(CampusDataProvider.Classroom::code))
                .map(r -> new FreeClassroom(r.code(), r.building(), r.capacity())).toList();
    }

    /** A planning aid based on mock requirements, prerequisites, seats and timetable collisions. */
    public RecommendationPlan recommendCourses(String semester) {
        if (semester == null || semester.isBlank()) throw new IllegalArgumentException("semester 不能为空");
        String term = semester.strip();
        Set<String> passed = passedCodes();
        Set<String> plannedCodes = data.schedule().stream().filter(m -> term.equals(m.semester()))
                .map(CampusDataProvider.ClassMeeting::courseCode).filter(c -> !passed.contains(c))
                .collect(Collectors.toSet());
        Map<String, Integer> plannedByCategory = plannedCodes.stream().map(this::courseDetail)
                .collect(Collectors.groupingBy(CampusDataProvider.Course::category,
                        Collectors.summingInt(CampusDataProvider.Course::credits)));
        CreditGap gap = myCreditGap();
        Map<String, Integer> projectedGap = gap.categories().stream().collect(Collectors.toMap(
                CategoryGap::category, c -> Math.max(0,
                        c.missingCredits() - plannedByCategory.getOrDefault(c.category(), 0))));
        Map<String, CampusDataProvider.CourseOffering> offerings = data.courseOfferings().stream()
                .filter(o -> term.equals(o.semester()))
                .collect(Collectors.toMap(CampusDataProvider.CourseOffering::courseCode, Function.identity()));
        List<RecommendationDecision> decisions = data.courses().stream()
                .filter(c -> term.equals(c.semester()))
                .filter(c -> !passed.contains(c.code()) && !plannedCodes.contains(c.code()))
                .filter(c -> projectedGap.getOrDefault(c.category(), 0) > 0)
                .sorted(Comparator.comparing(CampusDataProvider.Course::code))
                .map(c -> assessCourse(c, offerings.get(c.code()), passed, term))
                .toList();
        List<Recommendation> candidates = decisions.stream().filter(RecommendationDecision::eligible)
                .map(d -> {
                    var c = courseDetail(d.courseCode());
                    return new Recommendation(c.code(), c.name(), c.credits(), c.category(),
                            c.prerequisites() == null ? List.of() : c.prerequisites(), d.reason());
                }).toList();
        int plannedCredits = plannedByCategory.values().stream().mapToInt(Integer::intValue).sum();
        int projectedMissing = projectedGap.values().stream().mapToInt(Integer::intValue).sum();
        return new RecommendationPlan(term, gap.missingCredits(), plannedCredits,
                projectedMissing, candidates, decisions);
    }

    private RecommendationDecision assessCourse(CampusDataProvider.Course course,
                                                 CampusDataProvider.CourseOffering offering,
                                                 Set<String> passed, String term) {
        List<String> reasons = new java.util.ArrayList<>();
        if (course.prerequisites() != null && !passed.containsAll(course.prerequisites())) {
            reasons.add("先修课未全部通过");
        }
        if (offering == null || offering.meetings() == null || offering.meetings().isEmpty()) {
            reasons.add("缺少该学期的开课时间或容量数据");
        } else {
            if (offering.capacity() <= offering.enrolled()) reasons.add("模拟名额已满");
            boolean conflict = offering.meetings().stream().anyMatch(o -> data.schedule().stream()
                    .filter(m -> term.equals(m.semester()) && m.weekday() == o.weekday())
                    .anyMatch(m -> m.startSection() <= o.endSection()
                            && o.startSection() <= m.endSection()));
            if (conflict) reasons.add("与当前课表节次冲突");
        }
        if (!reasons.isEmpty()) return new RecommendationDecision(course.code(), false,
                String.join("；", reasons));
        return new RecommendationDecision(course.code(), true,
                "可补充" + course.category() + "缺口；先修条件满足；模拟剩余 "
                        + (offering.capacity() - offering.enrolled()) + " 个名额；与当前课表无冲突");
    }

    private Set<String> passedCodes() {
        return data.grades().stream().filter(g -> g.score() >= 60 && "已通过".equals(g.status()))
                .map(CampusDataProvider.Grade::courseCode).collect(Collectors.toCollection(HashSet::new));
    }
}
