package com.pkb.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pkb.campus.CampusService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.util.function.Function;

/** Local demo MCP endpoint backed solely by the campus domain service. */
@Configuration
public class CampusMcpConfig {
    @Bean
    HttpServletStreamableServerTransportProvider campusMcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/mcp")
                .securityValidator(headers -> {
                    String host = header(headers, "host");
                    String origin = header(headers, "origin");
                    if (host == null || !(host.startsWith("127.0.0.1:") || host.startsWith("localhost:"))) {
                        throw new ServerTransportSecurityException(403, "MCP demo requires a loopback Host");
                    }
                    if (origin != null) {
                        try {
                            URI uri = URI.create(origin);
                            if (!"http".equals(uri.getScheme()) ||
                                    !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())) ||
                                    !host.equalsIgnoreCase(uri.getAuthority())) {
                                throw new ServerTransportSecurityException(403, "Invalid MCP Origin");
                            }
                        } catch (IllegalArgumentException ex) {
                            throw new ServerTransportSecurityException(403, "Invalid MCP Origin");
                        }
                    }
                }).build();
    }

    @Bean
    ServletRegistrationBean<?> campusMcpServlet(HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, "/mcp");
    }

    @Bean
    McpSyncServer campusMcpServer(HttpServletStreamableServerTransportProvider transport,
                                  CampusService campus, ObjectMapper mapper) {
        var server = McpServer.sync(transport).serverInfo("dianbing-campus-mock", "0.1.0")
                .instructions("Only fictional demo student data is available. Grades and schedules are not real academic records.");

        server.toolCall(tool("search_courses", "Search demo courses by name or code and optional semester",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"semester\":{\"type\":\"string\"}},\"additionalProperties\":false}"),
                (exchange, request) -> result(mapper, request.arguments(), Set.of("query", "semester"), a ->
                        campus.searchCourses(string(a, "query"), string(a, "semester"))));
        server.toolCall(tool("get_course_detail", "Get one demo course by code",
                "{\"type\":\"object\",\"properties\":{\"courseCode\":{\"type\":\"string\"}},\"required\":[\"courseCode\"],\"additionalProperties\":false}"),
                (exchange, request) -> result(mapper, request.arguments(), Set.of("courseCode"), a ->
                        campus.courseDetail(string(a, "courseCode"))));
        server.toolCall(tool("get_my_grades", "Get grades for the fixed fictional demo student; optional semester",
                "{\"type\":\"object\",\"properties\":{\"semester\":{\"type\":\"string\"}},\"additionalProperties\":false}"),
                (exchange, request) -> result(mapper, request.arguments(), Set.of("semester"), a ->
                        Map.of("student", campus.currentStudent(), "grades", campus.myGrades(string(a, "semester")))));
        server.toolCall(tool("get_my_schedule", "Get schedule for the fixed fictional demo student",
                "{\"type\":\"object\",\"properties\":{\"semester\":{\"type\":\"string\"},\"weekday\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":7}},\"additionalProperties\":false}"),
                (exchange, request) -> result(mapper, request.arguments(), Set.of("semester", "weekday"), a ->
                        Map.of("student", campus.currentStudent(), "meetings", campus.mySchedule(
                                string(a, "semester"), integer(a, "weekday")))));
        server.toolCall(tool("calculate_my_credit_gap", "Calculate missing credits against fictional demo requirements; not a graduation audit",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
                (exchange, request) -> result(mapper, request.arguments(), Set.of(), a ->
                        Map.of("student", campus.currentStudent(), "creditGap", campus.myCreditGap())));
        server.toolCall(tool("get_my_exams", "Get examination dates for the fixed fictional demo student",
                "{\"type\":\"object\",\"properties\":{\"semester\":{\"type\":\"string\"}},\"additionalProperties\":false}"),
                (exchange, request) -> result(mapper, request.arguments(), Set.of("semester"), a ->
                        Map.of("student", campus.currentStudent(), "exams", campus.myExams(string(a, "semester")))));
        server.toolCall(tool("recommend_courses", "Recommend fictional demo courses using credit gap, current enrollment and prerequisites",
                "{\"type\":\"object\",\"properties\":{\"semester\":{\"type\":\"string\"}},\"required\":[\"semester\"],\"additionalProperties\":false}"),
                (exchange, request) -> result(mapper, request.arguments(), Set.of("semester"), a ->
                        Map.of("student", campus.currentStudent(), "plan", campus.recommendCourses(string(a, "semester")))));
        server.toolCall(tool("find_free_classrooms", "Find rooms free for every section in a weekly fictional timetable; not live availability",
                "{\"type\":\"object\",\"properties\":{\"semester\":{\"type\":\"string\"},\"weekday\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":7},\"startSection\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":12},\"endSection\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":12},\"building\":{\"type\":\"string\"},\"minCapacity\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"weekday\",\"startSection\",\"endSection\"],\"additionalProperties\":false}"),
                (exchange, request) -> result(mapper, request.arguments(), Set.of("semester", "weekday",
                        "startSection", "endSection", "building", "minCapacity"), a -> {
                    Integer day = integer(a, "weekday");
                    Integer start = integer(a, "startSection");
                    Integer end = integer(a, "endSection");
                    String term = string(a, "semester");
                    var rooms = campus.findFreeClassrooms(term, day, start, end,
                            string(a, "building"), integer(a, "minCapacity"));
                    return Map.of("semester", term == null || term.isBlank() ? campus.currentSemester() : term.strip(),
                            "weekday", day, "startSection", start, "endSection", end, "rooms", rooms);
                }));
        return server.build();
    }

    private McpSchema.Tool tool(String name, String description, String schema) {
        return McpSchema.Tool.builder().name(name).description(description)
                .inputSchema(McpJsonDefaults.getMapper(), schema).build();
    }

    private McpSchema.CallToolResult result(ObjectMapper mapper, Map<String, Object> args, Set<String> allowed,
                                            Function<Map<String, Object>, Object> action) {
        try {
            if (args == null) args = Map.of();
            for (String key : args.keySet()) {
                if (!allowed.contains(key)) throw new IllegalArgumentException("不支持的参数: " + key);
            }
            Object value = Map.of("dataSource", "fictional-mock", "result", action.apply(args));
            String json = mapper.writeValueAsString(value);
            return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(json)))
                    .structuredContent(value).build();
        } catch (IllegalArgumentException | java.util.NoSuchElementException ex) {
            return McpSchema.CallToolResult.builder().addTextContent(ex.getMessage()).isError(true).build();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("MCP result serialization failed", ex);
        }
    }

    private String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof String text) return text;
        throw new IllegalArgumentException(key + " 必须为字符串");
    }

    private Integer integer(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof Number number && number.doubleValue() == number.intValue()) return number.intValue();
        throw new IllegalArgumentException(key + " 必须为整数");
    }

    private String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
    }
}
