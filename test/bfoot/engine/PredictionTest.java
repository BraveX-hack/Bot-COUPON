package bfoot.engine;

import bfoot.data.Fixture;
import bfoot.model.Market;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionTest {

    private static MatchAnalysis analysis(double lh, double la) {
        Fixture f = new Fixture(1, Instant.now(), 17, "Premier League", 1, "Arsenal", 2, "Chelsea");
        return new MatchAnalysis(f, lh, la, -0.08, "", "", Double.NaN);
    }

    @Test
    void grandFavoriConfianceElevee() {
        Prediction p = Prediction.of(analysis(2.6, 0.6), 0.5, -0.08);
        assertEquals(Market.HOME_WIN, p.main());
        assertEquals(Prediction.Confidence.ELEVEE, p.confidence());
        assertTrue(p.scoreHome() > p.scoreAway(), "score le plus probable : " + p.scoreHome() + "-" + p.scoreAway());
        assertEquals("Victoire Arsenal", p.main().label("Arsenal", "Chelsea"));
    }

    @Test
    void matchSerreDonneUneDoubleChance() {
        Prediction p = Prediction.of(analysis(1.25, 1.15), 0.5, -0.08);
        assertTrue(p.main() == Market.DC_1X || p.main() == Market.DC_X2, "match serré → double chance, obtenu " + p.main());
        assertTrue(p.mainProbability() > 0.6);
        assertTrue(p.scoreProbability() < 0.2, "un score exact reste rare");
    }

    @Test
    void tendanceButs() {
        assertEquals(Market.OVER_2_5, Prediction.of(analysis(2.2, 1.6), 0.5, -0.08).goals());
        assertEquals(Market.UNDER_2_5, Prediction.of(analysis(0.9, 0.7), 0.5, -0.08).goals());
    }
}
