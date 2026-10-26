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
import java.util.Set;

/**
 * MCP prompts: ready-made instructions the client shows in its prompt menu (e.g. Claude desktop "+").
 * Prompt texts live in src/main/resources/prompts and use {{placeholder}} markers.
 */
@Component
public class GarminPrompts {

    private static final int DEFAULT_WEEKS = 12;
    private static final int MAX_WEEKS = 52;
    private static final Set<String> STYLES = Set.of("clean", "poster", "neon", "bib", "magazine", "splits");
    private static final Set<String> FORMATS = Set.of("story", "post", "square");

    public List<SyncPromptSpecification> specifications() {
        return List.of(trainingDashboard(), socialCard());
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
            return result(prompt, render(template, Map.of(
                    "weeks", String.valueOf(weeks),
                    "today", today.toString(),
                    "startDate", today.minusWeeks(weeks).toString())));
        });
    }

    private SyncPromptSpecification socialCard() {
        McpSchema.Prompt prompt = new McpSchema.Prompt(
                "social-card",
                "Turn a workout photo into an Instagram/WhatsApp card: writes the prompt for an image model "
                        + "(e.g. Gemini Nano Banana) with the Garmin stats, plus captions",
                List.of(new McpSchema.PromptArgument("activityId",
                                "Garmin activity id, default the latest activity", false),
                        new McpSchema.PromptArgument("style",
                                "clean | poster | neon | bib | magazine | splits; empty = show the menu", false),
                        new McpSchema.PromptArgument("format",
                                "story (9:16) | post (4:5) | square (1:1); empty = ask", false)));
        String template = load("prompts/social-card.md");

        return new SyncPromptSpecification(prompt, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            String activity = arg(args, "activityId");
            String style = arg(args, "style").toLowerCase();
            String format = arg(args, "format").toLowerCase();
            return result(prompt, render(template, Map.of(
                    "activity", activity.matches("\\d+") ? activity : "latest",
                    "style", STYLES.contains(style) ? style : "ask me",
                    "format", FORMATS.contains(format) ? format : "ask me")));
        });
    }

    private static McpSchema.GetPromptResult result(McpSchema.Prompt prompt, String text) {
        return new McpSchema.GetPromptResult(prompt.description(), List.of(
                new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
    }

    private static String render(String template, Map<String, String> values) {
        String text = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            text = text.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return text;
    }

    private static String arg(Map<String, Object> args, String name) {
        Object v = args == null ? null : args.get(name);
        return v == null ? "" : v.toString().trim();
    }

    private static int weeks(Map<String, Object> args) {
        String v = arg(args, "weeks");
        if (v.isEmpty()) {
            return DEFAULT_WEEKS;
        }
        try {
            return Math.clamp(Integer.parseInt(v), 1, MAX_WEEKS);
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
