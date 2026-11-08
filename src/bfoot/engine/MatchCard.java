package bfoot.engine;

import bfoot.data.Fixture;
import bfoot.data.MatchDetails;
import bfoot.model.Market;

import java.util.List;
import java.util.Map;

/** Fiche complète d'un match, prête à afficher (/analyse, /pronostics). */
public record MatchCard(
    Fixture fixture,
    Prediction prediction,
    double eloHome,
    double eloAway,
    double attackHome,
    double defenseHome,
    double attackAway,
    double defenseAway,
    double lambdaHome,
    double lambdaAway,
    String homeForm,
    String awayForm,
    MatchDetails details,
    Map<Market, Double> odds,
    double aiHomeFactor,
    double aiAwayFactor,
    int aiVotes,
    List<String> aiReasons,
    boolean avoidedByAi,
    /** Probabilité finale (modèle + marché) de chaque marché, pour comparer les sources. */
    Map<Market, Double> finalProbs
) {
    static MatchCard of(MatchAnalysis a, double modelWeight, double rho) {
        return new MatchCard(a.fixture, Prediction.of(a, modelWeight, rho), a.eloHome, a.eloAway,
            a.attackHome, a.defenseHome, a.attackAway, a.defenseAway, a.lambdaHome, a.lambdaAway,
            a.homeForm, a.awayForm, a.details, a.odds.current(), a.aiHomeFactor, a.aiAwayFactor,
            a.aiVotes, a.aiReasons, a.avoidedByAi, finalProbs(a, modelWeight));
    }

    private static Map<Market, Double> finalProbs(MatchAnalysis a, double modelWeight) {
        Map<Market, Double> out = new java.util.EnumMap<>(Market.class);
        for (Market m : Market.values()) out.put(m, a.finalProb(m, modelWeight));
        return out;
    }
}
