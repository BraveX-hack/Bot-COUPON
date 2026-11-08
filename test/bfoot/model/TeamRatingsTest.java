package bfoot.model;

import bfoot.data.PlayedMatch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamRatingsTest {

    @Test
    void retrouveLaHierarchieEtLAvantageDuTerrain() {
        Random rnd = new Random(7);
        int teams = 10;
        double[] att = new double[teams], def = new double[teams];
        for (int i = 0; i < teams; i++) {
            att[i] = 1.5 - 0.1 * i;
            def[i] = 0.6 + 0.1 * i;
        }
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        List<PlayedMatch> matches = new ArrayList<>();
        int id = 1;
        for (int rep = 0; rep < 6; rep++) {
            for (int h = 0; h < teams; h++) {
                for (int a = 0; a < teams; a++) {
                    if (h == a) continue;
                    int gh = poisson(rnd, 1.2 * 1.3 * att[h] * def[a]);
                    int ga = poisson(rnd, 1.2 * att[a] * def[h]);
                    matches.add(new PlayedMatch(id++, now.minus(Duration.ofDays(rnd.nextInt(300))), "L",
                        h, "T" + h, a, "T" + a, gh, ga));
                }
            }
        }
        TeamRatings r = TeamRatings.fit(matches, now, 1e9, 2);
        assertTrue(r.rating(0).attack() > r.rating(9).attack(), "la meilleure attaque doit être devant");
        assertTrue(r.rating(0).defense() < r.rating(9).defense(), "la meilleure défense doit encaisser moins");
        assertEquals(1.3, r.homeAdvantage(), 0.12);
        assertTrue(r.expectedHomeGoals(0, 9) > 2 * r.expectedAwayGoals(0, 9));
    }

    @Test
    void unSeulGrosScoreNeDonnePasUneAttaqueDelirante() {
        Random rnd = new Random(3);
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        List<PlayedMatch> matches = new ArrayList<>();
        int id = 1;
        for (int rep = 0; rep < 4; rep++) {
            for (int h = 0; h < 8; h++) {
                for (int a = 0; a < 8; a++) {
                    if (h == a) continue;
                    matches.add(new PlayedMatch(id++, now.minus(Duration.ofDays(10)), "L", h, "T" + h, a, "T" + a,
                        poisson(rnd, 1.5), poisson(rnd, 1.2)));
                }
            }
        }
        // équipe 99 : un seul match, gagné 7-0 contre une équipe moyenne
        matches.add(new PlayedMatch(id, now.minus(Duration.ofDays(3)), "Coupe", 99, "Nouveau", 3, "T3", 7, 0));
        TeamRatings r = TeamRatings.fit(matches, now, 120, 4);
        // brut, ce serait ~4,7× la moyenne ; le lissage le ramène sous 2,5
        assertTrue(r.rating(99).attack() < 2.5, "attaque lissée : " + r.rating(99).attack());
        assertTrue(r.rating(99).attack() > 1.2, "le 7-0 doit quand même compter");
    }

    private static int poisson(Random rnd, double lambda) {
        double limit = Math.exp(-lambda), p = 1;
        int k = 0;
        do {
            k++;
            p *= rnd.nextDouble();
        } while (p > limit);
        return k - 1;
    }
}
