package bfoot.engine;

import bfoot.ai.AiPanel;
import bfoot.coupon.CouponOptimizer;
import bfoot.data.Fixture;
import bfoot.data.RequestStats;

import java.time.Instant;
import java.util.List;

/** Résultat d'une analyse (semaine ou grands matchs du mois), prêt à être affiché. */
public record CouponReport(
    String source,
    Kind kind,
    Instant createdAt,
    Instant from,
    Instant to,
    List<Section> sections,
    List<Fixture> bigMatches,
    /** Fiches de tous les matchs avec cotes (servent à comparer les sources). */
    List<MatchCard> cards,
    int fixturesFound,
    int analysed,
    int withOdds,
    int competitions,
    int aiReviewed,
    int aiAvoided,
    int filteredLegs,
    List<AiPanel.ProviderStatus> aiProviders,
    RequestStats.Snapshot requests,
    String quota,
    List<String> warnings
) {
    public enum Kind { WEEK, BIG_MATCHES }

    /** Un groupe de coupons d'un même profil (ex : "cote ~10", "sécurité 50 %"). */
    public record Section(String key, CouponOptimizer.Config config, CouponOptimizer.Result result) {}
}
