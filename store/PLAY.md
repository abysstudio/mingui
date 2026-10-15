# Google Play submission: Mingui

Everything Play Console asks for, ready to paste. Package `io.github.abysstudio.mingui` (the Play
variant: no root code). Upload `mingui.aab` built with `./build.sh play aab` (signed with the upload key).

## Assets (this folder)

| Console field | File | Spec |
|---|---|---|
| App icon | `icon-512.png` | 512×512 PNG |
| Feature graphic | `feature-graphic.png` | 1024×500 |
| Phone screenshots (per language) | `en/`, `es/`, `ca/` `01_…`–`07_…png` | 1080×1920, 7 each. The first three show in search results, so they lead with the two search terms (habit tracker, app blocker) |
| Promo video | `promo/mingui-promo-{en,es,ca}.mp4` | **Play only takes a YouTube URL**: upload there (unlisted is fine) and paste the link |

The promo videos are rendered from `../promo/promo.html` (see `../promo/render.py`) and kept out of git; they're attached to the GitHub release.

## Store listing

### English (en-US, default)

**App name** (30 max): `Mingui: Habits & App Blocker`

**Short description** (80 max):
`Habit tracker & app blocker: finish your daily routine, then your apps unlock.`

**Full description:**

```
Mingui is a habit tracker and app blocker in one: a daily routine checklist with a little buddy that keeps your most distracting apps locked until today's habits are done. Build a morning routine that sticks and cut your screen time without the willpower battle.

FINISH YOUR HABITS FIRST, SCROLL LATER
Pick the apps that eat your mornings: social media, videos, games. Open one too early and your habit checklist covers it. Tick off your daily habits and the blocked apps unlock straight away.

A HABIT TRACKER THAT STICKS
• Timers for habits you want to really do, not just tick
• Weekday schedules, rest days and partial credit
• Streak freezes, so one bad day doesn't break your habit streak
• Forgot to tick something? Fix yesterday
• Honest edits: changes that would make today's routine easier start tomorrow

AN APP BLOCKER THAT'S FAIR
• Block any app until your routine is done; work apps can stay allowed
• Optional focus lock pulls you back to your checklist, with three 5-minute breaks a day
• "Pause for today" is always there as an emergency exit

A BUDDY ON YOUR SCREEN
Your buddy walks around your phone, cheers you on and levels up with you. Unlock colours, hats and entrances as your streak grows.

HABIT STREAKS & STATS
A 12-week habit heatmap, your best streak and the habits that need some love.

100% OFFLINE, NO ACCOUNTS
Mingui doesn't have Android's internet permission, so it can't send anything anywhere. No accounts, ads, analytics or trackers. Your habits stay on your phone.

ALSO INCLUDED
A home-screen widget, backups to a file you keep, and English, Spanish and Catalan. Open source (GPL-3.0): github.com/abysstudio/mingui

Install Mingui, add three habits for tomorrow morning and get your mornings back.
```

### Español (es-ES)

**Nombre**: `Mingui: Hábitos, bloquea apps`

**Descripción breve**:
`Hábitos diarios y bloqueo de apps: acaba tu rutina y tus apps se desbloquean.`

**Descripción completa:**

```
Mingui es un registro de hábitos y un bloqueador de apps en uno: una lista de hábitos diarios con una pequeña mascota que mantiene bloqueadas las apps que más te distraen hasta que terminas la rutina de hoy. Crea una rutina de mañana que funcione y reduce tu tiempo de pantalla sin pelearte con tu fuerza de voluntad.

PRIMERO TUS HÁBITOS, LUEGO EL SCROLL
Elige las apps que te roban la mañana: redes sociales, vídeos, juegos. Si abres una demasiado pronto, tu lista de hábitos la tapa. Marca tus hábitos diarios y las apps bloqueadas se abren al momento.

UN REGISTRO DE HÁBITOS QUE FUNCIONA
• Temporizadores para los hábitos que quieres hacer de verdad
• Días de la semana, días de descanso y crédito parcial
• Congeladores de racha, para que un mal día no rompa tu racha
• ¿Olvidaste marcar algo? Corrige el día de ayer
• Cambios honestos: lo que haría más fácil la rutina de hoy empieza mañana

UN BLOQUEADOR DE APPS JUSTO
• Bloquea cualquier app hasta que acabes tu rutina; las apps de trabajo pueden quedar permitidas
• El modo concentración opcional te devuelve a la lista, con tres descansos de 5 minutos al día
• «Pausar por hoy» siempre está ahí como salida de emergencia

UNA MASCOTA EN TU PANTALLA
Tu mascota pasea por el móvil, te anima y sube de nivel contigo. Desbloquea colores, gorros y entradas a medida que crece tu racha.

RACHAS Y ESTADÍSTICAS DE HÁBITOS
Un mapa de 12 semanas, tu mejor racha y los hábitos que necesitan un empujón.

100 % SIN CONEXIÓN, SIN CUENTAS
Mingui no tiene el permiso de internet de Android, así que no puede enviar nada a ningún sitio. Sin cuentas, anuncios, analíticas ni rastreadores. Tus hábitos se quedan en tu móvil.

Y ADEMÁS
Widget para la pantalla de inicio, copias de seguridad en un archivo tuyo, y en español, catalán e inglés. Código abierto (GPL-3.0): github.com/abysstudio/mingui

Instala Mingui, añade tres hábitos para mañana por la mañana y recupera tus mañanas.
```

