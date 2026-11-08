# B-Foot v2 — Documentation

Bot Telegram qui analyse les matchs d'une sélection de compétitions et renvoie des coupons construits uniquement avec de vraies cotes :

- **/semaine** : 3 coupons de cote ~10 avec les matchs des 7 prochains jours ;
- **/grandsmatchs** : les grands matchs du mois → 1 coupon **sécurité** (réussite estimée ≥ 50 %, cote ~1,6–2,4) + 1 coupon **cote ~120** (réussite réelle ~1 %, affichée sur le coupon).

## Deux sources de données, comparées

Chaque commande tourne sur **deux sources indépendantes**, en parallèle, avec le même modèle :

| | Sofascore | Duo (football-data.org + The Odds API) |
|---|---|---|
| Accès | non officiel, peut bloquer (403) | API officielles, clés gratuites |
| Compétitions | toute la liste | les 9 couvertes par l'offre gratuite football-data.org : Ligue des champions, Premier League, LaLiga, Serie A, Bundesliga, Ligue 1, Eredivisie, Primeira Liga, Brasileirão |
| Cotes | 1X2, double chance, +/- buts, BTTS, cotes d'ouverture | 1X2 et +/- buts (médiane de tous les bookmakers européens) |
| Contexte joueurs | absents, compos, notes | classement et face-à-face seulement |
| Résultats pour /bilan | oui (+ cote de clôture) | oui |

Après les deux rapports, le bot envoie une **comparaison** :
- coupons : pour chaque sélection, la probabilité que lui donne l'autre source (✅ d'accord, ⚠️ nettement moins probable, ➖ match absent de l'autre source) et le coupon le plus confirmé ;
- /pronostics : les deux pronostics côte à côte (✅ compatibles / ⚠️ désaccord) ;
- /analyse : la fiche complète + l'avis de l'autre source.

Une source en panne n'empêche pas l'autre : si Sofascore bloque, tu reçois quand même les résultats Duo. `DATA_SOURCES=duo` dans le .env pour n'utiliser que Duo.

**Activer Duo** : crée une clé gratuite sur football-data.org et sur the-odds-api.com, puis ajoute `FOOTBALL_DATA_KEY=` et `ODDS_API_KEY=` dans le .env.
Consommation : football-data ~4 requêtes par compétition (calendrier, résultats, saison précédente, classement), mises en cache ; The Odds API 2 crédits par compétition toutes les 12 h au plus (≈ 18 crédits pour une analyse complète, 500 crédits gratuits par mois). Le premier lancement prend ~3 min à cause de la limite de 10 requêtes/minute de football-data, puis tout vient du cache. Les crédits restants sont affichés dans le message.

## Commandes

| Commande | Effet |
|---|---|
| `/semaine` | 3 coupons de cote ~10 avec les matchs des 7 prochains jours |
| `/semaine 3` | même chose sur les 3 prochains jours |
| `/brave` | alias de `/semaine` (ton envoi automatique continue de marcher) |
| `/grandsmatchs` | grands matchs des 30 prochains jours : coupon sécurité + coupon cote ~120 |
| `/pronostics` | un pronostic clair (résultat, score le plus probable, confiance) pour chaque grand match des 3 prochains jours (`/pronostics 7` pour une semaine) |
| `/analyse Real Madrid` | fiche complète du prochain match d'une équipe : puissance, forme, absents, notes des joueurs, classement, face-à-face, avis des IA, pronostic |
| `/clubs Real Madrid, Arsenal` | clubs toujours considérés comme « grands » (`/clubs` pour voir, `/clubs vider` pour effacer) |
| `/bilan` | résultats par type de coupon : gagnés / perdus, ROI, réussite réelle vs prévue, cote de clôture (CLV) |
| `/whoami` | affiche ton chatId (à mettre dans `ALLOWED_CHAT_IDS`) |

## Installation

