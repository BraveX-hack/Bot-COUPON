package bfoot.model;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketPricingTest {

    @Test
    void retireLaMargeDu1X2EtDeriveLaDoubleChance() {
        Map<Market, Double> odds = new EnumMap<>(Market.class);
        odds.put(Market.HOME_WIN, 2.00);
        odds.put(Market.DRAW, 3.40);
        odds.put(Market.AWAY_WIN, 3.80);
        odds.put(Market.DC_1X, 1.25);
        Map<Market, Double> fair = MarketPricing.fairProbabilities(odds);
        double sum = fair.get(Market.HOME_WIN) + fair.get(Market.DRAW) + fair.get(Market.AWAY_WIN);
        assertEquals(1.0, sum, 1e-9);
        assertTrue(fair.get(Market.HOME_WIN) < 0.5, "la marge doit être retirée");
        assertEquals(fair.get(Market.HOME_WIN) + fair.get(Market.DRAW), fair.get(Market.DC_1X), 1e-9);
    }

    @Test
    void marcheIncompletUtiliseUneMargeParDefaut() {
        Map<Market, Double> odds = new EnumMap<>(Market.class);
        odds.put(Market.OVER_2_5, 1.80);
        Map<Market, Double> fair = MarketPricing.fairProbabilities(odds);
        assertEquals(1 / 1.80 / 1.06, fair.get(Market.OVER_2_5), 1e-9);
    }

    @Test
    void melange() {
        assertEquals(0.55, MarketPricing.blend(0.6, 0.5, 0.5), 1e-12);
    }
}
