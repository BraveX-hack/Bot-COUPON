package bfoot.coupon;

import bfoot.data.Fixture;
import bfoot.model.Market;

/**
 * Une sélection possible pour un coupon.
 *
 * @param odds          vraie cote du bookmaker (jamais une cote estimée)
 * @param modelProb     probabilité du modèle de score (après avis des IA)
 * @param marketProb    probabilité implicite du bookmaker, marge retirée
 * @param finalProb     mélange modèle / marché, utilisée pour décider
 */
public record Leg(Fixture fixture, Market market, double odds, double modelProb, double marketProb, double finalProb) {

    /** Espérance de gain pour 1 € misé sur cette seule sélection (1.05 = +5 %). */
    public double expectedValue() {
        return finalProb * odds;
    }

    public String label() {
        return market.label(fixture.homeName(), fixture.awayName());
    }
}
