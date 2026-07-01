package studio.voxsum.core.config

/**
 * App appearance mode. [AUTO] follows the OS light/dark setting (the default) and covers the common
 * phone case; [LIGHT] and [DARK] pin it; [EINK] is a manual choice for e-paper devices (flat white,
 * black ink) — it can't be auto-detected, so the user on an e-ink device selects it explicitly.
 */
enum class ThemeMode { AUTO, LIGHT, DARK, EINK }
