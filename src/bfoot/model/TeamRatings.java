package bfoot.model;

import bfoot.data.PlayedMatch;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Force offensive / défensive de chaque équipe, estimée à partir des matchs
 * récents (modèle de Maher / Dixon-Coles) :
 *
 *   buts attendus domicile  = mu × avantageDomicile × attaque(dom) × défense(ext)
 *   buts attendus extérieur = mu × attaque(ext) × défense(dom)
 *
 * Améliorations par rapport à l'ancien calcul (0,55 × buts marqués + 0,45 ×
 * buts encaissés) :
 *   - modèle multiplicatif standard, avec une moyenne de référence (mu) ;
 *   - l'adversaire compte : marquer 3 buts à une défense faible vaut moins
 *     que contre une défense forte (ajustement itératif) ;
 *   - les matchs récents pèsent plus (demi-vie configurable) ;
 *   - lissage vers la moyenne quand une équipe a peu de matchs (pas de
 *     valeurs extrêmes sur 3 matchs) ;
 *   - avantage du terrain estimé sur les données au lieu d'un 1,08 fixe.
 *
 * Aucune requête supplémentaire : tout vient des "derniers matchs" des équipes.
 */
public final class TeamRatings {

    public record Rating(double attack, double defense, double weightedMatches, int matches) {}

    private static final int ITERATIONS = 60;

    private final Map<Integer, Rating> ratings;
    private final double mu;
    private final double homeAdvantage;

    private TeamRatings(Map<Integer, Rating> ratings, double mu, double homeAdvantage) {
        this.ratings = ratings;
        this.mu = mu;
        this.homeAdvantage = homeAdvantage;
    }

    /**
     * @param matches       matchs terminés (sans doublons)
     * @param now           date de référence pour la pondération temporelle
     * @param halfLifeDays  un match de cet âge compte moitié moins qu'un match d'aujourd'hui
     * @param priorMatches  nombre de "matchs fictifs moyens" ajoutés à chaque équipe (lissage)
     */
    public static TeamRatings fit(Collection<PlayedMatch> matches, Instant now, double halfLifeDays, double priorMatches) {
        int n = matches.size();
        int[] home = new int[n], away = new int[n];
        double[] gh = new double[n], ga = new double[n], w = new double[n];
        Map<Integer, Integer> index = new HashMap<>();
        int k = 0;
        for (PlayedMatch m : matches) {
            home[k] = index.computeIfAbsent(m.homeId(), id -> index.size());
            away[k] = index.computeIfAbsent(m.awayId(), id -> index.size());
            gh[k] = m.homeGoals();
            ga[k] = m.awayGoals();
            double ageDays = Math.max(0, Duration.between(m.date(), now).toHours() / 24.0);
            w[k] = Math.pow(0.5, ageDays / halfLifeDays);
            k++;
        }
        int teams = index.size();
        double[] att = new double[teams], def = new double[teams];
        double[] wCount = new double[teams];
        int[] count = new int[teams];
        java.util.Arrays.fill(att, 1.0);
        java.util.Arrays.fill(def, 1.0);
        for (int i = 0; i < n; i++) {
            wCount[home[i]] += w[i];
            wCount[away[i]] += w[i];
            count[home[i]]++;
            count[away[i]]++;
        }

        double sumW = 0, sumH = 0, sumA = 0;
        for (int i = 0; i < n; i++) {
            sumW += w[i];
            sumH += w[i] * gh[i];
            sumA += w[i] * ga[i];
        }
        double mu = sumW > 0 ? Math.max(0.3, sumA / sumW) : 1.2;
        double hfa = (sumW > 0 && sumA > 0) ? clamp(sumH / sumA, 1.0, 1.6) : 1.25;
        double meanGoals = mu * Math.sqrt(hfa);
        double prior = priorMatches * meanGoals;

        for (int it = 0; it < ITERATIONS; it++) {
            double[] attNum = new double[teams], attDen = new double[teams];
            double[] defNum = new double[teams], defDen = new double[teams];
            for (int i = 0; i < n; i++) {
                int h = home[i], a = away[i];
                // buts marqués par h, attendus sans son attaque
                attNum[h] += w[i] * gh[i];
                attDen[h] += w[i] * mu * hfa * def[a];
                attNum[a] += w[i] * ga[i];
                attDen[a] += w[i] * mu * def[h];
                // buts encaissés par a (resp. h), attendus sans sa défense
                defNum[a] += w[i] * gh[i];
                defDen[a] += w[i] * mu * hfa * att[h];
                defNum[h] += w[i] * ga[i];
                defDen[h] += w[i] * mu * att[a];
            }
            for (int t = 0; t < teams; t++) {
                att[t] = (attNum[t] + prior) / (attDen[t] + prior);
                def[t] = (defNum[t] + prior) / (defDen[t] + prior);
            }
            double expH = 0, expA = 0;
            for (int i = 0; i < n; i++) {
                expH += w[i] * att[home[i]] * def[away[i]];
                expA += w[i] * att[away[i]] * def[home[i]];
            }
            if (expA > 0 && expH > 0) {
                mu = Math.max(0.3, sumA / expA);
                hfa = clamp((sumH / expH) / mu, 1.0, 1.6);
            }
        }

        Map<Integer, Rating> out = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : index.entrySet()) {
            int t = e.getValue();
            out.put(e.getKey(), new Rating(att[t], def[t], wCount[t], count[t]));
        }
        return new TeamRatings(out, mu, hfa);
    }

    public Rating rating(int teamId) {
        return ratings.get(teamId);
    }

    public double expectedHomeGoals(int homeId, int awayId) {
        Rating h = ratings.get(homeId), a = ratings.get(awayId);
        double attH = h != null ? h.attack() : 1.0, defA = a != null ? a.defense() : 1.0;
        return clamp(mu * homeAdvantage * attH * defA, 0.2, 4.5);
    }

    public double expectedAwayGoals(int homeId, int awayId) {
        Rating h = ratings.get(homeId), a = ratings.get(awayId);
        double attA = a != null ? a.attack() : 1.0, defH = h != null ? h.defense() : 1.0;
        return clamp(mu * attA * defH, 0.2, 4.5);
    }

    public double mu() { return mu; }
    public double homeAdvantage() { return homeAdvantage; }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
