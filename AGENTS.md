# AGENTS.md - HeyCyanSmartGlassesSDK

## Purpose
Software Development Kit (SDK) for HeyCyan Smart Glasses. Contains core libraries and sample applications for integrating features like camera access, sensor data, and display control.

## Project Structure
- `android/`: Android-specific SDK code and demo apps.
- `examples/`: Cross-platform or specific usage examples.
- `firmware_dump/`: Hardware firmware reference or dumps.

## Android Data Transfer Notes

- Media transfer (BLE + Wi-Fi Direct HTTP) reverse-engineering notes live in `android/AGENTS.md`.

## Development Environment
- **Conda Environment:** `LocalGPT_llama2`
- **STRICT RULE:** Never use `pip install` or download libraries into the `base` conda environment.

## Execution Rules
- Review `WIFI_TRANSFER_ARCHITECTURE.md` before working on data transmission features.
- Ensure any demo app changes are tested against the physical device if available.
