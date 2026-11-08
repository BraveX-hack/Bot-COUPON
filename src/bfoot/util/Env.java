package bfoot.util;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Point d'accès unique à la configuration : lit d'abord le fichier .env à la
 * racine du projet, puis retombe sur les vraies variables d'environnement
 * (pratique en production, où .env n'existe pas forcément).
 *
 * Toutes les variables ajoutées par cette version sont OPTIONNELLES : sans
 * elles, le bot utilise des valeurs par défaut raisonnables. Ton .env actuel
 * fonctionne donc tel quel.
 */
public final class Env {

    private static final Dotenv DOTENV = Dotenv.configure()
        .ignoreIfMissing()
        .ignoreIfMalformed()
        .load();

    private Env() {}

    public static String get(String key) {
        String value = DOTENV.get(key);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        String sys = System.getenv(key);
        return (sys == null || sys.isBlank()) ? null : sys.trim();
    }

    public static String get(String key, String defaultValue) {
        String v = get(key);
        return v == null ? defaultValue : v;
    }

    public static int getInt(String key, int defaultValue) {
        String v = get(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            Log.warn("Variable " + key + " invalide (entier attendu) : " + v + " — valeur par défaut " + defaultValue);
            return defaultValue;
        }
    }

    public static double getDouble(String key, double defaultValue) {
        String v = get(key);
        if (v == null) return defaultValue;
        try {
            return Double.parseDouble(v.replace(',', '.'));
        } catch (NumberFormatException e) {
            Log.warn("Variable " + key + " invalide (nombre attendu) : " + v + " — valeur par défaut " + defaultValue);
            return defaultValue;
        }
    }

    /** Liste d'identifiants numériques séparés par des virgules (ex : "123,456"). */
    public static Set<Long> getLongSet(String key) {
        Set<Long> result = new LinkedHashSet<>();
        String v = get(key);
        if (v == null) return result;
        for (String part : v.split("[,;\\s]+")) {
            if (part.isBlank()) continue;
            try {
                result.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException e) {
                Log.warn("Identifiant ignoré dans " + key + " : " + part);
            }
        }
        return result;
    }
}
