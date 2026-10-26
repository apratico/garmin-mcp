You are a data-minded endurance coach and a front-end designer. Using the Garmin tools of the
`garmin-personal` MCP server, build me a personal **training + recovery dashboard** covering the
last **{{weeks}} weeks** (today is {{today}}).

The point of this dashboard is what social training apps do not show: how training load and
recovery (HRV, sleep, resting heart rate, body battery) move together. Answer in my language.

## 1. Collect the data (tools, in this order)

1. `getHeartRateZones` - my configured HR zones.
2. `listActivities` with `startDate` = {{startDate}}, `endDate` = {{today}}, `limit` = 200.
   If the summary lacks a field you need, call it again with `raw=true`.
3. `getHrvRange` from {{startDate}} to {{today}}. If Garmin rejects the range, split it into
   chunks of 4 weeks.
4. For the **last 14 days** only: `getSleep` and `getDailySummary`, one call per day.
5. Call `getActivity` only for the 3 most recent activities, to show their laps/splits.

If a tool fails, say which one and why in one line, keep going, and show "n/a" in the affected
widget. **Never invent, interpolate or estimate missing values.**

## 2. Compute

- **Weekly volume** per sport: distance (km), moving time, number of sessions.
- **This week vs. the previous 4-week average** for distance, time and sessions (delta in %).
- **Training load** per activity = moving minutes x intensity factor, where intensity factor is
  avg HR / max HR (from the zones) - label it clearly as an *estimate*. Then the acute (7-day)
  vs. chronic (28-day) load ratio, with the 0.8-1.3 band highlighted.
- **Running**: average pace and average HR per week, plus efficiency (metres per heartbeat =
  speed in m/min / avg HR) to show aerobic progress.
- **Time in HR zones** per week, from the activities' average HR mapped onto my zones (say that
  it is approximate when per-second data is not used).
- **Recovery**: nightly HRV vs. its 7-day rolling average, resting HR trend, sleep duration and
  score, body battery high/low.

## 3. Build the dashboard

Pick the output that fits where you are running:

- **Claude desktop / claude.ai** (artifacts available): one self-contained React artifact, with
  Recharts for the charts.
- **Claude Code** or any client without artifacts: write a single self-contained file
  `garmin-dashboard.html` in the current directory (Chart.js from cdnjs, everything else inline),
  then tell me its path and the command to open it (`xdg-open` / `open`).

Either way, no runtime calls to Garmin: embed the processed data as a constant at the top of the
file, aggregated - not raw Garmin JSON.

Layout, top to bottom:

1. **Header**: period covered, total km / hours / sessions, and 4 KPI tiles with deltas
   (distance, time, load ratio, last-night HRV vs. baseline).
2. **Weekly volume**: stacked bars per sport, with a 4-week moving average line.
3. **Consistency calendar**: a GitHub-style heatmap of daily training minutes.
4. **Load vs. recovery**: one chart with daily load (bars) and HRV (line) on twin axes - the
   heart of the dashboard.
5. **Running trends**: pace and efficiency per week; HR zone distribution per week.
6. **Recovery panel**: HRV with baseline band, resting HR, sleep hours and score (last 14 days).
7. **Recent activities**: table with date, type, name, distance, time, pace, avg HR, ascent;
   the 3 most recent expandable to show laps.
8. **Coach notes**: at most 5 bullet points, each tied to a specific number in the dashboard
   (e.g. "HRV 12% below baseline for 3 nights while load ratio is 1.4"). Training suggestions
   only, no medical advice.

Design: dark, clean, data-dense but airy; one accent colour for running, distinct hues for other
sports, consistent across all charts; tabular numbers; responsive down to phone width; tooltips
on every chart; units on every axis. Use your own visual identity: no third-party app names,
logos or brand colours.

Finish with two lines in chat: what is in the dashboard, and anything that was missing.
