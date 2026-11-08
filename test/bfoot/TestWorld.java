package bfoot;

import bfoot.data.CachedSource;
import bfoot.data.ResponseCache;
import bfoot.model.Market;
import bfoot.model.ScoreModel;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Faux Sofascore pour les tests : deux championnats synthétiques (ids 17 et
 * 34, donc reconnus par Leagues), des équipes de force connue, 20 matchs
 * joués par équipe, une semaine de matchs à venir et des cotes "bookmaker"
 * bruitées avec 6 % de marge, au format JSON de Sofascore.
 */
public final class TestWorld implements CachedSource.Fetcher {

    public final Map<String, String> responses = new HashMap<>();
    public final AtomicInteger calls = new AtomicInteger();
    public final Map<Integer, double[]> strength = new HashMap<>(); // teamId -> {attack, defense}
    public final List<Integer> upcomingEventIds = new ArrayList<>();
    private final Random rnd = new Random(42);
    private int nextEventId = 1000;

    public TestWorld(Instant now) {
        addLeague(17, "Premier League", 100, now);
        addLeague(34, "Ligue 1", 200, now);
    }

    @Override
    public ResponseCache.Entry fetch(String path) {
        calls.incrementAndGet();
        String body = responses.get(path);
        return body == null ? CachedSource.response(404, "{}") : CachedSource.response(200, body);
    }

    private void addLeague(int tournamentId, String name, int firstTeamId, Instant now) {
        int seasonId = 5000 + tournamentId;
        responses.put("/unique-tournament/" + tournamentId + "/seasons",
            new JSONObject().put("seasons", new JSONArray().put(new JSONObject().put("id", seasonId))).toString());

        int teams = 12;
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < teams; i++) {
            int id = firstTeamId + i;
            ids.add(id);
            // de très fort (att 1.6, déf 0.6) à très faible (att 0.6, déf 1.5)
            double t = i / (double) (teams - 1);
            strength.put(id, new double[]{1.6 - t, 0.6 + 0.9 * t});
        }

        Map<Integer, JSONArray> history = new HashMap<>();
        ids.forEach(id -> history.put(id, new JSONArray()));
        // 20 matchs passés par équipe : aller-retour partiel, un tous les 7 jours
        for (int r = 0; r < 20; r++) {
            for (int i = 0; i < teams / 2; i++) {
                int h = ids.get((i + r) % teams);
                int a = ids.get((teams - 1 - i + r) % teams);
                if (h == a) continue;
                Instant when = now.minus(Duration.ofDays(7L * (20 - r)));
                int[] score = play(h, a);
                JSONObject ev = event(nextEventId++, when, "finished", h, a, tournamentId, name)
                    .put("homeScore", new JSONObject().put("current", score[0]).put("normaltime", score[0]))
                    .put("awayScore", new JSONObject().put("current", score[1]).put("normaltime", score[1]));
                history.get(h).put(ev);
                history.get(a).put(ev);
            }
        }
        history.forEach((id, events) -> responses.put("/team/" + id + "/events/last/0",
            new JSONObject().put("events", events).put("hasNextPage", true).toString()));

