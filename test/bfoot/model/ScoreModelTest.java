package bfoot.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreModelTest {

    @Test
    void poissonSansCorrectionDonneLesValeursExactes() {
        // Valeurs de référence calculées indépendamment (λ = 1,6 / 1,1)
        Map<Market, Double> p = ScoreModel.marketProbabilities(1.6, 1.1, 0.0);
        assertEquals(0.4896, p.get(Market.HOME_WIN), 1e-3);
        assertEquals(0.2489, p.get(Market.DRAW), 1e-3);
        assertEquals(0.2615, p.get(Market.AWAY_WIN), 1e-3);
        assertEquals(0.5064, p.get(Market.OVER_2_5), 1e-3);
        assertEquals(0.5324, p.get(Market.BTTS_YES), 1e-3);
    }

    @Test
    void marchesCoherentsEntreEux() {
        Map<Market, Double> p = ScoreModel.marketProbabilities(1.4, 0.9, -0.08);
        assertEquals(1.0, p.get(Market.HOME_WIN) + p.get(Market.DRAW) + p.get(Market.AWAY_WIN), 1e-9);
        assertEquals(1.0, p.get(Market.OVER_2_5) + p.get(Market.UNDER_2_5), 1e-9);
        assertEquals(1.0, p.get(Market.BTTS_YES) + p.get(Market.BTTS_NO), 1e-9);
        assertEquals(p.get(Market.HOME_WIN) + p.get(Market.DRAW), p.get(Market.DC_1X), 1e-9);
        assertTrue(p.get(Market.OVER_1_5) > p.get(Market.OVER_2_5));
        assertTrue(p.get(Market.OVER_2_5) > p.get(Market.OVER_3_5));
    }

    @Test
    void dixonColesAugmenteLesNuls() {
        double sansCorrection = ScoreModel.marketProbabilities(1.3, 1.1, 0.0).get(Market.DRAW);
        double avecCorrection = ScoreModel.marketProbabilities(1.3, 1.1, -0.1).get(Market.DRAW);
        assertTrue(avecCorrection > sansCorrection, "rho < 0 doit augmenter la probabilité de nul");
    }
}
