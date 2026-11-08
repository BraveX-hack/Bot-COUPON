package bfoot.store;

import bfoot.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Liste de clubs suivis, modifiable depuis Telegram (/clubs). Un match
 * impliquant l'un de ces clubs compte toujours comme un "grand match".
 * Fichier : data/clubs.txt (un club par ligne).
 */
public final class ClubList {

    private final Path file;

    public ClubList(Path dataDir) {
        this.file = dataDir.resolve("clubs.txt");
    }

    public synchronized List<String> clubs() {
        if (!Files.exists(file)) return List.of();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        } catch (IOException e) {
            Log.warn("Lecture de " + file + " impossible : " + e.getMessage());
            return List.of();
        }
    }

    public synchronized void replace(List<String> clubs) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, clubs.stream().map(String::trim).filter(s -> !s.isEmpty()).toList(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible d'enregistrer la liste de clubs : " + e.getMessage(), e);
        }
    }

    /** Vrai si le nom d'équipe Sofascore contient l'un des clubs de la liste (sans accents ni majuscules). */
    public boolean matches(String teamName) {
        String team = normalize(teamName);
        for (String club : clubs()) {
            String c = normalize(club);
            if (!c.isEmpty() && team.contains(c)) return true;
        }
        return false;
    }

    /** "Club Atlético de Madrid" → "club atletico de madrid". */
    public static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** "/clubs Real Madrid, Arsenal; Bayern" → [Real Madrid, Arsenal, Bayern]. */
    public static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split("[,;\\n]")) {
            if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }
}
