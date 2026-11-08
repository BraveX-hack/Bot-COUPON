package bfoot.engine;

import bfoot.coupon.Leg;
import bfoot.data.Fixture;
import bfoot.data.MatchDetails;
import bfoot.data.OddsParser;
import bfoot.model.Market;
import bfoot.model.MarketPricing;
import bfoot.model.ScoreModel;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Analyse complète d'un match : buts attendus, probabilités, cotes, filtres de fiabilité. */
final class MatchAnalysis {

    private static final Set<Market> RESULT_MARKETS = EnumSet.of(
        Market.HOME_WIN, Market.DRAW, Market.AWAY_WIN, Market.DC_1X, Market.DC_X2, Market.DC_12);

    /** Filtres de fiabilité, réglables dans le .env. */
    record Filters(double maxOddsDrift, double maxEloDisagreement) {}

    final Fixture fixture;
    final double baseLambdaHome;
    final double baseLambdaAway;
    final String homeForm;
    final String awayForm;
    /** Score attendu Elo de l'équipe à domicile (NaN si l'une des équipes est inconnue). */
    final double eloHomeExpected;
    /** Puissance des équipes : Elo, attaque et défense (1,0 = moyenne ; défense < 1 = solide). */
    double eloHome = Double.NaN, eloAway = Double.NaN;
    double attackHome = 1, defenseHome = 1, attackAway = 1, defenseAway = 1;
    /** Absents, compositions, note moyenne des joueurs, classement, face-à-face. */
    MatchDetails details = MatchDetails.unknown();
    /** Avis des IA (facteurs appliqués, raisons, nombre d'IA). */
    double aiHomeFactor = 1, aiAwayFactor = 1;
    int aiVotes;
    List<String> aiReasons = List.of();
    double lambdaHome;
    double lambdaAway;
    Map<Market, Double> modelProbs;
    OddsParser.Snapshot odds = new OddsParser.Snapshot(Map.of(), Map.of());
    Map<Market, Double> fairProbs = Map.of();
    boolean avoidedByAi;
    int filteredLegs;

    MatchAnalysis(Fixture fixture, double lambdaHome, double lambdaAway, double rho,
                  String homeForm, String awayForm, double eloHomeExpected) {
        this.fixture = fixture;
        this.baseLambdaHome = lambdaHome;
        this.baseLambdaAway = lambdaAway;
        this.homeForm = homeForm;
        this.awayForm = awayForm;
        this.eloHomeExpected = eloHomeExpected;
        applyFactors(1.0, 1.0, rho);
    }

    void applyFactors(double homeFactor, double awayFactor, double rho) {
        lambdaHome = baseLambdaHome * homeFactor;
        lambdaAway = baseLambdaAway * awayFactor;
        modelProbs = ScoreModel.marketProbabilities(lambdaHome, lambdaAway, rho);
    }

    void setOdds(OddsParser.Snapshot odds) {
        this.odds = odds;
        this.fairProbs = MarketPricing.fairProbabilities(odds.current());
    }

    /** Probabilité finale d'un marché : modèle mélangé au marché si une cote existe, modèle seul sinon. */
    double finalProb(Market m, double modelWeight) {
        Double market = fairProbs.get(m);
        double model = modelProbs.get(m);
        return market == null ? model : MarketPricing.blend(model, market, modelWeight);
    }

    /** Score attendu du modèle de score pour l'équipe à domicile (victoire 1, nul 0,5). */
    double modelHomeExpected() {
        return modelProbs.get(Market.HOME_WIN) + 0.5 * modelProbs.get(Market.DRAW);
    }

    /** Le modèle de score et l'Elo voient-ils le même rapport de force ? */
    boolean eloDisagrees(double maxDisagreement) {
        return !Double.isNaN(eloHomeExpected) && Math.abs(modelHomeExpected() - eloHomeExpected) > maxDisagreement;
    }

    /**
     * Une sélection par marché qui a une vraie cote, sauf :
     *   - si la cote a beaucoup monté depuis l'ouverture (le marché s'en détourne) ;
     *   - pour les marchés de résultat, si l'Elo contredit le modèle de score.
     */
    List<Leg> legs(double modelWeight, Filters filters) {
        List<Leg> legs = new ArrayList<>();
        filteredLegs = 0;
        if (avoidedByAi) return legs;
        boolean disagreement = eloDisagrees(filters.maxEloDisagreement());
        for (Map.Entry<Market, Double> e : odds.current().entrySet()) {
            Market m = e.getKey();
            Double market = fairProbs.get(m);
            if (market == null) continue;
            if (odds.drift(m) > filters.maxOddsDrift() || (disagreement && RESULT_MARKETS.contains(m))) {
                filteredLegs++;
                continue;
            }
            double model = modelProbs.get(m);
            legs.add(new Leg(fixture, m, e.getValue(), model, market, MarketPricing.blend(model, market, modelWeight)));
        }
        return legs;
    }
}
