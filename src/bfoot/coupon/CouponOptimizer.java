package bfoot.coupon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Construit des coupons combinés à partir d'une liste de sélections possibles.
 *
 * Trois profils (voir les fabriques de {@link Config}) :
 *   - cote ~10 (semaine) : 3 à 6 sélections ;
 *   - "sécurité 50 %" (grands matchs) : 1 à 3 sélections, réussite estimée ≥ 50 % ;
 *   - cote ~120 (grands matchs) : 5 à 10 sélections.
 *
 * Principes :
 *   - uniquement de vraies cotes bookmaker, une sélection par match ;
 *   - recherche en faisceau sur les combinaisons (pas un simple glouton) ;
 *   - objectif par défaut : la PLUS GRANDE PROBABILITÉ DE RÉUSSITE dans la
 *     fourchette de cote demandée, parmi les sélections qui ne sont pas
 *     perdantes à long terme (espérance ≥ seuil) ;
 *   - plusieurs coupons d'un même profil ne partagent aucun match si
 *     possible (au plus un sinon).
 */
public final class CouponOptimizer {

    public enum Objective { PROBABILITY, EXPECTED_VALUE }

    public record Config(
        String name,
        double targetOdds,
        double minTotalOdds,
        double maxTotalOdds,
        int minLegs,
        int maxLegs,
        double legMinOdds,
        double legMaxOdds,
        double legMinProbability,
        double minTicketProbability,
        double minEdge,
        double relaxedMinEdge,
        int coupons,
        Objective objective
    ) {
        /** Coupons de la semaine, cote ~target (10 par défaut). */
        public static Config forTarget(double target, int coupons) {
            return new Config("cote ~" + Math.round(target), target, target * 0.95, target * 1.10,
                3, 6, 1.30, 2.60, 0.45, 0.0, 0.0, -0.04, coupons, Objective.PROBABILITY);
        }

        /** Coupon "sécurité" : réussite estimée ≥ minProbability (50 % par défaut), cote ~1,6 à 2,4. */
        public static Config safe(double minProbability, int coupons) {
            return new Config("sécurité " + Math.round(minProbability * 100) + " %", 1.0 / minProbability,
                1.55, 2.40, 1, 3, 1.15, 2.40, 0.45, minProbability, 0.0, -0.04, coupons, Objective.PROBABILITY);
        }

        /** Gros coupon, cote ~target (120 par défaut). */
        public static Config longShot(double target, int coupons) {
            return new Config("cote ~" + Math.round(target), target, target * 0.92, target * 1.12,
                5, 10, 1.30, 3.20, 0.35, 0.0, 0.0, -0.04, coupons, Objective.PROBABILITY);
        }
    }

    public record Result(List<Ticket> tickets, boolean relaxed, int candidates) {}

    private static final int MAX_CANDIDATES = 80;
    private static final int MAX_PER_MATCH = 4;
    private static final int BEAM_WIDTH = 4000;
    private static final int MAX_VALID = 3000;

    private final Config config;