        // 4 journées à venir (une par semaine), 6 matchs chacune : la première tombe
        // dans les 7 prochains jours (/semaine), les 4 dans le mois (/grandsmatchs).
        JSONArray upcoming = new JSONArray();
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < teams / 2; i++) {
                int h = round == 0 ? ids.get(i * 2) : ids.get((i + round) % teams);
                int a = round == 0 ? ids.get(i * 2 + 1) : ids.get((teams - 1 - i + round) % teams);
                int eventId = nextEventId++;
                Instant when = now.plus(Duration.ofDays(7L * round)).plus(Duration.ofHours(24L + 20L * i));
                upcoming.put(event(eventId, when, "notstarted", h, a, tournamentId, name));
                upcomingEventIds.add(eventId);
                responses.put("/event/" + eventId + "/odds/1/all", odds(h, a).toString());
                addDetails(eventId, h, a);
            }
        }
        // un match déjà commencé : doit être ignoré
        upcoming.put(event(nextEventId++, now.plus(Duration.ofHours(2)), "inprogress", ids.get(0), ids.get(5), tournamentId, name));
        responses.put("/unique-tournament/" + tournamentId + "/season/" + seasonId + "/events/next/0",
            new JSONObject().put("events", upcoming).put("hasNextPage", false).toString());
    }

    /** Compositions (avec absents), forme d'avant-match et face-à-face, au format Sofascore. */
    private void addDetails(int eventId, int h, int a) {
        JSONArray homeMissing = new JSONArray();
        if (h % 3 == 0) {
            homeMissing.put(new JSONObject().put("type", "missing").put("description", "blessure")
                .put("player", new JSONObject().put("name", "Buteur " + h).put("shortName", "Buteur " + h).put("position", "F")));
        }
        if (h % 4 == 0) {
            homeMissing.put(new JSONObject().put("type", "doubtful")
                .put("player", new JSONObject().put("name", "Gardien " + h).put("position", "G")));
        }
        responses.put("/event/" + eventId + "/lineups", new JSONObject().put("confirmed", false)
            .put("home", new JSONObject().put("formation", "4-3-3").put("players", new JSONArray()).put("missingPlayers", homeMissing))
            .put("away", new JSONObject().put("formation", "4-4-2").put("players", new JSONArray()).put("missingPlayers", new JSONArray()))
            .toString());
        responses.put("/event/" + eventId + "/pregame-form", new JSONObject()
            .put("homeTeam", new JSONObject().put("avgRating", String.format(java.util.Locale.ROOT, "%.2f", 6.4 + strength.get(h)[0] / 2))
                .put("position", h % 100 + 1).put("form", new JSONArray().put("W").put("D")))
            .put("awayTeam", new JSONObject().put("avgRating", String.format(java.util.Locale.ROOT, "%.2f", 6.4 + strength.get(a)[0] / 2))
                .put("position", a % 100 + 1).put("form", new JSONArray().put("L")))
            .toString());
        responses.put("/event/" + eventId + "/h2h", new JSONObject()
            .put("teamDuel", new JSONObject().put("homeWins", 4).put("draws", 2).put("awayWins", 1)).toString());
    }

    private double[] lambdas(int h, int a) {
        double mu = 1.25, hfa = 1.25;
        return new double[]{mu * hfa * strength.get(h)[0] * strength.get(a)[1], mu * strength.get(a)[0] * strength.get(h)[1]};
    }

    private int[] play(int h, int a) {
        double[] l = lambdas(h, a);
        return new int[]{poisson(l[0]), poisson(l[1])};
    }

    private int poisson(double lambda) {
        double limit = Math.exp(-lambda), p = 1;
        int k = 0;
        do {
            k++;
            p *= rnd.nextDouble();
        } while (p > limit);
        return k - 1;
    }

    /** Cotes bookmaker : vraies probabilités bruitées (±12 %) puis 6 % de marge. */
    private JSONObject odds(int h, int a) {
        double[] l = lambdas(h, a);
        Map<Market, Double> p = ScoreModel.marketProbabilities(l[0], l[1], -0.08);
        JSONArray markets = new JSONArray();
        markets.put(market("Full time", null, p, Market.HOME_WIN, "1", Market.DRAW, "X", Market.AWAY_WIN, "2"));
        markets.put(market("Double chance", null, p, Market.DC_1X, "1X", Market.DC_X2, "X2", Market.DC_12, "12"));
        markets.put(market("Both teams to score", null, p, Market.BTTS_YES, "Yes", Market.BTTS_NO, "No"));
        markets.put(market("Match goals", "1.5", p, Market.OVER_1_5, "Over", Market.UNDER_1_5, "Under"));
        markets.put(market("Match goals", "2.5", p, Market.OVER_2_5, "Over", Market.UNDER_2_5, "Under"));
        markets.put(market("Match goals", "3.5", p, Market.OVER_3_5, "Over", Market.UNDER_3_5, "Under"));
        return new JSONObject().put("markets", markets);
    }

    private JSONObject market(String name, String line, Map<Market, Double> p, Object... pairs) {
        JSONArray choices = new JSONArray();
        double[] noisy = new double[pairs.length / 2];
        double sum = 0;
        for (int i = 0; i < noisy.length; i++) {
            noisy[i] = p.get((Market) pairs[2 * i]) * (1 + 0.12 * (rnd.nextDouble() * 2 - 1));
            sum += noisy[i];
        }
        boolean dc = name.equals("Double chance");
        for (int i = 0; i < noisy.length; i++) {
            double prob = dc ? noisy[i] : noisy[i] / sum;
            double decimal = 1.0 / Math.min(0.97, prob * 1.06);
            int num = (int) Math.round((decimal - 1) * 100);
            // cote d'ouverture : ±12 % autour de la cote actuelle (certaines ont donc "dérivé")
            int open = (int) Math.round((decimal * (1 + 0.12 * (rnd.nextDouble() * 2 - 1)) - 1) * 100);
            choices.put(new JSONObject().put("name", pairs[2 * i + 1]).put("fractionalValue", num + "/100")
                .put("initialFractionalValue", Math.max(1, open) + "/100"));
        }
        JSONObject m = new JSONObject().put("marketName", name).put("marketPeriod", "Full-time")
            .put("isLive", false).put("choices", choices);
        if (line != null) m.put("choiceGroup", line);
        return m;
    }

    private static JSONObject event(int id, Instant when, String status, int home, int away, int tournamentId, String name) {
        return new JSONObject()
            .put("id", id)
            .put("startTimestamp", when.getEpochSecond())
            .put("status", new JSONObject().put("type", status))
            .put("homeTeam", new JSONObject().put("id", home).put("name", "Équipe " + home))
            .put("awayTeam", new JSONObject().put("id", away).put("name", "Équipe " + away))
            .put("tournament", new JSONObject().put("name", name)
                .put("uniqueTournament", new JSONObject().put("id", tournamentId).put("name", name)));
    }
}
