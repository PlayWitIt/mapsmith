# Mapsmith

A client-side Fabric mod for Minecraft 1.21.11 that turns any picture into map art and gets it built. It converts
the picture to exact map colours, lines it up with the map grid, shows every block in the world, lists the
materials, saves schematics, and has a builder that places the blocks for you, walking in survival and flying in
creative.

By **PlayWit Creations**.

## Installing
1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.11**, plus
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Put `mapsmith-<version>+1.21.11.jar` in your `.minecraft/mods` folder.
3. In game, press **U** to open the studio.

Mapsmith **updates itself**: once a session it checks GitHub for the latest release and, if there is one, downloads it in the background and puts it in when you close the game (you can turn this off in Settings). No Modrinth wait, nothing to click.

## Making art
1. **Pick a picture.** In the studio, click **Open a picture…**, drop a picture onto the game window, or put pictures
   in `.minecraft/mapsmith/pictures`. PNG, JPEG, GIF, BMP and TGA all work.
2. **Tune it.** The preview shows exactly what the maps will show, and updates as you change things:
   - **Size**: 1 to 8 maps wide and tall.
   - **Flat** or **Staircase (3 shades)**. Maps shade each block by its height against the block north of it.
     Staircase art steps blocks up and down to get a light, normal and dark shade of every colour, which gives three
     times as many colours. Heights are kept as low as they can be: each run of steps goes back down to the ground.
     **Tallest** caps the height, so the art is quicker to build and easier to walk on.
   - **Dithering**: Floyd–Steinberg, Atkinson, Jarvis–Judice–Ninke, Stucki, Burkes, Sierra, Sierra Lite, ordered
     4×4 and 8×8, or none, with a strength setting. It runs in alternate directions on alternate rows, so there are no
     diagonal streaks.
   - **Framing**: Fill (drag the preview to pick the part), Fit or Stretch. Zoom by scrolling on the preview. Smooth
     scaling (averaged in linear light, so it doesn't darken) for photos; Sharp for pixel art.
   - **Brightness, contrast and colour**.

   Colours are matched in OKLab, so the nearest map colour is the one that *looks* nearest. Hover the preview to see
   the block, shade, height and world position of any pixel. Hold **Space** to see the picture instead.
3. **Blocks…**: pick which block makes each of the 60 colours (click for the next, shift-click to go back,
   right-click to leave a colour out). **What I'm carrying** builds the palette from your inventory, shulker boxes
   included. **No falling blocks** swaps out sand, gravel and the like. Flat art uses carpets where it can (2 wool
   make 3 carpets). You can also choose the block for the hidden supports.
4. **Materials…**: everything it takes, in stacks and shulker boxes, against what you're carrying. Copy the list
   to the clipboard.

## Building it
- **Place here** puts the art's north-west map on the map square you're standing in, with its bottom at your feet.
  Map squares are fixed: each covers 128×128 blocks from x and z = 128k − 64 to 128k + 63, which is exactly 8×8 chunks.
  The gold lines in the world are map borders and the faint ones are chunk borders. The HUD shows which square
  you're in and where its middle is.
- **The reference row.** The top row of a map is shaded against the blocks just north of it, which sit in the last
  row of the chunks north of the map. Mapsmith puts a row of blocks there at the right height (outlined in blue), so
  the top row comes out right.
- **Ghosts**: every block still to place shows in the world in its colour, supports in grey, and blocks to break are
  outlined in red.
- **The builder** (**G**, or **Build** in the studio) places blocks within reach, lowest first, so supports go in
  before what they hold up. It works out a face to place against that gives the right result (logs come out upright),
  and swaps the right block, or the best tool, into the hotbar. It breaks wrong blocks and anything in the way, fills
  holes in the ground, and moves on a strip at a time:
  - In **survival** it walks using its own pathfinder over the blocks that are there. It steps up one block, drops
    up to three (five if there's no other way), and never steps into lava, fire or cactus. Where the art is higher than
    anything it can climb, it pillars up with the support block, and clears the pillar away afterwards.
  - In **creative** it flies over the art, and takes the blocks out of the creative inventory.
  - When it runs out of a block it pauses and says which. Press G again once you have more. It also pauses at low
    health.
  - It places 8 blocks a second by default (20 in creative), turning to look at each one as you would. The studio
    says about how long the art will take.
- **Settings** (in the studio, or Mod Menu's Config button): builder speed in survival and creative, looking at
  blocks, fixing mistakes, moving on by itself, flying in creative, the ghosts and how far they're shown, the map and
  chunk borders, and the progress lines at the top left.
- **Map check**: as you build, Mapsmith works out what a map of the art would show from the loaded blocks, the same
  way the game draws one, and the HUD shows how much of it is right. Holding a map of the art shows how it compares
  with the art, pixel by pixel.
- **Making the map**: stand in the middle of the map square (the HUD gives it), with the whole square loaded, and
  use an empty map.

## Schematics
**Save schematics** writes a Litematica `.litematic` and a WorldEdit/Sponge `.schem` into `.minecraft/schematics`,
plus a PNG of exactly what the maps will show. Both schematics include the art blocks, the reference row and the
supports. Their corner goes on the west end of the reference row; the file name gives the coordinates.

## Commands
`/mapsmith` opens the studio. The other commands are `/mapsmith build`, `stop`, `place [mapsEast mapsSouth]`,
`materials`, `export`, `grid` and `ghosts`. All are client-side and never reach the server.

## Building from source
Requires JDK 21. `./gradlew build` builds the jar into `build/libs/`. `./gradlew test` runs the converter tests: every
pixel's shade checked against the game's shading formula, height limits, and whether every block can be placed.
`./gradlew runSelfTest` builds part of a staircase art in creative and checks that a real in-game map of it matches
pixel for pixel. It then builds flat carpet art in survival (with a wrong block to fix, a hole to fill and a block to
run out of), and then staircase art in survival, on foot. `./gradlew runStairsTest` runs only that last part.
Screenshots go to `run/screenshots/`.
