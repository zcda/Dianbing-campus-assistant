package com.dianbing.campus;

import com.dianbing.campus.client.CampusQueryGateway;
import java.util.List;

/** Test adapter for router tests that do not start the separate MCP server. */
public final class CampusServiceQueryGateway implements CampusQueryGateway {
    private final CampusService service;
    public CampusServiceQueryGateway(CampusService service) { this.service = service; }
    public List<CampusDataProvider.Course> searchCourses(String q, String s) { return service.searchCourses(q, s); }
    public CampusDataProvider.Course courseDetail(String c) { return service.courseDetail(c); }
    public List<CampusService.GradeView> myGrades(String s) { return service.myGrades(s); }
    public List<CampusService.MeetingView> mySchedule(String s, Integer d) { return service.mySchedule(s, d); }
    public CampusService.CreditGap myCreditGap() { return service.myCreditGap(); }
    public List<CampusService.ExamView> myExams(String s) { return service.myExams(s); }
    public CampusService.RecommendationPlan recommendCourses(String s) { return service.recommendCourses(s); }
    public List<CampusService.FreeClassroom> findFreeClassrooms(String s, Integer d, Integer a, Integer b, String building, Integer capacity) {
        return service.findFreeClassrooms(s, d, a, b, building, capacity);
    }
    public String currentSemester() { return service.currentSemester(); }
}
