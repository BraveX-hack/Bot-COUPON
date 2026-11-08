package bfoot.engine;

import bfoot.coupon.Leg;
import bfoot.coupon.Ticket;
import bfoot.data.Fixture;
import bfoot.data.TeamNames;
import bfoot.model.Market;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Compare deux analyses faites avec des sources de données différentes
 * (Sofascore et Duo). Les matchs sont rapprochés par l'heure et le nom des
 * équipes, puisque chaque source a ses propres identifiants.
 *
 * Une sélection est "confirmée" si l'autre source, sur le même match, voit
 * ce marché au moins aussi probable à 10 points près (ou à plus de 50 %).
 */
public final class SourceComparison {

    public enum Verdict { AGREE, DISAGREE, UNKNOWN }

    /** Avis de l'autre source sur une sélection. otherProbability = NaN si le match est absent de l'autre analyse. */
    public record LegCheck(Leg leg, double otherProbability, Verdict verdict) {}

    public record TicketCheck(String source, String sectionKey, int number, Ticket ticket, List<LegCheck> legs) {
        public long confirmed() { return legs.stream().filter(l -> l.verdict() == Verdict.AGREE).count(); }
        public long checked() { return legs.stream().filter(l -> l.verdict() != Verdict.UNKNOWN).count(); }
    }

    public record CouponComparison(String sourceA, String sourceB, List<TicketCheck> tickets, int commonMatches) {
        /** Coupon dont la plus grande part des sélections est confirmée par l'autre source. */
        public Optional<TicketCheck> mostConfirmed() {
            return tickets.stream().filter(t -> t.checked() > 0)
                .max((x, y) -> Double.compare((double) x.confirmed() / x.legs().size(), (double) y.confirmed() / y.legs().size()));
        }
    }

    /** Pronostics des deux sources pour un même match (b peut être null). */
    public record PredictionPair(MatchCard a, MatchCard b) {
        public boolean bothKnown() { return a != null && b != null; }
        public boolean agree() {
            return bothKnown() && compatible(a.prediction().main(), b.prediction().main());
        }
    }

    private SourceComparison() {}

    public static CouponComparison compare(CouponReport a, CouponReport b) {
        List<TicketCheck> checks = new ArrayList<>();
        checks.addAll(check(a, b));
        checks.addAll(check(b, a));
        int common = (int) a.cards().stream().filter(c -> find(b.cards(), c.fixture()).isPresent()).count();
        return new CouponComparison(a.source(), b.source(), checks, common);
    }

    private static List<TicketCheck> check(CouponReport from, CouponReport other) {
        List<TicketCheck> out = new ArrayList<>();
        for (CouponReport.Section s : from.sections()) {
            int n = 1;
            for (Ticket t : s.result().tickets()) {
                List<LegCheck> legs = new ArrayList<>();
                for (Leg l : t.legs()) {
                    Optional<MatchCard> card = find(other.cards(), l.fixture());
                    if (card.isEmpty()) {
                        legs.add(new LegCheck(l, Double.NaN, Verdict.UNKNOWN));
                        continue;
                    }
                    double p = card.get().finalProbs().getOrDefault(l.market(), Double.NaN);
                    Verdict v = Double.isNaN(p) ? Verdict.UNKNOWN
                        : (p >= 0.5 || p >= l.finalProb() - 0.10) ? Verdict.AGREE : Verdict.DISAGREE;
                    legs.add(new LegCheck(l, p, v));
                }
                out.add(new TicketCheck(from.source(), s.key(), n++, t, legs));
            }
        }
        return out;
    }

    public static List<PredictionPair> compare(PredictionReport a, PredictionReport b) {
        List<PredictionPair> pairs = new ArrayList<>();
        List<MatchCard> usedB = new ArrayList<>();
        for (MatchCard ca : a.cards()) {
            Optional<MatchCard> cb = find(b.cards(), ca.fixture());
            cb.ifPresent(usedB::add);
            pairs.add(new PredictionPair(ca, cb.orElse(null)));
        }
        for (MatchCard cb : b.cards()) {
            if (!usedB.contains(cb)) pairs.add(new PredictionPair(null, cb));
        }
        pairs.sort((x, y) -> (x.a() != null ? x.a() : x.b()).fixture().kickoff()
            .compareTo((y.a() != null ? y.a() : y.b()).fixture().kickoff()));
        return pairs;
    }

    public static Optional<MatchCard> find(List<MatchCard> cards, Fixture f) {
        MatchCard best = null;
        double bestScore = 0;
        for (MatchCard c : cards) {
            double s = TeamNames.matchScore(f.kickoff(), f.homeName(), f.awayName(),
                c.fixture().kickoff(), c.fixture().homeName(), c.fixture().awayName());
            if (s > bestScore) {
                bestScore = s;
                best = c;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Deux pronostics sont compatibles si l'un est inclus dans l'autre (ex : "victoire" et "victoire ou nul"). */
    static boolean compatible(Market x, Market y) {
        Set<Character> sx = outcomes(x), sy = outcomes(y);
        return sx.containsAll(sy) || sy.containsAll(sx);
    }

    private static Set<Character> outcomes(Market m) {
        Set<Character> s = new java.util.HashSet<>();
        if (m.wins(1, 0)) s.add('1');
        if (m.wins(1, 1) || m.wins(0, 0)) s.add('N');
        if (m.wins(0, 1)) s.add('2');
        return s;
    }
}
