package bfoot.model;

/**
 * Marchés utilisés pour les coupons. On ne garde QUE les marchés :
 *   - qu'un modèle de score peut prédire de façon cohérente (tous dérivent de
 *     la même grille de scores, donc ils ne se contredisent jamais) ;
 *   - pour lesquels Sofascore publie une vraie cote (sinon le coupon n'est
 *     pas jouable).
 * Les anciens marchés corners/touches demandaient ~60 requêtes par match pour
 * des statistiques détaillées : ils ont été retirés.
 */
public enum Market {
    HOME_WIN("Victoire %H"),
    DRAW("Match nul"),
    AWAY_WIN("Victoire %A"),
    DC_1X("%H ou nul"),
    DC_X2("%A ou nul"),
    DC_12("Pas de match nul"),
    OVER_1_5("Plus de 1,5 but"),
    UNDER_1_5("Moins de 1,5 but"),
    OVER_2_5("Plus de 2,5 buts"),
    UNDER_2_5("Moins de 2,5 buts"),
    OVER_3_5("Plus de 3,5 buts"),
    UNDER_3_5("Moins de 3,5 buts"),
    BTTS_YES("Les deux équipes marquent"),
    BTTS_NO("Les deux équipes ne marquent pas toutes les deux");

    private final String template;

    Market(String template) {
        this.template = template;
    }

    /** Libellé lisible avec le nom des équipes. */
    public String label(String home, String away) {
        return template.replace("%H", home).replace("%A", away);
    }

    /** Ce marché est-il gagné avec ce score final (temps réglementaire) ? */
    public boolean wins(int home, int away) {
        int total = home + away;
        return switch (this) {
            case HOME_WIN -> home > away;
            case DRAW -> home == away;
            case AWAY_WIN -> away > home;
            case DC_1X -> home >= away;
            case DC_X2 -> away >= home;
            case DC_12 -> home != away;
            case OVER_1_5 -> total >= 2;
            case UNDER_1_5 -> total <= 1;
            case OVER_2_5 -> total >= 3;
            case UNDER_2_5 -> total <= 2;
            case OVER_3_5 -> total >= 4;
            case UNDER_3_5 -> total <= 3;
            case BTTS_YES -> home > 0 && away > 0;
            case BTTS_NO -> home == 0 || away == 0;
        };
    }
}
