package bfoot.engine;

import bfoot.util.Env;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compétitions analysées (identifiants "uniqueTournament" Sofascore).
 *
 * Chaque entrée porte un motif de nom : si le nom renvoyé par Sofascore ne
 * correspond pas (mauvais identifiant), la compétition est ignorée et
 * signalée au lieu d'analyser silencieusement le mauvais championnat. Cela
 * permet d'ajouter les identifiants qui étaient à -1 sans risque.
 *
 * Pour restreindre la liste sans toucher au code : LEAGUE_IDS=17,8,23 dans le .env.
 *
 * Les compétitions avec un code football-data.org et une clé The Odds API sont
 * aussi analysées par la source "Duo" (offres gratuites de ces deux services).
 */
public final class Leagues {

    /**
     * @param footballDataCode code de la compétition chez football-data.org (null si non couverte)
     * @param oddsApiKey       clé du sport chez The Odds API (null si non couverte)
     */
    public record League(String name, int id, Pattern expectedName, String footballDataCode, String oddsApiKey) {
        League(String name, int id, String regex) {
            this(name, id, regex, null, null);
        }

        League(String name, int id, String regex, String footballDataCode, String oddsApiKey) {
            this(name, id, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), footballDataCode, oddsApiKey);
        }

        public boolean matches(String sofascoreName) {
            return sofascoreName == null || sofascoreName.isBlank() || expectedName.matcher(sofascoreName).find();
        }
    }

    public static final List<League> ALL = List.of(
        // Élite européenne
        new League("Ligue des champions", 7, "Champions League", "CL", "soccer_uefa_champs_league"),
        new League("Premier League", 17, "Premier League", "PL", "soccer_epl"),
        new League("LaLiga", 8, "La ?Liga|Primera", "PD", "soccer_spain_la_liga"),
        new League("Serie A", 23, "Serie A", "SA", "soccer_italy_serie_a"),
        new League("Bundesliga", 35, "Bundesliga", "BL1", "soccer_germany_bundesliga"),
        new League("Ligue 1", 34, "Ligue 1", "FL1", "soccer_france_ligue_one"),
        // Coupes d'Europe et continentales
        new League("Ligue Europa", 679, "Europa League"),
        new League("Ligue Conférence", 17015, "Conference League"),
        new League("Copa Libertadores", 384, "Libertadores"),
        new League("Coupe du monde des clubs", 357, "Club World Cup"),
        // Hors Europe (identifiants ajoutés : vérifiés automatiquement par le nom)
        new League("Brasileirão Série A", 325, "Brasileir|Série A|Serie A", "BSA", "soccer_brazil_campeonato"),
        new League("Liga Profesional (Argentine)", 155, "Liga Profesional|Argentin"),
        new League("Liga MX Apertura", 11621, "Liga MX|Apertura"),
        new League("Liga MX Clausura", 11620, "Liga MX|Clausura"),
        new League("Saudi Pro League", 955, "Saudi|Roshn"),
        new League("MLS", 242, "MLS|Major League Soccer"),
        // Coupes nationales
        new League("FA Cup", 19, "FA Cup"),
        new League("Copa del Rey", 329, "Copa del Rey"),
        new League("Coupe de France", 335, "Coupe de France"),
        new League("DFB-Pokal", 217, "DFB|Pokal"),
        new League("Coppa Italia", 328, "Coppa Italia"),
        // Autres championnats européens
        new League("Primeira Liga", 238, "Liga Portugal|Primeira", "PPL", "soccer_portugal_primeira_liga"),
        new League("Eredivisie", 37, "Eredivisie", "DED", "soccer_netherlands_eredivisie"),
        new League("Süper Lig", 52, "S[uü]per Lig"),
        // Féminin
        new League("Ligue des champions féminine", 696, "Women|Féminin|Feminine")
    );

    /** Compétitions des "grands matchs du mois" : Ligue des champions + top 5 européen. */
    public static final Set<Integer> BIG_MATCH_IDS = Set.of(7, 17, 8, 23, 35, 34);

    private Leagues() {}

    public static List<League> bigMatchLeagues() {
        return ALL.stream().filter(l -> BIG_MATCH_IDS.contains(l.id())).toList();
    }

    /** Liste active : toutes, ou seulement celles de LEAGUE_IDS si défini. */
    public static List<League> active() {
        Set<Long> filter = Env.getLongSet("LEAGUE_IDS");
        if (filter.isEmpty()) return ALL;
        return ALL.stream().filter(l -> filter.contains((long) l.id())).toList();
    }
}
