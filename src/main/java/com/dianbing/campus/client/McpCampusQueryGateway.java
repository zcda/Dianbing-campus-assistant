package com.dianbing.campus.client;

import com.dianbing.campus.CampusDataProvider;
import com.dianbing.campus.CampusService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class McpCampusQueryGateway implements CampusQueryGateway {
    private static final Set<String> ALLOWED_TOOLS = Set.of("search_courses", "get_course_detail",
            "get_my_grades", "get_my_schedule", "calculate_my_credit_gap", "get_my_exams",
            "recommend_courses", "find_free_classrooms");
    private final String serverUrl;
    private final ObjectMapper mapper;
    private volatile McpSyncClient client;

    public McpCampusQueryGateway(ObjectMapper mapper,
            @Value("${campus.mcp.server-url:http://127.0.0.1:8090}") String serverUrl) {
        this.mapper = mapper;
        this.serverUrl = serverUrl;
    }

    @Override public Optional<CampusDataProvider.Student> currentStudent() {
        JsonNode student = call("calculate_my_credit_gap", Map.of()).path("student");
        if (student.isMissingNode() || student.isNull()) return Optional.empty();
        return Optional.of(mapper.convertValue(student, CampusDataProvider.Student.class));
    }
    @Override public List<CampusDataProvider.Course> searchCourses(String query, String semester) {
        return list("search_courses", args("query", query, "semester", semester), null,
                CampusDataProvider.Course.class);
    }
    @Override public CampusDataProvider.Course courseDetail(String code) {
        return one("get_course_detail", args("courseCode", code), "result", CampusDataProvider.Course.class);
    }
    @Override public List<CampusService.GradeView> myGrades(String semester) {
        return list("get_my_grades", args("semester", semester), "grades", CampusService.GradeView.class);
    }
    @Override public List<CampusService.MeetingView> mySchedule(String semester, Integer weekday) {
        return list("get_my_schedule", args("semester", semester, "weekday", weekday), "meetings", CampusService.MeetingView.class);
    }
    @Override public CampusService.CreditGap myCreditGap() {
        return one("calculate_my_credit_gap", Map.of(), "creditGap", CampusService.CreditGap.class);
    }
    @Override public List<CampusService.ExamView> myExams(String semester) {
        return list("get_my_exams", args("semester", semester), "exams", CampusService.ExamView.class);
    }
    @Override public CampusService.RecommendationPlan recommendCourses(String semester) {
        return one("recommend_courses", args("semester", semester), "plan", CampusService.RecommendationPlan.class);
    }
    @Override public List<CampusService.FreeClassroom> findFreeClassrooms(String semester, Integer weekday,
            Integer startSection, Integer endSection, String building, Integer minCapacity) {
        return list("find_free_classrooms", args("semester", semester, "weekday", weekday,
                "startSection", startSection, "endSection", endSection, "building", building,
                "minCapacity", minCapacity), "rooms", CampusService.FreeClassroom.class);
    }
    @Override public String currentSemester() {
        return mySchedule(null, null).stream().map(CampusService.MeetingView::semester)
                .max(String::compareTo).orElseThrow(() -> new IllegalStateException("MCP returned no schedule"));
    }

    private <T> T one(String tool, Map<String, Object> args, String resultKey, Class<T> type) {
        JsonNode result = call(tool, args);
        return mapper.convertValue(resultKey == null ? result : result.path(resultKey), type);
    }
    private <T> List<T> list(String tool, Map<String, Object> args, String resultKey, Class<T> type) {
        JsonNode result = call(tool, args);
        if (resultKey != null) result = result.path(resultKey);
        if (result == null || result.isMissingNode() || result.isNull()) return List.of();
        var listType = TypeFactory.defaultInstance().constructCollectionType(List.class, type);
        return mapper.convertValue(result, listType);
    }
    private JsonNode call(String tool, Map<String, Object> arguments) {
        JsonNode result = callToolForAgent(tool, arguments);
        return result.path("result");
    }

    /** Discover schemas from the remote MCP server and expose only the campus read-tool allowlist. */
    public List<McpSchema.Tool> discoverTools() {
        return client().listTools().tools().stream()
                .filter(tool -> ALLOWED_TOOLS.contains(tool.name())).toList();
    }

    /** Validate and invoke one allowlisted, read-only MCP tool on behalf of the bounded Agent. */
    public JsonNode callToolForAgent(String toolName, Map<String, Object> arguments) {
        if (!ALLOWED_TOOLS.contains(toolName)) throw new IllegalArgumentException("Tool is not allowed: " + toolName);
        var spec = discoverTools().stream().filter(tool -> tool.name().equals(toolName)).findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP server did not advertise tool: " + toolName));
        validateArguments(spec, arguments);
        try {
            var result = client().callTool(McpSchema.CallToolRequest.builder().name(toolName)
                    .arguments(arguments).build());
            if (Boolean.TRUE.equals(result.isError())) {
                throw new IllegalStateException("MCP tool '" + toolName + "' failed: " + result.content());
            }
            JsonNode structured = mapper.valueToTree(result.structuredContent());
            if (structured != null && !structured.isNull()) return structured;
            for (var content : result.content()) {
                if (content instanceof McpSchema.TextContent text) {
                    return mapper.readTree(text.text());
                }
            }
            throw new IllegalStateException("MCP tool '" + toolName + "' returned no result");
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("MCP call failed: " + toolName, ex);
        }
    }

    private void validateArguments(McpSchema.Tool tool, Map<String, Object> arguments) {
        if (arguments == null) throw new IllegalArgumentException("Tool arguments are required");
        Map<String, Object> properties = tool.inputSchema().properties();
        if (properties == null) properties = Map.of();
        for (String key : arguments.keySet()) {
            if (!properties.containsKey(key)) throw new IllegalArgumentException("Unsupported argument: " + key);
        }
        List<String> required = tool.inputSchema().required();
        if (required != null) {
            for (String key : required) {
                if (!arguments.containsKey(key) || arguments.get(key) == null) {
                    throw new IllegalArgumentException("Missing required argument: " + key);
                }
            }
        }
        if (arguments.size() > 12) throw new IllegalArgumentException("Too many tool arguments");
        try {
            if (mapper.writeValueAsBytes(arguments).length > 4096) {
                throw new IllegalArgumentException("Tool arguments are too large");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("Tool arguments are not valid JSON", ex);
        }
    }
    private McpSyncClient client() {
        var current = client;
        if (current != null) return current;
        synchronized (this) {
            if (client == null) {
                var transport = HttpClientStreamableHttpTransport.builder(serverUrl)
                        .endpoint("/mcp").jsonMapper(McpJsonDefaults.getMapper())
                        .connectTimeout(Duration.ofSeconds(3)).build();
                client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(8)).build();
                client.initialize();
            }
            return client;
        }
    }
    @PreDestroy
    public void close() { if (client != null) client.closeGracefully(); }

    private Map<String, Object> args(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) if (values[i + 1] != null) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
