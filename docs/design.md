# Nastro: design note

Ferguson Consultancy Designs, 25 Oct 2026. Tested against Bellezza Necessaria (claude/bellezza-necessaria.md).

## Verdict

Bellezza Necessaria keeps the motion; the material pivots to retro. The doctrine's three principles describe how things move, and a cassette is a machine whose movement is all physics, so they fit it better than either Fold app. Its materials (smoked green-black, champagne, Alfa red, Italiana) do not: on a Walkman they would be ornament that answers to no force, which fails the charter's own test. The skin is the WM-EX610 in light blue (Mechack's own, 2001) and the tapes are the shells from his photos.

## The fold is the door

Mechack's idea, 25 Oct. The Fold's hinge is on the left, so the cover screen sits on the panel that swings open like a book cover: exactly a Walkman door.

| State | Screen | What it is |
|---|---|---|
| Folded | Cover | The face: tape behind smoked acrylic, readout, round keys, printed switches |
| Unfolding | Main | The face swings open on the crease (750 ms, ease in-out), shading as it turns |
| Unfolded | Main, right half | The open compartment: tape at full colour on its spindles, head, pinch roller, capstan |
| Unfolded | Main, left half | The inside of the door, hinge knuckles at the crease: the shelf |
| Folding | Cover | The door has shut: a heavy click and a small settle |

**Departure, on purpose:** opening a real Walkman stops the tape. Here it plays on with the door open, because unfolding to browse would otherwise cut the music (Mechack's tightness standard: friction is never enjoyable). Choosing another tape stops it.

## Principle by principle

**Membrane.** The tape is a literal membrane between two reels. Pack radii follow equal areas, and each reel turns at tape speed over its own radius, so the nearly empty hub spins fastest. Taking a tape off the shelf makes the tapes in its column lean into the gap, nearest most, and spring back.

**Concentrato.** Above about 4× speed the deck starts to empty, and by about 22× it is bare: the readout's time and title, the printed legends, the cassette's small print and the shelf fall away. The reels, the moving tape and the counter stay.

**Giulietta, with one refusal.** The transport keys never hide. Walkman keys were round and spaced to be found blind in a pocket; hiding them adds friction. Instead the printed words under the window are the switches (Auto reverse, Mega Bass, Hold), and they brighten as the finger nears.

## From the real machine

- 4.76 cm/s; fast wind 30× with motor spin-up (τ 0.28 s); play starts almost at once (τ 0.06 s)
- The counter counts take-up reel turns, geared 2:1
- Music sensor: ▶▶ or ◀◀ during play winds to the next gap or the start of the track
- Blank skip: the unrecorded end of a side winds past before auto reverse
- Each album goes on the shortest tape that holds it, split at the track nearest half way
- Every tape keeps its own position and side
- Tactile dot on ▶; amber LED beside it while playing
- Winding by hand: a reel turned under a finger pulls tape by angle × that reel's radius

## Whimsy that passes the test

- Spinning a reel with a finger, the pencil trick, made a gesture. The physics justifies it.
- Swiping the tape over to side B.
- The empty case left on the shelf, showing its card, while its tape is in the deck.

## Left out

Makers' logos and wordmarks on the shells, Sony's name and the Walkman mark, and Deck-Man's tape-tangling joke (friction dressed as fun).

## First recording, 25 Oct: the shelf at scale

Mechack's library made 808 tapes, most of them singles. A shelf you cannot get through is friction, so:

- **Singles tapes.** An artist's singles (one or two tracks each) share one tape labelled Singles, the way songs were taped off the radio. A lone single keeps its own tape.
- **The index (Giulietta).** A–Z sits hidden in the door's trim on the hinge side. Touching the edge wakes it; sliding jumps to each letter with a tick, and the letter is written large across the shelf while held. It blends back 1.2 s after release.
- **Fixes.** The heading scrolls with the tapes instead of sliding under them; the hinge knuckles, which read as scrollbars, became a single moulded groove; the play LED moved into the key strip.

## Second review, 25 Oct: two states, two identities

Mechack's verdict on the folded screen: it looked low effort, and the app was "neither here nor there". Folded showed a tape through a window, unfolded showed the same tape in a compartment. He offered two directions: make the whole app a cassette, or make the face a real player and save the tape for the unfolded screen.

**Chosen: the player outside, the tape inside.** A cassette alone has no controls, so play keys on a tape would be ornament no force put there, and the fold-as-door idea would be lost.

- **Folded: the player.** A lid brushed the other way from the body, with a seam; a slim chrome-ringed lens showing only the two hubs turning; a recessed reflective readout with the title as a marquee; four keys in a recessed channel moving as one membrane (the pressed key sinks, its neighbours tilt towards it); a knurled volume wheel standing proud of the right edge, driving the phone's media volume; a Hold slide that shows orange when locked; an operation lamp; "OPEN" with an arrow to the hinge; a battery door with the settings behind it.
- **Unfolded: the tape.** The compartment takes all of the right half except a slim readout and the keys.
- **Finishes.** Light blue and grey (the WM-EX610's own), graphite, rosso. Type etched through the dark finishes shows bright metal, as real etching does.
- **Sounds.** Synthesised from the mechanism's parts because recordings could not be used (tools/synth_sounds.py): key strike, mechanism drop, latch and pinch roller make the play key's ka-chunk; the door pops on its spring; the tape slides, drops on its spindles and the door shuts with a thunk and a catch; the motor's whirr follows the reels; optional tape hiss. Awaiting Mechack's ear.
- **Settings** behind the battery door (and on the shelf's heading when unfolded): finish, sounds, hiss, fast wind (real 30× or quick 60×), auto reverse, Mega Bass, Concentrato, door swing, singles tapes, shelf order (artist, album, newest), haptics.

## Third review, 25 Oct: the remote's readout, round keys, no false labels

- **Readout made like the WM-EX610 remote's**, from Mechack's photo of it: warm grey reflective glass (sampled and corrected against the white cloth in the photo) sunk under its glass in a black and silver surround; upright 14-segment characters with square-ended strokes reading PLAY F, FF F, REW R, STOP, OPEN or HOLD, where F/R is the tape's direction; each segment casts a faint shadow on the reflector behind it; no ghost segments; icons for the tape, auto reverse, Mega Bass and battery; the track title steps along the bottom row like a real LCD marquee.
- **Round keys.** The pills were not good enough. The EX610's keys were round and spaced for blind use (verified), and the charter already calls the circle "the perfect graphic shape" through the Audi TT's round aluminium parts, so the original and the mandate agree: spun aluminium discs with a polished chamfer, each in its own well, play the largest.
- **The Alfa bead ring around play** replaces the separate OPR lamp: lit while the tape runs, a bright head travelling at the take-up reel's speed and in its direction, unlit beads holding an ember.
- **Removed: "▲ OPEN"** (Mechack circled it). It pointed the wrong way and opened nothing; the door opens by unfolding. **Removed: the tactile dot on play**, by the same logic: a bump you cannot feel on glass is ornament.
- **Convex, sunken keys** (Mechack, 25 Oct): domed caps, folded and unfolded alike since they are the same keys on the same body, sunk into the body as on the WM-EX610. Each sits in a deep round hole: the upper lip in shadow and shading the top of the cap, the lower lip catching the light, the wall dark above and lit below. A sunken key throws no shadow outwards, only a tight contact shadow on the floor, and less light reaches the dome. A latched key (play while the tape runs) sits lower and dimmer.
- **The tape faces the hinge** (Mechack, 25 Oct): in portable players the heads sit by the door's hinge and the tape goes in open edge first towards them, so it drops onto the heads as the door closes (general knowledge, not confirmed for the EX610 specifically). The crease is the hinge, so the tape's open edge, the head, pinch roller and capstan now face the crease when unfolded, and the label runs down the far side. The lens on the folded face shows the tape the same way round.

## Fourth review, 25 Oct: Visconti

Mechack: the light blue, though his own Walkman's colour, lacked sleekness; apply Visconti green and make it slick.

- **Visconti is the default finish**, using the green the Fold apps already use (an approximation of Alfa Romeo's Verde Visconti; Alfa publishes no value), so the portfolio stays one family.
- **Brushed, not lacquered** (Mechack, 25 Oct; a clear-coat lacquer version came first and was replaced). The green is anodised metal brushed like the rest of the player: the body horizontally and the lid vertically, so the lid reads as its own panel. Brushed metal stretches its highlight into a soft streak across the grooves, a vertical streak on the body and a horizontal one on the lid, and that streak is the one highlight; the round shoulder highlight used on the other finishes is dropped.
- **Champagne** (the mandate's #D9BB86) for the engraving, the keys' satin aluminium, the Hold slide and a fine coachline round the lid's seam, as on an Alfa.
- The inside of the door turns dark green to match. Light blue, grey, graphite and rosso remain in settings.

## Sounds, 25 Oct: the traditional cassette sound

Mechack: placing the cassette did not sound like the traditional cassette sound. The first version was built round a low 104 Hz thump, which reads as a drum. A cassette is a hollow plastic box with two loose reels, so it was rebuilt from that: the shell drops into the holder with a hollow tap (resonances 0.9 to 4.7 kHz), its reels shift and rattle, the door shuts with a sharp plastic clack and only a small body knock (260 and 410 Hz), the catch clicks twice, and the reels settle. About 90% of its energy now sits between 500 Hz and 4 kHz; 6% lies below 300 Hz. Folding the phone and closing the settings now play a door-shut-only sound, since no tape is being placed.
