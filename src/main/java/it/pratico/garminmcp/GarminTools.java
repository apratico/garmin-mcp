package it.pratico.garminmcp;

import com.garmin.fit.FitDecoder;
import com.garmin.fit.FitMessages;
import com.garmin.fit.FitRuntimeException;
import com.garmin.fit.LapMesg;
import com.garmin.fit.Sport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MCP tools over the Garmin Connect Personal API (read-only).
 * Endpoints from the official Swagger: https://developer.garmin.com/gc-developer-program/personal/swagger-docs/
 */
@Component
public class GarminTools {

    private final GarminApiClient api;
    private final GarminProperties props;
    private final ObjectMapper mapper;

    public GarminTools(GarminApiClient api, GarminProperties props, ObjectMapper mapper) {
        this.api = api;
        this.props = props;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ activities

    @Tool(description = """
            List the user's Garmin activities (most recent first) as a compact summary: id, name, type,
            local start time, distance (km), duration, average pace, average/max heart rate, cadence, ascent.
            Dates are yyyy-MM-dd. Set raw=true to get the full JSON returned by Garmin.""")
    public String listActivities(
            @ToolParam(required = false, description = "Start date yyyy-MM-dd (optional)") String startDate,
            @ToolParam(required = false, description = "End date yyyy-MM-dd (optional)") String endDate,
            @ToolParam(required = false, description = "Activity type key, e.g. running (optional)") String activityType,
            @ToolParam(required = false, description = "Max number of activities, default 20") Integer limit,
            @ToolParam(required = false, description = "Return Garmin's raw JSON instead of the summary") Boolean raw) {
        Map<String, String> q = new HashMap<>();
        q.put("start", "0");
        q.put("limit", String.valueOf(limit == null || limit <= 0 ? 20 : Math.min(limit, 200)));
        q.put("startDate", startDate);
        q.put("endDate", endDate);
        q.put("activityType", activityType);
        JsonNode json = api.getJson("/activitylist-service/activities/search/activities", q);
        if (Boolean.TRUE.equals(raw)) {
            return json.toString();
        }
        JsonNode list = json.isArray() ? json : firstArray(json);
        if (list == null) {
            return json.toString();
        }
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode a : list) {
            out.add(summarize(a));
        }
        return out.toString();
    }

    @Tool(description = "Get the full detail of one activity (summary, metadata, splits/laps) as JSON.")
    public String getActivity(@ToolParam(description = "Garmin activity id") long activityId) {
        return api.getJson("/activity-service/activity/" + activityId, Map.of()).toString();
    }

    @Tool(description = """
            Download the ORIGINAL .FIT file of an activity and save it on the user's computer.
            Returns the absolute path of the saved .fit file (second-by-second HR, cadence, speed, laps).""")
    public String downloadActivityFit(@ToolParam(description = "Garmin activity id") long activityId)
            throws IOException {
        Path fit = fetchFit(activityId);
        return "{\"path\":\"" + fit.toAbsolutePath() + "\",\"bytes\":" + Files.size(fit) + "}";
    }

