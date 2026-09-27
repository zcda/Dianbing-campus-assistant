package com.dianbing.campus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class MockCampusDataProvider implements CampusDataProvider {
    private final Fixture fixture;

    public MockCampusDataProvider(ObjectMapper mapper) throws IOException {
        try (var input = new ClassPathResource("campus/mock-campus.json").getInputStream()) {
            fixture = mapper.readValue(input, Fixture.class);
        }
    }

    public Student student() { return fixture.student(); }
    public List<Course> courses() { return fixture.courses(); }
    public List<Grade> grades() { return fixture.grades(); }
    public List<ClassMeeting> schedule() { return fixture.schedule(); }
    public List<CreditRequirement> creditRequirements() { return fixture.creditRequirements(); }
    public List<Exam> exams() { return fixture.exams(); }
    public List<Classroom> classrooms() { return fixture.classrooms(); }
    public List<RoomBooking> roomBookings() { return fixture.roomBookings(); }
    public List<CourseOffering> courseOfferings() { return fixture.courseOfferings(); }

    private record Fixture(Student student, List<Course> courses, List<Grade> grades,
                           List<ClassMeeting> schedule, List<CreditRequirement> creditRequirements,
                           List<Exam> exams, List<Classroom> classrooms,
                           List<RoomBooking> roomBookings, List<CourseOffering> courseOfferings) {}
}
