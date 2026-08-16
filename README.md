# lycanoculus

Fixes Lycanites Mobs rendering corruption when Oculus/Iris shaders are active (creatures,
charges, equipment) via a batch renderer and shadow LOD handling.

Minecraft 1.20.1, Forge. MIT license.

## Third-party jars

`build.gradle` compiles against local copies of Lycanites Mobs and Oculus, not Maven
coordinates — the instance path they're normally loaded from contains a colon, which breaks
the Java classpath separator on Linux. These jars aren't redistributed in this repo; download
them yourself and place them in `libs/` before building:

- `lycanitesmobs-0.0.9-alpha.jar`
- `oculus-mc1.20.1-1.8.0.jar`
