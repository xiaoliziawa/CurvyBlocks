# Curvy Blocks

English | [简体中文](README_CN.md)

A curved block mod for Minecraft **1.21.1 / NeoForge 21.1.251 / Java 21**.

Hold any `block item` in your offhand to place smooth curves using that block's texture.

## Controls

Curved placement mode is enabled by default. Put a block in your offhand, right-click to set the start point, then move your crosshair and right-click again to finish.

The target point is shown as a ring that always faces the camera. It follows the camera position every rendered frame and smooths out the small jumps caused by grid snapping. Confirmed nodes are shown as smaller rings.

The curve preview is drawn as a continuous translucent outer surface that keeps the material texture and the placement status color. Rendering first resolves the depth of the nearest visible surface and then shades only that surface, so front and back faces are not blended repeatedly per sample segment, which would otherwise produce sliced banding.

| Input | Action |
| --- | --- |
| Right-click | Set the start point / confirm placement |
| Sneak + right-click | Add an intermediate node and keep editing |
| Enter | Finish the current preview |
| Left-click / Backspace (while editing) | Undo the last node |
| X | Cancel the current draft |
| Left-click a placed curve | Remove the entire curve |
| Middle mouse / vanilla "Pick Block" key | Pick the source block material of the curve under the crosshair |
| R | Cycle thickness: 1/8, 1/4, 1/2, 1 block |
| V | Toggle round / square cross-section |
| G | Cycle grid snapping: off, 1/16, 1/4, 1/2 block |
| Hold Sprint | Ignore blocks and curves and place points in mid-air |
| Hold Left Alt | Automatically find a path around obstacles for a blocked curve |
| Alt + scroll | Adjust the mid-air point distance, limited by vanilla interaction range |
| B | Toggle curved placement mode; when off, the offhand behaves normally |
| Hold H | Show the full controls in the hint box at the bottom left |
