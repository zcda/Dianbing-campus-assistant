package com.pkb.campus;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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

    @GetMapping("/api/campus/free-classrooms")
    public Map<String, Object> freeClassrooms(@RequestParam Integer weekday,
                                                @RequestParam Integer startSection,
                                                @RequestParam Integer endSection,
                                                @RequestParam(required = false) String semester,
                                                @RequestParam(required = false) String building,
                                                @RequestParam(required = false) Integer minCapacity) {
        try {
            var rooms = campus.findFreeClassrooms(semester, weekday, startSection, endSection,
                    building, minCapacity);
            String term = semester == null || semester.isBlank() ? campus.currentSemester() : semester.strip();
            return Map.of("dataSource", "fictional-mock", "semester", term,
                    "weekday", weekday, "startSection", startSection, "endSection", endSection,
                    "rooms", rooms);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
