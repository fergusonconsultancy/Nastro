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
