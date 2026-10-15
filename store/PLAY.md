# Google Play submission: Mingui

Everything Play Console asks for, ready to paste. Package `io.github.abysstudio.mingui` (the Play
variant: no root code). Upload `mingui.aab` built with `./build.sh play aab` (signed with the upload key).

## Assets (this folder)

| Console field | File | Spec |
|---|---|---|
| App icon | `icon-512.png` | 512×512 PNG |
| Feature graphic | `feature-graphic.png` | 1024×500 |
| Phone screenshots (per language) | `en/`, `es/`, `ca/` `01_…`–`08_…png` | 1080×1920, 8 each |
| Promo video | `promo/mingui-promo-{en,es,ca}.mp4` | **Play only takes a YouTube URL**: upload there (unlisted is fine) and paste the link |

The promo videos are rendered from `../promo/promo.html` (see `../promo/render.py`) and kept out of git; they're attached to the GitHub release.

## Store listing

### English (en-US, default)

**App name** (30 max): `Mingui: Habits & App Blocker`

**Short description** (80 max):
`Finish today's habits first. Then your distracting apps unlock. 100% offline.`

**Full description:**

```
Mingui is a daily habit checklist with a little buddy, and it keeps your most distracting apps locked until today's routine is done.

FINISH FIRST, SCROLL LATER
Pick the apps that eat your mornings. Open one too early and your checklist covers it. Tick off your habits and they unlock straight away.

A CHECKLIST THAT STICKS
• Timers for habits you want to really do, not just tick
• Weekday schedules, rest days and partial credit
• Streak freezes, so one bad day doesn't wreck your streak
• Forgot to tick something? Fix yesterday
• Edits that would make today easier start tomorrow, so the routine stays honest

A BUDDY ON YOUR SCREEN
Your buddy walks around your phone, cheers you on and levels up with you. Unlock colours, hats and entrances as your streak grows.

STREAKS & STATS
A 12-week heatmap, your best streak and the habits that need some love.

FOCUS LOCK (OPTIONAL)
Pulls you back to the checklist if you wander off, with three 5-minute breaks a day. "Pause for today" is always there as an emergency exit.

100% OFFLINE, NO ACCOUNTS
Mingui doesn't have Android's internet permission, so it can't send anything anywhere. No accounts, ads, analytics or trackers. Your habits stay on your phone.

Also: a home-screen widget, backups to a file you keep, and English, Spanish and Catalan.

Open source (GPL-3.0): github.com/abysstudio/mingui
```

### Español (es-ES)

**Nombre**: `Mingui: Hábitos y bloqueo`

**Descripción breve**:
`Primero tus hábitos de hoy. Luego tus apps se desbloquean. 100 % sin conexión.`

**Descripción completa:**

```
Mingui es una lista de hábitos diarios con una pequeña mascota, y mantiene bloqueadas las apps que más te distraen hasta que terminas la rutina de hoy.

PRIMERO TERMINA, LUEGO SCROLLEA
Elige las apps que te roban la mañana. Si abres una demasiado pronto, tu lista la tapa. Marca tus hábitos y se desbloquean al momento.

UNA LISTA QUE FUNCIONA
• Temporizadores para los hábitos que quieres hacer de verdad
• Días de la semana, días de descanso y crédito parcial
• Congeladores de racha, para que un mal día no la rompa
• ¿Olvidaste marcar algo? Corrige el día de ayer
• Los cambios que harían más fácil el día de hoy empiezan mañana, para que la rutina sea honesta

UNA MASCOTA EN TU PANTALLA
Tu mascota pasea por el móvil, te anima y sube de nivel contigo. Desbloquea colores, gorros y entradas a medida que crece tu racha.

RACHAS Y ESTADÍSTICAS
Un mapa de 12 semanas, tu mejor racha y los hábitos que necesitan un empujón.

BLOQUEO DE CONCENTRACIÓN (OPCIONAL)
Te devuelve a la lista si te despistas, con tres descansos de 5 minutos al día. «Pausar por hoy» siempre está ahí como salida de emergencia.

100 % SIN CONEXIÓN, SIN CUENTAS
Mingui no tiene el permiso de internet de Android, así que no puede enviar nada a ningún sitio. Sin cuentas, anuncios, analíticas ni rastreadores. Tus hábitos se quedan en tu móvil.

Además: widget para la pantalla de inicio, copias de seguridad en un archivo tuyo, y en español, catalán e inglés.

Código abierto (GPL-3.0): github.com/abysstudio/mingui
```

### Català (ca)

**Nom**: `Mingui: Hàbits i bloqueig`

**Descripció breu**:
`Primer els hàbits d'avui. Després les apps es desbloquegen. 100% sense connexió.`

**Descripció completa:**

```
Mingui és una llista d'hàbits diaris amb una petita mascota, i manté bloquejades les apps que més et distreuen fins que acabes la rutina d'avui.

PRIMER ACABA, DESPRÉS SCROLL
Tria les apps que et roben el matí. Si n'obres una massa aviat, la llista la tapa. Marca els hàbits i es desbloquegen a l'instant.

UNA LLISTA QUE FUNCIONA
• Temporitzadors per als hàbits que vols fer de debò
• Dies de la setmana, dies de descans i crèdit parcial
• Congeladors de ratxa, perquè un mal dia no la trenqui
• T'has oblidat de marcar alguna cosa? Corregeix ahir
• Els canvis que farien més fàcil el dia d'avui comencen demà, perquè la rutina sigui honesta

UNA MASCOTA A LA PANTALLA
La mascota passeja pel mòbil, t'anima i puja de nivell amb tu. Desbloqueja colors, barrets i entrades a mesura que creix la ratxa.

RATXES I ESTADÍSTIQUES
Un mapa de 12 setmanes, la teva millor ratxa i els hàbits que necessiten una empenta.

BLOQUEIG DE CONCENTRACIÓ (OPCIONAL)
Et torna a la llista si et despistes, amb tres descansos de 5 minuts al dia. «Pausa per avui» sempre hi és com a sortida d'emergència.

100 % SENSE CONNEXIÓ, SENSE COMPTES
Mingui no té el permís d'internet d'Android, així que no pot enviar res enlloc. Sense comptes, anuncis, analítiques ni rastrejadors. Els teus hàbits es queden al mòbil.

A més: giny per a la pantalla d'inici, còpies de seguretat en un fitxer teu, i en català, castellà i anglès.

Codi obert (GPL-3.0): github.com/abysstudio/mingui
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

- **Other brands in screenshots/video.** Screenshot 2 and the promo show "YouTube" as the example of a blocked app. Describing which apps you can block is normally fine, but Google's metadata policy dislikes third-party brand names in listings; if a reviewer objects, swap the example for a generic one. The listing text above avoids brand names on purpose.
- **Overlay over other apps.** It's only shown over apps the user chose to block, and there's always an exit (Pause for today), which is what reviewers check for in "app blocker" apps.
