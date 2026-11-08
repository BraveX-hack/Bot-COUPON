package bfoot.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubListTest {

    @Test
    void enregistreEtReconnaitSansAccentsNiMajuscules() throws Exception {
        ClubList clubs = new ClubList(Files.createTempDirectory("bfoot-clubs"));
        clubs.replace(ClubList.parse("Real Madrid, atletico madrid; Bayern"));
        assertEquals(List.of("Real Madrid", "atletico madrid", "Bayern"), clubs.clubs());
        assertTrue(clubs.matches("Real Madrid"));
        assertTrue(clubs.matches("Atlético Madrid"));
        assertTrue(clubs.matches("FC Bayern München"));
        assertFalse(clubs.matches("Arsenal"));
        clubs.replace(List.of());
        assertTrue(clubs.clubs().isEmpty());
    }
}
