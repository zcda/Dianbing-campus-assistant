package com.pkb.mcp;

import com.pkb.demo.CampusDemoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = CampusDemoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CampusMcpHttpTest {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void mcpDiscoveryAndGradeCallUseFictionalStudent() throws Exception {
        var initialized = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}", null);
        assertEquals(200, initialized.statusCode(), initialized.body());
        assertTrue(initialized.body().contains("dianbing-campus-mock"));
        String session = initialized.headers().firstValue("Mcp-Session-Id").orElse(null);

        var listed = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", session);
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(listed.body().contains("get_my_grades"));
        assertTrue(listed.body().contains("search_courses"));
        assertTrue(listed.body().contains("calculate_my_credit_gap"));
        assertTrue(listed.body().contains("get_my_exams"));
        assertTrue(listed.body().contains("recommend_courses"));
        assertTrue(listed.body().contains("find_free_classrooms"));

        var grade = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_my_grades\",\"arguments\":{\"semester\":\"2025-秋\"}}}", session);
        assertEquals(200, grade.statusCode(), grade.body());
        assertTrue(grade.body().contains("fictional-mock"));
        assertTrue(grade.body().contains("DEMO-001"));
        assertTrue(grade.body().contains("CS501"));
        assertFalse(grade.body().contains("student_id"));

        var gap = post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"calculate_my_credit_gap\",\"arguments\":{}}}", session);
        assertEquals(200, gap.statusCode(), gap.body());
        assertTrue(gap.body().contains("missingCredits"), gap.body());

        var exams = post("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"get_my_exams\",\"arguments\":{\"semester\":\"2026-秋\"}}}", session);
        assertEquals(200, exams.statusCode(), exams.body());
        assertTrue(exams.body().contains("2026-12-18"), exams.body());

        var recommendation = post("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"recommend_courses\",\"arguments\":{\"semester\":\"2026-秋\"}}}", session);
        assertEquals(200, recommendation.statusCode(), recommendation.body());
        assertTrue(recommendation.body().contains("CS506"), recommendation.body());
        assertTrue(recommendation.body().contains("CS505"), recommendation.body());
        assertTrue(recommendation.body().contains("先修课未全部通过"), recommendation.body());

        var freeRooms = post("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"find_free_classrooms\",\"arguments\":{\"semester\":\"2026-秋\",\"weekday\":2,\"startSection\":3,\"endSection\":4}}}", session);
        assertEquals(200, freeRooms.statusCode(), freeRooms.body());
        assertTrue(freeRooms.body().contains("A103"), freeRooms.body());
        assertTrue(freeRooms.body().contains("B203"), freeRooms.body());
        assertFalse(freeRooms.body().contains("A104"), freeRooms.body());
        assertFalse(freeRooms.body().contains("B202"), freeRooms.body());

        var forbidden = post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_my_grades\",\"arguments\":{\"studentId\":\"OTHER\"}}}", session);
        assertEquals(200, forbidden.statusCode(), forbidden.body());
        assertTrue(forbidden.body().contains("isError"), forbidden.body());
        assertTrue(forbidden.body().contains("true"), forbidden.body());

        var invalidDay = post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"get_my_schedule\",\"arguments\":{\"weekday\":8}}}", session);
        assertEquals(200, invalidDay.statusCode(), invalidDay.body());
        assertTrue(invalidDay.body().contains("isError"), invalidDay.body());

        var crossOrigin = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Origin", "https://untrusted.example")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}"))
                .build();
        assertEquals(403, client.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());

        var preview = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/campus/demo"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, preview.statusCode());
        assertTrue(preview.body().contains("DEMO-001"));
        assertTrue(preview.body().contains("fictional-mock"));
        assertTrue(preview.body().contains("creditGap"));
    }

    private HttpResponse<String> post(String body, String session) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) builder.header("Mcp-Session-Id", session);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
