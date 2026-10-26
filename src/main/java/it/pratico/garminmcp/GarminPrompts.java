package it.pratico.garminmcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * MCP prompts: ready-made instructions the client shows in its prompt menu (e.g. Claude desktop "+").
 * Prompt texts live in src/main/resources/prompts and use {{placeholder}} markers.
 */
@Component
public class GarminPrompts {

    private static final int DEFAULT_WEEKS = 12;
    private static final int MAX_WEEKS = 52;

    public List<SyncPromptSpecification> specifications() {
        return List.of(trainingDashboard());
    }

    private SyncPromptSpecification trainingDashboard() {
        McpSchema.Prompt prompt = new McpSchema.Prompt(
                "training-dashboard",
                "Build a personal training + recovery dashboard (volume, load, HRV, sleep) from Garmin data",
                List.of(new McpSchema.PromptArgument("weeks",
                        "Number of weeks to cover, default " + DEFAULT_WEEKS, false)));
        String template = load("prompts/training-dashboard.md");

        return new SyncPromptSpecification(prompt, (exchange, request) -> {
            int weeks = weeks(request.arguments());
            LocalDate today = LocalDate.now();
            String text = template
                    .replace("{{weeks}}", String.valueOf(weeks))
                    .replace("{{today}}", today.toString())
                    .replace("{{startDate}}", today.minusWeeks(weeks).toString());
            return new McpSchema.GetPromptResult(prompt.description(), List.of(
                    new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
        });
    }

    private static int weeks(Map<String, Object> args) {
        Object v = args == null ? null : args.get("weeks");
        if (v == null || v.toString().isBlank()) {
            return DEFAULT_WEEKS;
        }
        try {
            return Math.clamp(Integer.parseInt(v.toString().trim()), 1, MAX_WEEKS);
        } catch (NumberFormatException e) {
            return DEFAULT_WEEKS;
        }
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing prompt resource " + path, e);
        }
    }
}