    public CouponOptimizer(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    public Result build(List<Leg> allLegs) {
        List<Leg> candidates = candidates(allLegs, config.minEdge());
        List<Ticket> tickets = pick(candidates);
        if (tickets.size() >= config.coupons()) {
            return new Result(tickets, false, candidates.size());
        }
        // Pas assez de "value" : on accepte des sélections légèrement sous
        // l'équilibre, et on le signale dans le message.
        List<Leg> relaxed = candidates(allLegs, config.relaxedMinEdge());
        return new Result(pick(relaxed), true, relaxed.size());
    }

    /**
     * Choisit les coupons un par un : chaque nouveau coupon est cherché parmi
     * les matchs pas encore utilisés (coupons indépendants). S'il n'y en a pas
     * assez, on complète en tolérant un seul match commun.
     */
    private List<Ticket> pick(List<Leg> candidates) {
        List<Ticket> chosen = new ArrayList<>();
        java.util.Set<Integer> used = new java.util.HashSet<>();
        while (chosen.size() < config.coupons()) {
            List<Leg> free = candidates.stream().filter(l -> !used.contains(l.fixture().eventId())).toList();
            List<Ticket> ranked = search(free);
            if (ranked.isEmpty()) break;
            Ticket best = ranked.get(0);
            chosen.add(best);
            best.legs().forEach(l -> used.add(l.fixture().eventId()));
        }
        if (chosen.size() < config.coupons()) {
            for (Ticket t : search(candidates)) {
                if (chosen.size() >= config.coupons()) break;
                if (!chosen.contains(t) && chosen.stream().allMatch(o -> t.sharedMatches(o) <= 1)) chosen.add(t);
            }
        }
        return chosen;
    }

    /**
     * Sélections éligibles. Le "vivier" est choisi par espérance (neutre
     * vis-à-vis de la cote, pour garder des sélections de toutes les cotes) ;
     * l'objectif (probabilité ou espérance) s'applique au classement final.
     */
    public List<Leg> candidates(List<Leg> legs, double minEdge) {
        Comparator<Leg> order = Comparator.comparingDouble(Leg::expectedValue).reversed()
            .thenComparing(Comparator.comparingDouble(Leg::finalProb).reversed());
        Map<Integer, List<Leg>> byMatch = new HashMap<>();
        for (Leg l : legs) {
            if (l.odds() < config.legMinOdds() || l.odds() > config.legMaxOdds()) continue;
            if (l.finalProb() < config.legMinProbability()) continue;
            if (l.expectedValue() - 1 < minEdge) continue;
            byMatch.computeIfAbsent(l.fixture().eventId(), k -> new ArrayList<>()).add(l);
        }
        List<Leg> out = new ArrayList<>();
        for (List<Leg> list : byMatch.values()) {
            list.sort(order);
            out.addAll(list.subList(0, Math.min(MAX_PER_MATCH, list.size())));
        }
        out.sort(order);
        return out.size() > MAX_CANDIDATES ? new ArrayList<>(out.subList(0, MAX_CANDIDATES)) : out;
    }

    private record State(int[] idx, int last, double logOdds, double logEv, double logProb) {}

    /** Recherche en faisceau : combinaisons dans la fourchette de cote, classées selon l'objectif. */
    List<Ticket> search(List<Leg> c) {
        int m = c.size();
        if (m == 0) return List.of();
        double logMin = Math.log(config.minTotalOdds());
        double logMax = Math.log(config.maxTotalOdds());
        double logMinProb = config.minTicketProbability() > 0 ? Math.log(config.minTicketProbability()) : Double.NEGATIVE_INFINITY;
        double logBestLeg = c.stream().mapToDouble(l -> Math.log(l.odds())).max().orElse(0);
        double[] lo = new double[m], le = new double[m], lp = new double[m];
        for (int i = 0; i < m; i++) {
            lo[i] = Math.log(c.get(i).odds());
            le[i] = Math.log(c.get(i).expectedValue());
            lp[i] = Math.log(c.get(i).finalProb());
        }

        List<State> valid = new ArrayList<>();
        List<State> beam = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            if (lo[i] > logMax || lp[i] < logMinProb) continue;
            State s = new State(new int[]{i}, i, lo[i], le[i], lp[i]);
            beam.add(s);
            if (config.minLegs() <= 1 && lo[i] >= logMin) valid.add(s);
        }
        for (int depth = 2; depth <= config.maxLegs() && !beam.isEmpty(); depth++) {
            List<State> next = new ArrayList<>();
            int remainingAfter = config.maxLegs() - depth;
            for (State s : beam) {
                for (int j = s.last() + 1; j < m; j++) {
                    double newLogOdds = s.logOdds() + lo[j];
                    if (newLogOdds > logMax) continue;
                    if (newLogOdds + remainingAfter * logBestLeg < logMin) continue; // ne pourra jamais atteindre la cible
                    double newLogProb = s.logProb() + lp[j];
                    if (newLogProb < logMinProb) continue; // la réussite ne peut que baisser
                    if (usesMatch(c, s.idx(), c.get(j).fixture().eventId())) continue;
                    int[] idx = Arrays.copyOf(s.idx(), s.idx().length + 1);
                    idx[idx.length - 1] = j;
                    next.add(new State(idx, j, newLogOdds, s.logEv() + le[j], newLogProb));
                }
            }
            // Élagage sur l'espérance : neutre vis-à-vis de la cote, donc ne
            // favorise pas les combinaisons qui ne pourront pas atteindre la cible.
            next.sort(Comparator.comparingDouble(State::logEv).reversed());
            if (next.size() > BEAM_WIDTH) next = new ArrayList<>(next.subList(0, BEAM_WIDTH));
            if (depth >= config.minLegs()) {
                for (State s : next) {
                    if (s.logOdds() >= logMin) valid.add(s);
                }
            }
            beam = next;
        }
        Comparator<State> rank = config.objective() == Objective.PROBABILITY
            ? Comparator.comparingDouble(State::logProb).reversed().thenComparing(Comparator.comparingDouble(State::logEv).reversed())
            : Comparator.comparingDouble(State::logEv).reversed().thenComparing(Comparator.comparingDouble(State::logProb).reversed());
        valid.sort(rank);
        List<Ticket> out = new ArrayList<>();
        for (State s : valid.subList(0, Math.min(MAX_VALID, valid.size()))) {
            List<Leg> legs = new ArrayList<>();
            for (int i : s.idx()) legs.add(c.get(i));
            out.add(new Ticket(legs));
        }
        return out;
    }

    private static boolean usesMatch(List<Leg> c, int[] idx, int eventId) {
        for (int i : idx) {
            if (c.get(i).fixture().eventId() == eventId) return true;
        }
        return false;
    }
}
