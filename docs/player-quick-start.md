# OteTV player quick-start

## 1. Craft a TV and a remote

| Item | Recipe (rows top to bottom) |
| --- | --- |
| Wooden Monochrome TV (2x2) | planks planks planks / glass note-block planks / copper redstone copper |
| Woodgrain Color TV (2x1) | planks planks planks / glass redstone any-dye / copper note-block copper, or monochrome TV + redstone + red, green and blue dye (shapeless) |
| Portable TV (1x1) | - copper - / iron glass iron / iron redstone iron |
| CRT TV (2x2) | iron iron quartz / glass glass redstone / iron iron iron |
| Plasma TV (3x2) | glass glass glass / quartz redstone quartz / copper iron copper |
| OLED TV (3x2) | glass glass glass / quartz redstone quartz / quartz copper quartz |
| Universal Remote | - button - / quartz redstone quartz / - copper - |

All models play the same sources; they differ in look, size and picture shape (4:3 or 16:9).

## 2. Place it

Look at the floor where the TV should stand: it faces you, and the placement outline shows the whole footprint before
you click. Plasma and OLED TVs mount on a wall when you click the side of a solid block. Breaking any part drops the
whole TV once.

## 3. Watch

Use the TV (right-click) to open its menu:

- **Turn on**, then **Choose source**: channel 1 is the built-in OteTV demo. Server channels, a video link, YouTube
  and your own files are in the other tabs.
- **Watch** joins the shared session; everyone watching that TV shares one timeline. Seeing a TV never downloads
  anything until you choose to watch.
- **Enlarged viewing** shows the picture full screen with playback controls (also available as a key binding).
- The first time a TV wants a website, OteTV asks you which host and why. Your answer stays on your computer.

## 4. Pair the remote

Sneak and use the remote on a TV to pair it, then use the remote anywhere within range (32 blocks by default, same
dimension) to open its controls: power, channels, play/pause, seek, captions, volume and Mute for me. The default key
**O** opens the remote you hold.

## 5. Your own settings

- Volume follows Minecraft's Master and Jukebox/Note Blocks sliders, the TV's speaker setting, distance and your
  personal OteTV volume. **Mute for me** only affects you.
- Captions are personal: turn them on in the remote or the viewing screen.
- The Privacy screen lists the hosts you allowed, lets you revoke them, and adds LAN grants for a home media server.

## 6. Runtimes

- Direct media needs GStreamer on your computer (Linux: your distribution's packages; Windows: the official
  installer). The TV tells you if it is missing.
- In-world YouTube needs the optional OteTV browser runtime (about 140 MB, shown before you install). Without it,
  YouTube TVs offer **Open on YouTube** in your own browser.

## 7. Sharing a TV

The player who placed a TV owns it. In **Settings** the owner can name it, choose the picture style and redstone mode,
and trust other players as viewer, controller or editor. Owners can also allow public controls if the server permits.

Problems? See [troubleshooting.md](troubleshooting.md).
