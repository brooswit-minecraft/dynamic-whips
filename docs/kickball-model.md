# Kickball voxel model

The editable source is `src/main/resources/assets/dynamicwhips/vox/kickball.vox.hex`.
It is a reviewable hexadecimal representation of a MagicaVoxel 150 `.vox` file;
the Gradle resource task materializes the unchanged bytes at
`assets/dynamicwhips/vox/kickball.vox` during `processResources` and `jar`.
This keeps the binary source editable in ordinary text reviews while preserving
the exact `.vox` payload consumed at runtime.

The model is a 4×4×4, 32-voxel playground ball. Replacement assets must be
MagicaVoxel 150 files with the first `SIZE`/`XYZI` model using dimensions 1–16
on every axis, at most 1,024 voxels, and palette indices 1–255. Edit or replace
the hex payload at the same path, then let CI package it; no manual Minecraft
model export is needed.

During common setup, Dynamic Whips calls the shared
`io.github.brooswitminecraft:magicavoxel-loader:0.1.2` APIs
`VoxAssetLoader.load(InputStream)` and
`MinecraftItemModelConverter.convert(VoxelModel)`. The client renderer draws
the resulting native 0–16 element bounds through Minecraft's block-model
renderer. Gameplay and the Kickball entity remain unchanged when the asset is
replaced.

The consuming mod depends on the hash-pinned release JAR documented by
MINECRAFT-307; CI is the authoritative consumer-build and runtime-render check.
