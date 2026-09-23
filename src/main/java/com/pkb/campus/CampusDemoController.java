package com.pkb.campus;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Read-only preview for the same service used by MCP tools. */
@RestController
public class CampusDemoController {
    private final CampusService campus;

    public CampusDemoController(CampusService campus) { this.campus = campus; }

    @GetMapping("/api/campus/demo")
    public Map<String, Object> preview() {
        return Map.of("dataSource", "fictional-mock", "student", campus.currentStudent(),
                "courses", campus.searchCourses(null, null), "grades", campus.myGrades(null),
                "schedule", campus.mySchedule(null, null), "exams", campus.myExams(null),
                "creditGap", campus.myCreditGap(),
                "freeClassroomsExample", campus.findFreeClassrooms(campus.currentSemester(), 2, 3, 4, null, null),
                "recommendationPlan", campus.recommendCourses(campus.currentSemester()));
    }
}