### Català (ca)

**Nom**: `Mingui: Hàbits, bloqueja apps`

**Descripció breu**:
`Hàbits diaris i bloqueig d'apps: acaba la rutina i les apps es desbloquegen.`

**Descripció completa:**

```
Mingui és un registre d'hàbits i un bloquejador d'apps alhora: una llista d'hàbits diaris amb una petita mascota que manté bloquejades les apps que més et distreuen fins que acabes la rutina d'avui. Crea una rutina de matí que funcioni i redueix el temps de pantalla sense barallar-te amb la força de voluntat.

PRIMER ELS HÀBITS, DESPRÉS L'SCROLL
Tria les apps que et roben el matí: xarxes socials, vídeos, jocs. Si n'obres una massa aviat, la llista d'hàbits la tapa. Marca els hàbits diaris i les apps bloquejades s'obren a l'instant.

UN REGISTRE D'HÀBITS QUE FUNCIONA
• Temporitzadors per als hàbits que vols fer de debò
• Dies de la setmana, dies de descans i crèdit parcial
• Congeladors de ratxa, perquè un mal dia no trenqui la ratxa
• T'has oblidat de marcar alguna cosa? Corregeix ahir
• Canvis honestos: el que faria més fàcil la rutina d'avui comença demà

UN BLOQUEJADOR D'APPS JUST
• Bloqueja qualsevol app fins que acabis la rutina; les apps de feina poden quedar permeses
• El mode concentració opcional et torna a la llista, amb tres descansos de 5 minuts al dia
• «Pausa per avui» sempre hi és com a sortida d'emergència

UNA MASCOTA A LA PANTALLA
La mascota passeja pel mòbil, t'anima i puja de nivell amb tu. Desbloqueja colors, barrets i entrades a mesura que creix la ratxa.

RATXES I ESTADÍSTIQUES D'HÀBITS
Un mapa de 12 setmanes, la teva millor ratxa i els hàbits que necessiten una empenta.

100 % SENSE CONNEXIÓ, SENSE COMPTES
Mingui no té el permís d'internet d'Android, així que no pot enviar res enlloc. Sense comptes, anuncis, analítiques ni rastrejadors. Els teus hàbits es queden al mòbil.

I A MÉS
Giny per a la pantalla d'inici, còpies de seguretat en un fitxer teu, i en català, castellà i anglès. Codi obert (GPL-3.0): github.com/abysstudio/mingui

Instal·la Mingui, afegeix tres hàbits per a demà al matí i recupera els teus matins.
```

**Category:** Productivity · **Tags:** Habit tracker, Productivity, Self-improvement · **Contact email:** required, use the studio address · **Website:** https://abysstudio.github.io/mingui/

## Release notes: 1.7.8 (code 23)

```
<en-US>
• Edits that would make today's unfinished routine easier now start tomorrow (or apply today if you choose)
• The streak pill always fits on one line; a gold star marks your best streak
• Blocked apps are covered faster
</en-US>
<es-ES>
• Los cambios que harían más fácil la rutina de hoy sin terminar ahora empiezan mañana (o hoy, si lo eliges)
• La racha siempre cabe en una línea; una estrella dorada marca tu mejor racha
• Las apps bloqueadas se tapan más rápido
</es-ES>
<ca>
• Els canvis que farien més fàcil la rutina d'avui sense acabar ara comencen demà (o avui, si ho tries)
• La ratxa sempre cap en una línia; una estrella daurada marca la teva millor ratxa
• Les apps bloquejades es tapen més ràpid
</ca>
```

## Search optimisation (ASO)

Google Play indexes the title, short description, full description and release notes, then ranks by quality signals (installs, retention, ratings, Android vitals).

