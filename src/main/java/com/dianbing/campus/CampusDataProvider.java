package com.dianbing.campus;

import java.util.List;

/** Replaceable campus data boundary; the demo implementation reads a bundled fixture. */
public interface CampusDataProvider {
    Student student();
    List<Course> courses();
    List<Grade> grades();
    List<ClassMeeting> schedule();
    List<CreditRequirement> creditRequirements();
    List<Exam> exams();
    List<Classroom> classrooms();
    List<RoomBooking> roomBookings();
    List<CourseOffering> courseOfferings();

    record Student(String id, String name, String major, int cohort) {}
    record Course(String code, String name, int credits, String category, String semester,
                  String teacher, String description, List<String> prerequisites) {}
    record Grade(String courseCode, String semester, int score, String status) {}
    record ClassMeeting(String courseCode, String semester, int weekday, int startSection,
                        int endSection, String room) {}
    record CreditRequirement(String category, int requiredCredits) {}
    record Exam(String courseCode, String semester, String date, String startTime,
                String endTime, String room) {}
    record Classroom(String code, String building, int capacity) {}
    record RoomBooking(String roomCode, String semester, int weekday, int startSection,
                       int endSection, String courseCode) {}
    record OfferingMeeting(int weekday, int startSection, int endSection, String room) {}
    record CourseOffering(String courseCode, String semester, int capacity, int enrolled,
                          List<OfferingMeeting> meetings) {}
}
