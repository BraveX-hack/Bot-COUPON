package bfoot.model;

import java.util.EnumMap;
import java.util.Map;

/**
 * Distribution des scores d'un match et probabilités de chaque marché.
 *
 * Remplace la simulation Monte Carlo à 500 000 tirages : avec deux lois de
 * Poisson, la probabilité de chaque score (0-0, 1-0, ... 10-10) se calcule
 * EXACTEMENT. C'est ce que le Monte Carlo approchait, mais sans bruit
 * aléatoire et en quelques microsecondes au lieu de ~1 s par match.
 *
 * Ajout de la correction Dixon-Coles (paramètre rho) : un Poisson simple
 * sous-estime les petits scores serrés (0-0, 1-1) ; rho < 0 les rééquilibre.
 */
public final class ScoreModel {

    public static final int MAX_GOALS = 10;

    private ScoreModel() {}

    /** Grille P(score domicile = i, score extérieur = j), normalisée à 1. */
    public static double[][] scoreMatrix(double lambdaHome, double lambdaAway, double rho) {
        double[] ph = poisson(lambdaHome);
        double[] pa = poisson(lambdaAway);
        double[][] m = new double[MAX_GOALS + 1][MAX_GOALS + 1];
        double total = 0;
        for (int i = 0; i <= MAX_GOALS; i++) {
            for (int j = 0; j <= MAX_GOALS; j++) {
                double p = ph[i] * pa[j] * Math.max(0.0, tau(i, j, lambdaHome, lambdaAway, rho));
                m[i][j] = p;
                total += p;
            }
        }
        for (int i = 0; i <= MAX_GOALS; i++) {
            for (int j = 0; j <= MAX_GOALS; j++) {
                m[i][j] /= total;
            }
        }
        return m;
    }

    /** Probabilité de chaque marché, toutes issues de la même grille (donc cohérentes entre elles). */
    public static Map<Market, Double> marketProbabilities(double[][] m) {
        Map<Market, Double> out = new EnumMap<>(Market.class);
        for (Market market : Market.values()) out.put(market, 0.0);
        for (int i = 0; i <= MAX_GOALS; i++) {
            for (int j = 0; j <= MAX_GOALS; j++) {
                double p = m[i][j];
                for (Market market : Market.values()) {
                    if (market.wins(i, j)) out.merge(market, p, Double::sum);
                }
            }
        }
        return out;
    }

    public static Map<Market, Double> marketProbabilities(double lambdaHome, double lambdaAway, double rho) {
        return marketProbabilities(scoreMatrix(lambdaHome, lambdaAway, rho));
    }

    private static double[] poisson(double lambda) {
        double[] p = new double[MAX_GOALS + 1];
        p[0] = Math.exp(-lambda);
        for (int k = 1; k <= MAX_GOALS; k++) {
            p[k] = p[k - 1] * lambda / k;
        }
        return p;
    }

    private static double tau(int i, int j, double lh, double la, double rho) {
        if (i == 0 && j == 0) return 1 - lh * la * rho;
        if (i == 0 && j == 1) return 1 + lh * rho;
        if (i == 1 && j == 0) return 1 + la * rho;
        if (i == 1 && j == 1) return 1 - rho;
        return 1.0;
    }
}