- **Target terms:** habit tracker, app blocker, daily habits, daily routine, morning routine, screen time, focus. ES: hábitos diarios, registro de hábitos, bloqueador de apps, rutina, tiempo de pantalla. CA: hàbits diaris, bloquejador d'apps, rutina, temps de pantalla.
- **Title** = brand + the two main terms. **Short description** opens with them. **Full description** puts them in the first two sentences and repeats each 3–5 times naturally, ending with a call to action.
- **Screenshots:** captions carry the terms (Habit tracker / App blocker / Habit streaks). No third-party apps or logos.
- **Video:** the promo is 25 s and shows the app from the first second. Its first frame replaces screenshot 1 in the listing, so check the YouTube thumbnail before linking it.
- **After launch:** run a Store listing experiment on the icon first, then screenshot 1. Reply to every review (reviewers are notified and often update their rating). Keep crash rate < 1 % and ANR < 0.5 % in Android vitals. Change one field at a time, every 4–6 weeks.
- **Web:** the website (abysstudio.github.io/mingui) and the GitHub README link to the Play listing once it's live; links from the web count toward ranking.

## App content (Policy → App content)

| Form | Answer |
|---|---|
| Privacy policy | `https://abysstudio.github.io/mingui/privacy/` |
| Ads | **No**, the app doesn't contain ads |
| App access | **All functionality is available without special access** (no login). Optional reviewer note below |
| Content rating (IARC) | Category *Utility, Productivity, Communication or Other*; answer **No** to everything (no violence, user content, sharing, location, purchases, gambling). Expected: Everyone / PEGI 3 |
| Target audience | **13–15, 16–17, 18+** (don't tick under 13: that pulls the app into the Families policy) |
| News app | No |
| Health apps | No health features (it's a habit/productivity tool; it reads no health data) |
| Financial features | None |
| Government app | No |
| Data safety | **No data collected, no data shared.** Data is encrypted in transit: not applicable (no network). Users can request deletion: not applicable (nothing leaves the device; uninstalling deletes everything) |
| Foreground service permissions | Declare **Special use** (see below) |

### Foreground service declaration (needs a video)

Play asks for each foreground service type. Mingui uses one: `specialUse`.

- **Feature:** User-enabled focus session.
- **Description:** "While the user's daily routine is unfinished, the service watches which app is in the foreground so it can enforce the user's own app blocks (covering a blocked app with the checklist), and shows the user's on-screen companion. It stops when the routine is done or the user pauses it. The user can see and stop it from its notification."
- **Why it can't be deferred or interrupted:** blocking only works if it reacts the moment the user opens a blocked app; WorkManager or alarms can't do that.
- **Video link (required):** a short screen recording (YouTube, unlisted) showing: choose an app to block → open it → the checklist covers it → finish the routine → the app opens normally → the notification with Pause.

### Sensitive permissions in the Play build

| Permission | Play declaration form? | Notes |
|---|---|---|
| `PACKAGE_USAGE_STATS` (Usage access) | No form | Granted by the user in Settings; the app explains why before sending them there |
| `SYSTEM_ALERT_WINDOW` | No form | Covers blocked apps, shows the buddy |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Yes, above | |
| `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` | No | |

Not in the Play build (root variant only): `QUERY_ALL_PACKAGES`, `USE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. No Accessibility service.

### Reviewer note (App access → instructions, optional but helps)

```
No account or login. To see app blocking:
1. Open Mingui and go through setup (habits are pre-filled).
2. When asked, pick an app to block (e.g. Chrome) and allow Usage access and Display over other apps.
3. Open the blocked app: the checklist covers it until the routine is done.
4. Tick all habits: the app opens normally.
"Pause for today" (in the notification and the app) turns blocking off until tomorrow.
```

## Before production: things only you can do

1. **Closed testing first.** New personal developer accounts must run a closed test with at least 12 testers opted in for 14 days in a row before applying for production.
2. Upload the promo and the foreground-service demo videos to YouTube and paste the links.
3. Add the contact email in the store listing.
4. Upload `mingui.aab` to the closed-testing track, paste the release notes, and roll out.

## Review risks to keep an eye on

- **Other brands in the video.** The screenshots no longer name or show any third-party app, but the promo video still shows "YouTube" as the example of a blocked app. Describing which apps you can block is normally fine, but Google's metadata policy dislikes third-party brand names in listings; if a reviewer objects, swap the example for a generic one. The listing text above avoids brand names on purpose.
- **Overlay over other apps.** It's only shown over apps the user chose to block, and there's always an exit (Pause for today), which is what reviewers check for in "app blocker" apps.
