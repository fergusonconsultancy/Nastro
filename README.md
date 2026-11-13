# Nastro

A cassette Walkman for the Galaxy Z Fold8 Ultra, built from the tapes and the light-blue Sony WM-EX610 of 2001.

**The fold is the door.** Folded, the cover screen is the Walkman's face: the tape behind smoked acrylic, a readout with a tape counter, four round keys and the printed switches. Unfolding swings that face open on the crease. The right half is the compartment it covered, with the tape at full colour on its spindles and the head and pinch roller at its foot. The left half is the inside of the door, where your tapes are kept, joined to the body by hinge knuckles on the crease. The tape is drawn no bigger than a real one, so the phone itself becomes the Walkman. Folding shuts it with a clunk.

Every album on the phone is dubbed onto the shortest cassette that holds it (C60, C90 or C120), split into sides at the track nearest half way, with its label written by hand. The shells are drawn from real tapes of the period, without makers' marks.

## How it behaves

- **Tape physics.** Tape runs at 4.76 cm/s. Each reel turns at tape speed over its own radius, so the nearly empty hub spins fastest. The counter counts turns of the take-up reel.
- **Keys.** ▶ plays. ■ stops; folded, pressing it again opens the door to the shelf. ▶▶ and ◀◀ during play wind to the next gap or back to the start of the track (the EX610's music sensor). Stopped, they wind at 30 times play speed.
- **By hand.** Spin a reel with your finger to wind the tape. Swipe the tape sideways to turn it over.
- **Printed switches.** Auto reverse, Mega Bass (the platform bass boost) and Hold. They brighten as your finger comes near.
- **Blank skip.** At the end of a side the blank tape winds past, then auto reverse turns round or the deck stops.
- **Every tape is where you left it.** Position and side are kept per album.
- **Mixtapes.** Pick the blank at the head of the shelf, write its name, choose a shell and put songs on in your own order. It goes onto the shortest tape that holds them. Hold a mixtape on the shelf to change it or throw it away.
- **Any way up.** Turned on its side, the cover screen lays the player out sideways and the open phone puts the deck above the shelf, everything upright.
- **Opening the door stops the tape.** Unfold the phone and the play key pops up. Turn the tape over, wind it by hand or choose another and it drops in; fold the phone to shut the door and it plays.
- Headphones pulled out stop it. Lock screen, headset and notification controls drive the same deck.

## Design

Bellezza Necessaria governs the motion; the material is the WM-EX610. See `docs/design.md`.

## Get the APK (no Android Studio needed)

1. Push this folder to a GitHub repository.
2. Open the repository's **Actions** tab. The **Build APK** workflow runs on every push.
3. When it goes green, open the run and download **Nastro-apk**.
4. Unzip it, copy the `.apk` to the phone and open it. Allow installs from that source when asked.

On first run, allow access to music, and notifications so the tape carries on with the screen off.

## Notes

- No libraries: platform MediaPlayer, MediaSession and Canvas only.
- Fonts: Caveat (handwriting), Barlow Condensed (printing), Jost. All SIL Open Font License, licences in `app/src/main/assets/fonts`.
- The album list is read when the app starts; new music appears after reopening it.
- Untested on hardware at the time of writing.
