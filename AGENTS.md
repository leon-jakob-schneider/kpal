# kpal Structure

- `io/audio`
  Shared audio I/O module. Owns the audio-specific interfaces, data types, and platform implementations.

- `device`
  Aggregates I/O modules behind a single `Device` interface. `DeviceImpl` wires platform-specific implementations into that surface.

- `device-qa-android-app`
  Android QA app built on top of `device` for manual device/audio validation.

- `device-qa-ios-app`
  iOS QA app built on top of `device` for manual device/audio validation.
