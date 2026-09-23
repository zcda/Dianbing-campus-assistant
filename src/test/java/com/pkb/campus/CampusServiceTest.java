package com.pkb.campus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CampusServiceTest {
    private final CampusService service;

    CampusServiceTest() throws Exception {
        service = new CampusService(new MockCampusDataProvider(new ObjectMapper()));
    }

    @Test
    void gradeAndScheduleAreJoinedToCourseCatalog() {
        assertEquals(3, service.myGrades("2025-秋").size());
        assertEquals("高级算法", service.myGrades("2025-秋").get(0).courseName());
        assertEquals(3, service.mySchedule("2026-秋", 2).get(0).startSection());
        assertEquals("分布式系统", service.mySchedule("2026-秋", 2).get(0).courseName());
        assertTrue(service.myGrades("2027-春").isEmpty());
    }

    @Test
    void courseFiltersAndInputValidation() {
        assertEquals("CS502", service.searchCourses("机器", "2025-秋").get(0).code());
        assertEquals("数据库系统", service.courseDetail("cs503").name());
        assertThrows(IllegalArgumentException.class, () -> service.mySchedule(null, 8));
        assertThrows(java.util.NoSuchElementException.class, () -> service.courseDetail("missing"));
    }

    @Test
    void creditGapUsesPassedCreditsByCategory() {
        var gap = service.myCreditGap();
        assertEquals(14, gap.requiredCredits());
        assertEquals(8, gap.earnedCredits());
        assertEquals(6, gap.missingCredits());
        assertEquals(6, gap.categories().get(0).missingCredits());
    }

    @Test
    void retakenCourseCountsOnlyOnceAndFailedCourseCountsZero() throws Exception {
        CampusDataProvider base = new MockCampusDataProvider(new ObjectMapper());
        CampusDataProvider retakes = new CampusDataProvider() {
            public Student student() { return base.student(); }
            public List<Course> courses() { return base.courses(); }
            public List<Grade> grades() { return List.of(
                    new Grade("CS501", "2026-秋", 91, "已通过"),
                    new Grade("CS501", "2027-春", 94, "已通过"),
                    new Grade("CS502", "2026-秋", 59, "未通过")); }
            public List<ClassMeeting> schedule() { return base.schedule(); }
            public List<CreditRequirement> creditRequirements() { return base.creditRequirements(); }
            public List<Exam> exams() { return base.exams(); }
            public List<Classroom> classrooms() { return base.classrooms(); }
            public List<RoomBooking> roomBookings() { return base.roomBookings(); }
            public List<CourseOffering> courseOfferings() { return base.courseOfferings(); }
        };
        assertEquals(3, new CampusService(retakes).myCreditGap().earnedCredits());
    }

    @Test
    void examsJoinCourseNamesAndFilterByTerm() {
        assertEquals(2, service.myExams("2026-秋").size());
        assertEquals("数据库系统", service.myExams("2026-秋").get(0).courseName());
        assertTrue(service.myExams("2027-春").isEmpty());
    }

    @Test
    void recommendationsAccountForPassedPlannedAndMissingPrerequisites() {
        var plan = service.recommendCourses("2026-秋");
        assertEquals(6, plan.missingPassedCredits());
        assertEquals(4, plan.plannedCredits());
        assertEquals(2, plan.projectedMissingCredits());
        assertEquals(List.of("CS506"), plan.candidates().stream().map(CampusService.Recommendation::courseCode).toList());
        assertTrue(plan.candidates().get(0).reason().contains("模拟剩余 15 个名额"));
        assertEquals("先修课未全部通过", plan.decisions().stream()
                .filter(d -> d.courseCode().equals("CS505")).findFirst().orElseThrow().reason());
        assertThrows(IllegalArgumentException.class, () -> service.recommendCourses(null));
    }

    @Test
    void recommendationRejectsFullAndConflictingOfferingWithReasons() throws Exception {
        CampusDataProvider base = new MockCampusDataProvider(new ObjectMapper());
        CampusDataProvider changed = new CampusDataProvider() {
            public Student student() { return base.student(); }
            public List<Course> courses() { return base.courses(); }
            public List<Grade> grades() { return base.grades(); }
            public List<ClassMeeting> schedule() { return base.schedule(); }
            public List<CreditRequirement> creditRequirements() { return base.creditRequirements(); }
            public List<Exam> exams() { return base.exams(); }
            public List<Classroom> classrooms() { return base.classrooms(); }
            public List<RoomBooking> roomBookings() { return base.roomBookings(); }
            public List<CourseOffering> courseOfferings() { return List.of(
                    new CourseOffering("CS506", "2026-秋", 40, 40,
                            List.of(new OfferingMeeting(2, 4, 5, "A104")))); }
        };
        var plan = new CampusService(changed).recommendCourses("2026-秋");
        assertTrue(plan.candidates().isEmpty());
        var decision = plan.decisions().stream().filter(d -> d.courseCode().equals("CS506"))
                .findFirst().orElseThrow();
        assertFalse(decision.eligible());
        assertTrue(decision.reason().contains("名额已满"));
        assertTrue(decision.reason().contains("节次冲突"));
    }

    @Test
    void freeRoomsExcludeAnyOverlappingBookingAndHonorFilters() {
        assertEquals(List.of("A103", "B203"), service.findFreeClassrooms("2026-秋", 2, 3, 4,
                null, null).stream().map(CampusService.FreeClassroom::code).toList());
        assertEquals(List.of("A103"), service.findFreeClassrooms("2026-秋", 2, 3, 4,
                "A楼", 50).stream().map(CampusService.FreeClassroom::code).toList());
        assertEquals(4, service.findFreeClassrooms("2026-秋", 2, 7, 8, null, null).size());
        assertThrows(IllegalArgumentException.class,
                () -> service.findFreeClassrooms(null, null, 3, 4, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.findFreeClassrooms(null, 2, 4, 3, null, null));
    }
}
