package bfoot.engine;

import bfoot.ai.AiPanel;
import bfoot.ai.MatchContext;
import bfoot.coupon.CouponOptimizer;
import bfoot.coupon.Leg;
import bfoot.data.Fixture;
import bfoot.data.DataProvider;
import bfoot.data.MatchDetails;
import bfoot.data.OddsParser;
import bfoot.data.PlayedMatch;
import bfoot.data.RequestStats;
import bfoot.data.SourceException;
import bfoot.model.EloRatings;
import bfoot.model.Market;
import bfoot.model.TeamRatings;
import bfoot.store.ClubList;
import bfoot.util.Env;
import bfoot.util.Log;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Moteur d'analyse commun aux deux commandes :
 *
 *   /semaine       tous les matchs des compétitions suivies sur 7 jours
 *                  → 3 coupons de cote ~10 ;
 *   /grandsmatchs  les grands matchs du mois (Ligue des champions + top 5,
 *                  deux équipes fortes, ou un club de ta liste /clubs)
 *                  → 1 coupon "sécurité 50 %" + 1 coupon cote ~120.
 *
 * Étapes (et coût en requêtes, tout est mis en cache) :
 *   1. calendrier de chaque compétition         ~2 requêtes / compétition
 *   2. derniers matchs de chaque équipe          1 requête / équipe
 *   3. modèle de score + Elo                     0 requête
 *   4. cotes réelles (+ cotes d'ouverture)       1 requête / match retenu
 *   5. filtres de fiabilité                      0 requête
 *   6. relecture par les 3 IA (lots de 6 matchs) quelques appels
 *   7. optimisation des coupons                  0 requête
 */
public class CouponEngine {

    private static final DateTimeFormatter KICKOFF = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.FRENCH);

    private final DataProvider data;
    private final AiPanel aiPanel;
    private final Clock clock;
    private final ZoneId zone;

    private final double modelWeight = Env.getDouble("MODEL_WEIGHT", 0.5);
    private final double rho = Env.getDouble("DIXON_COLES_RHO", -0.08);
    private final double halfLifeDays = Env.getDouble("FORM_HALF_LIFE_DAYS", 120);
    private final double priorMatches = Env.getDouble("RATING_PRIOR_MATCHES", 4);
    private final int minTeamMatches = Env.getInt("MIN_TEAM_MATCHES", 6);
    private final int aiMaxMatches = Env.getInt("AI_MAX_MATCHES", 30);
    private final int kickoffMarginMinutes = Env.getInt("KICKOFF_MARGIN_MINUTES", 30);
    private final int parallelism = Math.max(1, Env.getInt("SOFASCORE_MAX_CONCURRENCY", 4));
    private final MatchAnalysis.Filters filters = new MatchAnalysis.Filters(
        Env.getDouble("MAX_ODDS_DRIFT", 0.08), Env.getDouble("MAX_ELO_DISAGREEMENT", 0.12));

    private final CouponOptimizer.Config weekConfig = CouponOptimizer.Config.forTarget(
        Env.getDouble("COUPON_TARGET_ODDS", 10.0), Env.getInt("COUPON_COUNT", 3));
    private final CouponOptimizer.Config safeConfig = CouponOptimizer.Config.safe(
        Env.getDouble("BIG_SAFE_MIN_PROBABILITY", 0.50), Env.getInt("BIG_SAFE_COUNT", 1));
    private final CouponOptimizer.Config longShotConfig = CouponOptimizer.Config.longShot(
        Env.getDouble("BIG_LONGSHOT_TARGET_ODDS", 120.0), Env.getInt("BIG_LONGSHOT_COUNT", 1));
    private final int bigMatchesMax = Env.getInt("BIG_MATCHES_MAX", 25);
    /** Contexte joueurs (absents, compos, notes, classement) pour les matchs analysés en détail. */
    private final boolean matchDetails = !"false".equalsIgnoreCase(Env.get("MATCH_DETAILS", "true"));
    private final int predictionsMax = Env.getInt("PREDICTIONS_MAX", 30);
    private final double strongTeamQuantile = Env.getDouble("BIG_MATCH_STRONG_QUANTILE", 0.65);

    public CouponEngine(DataProvider data, AiPanel aiPanel, Clock clock, ZoneId zone) {
        this.data = data;
        this.aiPanel = aiPanel;
        this.clock = clock;
        this.zone = zone;
    }

    public DataProvider provider() {
        return data;
    }

    /** Compétitions couvertes par cette source parmi une liste. */
    private List<Leagues.League> covered(List<Leagues.League> leagues) {
        return leagues.stream().filter(data::supports).toList();
    }

    // ------------------------------------------------------------------
    // /semaine
    // ------------------------------------------------------------------

    public CouponReport weekly(int days) {
        Run run = new Run(days);
        Map<Integer, Fixture> fixtures = run.fixtures(covered(Leagues.active()));
        Model model = run.model(fixtures.values());
        List<MatchAnalysis> priced = run.price(model.analyses());

        // Présélection pour les IA : les matchs qui fournissent des sélections candidates
        CouponOptimizer optimizer = new CouponOptimizer(weekConfig);
        List<Leg> preLegs = legsOf(priced);
        Map<Integer, MatchAnalysis> byId = index(priced);
        List<MatchAnalysis> shortlist = optimizer.candidates(preLegs, weekConfig.relaxedMinEdge()).stream()
            .map(l -> byId.get(l.fixture().eventId())).distinct().limit(aiMaxMatches).toList();
        run.details(shortlist, false);
        AiPanel.Result ai = run.review(shortlist);

        List<Leg> legs = legsOf(priced);
        CouponOptimizer.Result result = optimizer.build(legs);
        if (result.tickets().size() < weekConfig.coupons()) {
            run.warnings.add("Seulement " + result.tickets().size() + " coupon(s) possible(s) dans la fourchette de cote.");
        }
        return run.report(CouponReport.Kind.WEEK,
            List.of(new CouponReport.Section("semaine", weekConfig, result)), List.of(),
            fixtures.size(), model.analyses().size(), priced, shortlist.size(), ai);
    }

    // ------------------------------------------------------------------
    // /grandsmatchs
    // ------------------------------------------------------------------

    public CouponReport bigMatches(int days, ClubList clubs) {
        Run run = new Run(days);
        Map<Integer, Fixture> fixtures = run.fixtures(covered(Leagues.bigMatchLeagues()));
        Model model = run.model(fixtures.values());

        List<MatchAnalysis> big = selectBigMatches(model, clubs);
        // Les cotes ne sont demandées QUE pour les grands matchs retenus.
        List<MatchAnalysis> priced = run.price(big);
        if (priced.size() < big.size()) {
            run.warnings.add((big.size() - priced.size()) + " grand(s) match(s) sans cote publiée pour l'instant "
                + "(normal à plus de 2 semaines) : relance plus tard pour les inclure.");
        }
        run.details(priced, false);
        AiPanel.Result ai = run.review(priced);

        List<Leg> legs = legsOf(priced);
        List<CouponReport.Section> sections = new ArrayList<>();
        for (CouponOptimizer.Config cfg : List.of(safeConfig, longShotConfig)) {
            CouponOptimizer.Result r = new CouponOptimizer(cfg).build(legs);
            if (r.tickets().isEmpty()) {
                run.warnings.add("Coupon " + cfg.name() + " impossible avec les grands matchs qui ont déjà des cotes.");
            }
            sections.add(new CouponReport.Section(cfg == safeConfig ? "mois-securite" : "mois-gros", cfg, r));
        }
        return run.report(CouponReport.Kind.BIG_MATCHES, sections, big.stream().map(a -> a.fixture).toList(),
            fixtures.size(), model.analyses().size(), priced, priced.size(), ai);
    }

    // ------------------------------------------------------------------
    // /pronostics : un pronostic clair pour chaque grand match des prochains jours
    // ------------------------------------------------------------------

    public PredictionReport predictions(int days, ClubList clubs) {
        Run run = new Run(days);
        Map<Integer, Fixture> fixtures = run.fixtures(covered(Leagues.bigMatchLeagues()));
        Model model = run.model(fixtures.values());
        // Les clubs de la liste d'abord, puis l'ordre chronologique.
        List<MatchAnalysis> chosen = model.analyses().stream()
            .sorted(Comparator.comparing((MatchAnalysis a) -> !(clubs.matches(a.fixture.homeName()) || clubs.matches(a.fixture.awayName())))
                .thenComparing(a -> a.fixture.kickoff()))
            .limit(predictionsMax).toList();
        run.price(chosen); // les cotes servent de 2e avis ; un match sans cote garde le modèle seul
        run.details(chosen, true);
        AiPanel.Result ai = run.review(chosen);
        List<MatchCard> cards = chosen.stream()
            .sorted(Comparator.comparing(a -> a.fixture.kickoff()))
            .map(a -> MatchCard.of(a, modelWeight, rho)).toList();
        return run.predictionReport(cards, null, ai);
    }

    // ------------------------------------------------------------------
    // /analyse <équipe> : fiche complète du prochain match d'une équipe
    // ------------------------------------------------------------------

    public PredictionReport analyse(String teamQuery, int days) {
        Run run = new Run(days);
        String q = ClubList.normalize(teamQuery);
        Fixture target = null;
        Leagues.League targetLeague = null;
        Map<Integer, Fixture> leagueFixtures = new LinkedHashMap<>();
        for (Leagues.League league : covered(Leagues.active())) {
            List<Fixture> list = data.fixtures(league, run.from, run.to, run.warnings);
            for (Fixture f : list) {
                boolean hit = !q.isEmpty() && (ClubList.normalize(f.homeName()).contains(q) || ClubList.normalize(f.awayName()).contains(q));
                if (hit && (target == null || f.kickoff().isBefore(target.kickoff()))) {
                    target = f;
                    targetLeague = league;
                    leagueFixtures.clear();
                    list.forEach(x -> leagueFixtures.put(x.eventId(), x));
                }
            }
        }
        if (target == null) {
            return run.predictionReport(List.of(), teamQuery, AiPanel.Result.empty());
        }
        run.competitions.add(targetLeague.id());
        // Les autres matchs de la compétition servent à estimer la force des adversaires.
        Model model = run.model(leagueFixtures.values());
        final int targetId = target.eventId();
        List<MatchAnalysis> mine = model.analyses().stream().filter(a -> a.fixture.eventId() == targetId).toList();
        if (mine.isEmpty()) {
            run.warnings.add("Historique insuffisant pour analyser " + target.label() + ".");
            return run.predictionReport(List.of(), teamQuery, AiPanel.Result.empty());
        }
        run.price(mine);
        run.details(mine, true);
        AiPanel.Result ai = run.review(mine);
        return run.predictionReport(List.of(MatchCard.of(mine.get(0), modelWeight, rho)), teamQuery, ai);
    }

    /**
     * Grand match = deux équipes fortes (Elo des deux équipes au-dessus du
     * quantile choisi parmi les équipes de ces compétitions), ou un club de la
     * liste /clubs. Classés par prestige (Elo de la plus faible des deux).
     */
    private List<MatchAnalysis> selectBigMatches(Model model, ClubList clubs) {
        List<Double> elos = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (MatchAnalysis a : model.analyses()) {
            if (seen.add(a.fixture.homeId())) elos.add(model.elo().rating(a.fixture.homeId()));
            if (seen.add(a.fixture.awayId())) elos.add(model.elo().rating(a.fixture.awayId()));
        }
        if (elos.isEmpty()) return List.of();
        elos.sort(Double::compare);
        double threshold = elos.get(Math.min(elos.size() - 1, (int) Math.floor(strongTeamQuantile * elos.size())));

        List<MatchAnalysis> listed = new ArrayList<>();
        List<MatchAnalysis> strong = new ArrayList<>();
        for (MatchAnalysis a : model.analyses()) {
            boolean inList = clubs.matches(a.fixture.homeName()) || clubs.matches(a.fixture.awayName());
            double weaker = prestige(model, a);
            if (inList) listed.add(a);
            else if (weaker >= threshold) strong.add(a);
        }
        Comparator<MatchAnalysis> byPrestige = Comparator.comparingDouble((MatchAnalysis a) -> prestige(model, a)).reversed();
        listed.sort(byPrestige);
        strong.sort(byPrestige);
        List<MatchAnalysis> out = new ArrayList<>(listed);
        for (MatchAnalysis a : strong) {
            if (out.size() >= Math.max(bigMatchesMax, listed.size())) break;
            out.add(a);
        }
        return out;
    }

    private static double prestige(Model model, MatchAnalysis a) {
        return Math.min(model.elo().rating(a.fixture.homeId()), model.elo().rating(a.fixture.awayId()));
    }

    // ------------------------------------------------------------------
    // Étapes communes
    // ------------------------------------------------------------------

    private record Model(List<MatchAnalysis> analyses, EloRatings elo) {}

    /** Une exécution : fenêtre de temps, compteurs et remarques. */
    private final class Run {
        final Instant now = clock.instant();
        final Instant from = now.plus(Duration.ofMinutes(kickoffMarginMinutes));
        final Instant to;
        final RequestStats.Snapshot before = data.stats().snapshot();
        final List<String> warnings = new ArrayList<>();
        final Set<Integer> competitions = new LinkedHashSet<>();
        Map<Integer, List<PlayedMatch>> history = Map.of();

        Run(int days) {
            this.to = now.plus(Duration.ofDays(days));
        }

        Map<Integer, Fixture> fixtures(List<Leagues.League> leagues) {
            Map<Integer, Fixture> fixtures = new LinkedHashMap<>();
            for (Leagues.League league : leagues) {
                addFixtures(league, data.fixtures(league, from, to, warnings), fixtures, competitions, warnings);
            }
            if (fixtures.isEmpty()) {
                // Filet de sécurité propre à la source (Sofascore : calendrier jour par jour).
                for (Leagues.League league : leagues) {
                    addFixtures(league, data.fixturesFallback(league, from, to), fixtures, competitions, warnings);
                }
            }
            Log.info("Calendrier : " + fixtures.size() + " matchs sur " + competitions.size() + " compétitions.");
            return fixtures;
        }

        Model model(Collection<Fixture> fixtures) {
            Set<Integer> teamIds = new LinkedHashSet<>();
            fixtures.forEach(f -> { teamIds.add(f.homeId()); teamIds.add(f.awayId()); });
            history = parallelMap(teamIds, data::recentMatches, warnings, "historique équipe");
            Map<Integer, PlayedMatch> played = new HashMap<>();
            history.values().forEach(l -> l.forEach(m -> played.putIfAbsent(m.eventId(), m)));

            TeamRatings ratings = TeamRatings.fit(played.values(), now, halfLifeDays, priorMatches);
            EloRatings elo = EloRatings.fit(played.values());
            List<MatchAnalysis> analyses = new ArrayList<>();
            int insufficient = 0;
            for (Fixture f : fixtures) {
                List<PlayedMatch> hh = history.getOrDefault(f.homeId(), List.of());
                List<PlayedMatch> ah = history.getOrDefault(f.awayId(), List.of());
                if (hh.size() < minTeamMatches || ah.size() < minTeamMatches) {
                    insufficient++;
                    continue;
                }
                double eloExpected = elo.knows(f.homeId()) && elo.knows(f.awayId())
                    ? elo.homeExpectedScore(f.homeId(), f.awayId()) : Double.NaN;
                MatchAnalysis a = new MatchAnalysis(f,
                    ratings.expectedHomeGoals(f.homeId(), f.awayId()),
                    ratings.expectedAwayGoals(f.homeId(), f.awayId()),
                    rho, form(hh, f.homeId()), form(ah, f.awayId()), eloExpected);
                a.eloHome = elo.rating(f.homeId());
                a.eloAway = elo.rating(f.awayId());
                TeamRatings.Rating rh = ratings.rating(f.homeId()), ra = ratings.rating(f.awayId());
                if (rh != null) { a.attackHome = rh.attack(); a.defenseHome = rh.defense(); }
                if (ra != null) { a.attackAway = ra.attack(); a.defenseAway = ra.defense(); }
                analyses.add(a);
            }
            if (insufficient > 0) warnings.add(insufficient + " match(s) écarté(s) faute d'historique suffisant.");
            return new Model(analyses, elo);
        }

        /** Télécharge les cotes et ne garde que les matchs qui en ont. */
        List<MatchAnalysis> price(List<MatchAnalysis> analyses) {
            Map<Fixture, OddsParser.Snapshot> odds = parallelMap(
                analyses.stream().map(a -> a.fixture).toList(), data::oddsSnapshot, warnings, "cotes");
            analyses.forEach(a -> {
                OddsParser.Snapshot s = odds.get(a.fixture);
                if (s != null) a.setOdds(s);
            });
            return analyses.stream().filter(a -> !a.fairProbs.isEmpty()).toList();
        }

        /** Contexte joueurs/équipe (absents, compos, notes, classement, face-à-face), en parallèle et en cache. */
        void details(List<MatchAnalysis> analyses, boolean withHeadToHead) {
            if (!matchDetails || analyses.isEmpty()) return;
            Map<Fixture, MatchDetails> details = parallelMap(analyses.stream().map(a -> a.fixture).toList(),
                f -> data.details(f, withHeadToHead), warnings, "contexte joueurs");
            analyses.forEach(a -> a.details = details.getOrDefault(a.fixture, MatchDetails.unknown()));
        }

        /** Relecture par les IA puis recalcul cohérent de tous les marchés. */
        AiPanel.Result review(List<MatchAnalysis> shortlist) {
            AiPanel.Result ai = aiPanel.review(shortlist.stream().map(CouponEngine.this::context).toList());
            for (MatchAnalysis a : shortlist) {
                AiPanel.Consensus c = ai.consensus().get(a.fixture.eventId());
                if (c == null) continue;
                a.applyFactors(c.homeFactor(), c.awayFactor(), rho);
                a.aiHomeFactor = c.homeFactor();
                a.aiAwayFactor = c.awayFactor();
                a.aiVotes = c.votes();
                a.aiReasons = c.reasons();
                if (c.avoid()) a.avoidedByAi = true;
            }
            return ai;
        }

        PredictionReport predictionReport(List<MatchCard> cards, String query, AiPanel.Result ai) {
            RequestStats.Snapshot used = data.stats().snapshot().since(before);
            Log.info("Pronostics : " + cards.size() + " match(s), " + used.network() + " requêtes réseau, "
                + used.cacheHits() + " en cache.");
            return new PredictionReport(data.name(), now, from, to, cards, query, ai.providers(), used, data.quotaInfo(), warnings);
        }

        CouponReport report(CouponReport.Kind kind, List<CouponReport.Section> sections, List<Fixture> bigMatches,
                            int found, int analysed, List<MatchAnalysis> priced, int aiReviewed, AiPanel.Result ai) {
            RequestStats.Snapshot used = data.stats().snapshot().since(before);
            int avoided = (int) priced.stream().filter(a -> a.avoidedByAi).count();
            int filtered = priced.stream().mapToInt(a -> a.filteredLegs).sum();
            Log.info("Analyse " + kind + " : " + priced.size() + " matchs avec cotes, "
                + used.network() + " requêtes réseau, " + used.cacheHits() + " en cache.");
            List<MatchCard> cards = priced.stream().map(a -> MatchCard.of(a, modelWeight, rho)).toList();
            return new CouponReport(data.name(), kind, now, from, to, sections, bigMatches, cards, found, analysed, priced.size(),
                competitions.size(), aiReviewed, avoided, filtered, ai.providers(), used, data.quotaInfo(), warnings);
        }
    }

    private List<Leg> legsOf(List<MatchAnalysis> analyses) {
        List<Leg> legs = new ArrayList<>();
        analyses.forEach(a -> legs.addAll(a.legs(modelWeight, filters)));
        return legs;
    }

    private static Map<Integer, MatchAnalysis> index(List<MatchAnalysis> analyses) {
        Map<Integer, MatchAnalysis> byId = new HashMap<>();
        analyses.forEach(a -> byId.put(a.fixture.eventId(), a));
        return byId;
    }

    private static void addFixtures(Leagues.League league, List<Fixture> list, Map<Integer, Fixture> fixtures,
                                    Set<Integer> competitions, List<String> warnings) {
        if (list.isEmpty()) return;
        String actual = list.get(0).tournamentName();
        if (!league.matches(actual)) {
            warnings.add(league.name() + " (id " + league.id() + ") correspond en fait à « " + actual + " » : ignorée.");
            return;
        }
        competitions.add(league.id());
        list.forEach(f -> fixtures.putIfAbsent(f.eventId(), f));
    }

    private MatchContext context(MatchAnalysis a) {
        Fixture f = a.fixture;
        return new MatchContext(f.eventId(), KICKOFF.format(f.kickoff().atZone(zone)), f.tournamentName(),
            f.homeName(), f.awayName(), a.lambdaHome, a.lambdaAway,
            a.modelProbs.get(Market.HOME_WIN), a.modelProbs.get(Market.DRAW), a.modelProbs.get(Market.AWAY_WIN),
            a.modelProbs.get(Market.OVER_2_5), a.modelProbs.get(Market.BTTS_YES), a.homeForm, a.awayForm,
            contextText(a));
    }

    /** Résumé texte du contexte joueurs/équipe, envoyé aux IA. */
    static String contextText(MatchAnalysis a) {
        MatchDetails d = a.details;
        List<String> parts = new ArrayList<>();
        parts.add(String.format(Locale.ROOT, "Elo %.0f vs %.0f", a.eloHome, a.eloAway));
        if (d.homePosition() > 0 && d.awayPosition() > 0) parts.add("classement " + d.homePosition() + "e vs " + d.awayPosition() + "e");
        if (!Double.isNaN(d.homeAvgRating()) && !Double.isNaN(d.awayAvgRating())) {
            parts.add(String.format(Locale.ROOT, "note moyenne des joueurs %.2f vs %.2f", d.homeAvgRating(), d.awayAvgRating()));
        }
        if (d.lineupConfirmed() != null) parts.add("compositions " + (d.lineupConfirmed() ? "confirmées" : "probables"));
        parts.add("absents dom: " + absentsText(d.homeMissing()));
        parts.add("absents ext: " + absentsText(d.awayMissing()));
        if (d.headToHead() != null && d.headToHead().total() > 0) {
            parts.add("face-à-face " + d.headToHead().homeWins() + "V-" + d.headToHead().draws() + "N-" + d.headToHead().awayWins() + "D");
        }
        return String.join(" ; ", parts);
    }

    private static String absentsText(List<MatchDetails.Absence> list) {
        if (list.isEmpty()) return "aucun signalé";
        return list.stream().limit(8).map(MatchDetails.Absence::describe).collect(java.util.stream.Collectors.joining(", "));
    }

    /** 5 derniers résultats, du plus récent au plus ancien : "V2-1 N0-0 D1-3 …". */
    static String form(List<PlayedMatch> matches, int teamId) {
        StringBuilder sb = new StringBuilder();
        matches.stream().sorted(Comparator.comparing(PlayedMatch::date).reversed()).limit(5).forEach(m -> {
            int gf = m.homeId() == teamId ? m.homeGoals() : m.awayGoals();
            int ga = m.homeId() == teamId ? m.awayGoals() : m.homeGoals();
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(m.resultFor(teamId)).append(gf).append('-').append(ga);
        });
        return sb.isEmpty() ? "inconnue" : sb.toString();
    }

    private <K, V> Map<K, V> parallelMap(Collection<K> keys, Function<K, V> fn, List<String> warnings, String what) {
        Map<K, V> out = new ConcurrentHashMap<>();
        Map<String, Integer> errors = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (K k : keys) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        out.put(k, fn.apply(k));
                    } catch (SourceException e) {
                        errors.merge(e.isBlocked() ? "bloqué" : "erreur " + e.status(), 1, Integer::sum);
                    } catch (Exception e) {
                        errors.merge("illisible", 1, Integer::sum);
                        Log.error(what + " " + k, e);
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }
        errors.forEach((reason, n) -> warnings.add(n + " requête(s) " + what + " en échec (" + reason + ")."));
        return out;
    }
}
