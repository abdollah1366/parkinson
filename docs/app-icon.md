# App icon

**Concept:** neurology plus motor assessment, built from three elements:
* a brain (top view, two hemispheres with gyri);
* one motor neuron (amber) whose axon runs from the motor cortex to the midline and down the brainstem;
* a movement trace: the measured motor output.

The design deliberately avoids a generic "AI network" pattern, pills or other pharmaceutical
symbols, and any text.

| Resource | Purpose |
|---|---|
| `drawable/ic_launcher_background.xml` | Deep medical blue → teal gradient (matches `MedicalPrimary`); opaque, so the icon works on light and dark wallpapers |
| `drawable/ic_launcher_foreground.xml` | Brain, motor neuron and movement trace, inside the 66 dp safe zone |
| `drawable/ic_launcher_monochrome.xml` | One-color layer for Android 13+ themed icons |
| `mipmap-anydpi-v26/ic_launcher(.xml, _round.xml)` | Adaptive icon (API 26+) |
| `mipmap-*dpi/ic_launcher(_round).webp` | Pre-Android 8 launchers (minSdk 24), rendered from the vectors |

The manifest is unchanged (`@mipmap/ic_launcher`, `@mipmap/ic_launcher_round`). The launcher, the
app list and Settings → App info all use this application icon.

`LauncherIconRenderTest` (Robolectric, native graphics) checks on every run that:
* the icon is adaptive and has a monochrome layer;
* the foreground stays inside the safe zone;
* the background is opaque.

To regenerate the legacy webp files and a preview sheet after changing the vectors:

```
ICON_OUT=<folder> ./gradlew :app:testDebugUnitTest --tests "*LauncherIconRenderTest*"
```

Then copy `<folder>/mipmap-*dpi/*.webp` into `app/src/main/res/`.