1. Java 21 + Maven, Python 3 avec `pip install curl_cffi`.
2. Copie ton `.env` à la racine (même fichier qu'avant, il reste compatible). Ajoute de préférence `ALLOWED_CHAT_IDS=<ton chatId>`.
3. `mvn package` puis `java -jar target/b-foot.jar` (ou `mvn exec:java`).
4. Les tests : `mvn test`.

Le bot crée deux dossiers à côté de lui : `cache/` (réponses Sofascore et IA) et `data/` (historique des coupons pour `/bilan`).

## Comment un coupon est construit

```
Calendrier des compétitions (7 ou 30 jours)
   └─► derniers matchs de chaque équipe ──► force attaque/défense de chaque équipe
         └─► buts attendus (λ) ──► grille exacte des scores (Poisson + Dixon-Coles)
               └─► probabilité de chaque marché (1X2, double chance, +/- 1,5 / 2,5 / 3,5, BTTS)
                     └─► comparaison avec les vraies cotes (marge du bookmaker retirée)
                           └─► présélection ──► relecture par Gemini, Mistral, Groq (par lots)
                                 └─► recalcul ──► filtres (cote qui dérive, désaccord Elo)
                                       └─► optimiseur : coupons les plus probables dans la fourchette de cote
```

1. **Force des équipes** (`model/TeamRatings`) : modèle multiplicatif attaque × défense, ajusté à la force des adversaires, matchs récents pondérés (demi-vie 120 jours), lissage pour les équipes avec peu de matchs, avantage du terrain estimé sur les données. Les amicaux et les matchs reportés sont exclus ; seul le score du temps réglementaire compte.
2. **Distribution des scores** (`model/ScoreModel`) : calcul exact (ce que le Monte Carlo approchait avec 500 000 tirages), plus la correction Dixon-Coles des petits scores. Tous les marchés viennent de la même grille, donc ils sont toujours cohérents entre eux.
3. **Marché** (`model/MarketPricing`) : la cote du bookmaker est convertie en probabilité sans marge. Probabilité finale = 50 % modèle + 50 % marché (`MODEL_WEIGHT`).
4. **IA** (`ai/`) : les 3 IA reçoivent les matchs présélectionnés (30 max, 6 par appel) avec λ, probabilités et forme récente. Elles renvoient des facteurs prudents (0,85 à 1,15) sur les buts attendus et peuvent signaler un match à éviter (majorité). Le modèle recalcule ensuite tous les marchés. Avis mis en cache 12 h.
5. **Filtres de fiabilité** (inspirés des outils professionnels) :
   - **mouvement des cotes** : une sélection dont la cote a monté de plus de 8 % depuis l'ouverture est écartée (le marché s'en détourne) ;
   - **deuxième avis Elo** (`model/EloRatings`, principe ClubElo) : si l'Elo et le modèle de score ne voient pas le même rapport de force, les paris sur le résultat de ce match sont écartés.
6. **Coupons** (`coupon/CouponOptimizer`) : uniquement de vraies cotes, une sélection par match ; parmi les combinaisons qui ne sont pas perdantes à long terme, on garde **celles qui ont la plus grande probabilité de réussite** dans la fourchette de cote. Les coupons d'un même type sont construits sur des matchs différents.

| Coupon | Sélections | Cote | Probabilité min. par sélection |
|---|---|---|---|
| Semaine | 3 à 6 | 9,5 à 11 | 45 % |
| Sécurité (grands matchs) | 1 à 3 | 1,55 à 2,40, réussite totale ≥ 50 % | 45 % |
| Cote ~120 (grands matchs) | 5 à 10 | 110 à 134 | 35 % |

## Paramètres pris en compte pour chaque match

| Paramètre | Source | Utilisation |
|---|---|---|
| Puissance attaque / défense | ~30 derniers matchs de chaque équipe, ajustés à la force des adversaires | buts attendus du modèle |
| Classement Elo | mêmes matchs | 2e avis + prestige des matchs |
| Forme (5 derniers) | mêmes matchs | transmise aux IA, affichée |
| Absents (blessés, suspendus, incertains) avec leur poste | compositions Sofascore | transmis aux IA, qui ajustent les buts attendus (un buteur ou un gardien titulaire absent compte) |
| Composition confirmée ou probable, système de jeu | compositions Sofascore | affiché, transmis aux IA |
| Note moyenne des joueurs, classement | forme d'avant-match Sofascore | transmis aux IA, affiché |
| Face-à-face | Sofascore | fiche /analyse et /pronostics |
| Cotes et leur mouvement | Sofascore | probabilité finale (mélange modèle/marché), filtre de fiabilité |

Le contexte joueurs coûte 2 requêtes par match (3 avec le face-à-face) : il n'est chargé que pour les matchs analysés en détail (présélection des coupons, /pronostics, /analyse), et mis en cache (30 min à 12 h selon la proximité du match). Les avis des IA sont redemandés automatiquement si un nouvel absent est annoncé.

### « Résultat fixe »

Le bot donne **un pronostic clair** par match (le résultat le plus probable, ou la double chance si le match est trop serré, + le score le plus probable + un niveau de confiance), mais **jamais un résultat garanti** : même un pronostic à 65 % se trompe une fois sur trois, et le score exact le plus probable ne sort qu'environ 1 fois sur 8 à 1 fois sur 12. Les « matchs fixes » vendus en ligne sont des arnaques.

