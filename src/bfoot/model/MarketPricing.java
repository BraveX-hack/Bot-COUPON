package bfoot.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Conversion des cotes du bookmaker en probabilités "justes" (marge retirée)
 * et mélange avec les probabilités du modèle.
 *
 * Pourquoi mélanger : la cote du marché résume l'avis de milliers de parieurs
 * et contient des infos que notre modèle n'a pas (compositions, blessures du
 * jour). Une probabilité finale = poids × modèle + (1 − poids) × marché est
 * beaucoup mieux calibrée que le modèle seul, et une "value" n'apparaît que
 * si le modèle s'écarte nettement du marché.
 */
public final class MarketPricing {

    /** Marge supposée quand on n'a qu'un côté du marché (typique : 5–7 %). */
    private static final double DEFAULT_MARGIN = 0.06;

    private static final List<List<Market>> GROUPS = List.of(
        List.of(Market.HOME_WIN, Market.DRAW, Market.AWAY_WIN),
        List.of(Market.OVER_1_5, Market.UNDER_1_5),
        List.of(Market.OVER_2_5, Market.UNDER_2_5),
        List.of(Market.OVER_3_5, Market.UNDER_3_5),
        List.of(Market.BTTS_YES, Market.BTTS_NO)
    );

    private MarketPricing() {}

    /** Probabilités implicites sans marge, pour chaque marché qui a une cote. */
    public static Map<Market, Double> fairProbabilities(Map<Market, Double> odds) {
        Map<Market, Double> fair = new EnumMap<>(Market.class);
        for (List<Market> group : GROUPS) {
            boolean complete = group.stream().allMatch(odds::containsKey);
            if (complete) {
                double overround = group.stream().mapToDouble(m -> 1.0 / odds.get(m)).sum();
                for (Market m : group) fair.put(m, (1.0 / odds.get(m)) / overround);
            } else {
                for (Market m : group) {
                    if (odds.containsKey(m)) fair.put(m, Math.min(0.99, (1.0 / odds.get(m)) / (1 + DEFAULT_MARGIN)));
                }
            }
        }
        // Double chance : dérivée du 1X2 sans marge quand il est complet
        // (plus fiable que les cotes double chance elles-mêmes).
        if (fair.containsKey(Market.HOME_WIN) && fair.containsKey(Market.DRAW) && fair.containsKey(Market.AWAY_WIN)) {
            double h = fair.get(Market.HOME_WIN), d = fair.get(Market.DRAW), a = fair.get(Market.AWAY_WIN);
            if (odds.containsKey(Market.DC_1X)) fair.put(Market.DC_1X, h + d);
            if (odds.containsKey(Market.DC_X2)) fair.put(Market.DC_X2, d + a);
            if (odds.containsKey(Market.DC_12)) fair.put(Market.DC_12, h + a);
        } else {
            for (Market m : List.of(Market.DC_1X, Market.DC_X2, Market.DC_12)) {
                if (odds.containsKey(m)) fair.put(m, Math.min(0.99, (1.0 / odds.get(m)) / (1 + DEFAULT_MARGIN)));
            }
        }
        return fair;
    }

    /** p finale = poids × p modèle + (1 − poids) × p marché. */
    public static double blend(double modelProbability, double marketProbability, double modelWeight) {
        return modelWeight * modelProbability + (1 - modelWeight) * marketProbability;
    }
}
