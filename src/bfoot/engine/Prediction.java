package bfoot.engine;

import bfoot.model.Market;
import bfoot.model.ScoreModel;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pronostic "clair" d'un match : le résultat le plus probable, le score le
 * plus probable, une tendance buts / BTTS et un niveau de confiance.
 *
 * Ce n'est jamais un résultat garanti : même le pronostic "confiance élevée"
 * (≥ 65 %) se trompe environ une fois sur trois. Le pourcentage affiché dit
 * exactement à quel point.
 */
public record Prediction(
    Market main,
    double mainProbability,
    Confidence confidence,
    Market goals,
    double goalsProbability,
    Market btts,
    double bttsProbability,
    int scoreHome,
    int scoreAway,
    double scoreProbability,
    Map<Market, Double> probabilities
) {
    public enum Confidence {
        ELEVEE("élevée"), MOYENNE("moyenne"), FAIBLE("faible");
        public final String label;
        Confidence(String label) { this.label = label; }
    }

    static Prediction of(MatchAnalysis a, double modelWeight, double rho) {
        Map<Market, Double> p = new EnumMap<>(Market.class);
        for (Market m : List.of(Market.HOME_WIN, Market.DRAW, Market.AWAY_WIN, Market.DC_1X, Market.DC_X2,
                Market.OVER_2_5, Market.UNDER_2_5, Market.BTTS_YES, Market.BTTS_NO)) {
            p.put(m, a.finalProb(m, modelWeight));
        }
        // Résultat : le plus probable des trois ; s'il est trop serré (< 45 %),
        // on donne la double chance la plus probable, plus honnête.
        Market main = Market.HOME_WIN;
        for (Market m : List.of(Market.DRAW, Market.AWAY_WIN)) {
            if (p.get(m) > p.get(main)) main = m;
        }
        if (p.get(main) < 0.45) {
            main = p.get(Market.DC_1X) >= p.get(Market.DC_X2) ? Market.DC_1X : Market.DC_X2;
        }
        double mainProb = p.get(main);
        Confidence confidence = mainProb >= 0.65 ? Confidence.ELEVEE : mainProb >= 0.50 ? Confidence.MOYENNE : Confidence.FAIBLE;

        Market goals = p.get(Market.OVER_2_5) >= 0.55 ? Market.OVER_2_5 : p.get(Market.UNDER_2_5) >= 0.55 ? Market.UNDER_2_5 : null;
        Market btts = p.get(Market.BTTS_YES) >= 0.58 ? Market.BTTS_YES : p.get(Market.BTTS_NO) >= 0.58 ? Market.BTTS_NO : null;

        double[][] grid = ScoreModel.scoreMatrix(a.lambdaHome, a.lambdaAway, rho);
        int bh = 0, ba = 0;
        for (int i = 0; i < grid.length; i++) {
            for (int j = 0; j < grid[i].length; j++) {
                if (grid[i][j] > grid[bh][ba]) { bh = i; ba = j; }
            }
        }
        return new Prediction(main, mainProb, confidence, goals, goals == null ? 0 : p.get(goals),
            btts, btts == null ? 0 : p.get(btts), bh, ba, grid[bh][ba], p);
    }
}
