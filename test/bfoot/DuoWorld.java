package bfoot;

import bfoot.data.CachedSource;
import bfoot.data.ResponseCache;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Faux football-data.org + faux The Odds API, construits à partir du même
 * monde que {@link TestWorld} : mêmes matchs, mêmes scores, mais identifiants
 * différents et noms écrits autrement ("Équipe 100 FC"), comme dans la réalité.
 */
public final class DuoWorld {

    public static final int ID_OFFSET = 900_000;
    private static final Map<Integer, String[]> LEAGUES = Map.of(
        17, new String[]{"PL", "soccer_epl", "Premier League"},
        34, new String[]{"FL1", "soccer_france_ligue_one", "Ligue 1"});

    public final AtomicInteger fdCalls = new AtomicInteger();
    public final AtomicInteger oddsCalls = new AtomicInteger();
    private final Map<String, JSONArray> upcoming = new HashMap<>();   // code -> matches
    private final Map<String, JSONArray> finished = new HashMap<>();   // code -> matches
    private final Map<String, JSONArray> oddsEvents = new HashMap<>(); // sport key -> events

    public DuoWorld(TestWorld world) {
        for (Map.Entry<Integer, String[]> e : LEAGUES.entrySet()) {
            int tid = e.getKey();
            String code = e.getValue()[0], sport = e.getValue()[1], name = e.getValue()[2];
            JSONArray up = new JSONArray(), fin = new JSONArray(), odds = new JSONArray();
            JSONArray next = new JSONObject(world.responses.get("/unique-tournament/" + tid + "/season/" + (5000 + tid) + "/events/next/0"))
                .getJSONArray("events");
            Map<Integer, JSONObject> finishedById = new LinkedHashMap<>();
            for (int i = 0; i < next.length(); i++) {
                JSONObject ev = next.getJSONObject(i);
                if (!"notstarted".equals(ev.getJSONObject("status").getString("type"))) continue;
                up.put(match(ev, code, name, "TIMED", null));
                String sofaOdds = world.responses.get("/event/" + ev.getInt("id") + "/odds/1/all");
                if (sofaOdds != null) odds.put(oddsEvent(ev, sofaOdds, sport));
                for (String side : List.of("homeTeam", "awayTeam")) {
                    int teamId = ev.getJSONObject(side).getInt("id");
                    JSONArray last = new JSONObject(world.responses.get("/team/" + teamId + "/events/last/0")).getJSONArray("events");
                    for (int k = 0; k < last.length(); k++) finishedById.putIfAbsent(last.getJSONObject(k).getInt("id"), last.getJSONObject(k));
                }
            }
            finishedById.values().forEach(ev -> fin.put(match(ev, code, name, "FINISHED", ev)));
            upcoming.put(code, up);
            finished.put(code, fin);
            oddsEvents.put(sport, odds);
        }
    }

    /** Fetcher football-data.org. */
    public CachedSource.Fetcher footballData() {
        return path -> {
            fdCalls.incrementAndGet();
            String[] p = path.split("\\?", 2);
            String[] seg = p[0].split("/");
            if (seg.length >= 4 && seg[1].equals("competitions")) {
                String code = seg[2];
                if (!upcoming.containsKey(code)) return CachedSource.response(403, "{\"message\":\"restricted\"}");
                if (seg[3].equals("standings")) return CachedSource.response(200, standings(code).toString());
                String q = p.length > 1 ? p[1] : "";
                if (q.contains("season=")) return CachedSource.response(403, "{\"message\":\"not in your plan\"}");
                if (q.contains("status=FINISHED")) {
                    return CachedSource.response(200, new JSONObject().put("matches", finished.get(code)).toString());
                }
                return CachedSource.response(200, new JSONObject().put("matches", upcoming.get(code)).toString());
            }
            return CachedSource.response(404, "{}");
        };
    }

    /** Fetcher The Odds API. */
    public CachedSource.Fetcher oddsApi() {
        return path -> {
            oddsCalls.incrementAndGet();
            String sport = path.split("/")[2];
            JSONArray events = oddsEvents.get(sport);
            return events == null ? CachedSource.response(404, "{}") : CachedSource.response(200, events.toString());
        };
    }

