package bfoot.model;

import bfoot.data.PlayedMatch;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Classement Elo des équipes (même principe que ClubElo / penaltyblog),
 * recalculé à partir des matchs déjà téléchargés : 0 requête de plus.
 *
 * Deux usages :
 *   - "deuxième avis" : si le modèle de score et l'Elo ne sont pas d'accord
 *     sur le rapport de force d'un match, on évite les paris sur le résultat
 *     de ce match (c'est souvent là que le modèle se trompe) ;
 *   - mesurer le prestige d'un match pour la sélection des grands matchs.
 */
public final class EloRatings {

    private static final double START = 1500;
    private static final double K = 20;
    private static final double HOME_ADVANTAGE = 65;

    private final Map<Integer, Double> ratings;

    private EloRatings(Map<Integer, Double> ratings) {
        this.ratings = ratings;
    }

    public static EloRatings fit(Collection<PlayedMatch> matches) {
        Map<Integer, Double> r = new HashMap<>();
        matches.stream().sorted(Comparator.comparing(PlayedMatch::date)).forEach(m -> {
            double rh = r.getOrDefault(m.homeId(), START);
            double ra = r.getOrDefault(m.awayId(), START);
            double expected = expectedScore(rh + HOME_ADVANTAGE, ra);
            double actual = m.homeGoals() > m.awayGoals() ? 1 : m.homeGoals() == m.awayGoals() ? 0.5 : 0;
            int diff = Math.abs(m.homeGoals() - m.awayGoals());
            // un large écart de buts compte un peu plus (formule World Football Elo)
            double margin = diff <= 1 ? 1 : diff == 2 ? 1.5 : (11 + diff) / 8.0;
            double delta = K * margin * (actual - expected);
            r.put(m.homeId(), rh + delta);
            r.put(m.awayId(), ra - delta);
        });
        return new EloRatings(r);
    }

    public double rating(int teamId) {
        return ratings.getOrDefault(teamId, START);
    }

    public boolean knows(int teamId) {
        return ratings.containsKey(teamId);
    }

    /** Score attendu de l'équipe à domicile (victoire = 1, nul = 0,5), avantage du terrain compris. */
    public double homeExpectedScore(int homeId, int awayId) {
        return expectedScore(rating(homeId) + HOME_ADVANTAGE, rating(awayId));
    }

    static double expectedScore(double a, double b) {
        return 1.0 / (1.0 + Math.pow(10, (b - a) / 400.0));
    }
}
