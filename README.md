# juketify

a better jukebox. right click ur jukebox, enter the music you wanna listen to and it'll find it and play it for you from the jukebox. everyone nearby hears it too, with real distance falloff.

if you don't already have the song it searches for it online, downloads it once, and it just plays for everyone in range after that.

built for minecraft 26.1.2.

## setup

drop `.ogg` files into the `music` folder in your game dir. gets created the first time you run the game.

name them `Artist - Title.ogg` so search actually works well. no dash still works, it just matches on the whole filename then.

only ogg vorbis works, that's literally the only format minecraft's audio engine can decode (same reason resource packs use it). convert stuff with ffmpeg:

```
ffmpeg -i song.mp3 -c:a libvorbis -q:a 5 "Artist - Title.ogg"
```

## server side

whoever's hosting needs `yt-dlp` and `ffmpeg` installed and on PATH on their own machine. that's what actually does the searching and converting when someone asks for a song that isn't cached yet.

everything rides over the same minecraft connection, game + the music itself, so there's no extra port to forward or anything else to open up. first time anyone asks for a new song there's a real wait while it searches and downloads, after that it's cached and instant for everyone.

hearing range is set with `/juketify radius <blocks>`, needs gamemaster. no args just tells you what it's currently set to. 16-128, defaults to 64.

## using it

right click a jukebox with an empty hand to open the search screen. holding a music disc still works normally.

type something and hit enter. if you already have it, closest match plays instantly. if not, it searches online, downloads it, and plays for everyone in range from then on.

- **stop** stops it for everyone in range
- **rescan** re-reads the music folder without restarting
- breaking the jukebox stops it too

joining mid-song, changing dimension, or walking into range gets you synced to where the track currently is instead of restarting it from the beginning.

## building

needs jdk 25, minecraft 26.1.2 requires it.

```
./gradlew build
```

jar ends up in `build/libs`. grab the plain one, not the `-sources` one.

run a dev client:

```
./gradlew runClient
```

## rough edges

no queue, no skip. volume is whatever your jukebox slider is set to.

online search just grabs yt-dlp's top result, no way to pick a different one. if it fails (no yt-dlp, no internet, no match) you get told, but there's no retry button, just search again.

server trusts whatever a client asks it to play, give or take a range check. fine among friends, not hardened for a public server.

a track that reaches its own end doesn't clear properly yet, still says it's playing when it's actually done.

## license

MIT
