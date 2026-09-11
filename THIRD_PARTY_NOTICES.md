# Third-party Notices

PSD2Live is licensed under GNU GPL version 3. See `LICENSE`.

## Umamo

This project integrates portions of the Umamo project (`format`, `runtime`, `interop`, `render`, and `edit` core modules). Umamo is authored by Alexia E. Smith and contributors, licensed under GNU GPL version 3.


## Stretchy Studio

Copyright (c) 2026 Nguyen Phan. Licensed under the MIT License.

PSD2Live independently implements concepts inspired by See-Through semantic organization, connected-component layer splitting, image analysis, atlas packing, and adaptive mesh generation. It does not embed Stretchy Studio's React/WebGL user interface.

## Model Context Protocol Kotlin SDK and Ktor

The built-in local Agent bridge uses the official Model Context Protocol Kotlin SDK, maintained by the Model Context Protocol project in collaboration with JetBrains. New SDK contributions are licensed under Apache-2.0 and existing portions under MIT. The HTTP transport is provided by Ktor under the Apache-2.0 license.

## Live2D Cubism

`Live2D`, `Cubism`, `.cmo3`, `.moc3`, and associated schema identifiers are trademarks or registered trademarks of Live2D Inc., used herein solely for format specification and interoperability purposes. This project is not affiliated with, endorsed by, or sponsored by Live2D Inc., and strictly complies with the Live2D Proprietary Software License: **it does not embed, include, or redistribute official proprietary Live2D Cubism SDK binaries, headers, or shader sources**.

For instructions on configuring an official SDK runtime locally for official rendering and physical dynamics consistency verification (Ground Truth), see:
- [Live2D SDK Setup Guide (English)](docs/en/CUBISM_SDK_SETUP.md)
- [Live2D SDK 配置指南 (中文)](docs/zh/CUBISM_SDK_SETUP.md)
- [Live2D SDK 設定・利用ガイド (日本語)](docs/ja/CUBISM_SDK_SETUP.md)

## Native file dialogs (LWJGL / Native File Dialog Extended)

The Windows file, save and folder pickers use Native File Dialog Extended through the existing LWJGL 3.4.2 platform and its `lwjgl-nfd` module. LWJGL uses the BSD 3-Clause license; Native File Dialog Extended uses the zlib license. The platform-specific native library ships with the application; no PowerShell helper is required.

- Sources: https://github.com/LWJGL/lwjgl3 and https://github.com/btzy/nativefiledialog-extended
- Licenses: `licenses/LWJGL-LICENSE.md`, `licenses/nativefiledialog-extended-LICENSE.txt`
- Reviewed 2026-09-12: upstream Windows implementation uses `IFileOpenDialog` / `IFileSaveDialog`, UTF-8 bindings with Windows Unicode conversion, filesystem selection, parent window handles and default save extensions. Matching the existing LWJGL BOM avoids introducing a second native/COM binding framework. Native dialog calls are blocking and cancel through the operating-system dialog; errors are surfaced separately from cancellation. macOS/Linux retain the AWT/Swing implementation and were not validated on this Windows host.