## Grands matchs du mois

- Compétitions : Ligue des champions + Premier League, LaLiga, Serie A, Bundesliga, Ligue 1, sur 30 jours.
- Un match est « grand » si les **deux** équipes sont dans le tiers supérieur du classement Elo de ces compétitions, ou si l'une d'elles est dans ta liste `/clubs` (toujours incluse). Les 25 plus prestigieux sont retenus.
- Les cotes ne sont téléchargées que pour ces matchs. Les bookmakers publient rarement les cotes plus de 2 semaines à l'avance : relance `/grandsmatchs` chaque semaine pour inclure les affiches suivantes.

### Pourquoi pas « cote 120 avec 50 % de réussite »

C'est mathématiquement impossible : la cote 120 signifie que le bookmaker estime la probabilité à ~0,8 %. Pour réussir 50 % du temps, il faudrait qu'il se trompe d'un facteur 60, et les meilleurs parieurs au monde ont un avantage de 2 à 5 %. Le bot propose donc les deux coupons séparément, avec leur vraie probabilité : un coupon sûr (~50 %, petite cote) et un gros coupon (~1 %, à jouer avec une toute petite mise).

## Consommation de requêtes

| | Avant (`/brave`) | Maintenant (`/semaine`) |
|---|---|---|
| Par match | ~70 requêtes Sofascore + 3 appels IA | ~1 cote + 2 équipes partagées |
| Semaine de ~120 matchs | ~8 000 requêtes + ~360 appels IA | ~400 requêtes + ~15 appels IA |
| Relancé le même jour | tout est refait | ~0 (cache) |

Mécanismes : cache disque avec durée de vie par type (saisons 7 j, calendrier 6 h, équipes 12 h, cotes 3 h), requêtes identiques simultanées fusionnées, rapport réutilisé pendant 60 min, coupe-circuit de 10 min après 5 blocages Sofascore, délai minimal entre requêtes.

## À vérifier au premier lancement

Je n'ai pas pu appeler Sofascore ni les IA depuis l'environnement de développement. Les points suivants ont un repli automatique, mais regarde la console au premier `/semaine` :

- **Liste "prochains matchs"** (`/unique-tournament/{id}/season/{saison}/events/next/0`) : si elle ne renvoie rien du tout, le bot repasse automatiquement en calendrier jour par jour.
- **Nouveaux identifiants de compétitions** (Brasileirão 325, Argentine 155, Coupe de France 335, Primeira Liga 238, Eredivisie 37, Süper Lig 52, Mondial des clubs 357, Liga MX Clausura 11620) : vérifiés automatiquement par le nom renvoyé par Sofascore ; un identifiant faux est ignoré et signalé dans les remarques du message.
- **Format des cotes** : les deux formats connus sont gérés (voir `OddsParserTest`), ainsi que les cotes d'ouverture (`initialFractionalValue`).
- **Gemini en REST** avec ta clé actuelle, et paramètres Groq (`reasoning_format`, `reasoning_effort`) : un paramètre refusé est retiré automatiquement.

## Suivre la fiabilité réelle : /bilan

- Résultats séparés par type de coupon (semaine, sécurité, cote ~120) : gagnés, perdus, ROI, nombre de coupons gagnants **prévus** par le modèle vs **réels**.
- **CLV (cote de clôture)** : après chaque match, le bot lit une fois la dernière cote d'avant-match et la compare à celle du coupon. Battre la clôture en moyenne est le meilleur signe d'un vrai avantage (c'est l'indicateur suivi par Trademate et les parieurs professionnels), et il apparaît bien plus vite que dans les résultats.
- Point à vérifier : la cote de clôture suppose que Sofascore garde les cotes d'avant-match après la rencontre. Si la ligne CLV n'apparaît jamais dans /bilan, ce n'est pas le cas.

## Ce qui a été retiré

- Corners et touches : ils coûtaient ~60 requêtes par match (statistiques détaillées de chaque match passé) et les cotes corners n'étaient pas lues.
- Les classes IA jamais utilisées (Cerebras, Cloudflare, DeepSeek), l'ancien Monte Carlo, l'ancien agrégateur, `lib/`, `target/`, `brave_test.json`, `sofascore_fetch.py`.
- La dépendance `google-genai`.

## Avertissement

Les probabilités sont des estimations : aucun coupon n'est sûr. Utilise `/bilan` pendant plusieurs semaines (au moins 50 à 100 sélections réglées) avant de faire confiance au modèle, et joue de façon responsable.
