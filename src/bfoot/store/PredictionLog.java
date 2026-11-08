package bfoot.store;

import bfoot.coupon.Leg;
import bfoot.coupon.Ticket;
import bfoot.data.DataProvider;
import bfoot.data.FootballData;
import bfoot.data.SourceException;
import bfoot.model.Market;
import bfoot.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Historique des coupons envoyés et de leurs résultats.
 *
 * Chaque coupon est enregistré une fois (data/coupons.jsonl) avec son type
 * (semaine, mois-securite, mois-gros). /bilan va chercher le score final des
 * matchs terminés et, une seule fois, leur dernière cote d'avant-match
 * (data/results.json), puis calcule par type : coupons gagnés, ROI, taux de
 * réussite réel vs prévu, et "CLV" (avons-nous joué une meilleure cote que
 * la cote de clôture ? C'est l'indicateur des parieurs professionnels :
 * battre régulièrement la clôture prouve un vrai avantage, bien avant que
 * les résultats ne le montrent).
 */
public final class PredictionLog {

    private final Path couponsFile;
    private final Path resultsFile;

    public PredictionLog(Path dir) {
        this.couponsFile = dir.resolve("coupons.jsonl");
        this.resultsFile = dir.resolve("results.json");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Log.warn("Impossible de créer " + dir + " : " + e.getMessage());
        }
    }

    // --- Enregistrement ---

    /**
     * @param source clé de la source qui a produit les coupons ("sofascore", "duo") :
     *               c'est elle qui fournira les résultats au moment du bilan.
     * @param type   type de coupon ("semaine", "mois-securite", "mois-gros")
     */
    public synchronized int record(String source, String type, List<Ticket> tickets, Instant createdAt) {
        Set<String> known = loadCoupons().stream().map(c -> c.optString("signature")).collect(Collectors.toSet());
        int added = 0;
        for (Ticket t : tickets) {
            String signature = source + "#" + signature(t);
            if (known.contains(signature)) continue;
            JSONArray legs = new JSONArray();
            for (Leg l : t.legs()) {
                legs.put(new JSONObject()
                    .put("eventId", l.fixture().eventId())
                    .put("kickoff", l.fixture().kickoff().getEpochSecond())
                    .put("match", l.fixture().label())
                    .put("market", l.market().name())
                    .put("label", l.label())
                    .put("odds", l.odds())
                    .put("modelProb", l.modelProb())
                    .put("marketProb", l.marketProb())
                    .put("finalProb", l.finalProb()));
            }
            JSONObject line = new JSONObject()
                .put("source", source)
                .put("type", type)
                .put("createdAt", createdAt.getEpochSecond())
                .put("signature", signature)
                .put("odds", t.odds())
                .put("probability", t.probability())
                .put("legs", legs);
            try {
                Files.writeString(couponsFile, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                known.add(signature);
                added++;
            } catch (IOException e) {
                Log.warn("Coupon non enregistré : " + e.getMessage());
            }
        }
        return added;
    }

    static String signature(Ticket t) {
        return t.legs().stream().map(l -> l.fixture().eventId() + ":" + l.market().name()).sorted()
            .collect(Collectors.joining("|"));
    }

    // --- Bilan ---

    /** Bilan d'un type de coupon (mise de 1 par coupon). */
    public record TypeSummary(int coupons, int won, int lost, int pending, double staked, double returned,
                              double expectedProbabilitySum) {
        public double roi() { return staked > 0 ? (returned - staked) / staked : 0; }
        /** Nombre de coupons gagnants que le modèle prévoyait parmi les coupons réglés. */
        public double expectedWins() { return expectedProbabilitySum; }
    }

    public record Summary(Map<String, TypeSummary> byType, int legsWon, int legsLost, double expectedLegHitRate,
                          int clvLegs, double averageClv, int newlySettled, int lookupErrors) {
        public int coupons() { return byType.values().stream().mapToInt(TypeSummary::coupons).sum(); }
        public double legHitRate() { return legsWon + legsLost > 0 ? (double) legsWon / (legsWon + legsLost) : 0; }
    }

    /** Compatibilité : une seule source (Sofascore). */
    public synchronized Summary settle(FootballData data, Instant now) {
        return settle(Map.of(data.key(), data), now);
    }

    /** Clé d'un match dans results.json : l'id seul pour Sofascore (historique), préfixé sinon. */
    private static String resultKey(String source, int eventId) {
        return "sofascore".equals(source) ? String.valueOf(eventId) : source + ":" + eventId;
    }

    /** Met à jour les résultats des matchs terminés (auprès de la source de chaque coupon) puis calcule le bilan. */
    public synchronized Summary settle(Map<String, DataProvider> providers, Instant now) {
        List<JSONObject> coupons = loadCoupons();
        JSONObject results = loadResults();
        int newlySettled = 0, errors = 0;

        Set<String> toCheck = new HashSet<>();
        for (JSONObject c : coupons) {
            String source = c.optString("source", "sofascore");
            for (Object o : c.getJSONArray("legs")) {
                JSONObject leg = (JSONObject) o;
                String key = resultKey(source, leg.getInt("eventId"));
                boolean over = Instant.ofEpochSecond(leg.getLong("kickoff")).plus(Duration.ofMinutes(130)).isBefore(now);
                if (over && !results.has(key)) toCheck.add(source + "|" + leg.getInt("eventId"));
            }
        }
        for (String item : toCheck) {
            String source = item.substring(0, item.indexOf('|'));
            int id = Integer.parseInt(item.substring(item.indexOf('|') + 1));
            DataProvider data = providers.get(source);
            if (data == null) continue; // source désactivée : on réessaiera plus tard
            try {
                Optional<FootballData.MatchOutcome> outcome = data.outcome(id);
                if (outcome.isEmpty()) continue;
                FootballData.MatchOutcome r = outcome.get();
                JSONObject entry = new JSONObject().put("void", r.voided()).put("home", r.homeGoals()).put("away", r.awayGoals());
                if (!r.voided()) {
                    try {
                        JSONObject closing = new JSONObject();
                        data.closingOdds(id).forEach((m, odd) -> closing.put(m.name(), odd));
                        entry.put("closing", closing);
                    } catch (SourceException e) {
                        // cote de clôture indisponible : le bilan se fait sans CLV pour ce match
                    }
                }
                results.put(resultKey(source, id), entry);
                newlySettled++;
            } catch (SourceException e) {
                errors++;
            }
        }
        if (newlySettled > 0) saveResults(results);

        Map<String, double[]> acc = new LinkedHashMap<>(); // coupons, won, lost, pending, staked, returned, expected
        int legsWon = 0, legsLost = 0, expectedCount = 0, clvCount = 0;
        double expectedSum = 0, clvSum = 0;
        for (JSONObject c : coupons) {
            String source = c.optString("source", "sofascore");
            String type = ("sofascore".equals(source) ? "" : source + ":") + c.optString("type", "semaine");
            double[] a = acc.computeIfAbsent(type, k -> new double[7]);
            a[0]++;
            boolean anyLost = false, anyPending = false;
            double odds = 1.0, prob = 1.0;
            for (Object o : c.getJSONArray("legs")) {
                JSONObject leg = (JSONObject) o;
                JSONObject r = results.optJSONObject(resultKey(source, leg.getInt("eventId")));
                if (r == null) {
                    anyPending = true;
                    continue;
                }
                if (r.optBoolean("void")) continue; // match annulé : sélection remboursée (cote 1)
                Market market = Market.valueOf(leg.getString("market"));
                boolean ok = market.wins(r.getInt("home"), r.getInt("away"));
                double p = leg.optDouble("finalProb", 0);
                expectedSum += p;
                expectedCount++;
                prob *= p;
                JSONObject closing = r.optJSONObject("closing");
                if (closing != null && closing.has(market.name())) {
                    clvSum += leg.getDouble("odds") / closing.getDouble(market.name()) - 1;
                    clvCount++;
                }
                if (ok) {
                    legsWon++;
                    odds *= leg.getDouble("odds");
                } else {
                    legsLost++;
                    anyLost = true;
                }
            }
            if (anyLost) {
                a[2]++;
                a[4]++;
                a[6] += prob;
            } else if (anyPending) {
                a[3]++;
            } else {
                a[1]++;
                a[4]++;
                a[5] += odds;
                a[6] += prob;
            }
        }
        Map<String, TypeSummary> byType = new LinkedHashMap<>();
        acc.forEach((type, a) -> byType.put(type,
            new TypeSummary((int) a[0], (int) a[1], (int) a[2], (int) a[3], a[4], a[5], a[6])));
        return new Summary(byType, legsWon, legsLost, expectedCount > 0 ? expectedSum / expectedCount : 0,
            clvCount, clvCount > 0 ? clvSum / clvCount : 0, newlySettled, errors);
    }

    private List<JSONObject> loadCoupons() {
        List<JSONObject> out = new ArrayList<>();
        if (!Files.exists(couponsFile)) return out;
        try {
            for (String line : Files.readAllLines(couponsFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    out.add(new JSONObject(line));
                } catch (Exception ignored) {
                    // ligne corrompue ignorée
                }
            }
        } catch (IOException e) {
            Log.warn("Lecture de l'historique impossible : " + e.getMessage());
        }
        return out;
    }

    private JSONObject loadResults() {
        try {
            return Files.exists(resultsFile) ? new JSONObject(Files.readString(resultsFile, StandardCharsets.UTF_8)) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void saveResults(JSONObject results) {
        try {
            Files.writeString(resultsFile, results.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("Résultats non enregistrés : " + e.getMessage());
        }
    }
}