    @Tool(description = """
            Laps of an activity as recorded by the watch (manual laps, auto-lap every km, or workout
            steps), read from the original .FIT file. One row per lap: distance (km), timer time,
            pace (min/km, foot sports) or speed (km/h), average/max HR, cadence (spm for running),
            ascent/descent, average power, intensity (active/rest/warmup/cooldown) and lap trigger.
            Use it for intervals and per-km splits; getActivity does not have them.""")
    public String getActivityLaps(@ToolParam(description = "Garmin activity id") long activityId)
            throws IOException {
        FitMessages fit;
        try (InputStream in = Files.newInputStream(fetchFit(activityId))) {
            fit = new FitDecoder().decode(in);
        } catch (FitRuntimeException e) {
            throw new GarminApiClient.GarminApiException("Cannot decode the .FIT file of " + activityId
                    + ": " + e.getMessage(), e);
        }
        Sport sport = fit.getSessionMesgs().isEmpty() ? null : fit.getSessionMesgs().getFirst().getSport();
        boolean foot = sport == Sport.RUNNING || sport == Sport.WALKING || sport == Sport.HIKING;

        ArrayNode laps = mapper.createArrayNode();
        int n = 0;
        for (LapMesg lap : fit.getLapMesgs()) {
            ObjectNode o = laps.addObject();
            o.put("lap", ++n);
            float dist = orZero(lap.getTotalDistance());
            float timer = orZero(lap.getTotalTimerTime());
            Float speed = lap.getEnhancedAvgSpeed() != null ? lap.getEnhancedAvgSpeed() : lap.getAvgSpeed();
            o.put("km", round(dist / 1000.0, 2));
            o.put("time", hms(timer));
            if (speed != null && speed > 0) {
                if (foot) {
                    o.put("pace", hms(1000.0 / speed) + "/km");
                } else {
                    o.put("kmh", round(speed * 3.6, 1));
                }
            }
            putIfSet(o, "avgHR", lap.getAvgHeartRate());
            putIfSet(o, "maxHR", lap.getMaxHeartRate());
            if (lap.getAvgCadence() != null) {
                double cadence = lap.getAvgCadence() + orZero(lap.getAvgFractionalCadence());
                // FIT stores running cadence as strides per minute; Garmin Connect shows steps per minute
                o.put(foot ? "cadenceSpm" : "cadence", Math.round(foot ? cadence * 2 : cadence));
            }
            putIfSet(o, "ascentM", lap.getTotalAscent());
            putIfSet(o, "descentM", lap.getTotalDescent());
            putIfSet(o, "avgPowerW", lap.getAvgPower());
            if (lap.getIntensity() != null) {
                o.put("intensity", lap.getIntensity().name().toLowerCase());
            }
            if (lap.getLapTrigger() != null) {
                o.put("trigger", lap.getLapTrigger().name().toLowerCase());
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("activityId", activityId);
        if (sport != null) {
            out.put("sport", sport.name().toLowerCase());
        }
        out.set("laps", laps);
        return out.toString();
    }

    // ------------------------------------------------------------------ health

    @Tool(description = "Daily HRV (overnight) summaries for a date range, yyyy-MM-dd.")
    public String getHrvRange(@ToolParam(description = "From date yyyy-MM-dd") String fromDate,
                              @ToolParam(description = "To date yyyy-MM-dd") String toDate) {
        return api.getJson("/hrv-service/hrv/daily/" + fromDate + "/" + toDate, Map.of()).toString();
    }

    @Tool(description = "Daily heart rate for one date (includes resting heart rate), yyyy-MM-dd.")
    public String getDailyHeartRate(@ToolParam(description = "Date yyyy-MM-dd") String date) {
        return api.getJson("/wellness-service/wellness/dailyHeartRate", Map.of("date", date)).toString();
    }

    @Tool(description = "Sleep data for one night, yyyy-MM-dd (the date of the morning you woke up).")
    public String getSleep(@ToolParam(description = "Date yyyy-MM-dd") String date) {
        return api.getJson("/sleep-service/sleep/dailySleep", Map.of("date", date)).toString();
    }

    @Tool(description = "Heart rate zones currently configured in Garmin Connect.")
    public String getHeartRateZones() {
        return api.getJson("/biometric-service/heartRateZones/v2", Map.of()).toString();
    }

    @Tool(description = "Daily user summary for one date (steps, calories, stress, body battery, resting HR), yyyy-MM-dd.")
    public String getDailySummary(@ToolParam(description = "Date yyyy-MM-dd") String date) {
        return api.getJson("/usersummary-service/usersummary/daily", Map.of("calendarDate", date)).toString();
    }

    // ------------------------------------------------------------------ generic

    @Tool(description = """
            Generic read-only GET on the Garmin Connect Personal API, for endpoints not covered by the
            other tools or when a query parameter name has to be adjusted. 'path' is relative and starts
            with '/', e.g. /fitnessage-service/fitnessage/v2/2026-10-25 . 'query' is an optional
            URL query string without '?', e.g. date=2026-10-25&foo=bar .""")
    public String garminGet(@ToolParam(description = "Relative API path starting with /") String path,
                            @ToolParam(required = false, description = "Query string, e.g. a=1&b=2") String query) {
        Map<String, String> q = new HashMap<>();
        if (query != null && !query.isBlank()) {
            for (String pair : query.split("&")) {
                int i = pair.indexOf('=');
                if (i > 0) {
                    q.put(pair.substring(0, i), pair.substring(i + 1));
                }
            }
        }
        return api.getJson(path, q).toString();
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode summarize(JsonNode a) {
        ObjectNode o = mapper.createObjectNode();
        copy(a, o, "activityId", "id");
        copy(a, o, "activityName", "name");
        if (a.has("activityType")) {
            o.put("type", a.path("activityType").path("typeKey").asString(null));
        }
        copy(a, o, "startTimeLocal", "start");
        double distM = a.path("distance").asDouble(0);
        double durS = a.path("movingDuration").asDouble(a.path("duration").asDouble(0));
        if (distM > 0) {
            o.put("km", round(distM / 1000.0, 2));
        }
        if (durS > 0) {
            o.put("duration", hms(durS));
        }
        if (distM > 0 && durS > 0) {
            o.put("pace", hms(durS / (distM / 1000.0)) + "/km");
        }
        copy(a, o, "averageHR", "avgHR");
        copy(a, o, "maxHR", "maxHR");
        copy(a, o, "averageRunningCadenceInStepsPerMinute", "cadenceSpm");
        copy(a, o, "elevationGain", "ascentM");
        return o;
    }

    private static void copy(JsonNode from, ObjectNode to, String field, String as) {
        JsonNode v = from.get(field);
        if (v != null && !v.isNull()) {
            to.set(as, v);
        }
    }

    private static JsonNode firstArray(JsonNode json) {
        for (String k : List.of("activityList", "activities", "items", "data")) {
            if (json.path(k).isArray()) {
                return json.get(k);
            }
        }
        return null;
    }

    /** Downloads the original .FIT file once and keeps it in {@code <data-dir>/fit}. */
    private Path fetchFit(long activityId) throws IOException {
        Path dir = Path.of(props.dataDir(), "fit");
        Path target = dir.resolve(activityId + ".fit");
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }
        byte[] data = api.getBytes("/download-service/files/activity/" + activityId, Map.of());
        Files.createDirectories(dir);
        if (!isZip(data)) {
            Files.write(target, data);
            return target;
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().toLowerCase().endsWith(".fit")) {
                    Files.write(target, zip.readAllBytes());
                    return target;
                }
            }
        }
        throw new GarminApiClient.GarminApiException("The download of activity " + activityId
                + " is a zip without a .fit file");
    }

    private static void putIfSet(ObjectNode o, String name, Number v) {
        if (v != null) {
            o.put(name, v.longValue());
        }
    }

    private static float orZero(Float v) {
        return v == null ? 0f : v;
    }

    private static boolean isZip(byte[] d) {
        return d.length > 4 && d[0] == 'P' && d[1] == 'K';
    }

    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    private static String hms(double seconds) {
        long s = Math.round(seconds);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }
}
