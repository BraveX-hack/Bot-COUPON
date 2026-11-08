package bfoot.coupon;

import java.util.Comparator;
import java.util.List;

/** Un coupon combiné. Une seule sélection par match (les marchés d'un même match ne sont pas indépendants). */
public record Ticket(List<Leg> legs) {

    public Ticket {
        legs = legs.stream().sorted(Comparator.comparing(l -> l.fixture().kickoff())).toList();
    }

    public double odds() {
        return legs.stream().mapToDouble(Leg::odds).reduce(1.0, (a, b) -> a * b);
    }

    /** Probabilité estimée que TOUT le coupon passe (sélections supposées indépendantes). */
    public double probability() {
        return legs.stream().mapToDouble(Leg::finalProb).reduce(1.0, (a, b) -> a * b);
    }

    /** Espérance de gain pour 1 € misé (1.20 = +20 % selon le modèle). */
    public double expectedValue() {
        return odds() * probability();
    }

    public boolean sharesMatchWith(Ticket other) {
        return legs.stream().anyMatch(l -> other.legs.stream().anyMatch(o -> o.fixture().eventId() == l.fixture().eventId()));
    }

    public long sharedMatches(Ticket other) {
        return legs.stream().filter(l -> other.legs.stream().anyMatch(o -> o.fixture().eventId() == l.fixture().eventId())).count();
    }
}