    private static JSONObject match(JSONObject ev, String code, String compName, String status, JSONObject finishedEv) {
        JSONObject m = new JSONObject()
            .put("id", ID_OFFSET + ev.getInt("id"))
            .put("utcDate", Instant.ofEpochSecond(ev.getLong("startTimestamp")).toString())
            .put("status", status)
            .put("competition", new JSONObject().put("code", code).put("name", compName))
            .put("season", new JSONObject().put("startDate", "2026-08-01"))
            .put("homeTeam", team(ev.getJSONObject("homeTeam")))
            .put("awayTeam", team(ev.getJSONObject("awayTeam")));
        JSONObject score = new JSONObject().put("duration", "REGULAR");
        if (finishedEv != null) {
            score.put("fullTime", new JSONObject()
                .put("home", finishedEv.getJSONObject("homeScore").getInt("normaltime"))
                .put("away", finishedEv.getJSONObject("awayScore").getInt("normaltime")));
        } else {
            score.put("fullTime", new JSONObject().put("home", JSONObject.NULL).put("away", JSONObject.NULL));
        }
        return m.put("score", score);
    }

    private static JSONObject team(JSONObject sofaTeam) {
        String name = sofaTeam.getString("name");
        return new JSONObject().put("id", ID_OFFSET + sofaTeam.getInt("id")).put("name", name + " FC").put("shortName", name);
    }

    /** Convertit les cotes Sofascore du match en événement The Odds API (3 bookmakers légèrement différents). */
    private static JSONObject oddsEvent(JSONObject ev, String sofaOdds, String sport) {
        String home = ev.getJSONObject("homeTeam").getString("name"), away = ev.getJSONObject("awayTeam").getString("name");
        JSONArray markets = new JSONObject(sofaOdds).getJSONArray("markets");
        JSONArray h2h = new JSONArray(), totals = new JSONArray();
        for (int i = 0; i < markets.length(); i++) {
            JSONObject m = markets.getJSONObject(i);
            String mName = m.getString("marketName");
            JSONArray choices = m.getJSONArray("choices");
            for (int c = 0; c < choices.length(); c++) {
                JSONObject ch = choices.getJSONObject(c);
                String[] frac = ch.getString("fractionalValue").split("/");
                double price = Double.parseDouble(frac[0]) / Double.parseDouble(frac[1]) + 1;
                if (mName.equals("Full time")) {
                    String n = switch (ch.getString("name")) { case "1" -> home; case "2" -> away; default -> "Draw"; };
                    h2h.put(new JSONObject().put("name", n).put("price", price));
                } else if (mName.equals("Match goals")) {
                    totals.put(new JSONObject().put("name", ch.getString("name")).put("price", price)
                        .put("point", Double.parseDouble(m.getString("choiceGroup"))));
                }
            }
        }
        JSONArray bookmakers = new JSONArray();
        double[] shifts = {-0.02, 0.0, 0.02};
        for (int b = 0; b < shifts.length; b++) {
            bookmakers.put(new JSONObject().put("key", "book" + b).put("markets", new JSONArray()
                .put(new JSONObject().put("key", "h2h").put("outcomes", shift(h2h, shifts[b])))
                .put(new JSONObject().put("key", "totals").put("outcomes", shift(totals, shifts[b])))));
        }
        return new JSONObject().put("id", "odds-" + ev.getInt("id")).put("sport_key", sport)
            .put("commence_time", Instant.ofEpochSecond(ev.getLong("startTimestamp")).toString())
            .put("home_team", home).put("away_team", away).put("bookmakers", bookmakers);
    }

    private static JSONArray shift(JSONArray outcomes, double delta) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < outcomes.length(); i++) {
            JSONObject o = new JSONObject(outcomes.getJSONObject(i).toString());
            o.put("price", o.getDouble("price") + delta);
            out.put(o);
        }
        return out;
    }

    private JSONObject standings(String code) {
        // classement simple : ordre des identifiants (équipe la plus forte = 1re)
        List<Integer> ids = new ArrayList<>();
        JSONArray fin = finished.get(code);
        for (int i = 0; i < fin.length(); i++) {
            for (String side : List.of("homeTeam", "awayTeam")) {
                int id = fin.getJSONObject(i).getJSONObject(side).getInt("id");
                if (!ids.contains(id)) ids.add(id);
            }
        }
        ids.sort(Integer::compare);
        JSONArray table = new JSONArray();
        for (int i = 0; i < ids.size(); i++) {
            table.put(new JSONObject().put("position", i + 1).put("team", new JSONObject().put("id", ids.get(i))));
        }
        return new JSONObject().put("standings", new JSONArray().put(new JSONObject().put("type", "TOTAL").put("table", table)));
    }
}
