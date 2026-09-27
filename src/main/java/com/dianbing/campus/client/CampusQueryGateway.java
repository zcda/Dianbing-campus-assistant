package com.dianbing.campus.client;

import com.dianbing.campus.CampusDataProvider;
import com.dianbing.campus.CampusService;

import java.util.List;
import java.util.Optional;

/** Campus queries consumed by the product; production is backed by an MCP client. */
public interface CampusQueryGateway {
    /** 当前登录学生的基础档案；尚未接入身份系统的实现可以返回空。 */
    default Optional<CampusDataProvider.Student> currentStudent() { return Optional.empty(); }
    List<CampusDataProvider.Course> searchCourses(String query, String semester);
    CampusDataProvider.Course courseDetail(String code);
    List<CampusService.GradeView> myGrades(String semester);
    List<CampusService.MeetingView> mySchedule(String semester, Integer weekday);
    CampusService.CreditGap myCreditGap();
    List<CampusService.ExamView> myExams(String semester);
    CampusService.RecommendationPlan recommendCourses(String semester);
    List<CampusService.FreeClassroom> findFreeClassrooms(String semester, Integer weekday,
            Integer startSection, Integer endSection, String building, Integer minCapacity);
    String currentSemester();
}
