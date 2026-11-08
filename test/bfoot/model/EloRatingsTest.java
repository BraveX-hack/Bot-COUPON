package bfoot.model;

import bfoot.data.PlayedMatch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EloRatingsTest {

    @Test
    void lEquipeQuiGagneMonteEtLAvantageDuTerrainCompte() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        List<PlayedMatch> matches = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            matches.add(new PlayedMatch(i, t.plusSeconds(86400L * i), "L", 1, "Fort", 2, "Faible", 2, 0));
        }
        EloRatings elo = EloRatings.fit(matches);
        assertTrue(elo.rating(1) > elo.rating(2) + 150, "écart : " + (elo.rating(1) - elo.rating(2)));
        assertTrue(elo.homeExpectedScore(1, 2) > 0.75, "score attendu : " + elo.homeExpectedScore(1, 2));
        assertEquals(1500, elo.rating(99), 1e-9);
        // à force égale, l'équipe à domicile est légèrement favorite
        assertTrue(EloRatings.fit(List.of()).homeExpectedScore(3, 4) > 0.55);
    }
}
